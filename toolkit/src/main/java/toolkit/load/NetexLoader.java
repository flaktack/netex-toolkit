package toolkit.load;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import noi.netex.model.EntityStructure;
import noi.netex.model.VersionFrameDefaultsStructure;
import toolkit.geo.Projection;
import toolkit.model.ModelRegistry;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import toolkit.util.Strictness;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Feed file or zip to Store: a StAX cursor loop over the interesting-element set, feeding
/// `store.insertAnyObjects` through a pull iterator. One write txn per stream.
///
/// The attribute denormalisation arms live in [CopyingCapture], which sees every event: they apply
/// at any depth inside a frame, and this loop never sees the inside of an interesting element
/// because the unmarshaller consumes that subtree. This class contributes only the effective
/// FrameDefaults for the region the cursor is in.
///
/// Each interesting element's XML is captured while JAXB unmarshals it and handed to the store as
/// `WithFragment`, so the value encode is LZ4-only. A failed capture falls back to the store's
/// re-marshal path, counted and warned.
///
/// One `NetexLoader` is shareable across threads. A [Store] is not: one per thread.
///
/// Inputs: .xml, .xml.gz, .zip (every \*.xml member).
public final class NetexLoader {

    private static final String NETEX_NS = ModelRegistry.NETEX_NS;

    private final NameSets sets;
    private final XMLInputFactory xif;
    private final JAXBContext ctx;

    public NetexLoader() {
        try {
            this.sets = NameSets.fromResources();
            this.xif = new com.ctc.wstx.stax.WstxInputFactory();
            xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            xif.setProperty(XMLInputFactory.IS_COALESCING, false);
            xif.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
            // Full-package context: seconds to build, so shared with the value codec.
            this.ctx = toolkit.codec.FragmentCodec.packageContext();
        } catch (Exception e) {
            throw new IllegalStateException("loader init failed", e);
        }
    }

    /// Load a feed into the store (one write txn per XML stream). Zip-member stream labels are
    /// qualified with the feed name so log lines stay attributable under parallel multi-feed load.
    public LoadStats load(Path input, Store store) throws Exception {
        String name = input.getFileName().toString();
        String lower = name.toLowerCase();
        LoadStats total = LoadStats.ZERO;
        if (lower.endsWith(".zip")) {
            try (ZipFile zf = new ZipFile(input.toFile())) {
                // Member contract is ZipLoad's: .xml AND .xml.gz, through the same wrap().
                int nonXml = 0;
                int loaded = 0;
                Iterator<? extends ZipEntry> en = zf.entries().asIterator();
                while (en.hasNext()) {
                    ZipEntry e = en.next();
                    if (e.isDirectory()) continue;
                    String member = e.getName().toLowerCase();
                    if (!member.endsWith(".xml") && !member.endsWith(".xml.gz")) {
                        nonXml++;
                        continue;
                    }
                    try (InputStream in = ZipLoad.wrap(zf.getInputStream(e), e.getName())) {
                        total = total.plus(loadStream(in, store, name + "!" + e.getName()));
                        loaded++;
                    }
                }
                // A zip with no loadable member is refused, as in ZipLoad.
                if (loaded == 0) {
                    throw new IllegalStateException(input + ": no loadable XML members"
                            + (nonXml > 0 ? " (" + nonXml + " non-XML members ignored)" : ""));
                }
            }
        } else if (lower.endsWith(".gz")) {
            try (InputStream in = new BufferedInputStream(
                    new GZIPInputStream(Files.newInputStream(input), 1 << 16), 1 << 20)) {
                total = total.plus(loadStream(in, store, name));
            }
        } else {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(input), 1 << 20)) {
                total = total.plus(loadStream(in, store, name));
            }
        }
        if (total.objects() == 0) {
            Log.warn("[load] %s: 0 objects inserted (no XML members, or nothing interesting)", name);
        }
        reportDropped(name, total);
        return total;
    }

    /// What the loader dropped, decided once per feed with the totals in hand. Called by every entry
    /// point that owns a whole feed. Tolerated by default rather than refused — both fire on the
    /// national corpus.
    ///
    /// The unmapped message carries the element names, because they separate the two causes: a NeTEx
    /// abstract head means the feed used a non-instantiable name, a concrete type means
    /// `interesting.txt` and the model jar have skewed and a supported class is being dropped.
    public static void reportDropped(String tag, LoadStats stats) {
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", stats.unmapped(), tag,
                "interesting elements were skipped whole — the model has no class for "
                + stats.unmappedNames());
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", stats.skippedNoId(), tag,
                "objects vanished from the store because the source element carried no id "
                + "(NeTEx requires one; the JAXB binding does not, so nothing else can see it)");
    }

    /// Parse one XML stream and insert every interesting object; commits its own write txn.
    public LoadStats loadStream(InputStream in, Store store, String label) throws Exception {
        Parse p = open(in, label);
        try (Txn txn = store.rwTxn()) {
            store.insertAnyObjects(txn, p.objects());
            txn.commit();
        } finally {
            p.closeReader();
        }
        return p.finish();
    }

    /// The parse half of [#loadStream], so a caller can put the store write on another thread. LMDB
    /// has a single writer and `V2Store` state is thread-confined, so parse workers hand objects to
    /// one writer.
    ///
    /// Every per-stream mutable object is created here, so concurrent `open` calls are safe. The
    /// returned [Parse] is not: it belongs to whichever thread drains it.
    public Parse open(InputStream in, String label) throws Exception {
        long t0 = System.nanoTime();
        // Unmarshalling happens on this thread, so JAXB's Coordinator thread-local is pinned here
        // too (see toolkit.codec.JaxbPin).
        toolkit.codec.JaxbPin.pin();
        // The label is the reader's system id, so a well-formedness error names the stream instead
        // of Woodstox's `{unknown-source}`.
        CopyingCapture reader = new CopyingCapture(
                (org.codehaus.stax2.XMLStreamReader2) xif.createXMLStreamReader(label, in), sets);
        // The handler counts bind events and delegates its verdict to the RI's default, so tolerance
        // is unchanged; the stream label is the per-feed axis of the report.
        Unmarshaller unmarshaller = ctx.createUnmarshaller();
        toolkit.codec.BindEvents events =
                toolkit.codec.BindEvents.unmarshalling(unmarshaller.getEventHandler());
        events.scope(label);
        unmarshaller.setEventHandler(events);
        return new Parse(label, t0, reader,
                new ParseIterator(label, reader, sets, unmarshaller, events));
    }

    /// One stream's parse in progress: the pull iterator, the reader to close once it is drained,
    /// and the per-stream log line + [LoadStats].
    public static final class Parse {
        private final String label;
        private final long t0;
        private final CopyingCapture reader;
        private final ParseIterator it;

        Parse(String label, long t0, CopyingCapture reader, ParseIterator it) {
            this.label = label;
            this.t0 = t0;
            this.reader = reader;
            this.it = it;
        }

        /// The objects of this stream, in document order. Drain fully, then [#closeReader].
        public Iterator<Object> objects() {
            return it;
        }

        public String label() {
            return label;
        }

        public void closeReader() throws XMLStreamException {
            reader.close();
        }

        /// Emit the per-stream warnings + log line and return the stats (the tail of loadStream).
        public LoadStats finish() {
            long nanos = System.nanoTime() - t0;
            if (it.unmapped > 0) {
                Log.warn("[load] %s: %d interesting elements had no model class: %s", label, it.unmapped, it.unmappedNames);
            }
            if (it.skippedNoId > 0) {
                Log.warn("[load] %s: %d objects skipped (no id)", label, it.skippedNoId);
            }
            if (it.captureFallbacks > 0) {
                Log.warn("[load] %s: %d objects fell back to JAXB re-marshal capture", label, it.captureFallbacks);
            }
            if (!it.badLocationSystems.isEmpty()) {
                Log.warn("[load] %s: FrameDefaults/DefaultLocationSystem is not a CRS and was "
                        + "ignored, leaving the geometries under it without an srsName: %s",
                        label, it.badLocationSystems);
            }
            Log.info("[load] %s: %d objects inserted (%d FrameDefaults, %.1f s)",
                    label, it.objects, it.frameDefaults, nanos / 1e9);
            return new LoadStats(it.objects, it.frameDefaults, it.skippedNoId, it.unmapped,
                    it.captureFallbacks, nanos, it.unmappedNames);
        }
    }

    // ---------------------------------------------------------------- parse iterator

    /// Pull iterator over the interesting objects of one stream.
    private static final class ParseIterator implements Iterator<Object> {
        /// The stream label, carried for the one message that has no other way to name it — see
        /// [#hasNext()].
        final String label;
        final CopyingCapture r;
        final NameSets sets;
        final Unmarshaller unmarshaller;
        /// The counting handler installed on [#unmarshaller].
        final toolkit.codec.BindEvents events;
        final Map<String, Class<?>> classCache = new HashMap<>();

        /// What the unmarshaller sees: `r` plus the xs:collapse on enum-valued element text that
        /// the JAXB RI omits. The fragment tee stays on `r`, so the stored bytes remain verbatim
        /// while the bean carries the collapsed value.
        final toolkit.codec.EnumWhitespace.Reader enumText;

        // The denormalisation state. The id/version stacks live in CopyingCapture instead: they must
        // be maintained for every element of the document, including the ones inside a consumed
        // subtree.
        final ArrayList<VersionFrameDefaultsStructure> frameDefaultsStack = new ArrayList<>();
        String currentDatasourceRef;
        String currentResponsibilitySetRef;
        String currentLocationSystem;

        // Bindings declared by every open, non-consumed ancestor (head = innermost); feeds the
        // capture's namespace hoisting. Consumed subtrees are balanced and invisible to this loop,
        // so push/pop stays symmetric.
        static final List<String[]> NO_NS = List.of();
        final ArrayDeque<List<String[]>> nsStack = new ArrayDeque<>();

        long objects, unmapped, frameDefaults, skippedNoId, captureFallbacks;
        final Set<String> unmappedNames = new HashSet<>();
        /// The DefaultLocationSystem values refused by [#locationSystemOf], distinct.
        final Set<String> badLocationSystems = new HashSet<>();
        Object pending;
        boolean exhausted;

        ParseIterator(String label, CopyingCapture r, NameSets sets, Unmarshaller unmarshaller,
                      toolkit.codec.BindEvents events) {
            this.label = label;
            this.r = r;
            this.sets = sets;
            this.unmarshaller = unmarshaller;
            this.events = events;
            this.enumText = toolkit.codec.EnumWhitespace.wrap(r);
            // The reader is per stream. See BindEvents.reader.
            events.reader(this.enumText);
        }

        List<String[]> currentNsDecls() {
            int n = r.getNamespaceCount();
            if (n == 0) return NO_NS;
            List<String[]> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String p = r.getNamespacePrefix(i);
                String u = r.getNamespaceURI(i);
                out.add(new String[] {p == null ? "" : p, u == null ? "" : u});
            }
            return out;
        }

        Map<String, String> inScopeNs() {
            Map<String, String> m = new java.util.LinkedHashMap<>();
            for (List<String[]> decls : nsStack) { // innermost first: putIfAbsent keeps it
                for (String[] d : decls) m.putIfAbsent(d[0], d[1]);
            }
            return m;
        }

        /// The model class for an interesting element name, or null when the model has none.
        ///
        /// The `catch` is a lookup miss, and not a rare one: the abstract substitution-group heads
        /// in the interesting set get no generated class, because no instance document may contain
        /// them. A null therefore means either a schema-invalid name in the feed or skew between
        /// `interesting.txt` and the model jar, and this method cannot tell them apart —
        /// [NetexLoader#reportDropped] decides once per feed, on the total.
        ///
        /// `computeIfAbsent` stores no null mapping, so an unmapped name pays a classloader miss
        /// per occurrence rather than once.
        Class<?> classFor(String localName) {
            return classCache.computeIfAbsent(localName, ln -> {
                try {
                    return Class.forName(ModelRegistry.MODEL_PKG + "." + ln);
                } catch (ClassNotFoundException e) {
                    return null;
                }
            });
        }

        @Override
        public boolean hasNext() {
            if (pending != null) return true;
            if (exhausted) return false;
            try {
                pending = advance();
            } catch (Exception e) {
                throw new IllegalStateException(label + ": parse failed" + at(e), e);
            }
            if (pending == null) exhausted = true;
            return pending != null;
        }

        @Override
        public Object next() {
            if (!hasNext()) throw new NoSuchElementException();
            Object o = pending;
            pending = null;
            return o;
        }

        /// The parse position as a message suffix, or `""` when the throwable carries none. This
        /// decorates a failure that is already being reported, so it never raises one of its own.
        /// The hop count guards against a cyclic cause chain.
        private static String at(Throwable e) {
            Throwable t = e;
            for (int hop = 0; t != null && hop < 8; hop++, t = unwrap(t)) {
                if (t instanceof XMLStreamException xse && xse.getLocation() != null) {
                    Location l = xse.getLocation();
                    return " at row " + l.getLineNumber() + ", col " + l.getColumnNumber()
                            + (l.getCharacterOffset() >= 0
                                    ? ", char offset " + l.getCharacterOffset() : "");
                }
            }
            return "";
        }

        /// `getCause` is not enough: a [JAXBException]'s cause is null and the parser's exception
        /// hangs off `getLinkedException` instead, which is the hop between the two frames the
        /// unmarshal path always produces.
        private static Throwable unwrap(Throwable t) {
            Throwable next = (t instanceof JAXBException je && je.getLinkedException() != null)
                    ? je.getLinkedException() : t.getCause();
            return next == t ? null : next;
        }

        /// Scan forward to the next interesting bound object (null at end of stream).
        private Object advance() throws Exception {
            int ev = r.getEventType();
            while (true) {
                boolean consumed = false;
                Object bound = null;
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    final String ln = r.getLocalName();
                    final boolean isNetex = NETEX_NS.equals(r.getNamespaceURI());
                    if (isNetex && sets.interesting.contains(ln)) {
                        Class<?> c = classFor(ln);
                        if (c == null) {
                            unmapped++;
                            unmappedNames.add(ln);
                            skipSubtree(r);
                            if (r.hasNext()) r.next();
                        } else {
                            // Tee the subtree's events into the copying writer while JAXB consumes
                            // them; a capture that cannot start is a counted fallback, not a fault.
                            boolean began = false;
                            try {
                                r.beginCapture(inScopeNs());
                                began = true;
                            } catch (Exception ce) {
                                captureFallbacks++;
                            }
                            // The wrapper only re-reports text; every event still passes through r,
                            // so the capture is unaffected.
                            enumText.reset();
                            events.binding(c);
                            Object o = unmarshaller.unmarshal(enumText, c);
                            byte[] fragment = null;
                            if (began) {
                                try {
                                    fragment = r.finishCapture();
                                } catch (Exception ce) {
                                    r.abortCapture();
                                    captureFallbacks++;
                                }
                            }
                            Object v = (o instanceof JAXBElement<?> je) ? je.getValue() : o;
                            // No post-bind fix-up: every denormalisation arm is injected into the
                            // reader before the unmarshaller reads the start tag, so the bean and
                            // the captured fragment carry them from the same decision.
                            //
                            // A class that is not an EntityStructure is a build defect and is
                            // refused; an EntityStructure with a null id is feed content (the
                            // binding makes id optional though NeTEx requires it) and is counted.
                            if (v == null) {
                                // xsi:nil on an interesting element. Feed content, nothing to
                                // store — counted with the id-less ones.
                                skippedNoId++;
                            } else if (!(v instanceof EntityStructure)) {
                                throw new IllegalStateException(
                                        "interesting element <" + ln + "> binds to "
                                        + v.getClass().getName() + ", which is not an "
                                        + "EntityStructure and can carry no id — the interesting "
                                        + "set and the model disagree; every instance would be "
                                        + "dropped from the store");
                            } else if (((EntityStructure) v).getId() != null) {
                                objects++;
                                bound = fragment != null ? new toolkit.store.WithFragment(v, fragment) : v;
                            } else {
                                skippedNoId++;
                            }
                        }
                        consumed = true;
                    } else if (isNetex && ln.equals("FrameDefaults")) {
                        enumText.reset();
                        events.binding(VersionFrameDefaultsStructure.class);
                        Object o = unmarshaller.unmarshal(
                                enumText, VersionFrameDefaultsStructure.class);
                        VersionFrameDefaultsStructure fd =
                                (VersionFrameDefaultsStructure) ((o instanceof JAXBElement<?> je) ? je.getValue() : o);
                        frameDefaults++;
                        if (!frameDefaultsStack.isEmpty()) {
                            frameDefaultsStack.set(frameDefaultsStack.size() - 1, fd);
                        }
                        if (fd.getDefaultDataSourceRef() != null) {
                            currentDatasourceRef = fd.getDefaultDataSourceRef().getRef();
                        }
                        if (fd.getDefaultResponsibilitySetRef() != null) {
                            currentResponsibilitySetRef = fd.getDefaultResponsibilitySetRef().getRef();
                        }
                        String ls = locationSystemOf(fd);
                        if (ls != null) {
                            currentLocationSystem = ls;
                        }
                        r.setFrameDefaults(currentDatasourceRef, currentResponsibilitySetRef,
                                currentLocationSystem);
                        consumed = true;
                    } else {
                        // Ordinary element: push its ns bindings for the capture's hoisting.
                        nsStack.push(currentNsDecls());
                        if (sets.frames.contains(ln)) {
                            frameDefaultsStack.add(null);
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    final String ln = r.getLocalName();
                    if (!nsStack.isEmpty()) nsStack.pop();
                    if (sets.frames.contains(ln)) {
                        // Frame end: pop, then recompute from the enclosing frames outward.
                        if (!frameDefaultsStack.isEmpty()) {
                            frameDefaultsStack.remove(frameDefaultsStack.size() - 1);
                        }
                        currentDatasourceRef = null;
                        currentResponsibilitySetRef = null;
                        currentLocationSystem = null;
                        for (int i = frameDefaultsStack.size() - 1; i >= 0; i--) {
                            VersionFrameDefaultsStructure fd = frameDefaultsStack.get(i);
                            if (fd == null) continue;
                            if (currentDatasourceRef == null && fd.getDefaultDataSourceRef() != null) {
                                currentDatasourceRef = fd.getDefaultDataSourceRef().getRef();
                            }
                            if (currentResponsibilitySetRef == null && fd.getDefaultResponsibilitySetRef() != null) {
                                currentResponsibilitySetRef = fd.getDefaultResponsibilitySetRef().getRef();
                            }
                            if (currentLocationSystem == null) {
                                currentLocationSystem = locationSystemOf(fd);
                            }
                        }
                        r.setFrameDefaults(currentDatasourceRef, currentResponsibilitySetRef,
                                currentLocationSystem);
                    }
                }

                if (consumed) {
                    ev = r.getEventType(); // unmarshal/skip left us at the event AFTER the element
                    if (bound != null) return bound;
                    continue;
                }
                if (!r.hasNext()) return null;
                ev = r.next();
            }
        }

        /// The frame's DefaultLocationSystem, or null when it is absent or is not a CRS proj4j can
        /// resolve.
        ///
        /// The value is denormalised: CopyingCapture's N7 arm stamps it onto every srsName-less
        /// geometry under the frame (PROV-CAP srsname-depth), so one bad frame default becomes tens
        /// of thousands of geometries that no CRS registry can resolve. Refused here, those
        /// geometries keep no srsName and Projection's resolution rule reads them as WGS84.
        ///
        /// Publishers really do write a non-CRS here: three abruzzo RAP feeds
        /// (IT-ITF1 0083, 13, TUA-MAAS) carry `<DefaultLocationSystem>Europe/Rome</...>`, copied
        /// from the `DefaultLocale/TimeZone` two lines above it in their own FrameDefaults.
        String locationSystemOf(VersionFrameDefaultsStructure fd) {
            String ls = fd.getDefaultLocationSystem();
            if (ls == null) return null;
            if (Projection.isResolvableCrs(ls)) return ls;
            badLocationSystems.add(ls);
            return null;
        }

        static void skipSubtree(XMLStreamReader r) throws XMLStreamException {
            int d = 1;
            while (d > 0) {
                int e = r.next();
                if (e == XMLStreamConstants.START_ELEMENT) d++;
                else if (e == XMLStreamConstants.END_ELEMENT) d--;
            }
        }
    }

    // ---------------------------------------------------------------- name sets

    /// The element-name sets exported from the model, bundled as resources.
    public static final class NameSets {
        public final Set<String> interesting = new HashSet<>();
        public final Set<String> frames = new HashSet<>();
        public final Set<String> withId = new HashSet<>();
        public final Set<String> withVersion = new HashSet<>();
        /// Every model class that carries an srsName attribute.
        public final Set<String> srsName = new HashSet<>();
        /// Classes derived from `DataManagedObjectStructure` — the set both the `dataSourceRef`
        /// and `responsibilitySetRef` arms use; see `CopyingCapture.startElement`.
        public final Set<String> dataManagedObjects = new HashSet<>();

        public static NameSets fromResources() throws IOException {
            NameSets s = new NameSets();
            readInto("interesting.txt", s.interesting);
            readInto("frames.txt", s.frames);
            readInto("with_id.txt", s.withId);
            readInto("with_version.txt", s.withVersion);
            readInto("srs_name.txt", s.srsName);
            readInto("datasource_refs.txt", s.dataManagedObjects);
            return s;
        }

        private static void readInto(String file, Set<String> into) throws IOException {
            for (String line : readResource(file)) {
                if (!line.isBlank()) into.add(line.strip());
            }
        }

        private static List<String> readResource(String file) throws IOException {
            String res = "/toolkit/sets/" + file;
            try (InputStream in = NameSets.class.getResourceAsStream(res)) {
                if (in == null) throw new IOException("missing resource " + res);
                List<String> lines = new ArrayList<>();
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
                return lines;
            }
        }
    }
}
