package toolkit.harness;

// This file is the EPIP PROFILE: which class goes in which frame, in which container, in which
// order, and what the closure is.
//
// Streams every object of a store into an EPIP-shaped NeTEx zip:
//
//   shared-01-resource.xml   ResourceFrame COMMON (dataSources, responsibilitySets, typesOfValue,
//                            organisations, vehicleTypes, zones — the XSD sequence)
//   shared-02-sites.xml      SiteFrame EU_PI_STOP (topographicPlaces, stopPlaces)
//   shared-03-network.xml    ServiceFrame EU_PI_NETWORK (Network, directions, lines, ...,
//                            stopAssignments — no routes, no journey patterns, no serviceLinks)
//   shared-04-links.xml      TimetableFrame EU_PI_TIMETABLE_LINKS (trainNumbers); omitted when
//                            empty.
//   shared-05-calendar.xml   ServiceCalendarFrame EU_PI_CALENDAR, UicOperatingPeriods only
//   shared-06-other.xml      GeneralFrame OTHER_REFERENCED — the reference-graph closure over the
//                            30 listed classes; TrainNumbers excluded; omitted when empty
//   gSS-shared.xml           ServiceFrame(serviceLinks) + TimetableFrame(journeyInterchanges) —
//                            the SHARD's shared group file; omitted when the shard has neither
//   gSS-NNNN.xml             ServiceFrame(journeyPatterns) + TimetableFrame(their journeys)
//
// The three level split simplifies processing: a shared-group file's entities stay indexed for
// the whole group; an individual file's index is popped the moment that file is mapped.
//
// Entry order is g* < shared-*, and within a shard the numbered files precede gSS-shared.xml.
// Entry metadata is pinned to the 1980 DOS epoch by [ZipWriter]. The content is reproducible only
// when --publication-timestamp and --frame-version are both given; without them the envelope reads
// the clock and two exports of one store differ in those two fields. tools/diff/export_zip.py
// normalises <PublicationTimestamp>, so it compares either way.
//
// Stored values are already LZ4'd XML fragments, so emission is decompress + byte copy between
// literal frame wrappers. shared-06 is the one exception: the closure decodes each
// member once, then still copies the stored fragment bytes.
//
// Every emitted fragment passes through toolkit.codec.FragmentOrder. That is a byte scan: it reorders a fragment's top-level children into the class's declared XSD sequence when
// the source feed emitted them out of order, and returns the same array when it did not. Without it the export would carry the source's schema violations verbatim. It
// is passed to FrameWriter as its Fragments hook, so it holds for every emitted
// fragment; a profile that wants the stored bytes verbatim omits it.
//
// Emission is single-threaded: most of the entry-emission time is zlib inside the one Deflater a
// ZipOutputStream owns. The prologue's four index reads do run on four
// threads; the joins, and so the emitted order, stay single-threaded.

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import toolkit.codec.FragmentCodec;
import toolkit.codec.FragmentOrder;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.ModelRegistry;
import toolkit.store.IdRow;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;
import toolkit.transform.epip.EpipConfig;
import toolkit.util.Log;
import toolkit.util.PeekIterator;
import toolkit.util.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class EpipDbToZip {

    // ------------------------------------------------------------ the driver's half of the CLI

    /// The log tag, and so the prefix on every line this class writes. The same string is passed
    /// to `DbToZip.driverMain`, so one export's output carries one prefix.
    public static final String TAG = "epip-db-to-xml";

    /// Named for the driver: it is what a user types.
    public static final String USAGE = "EpipDbToXml <db> <out.zip> [--shards N (>= 1)]"
            + " [--journeys-per-file N] [--participant-ref S] [--description S]"
            + " [--composite-frame-id S] [--publication-timestamp ISO] [--frame-version S]"
            + " [--codespace S] [--codespace-country CC] [--codespace-local S]"
            + " [--level N (0..9)] [--log-file F]";

    /// The flags the harness cannot know about; `--level` and `--log-file` come from [DbToZip].
    public static String[] flags() {
        return new String[] {"--shards=", "--journeys-per-file=", "--participant-ref=",
                "--description=", "--composite-frame-id=", "--publication-timestamp=",
                "--frame-version=", "--codespace=", "--codespace-country=", "--codespace-local="};
    }

    /// The profile's own options, read off the parsed [Args] before the store is opened — a
    /// [DbToZip.Setup], as a method reference.
    ///
    /// The shard count is pinned rather than host-derived: it decides the artefact, where thread
    /// counts decide only wall time.
    ///
    /// journeys-per-file is a bound rather than a target: only a single pattern with more journeys
    /// than the bound can overshoot it.
    ///
    /// The five envelope values are the publisher's, not the profile's, which is why they are flags
    /// and not constants: an export labelled with this toolkit's own name is a default, not an
    /// answer. `typeOfFrameRef` is NOT among them — it is what makes the document EPIP, so a flag
    /// there would let a caller mislabel a document this profile did not produce.
    ///
    /// Supplying both `--publication-timestamp` and `--frame-version` makes the export
    /// byte-reproducible; see [NetexEnvelope].
    public static DbToZip.ExportFn setup(Args a) {
        int shards = a.intFlag("--shards", 8);
        int journeysPerFile = a.intFlag("--journeys-per-file", 12_500);
        if (shards < 1) {
            System.err.println("usage: " + USAGE);
            System.exit(2);
        }
        NetexEnvelope.Options envelope = new NetexEnvelope.Options(
                a.get("--participant-ref", "netex-toolkit"),
                a.get("--description", TAG),
                // null: minted in export(), which is the first point that knows the codespace.
                a.get("--composite-frame-id", null),
                "epip:EU_PI_NETWORK_OFFER",
                a.dateTimeFlag("--publication-timestamp", null),
                a.get("--frame-version", null));
        String country = a.get("--codespace-country", "");
        String local = a.get("--codespace-local", "");
        // Must match the transform's own --codespace: it names the prefix of every id neither side
        // could read off a source object, and the two sides mint those ids independently.
        String codespace = a.get("--codespace", EpipConfig.CODESPACE);
        return (db, tx, zip) -> new EpipDbToZip(db, tx, zip, envelope, country, local, codespace)
                .export(shards, journeysPerFile);
    }

    // ------------------------------------------------------------ class routing (simple names)
    // Container order is the XSD sequence.
    private static final Map<String, List<String>> RESOURCE = orderedMap(
            "dataSources", "DataSource",
            "responsibilitySets", "ResponsibilitySet",
            // typesOfValueInFrame_RelStructure is a repeated CHOICE of ValueSet and TypeOfValue, so
            // a TypeOfValue subclass goes in directly and in any order. TypeOfService reaches it
            // through the substitution group TypeOfService < TypeOfEntity < TypeOfValue.
            "typesOfValue", "ValueSet,TypeOfService",
            "organisations", "Authority,Operator",
            "vehicleTypes", "VehicleType",
            "zones", "TransportAdministrativeZone");
    private static final Map<String, List<String>> SITES = orderedMap(
            "topographicPlaces", "TopographicPlace",
            "stopPlaces", "StopPlace");
    // No "routes": the EPIP service frame has no routes block — Route objects reach the export
    // only through the OTHER_REFERENCED closure. ServiceFrame sequence: network precedes
    // directions, then lines < destinationDisplays < scheduledStopPoints < serviceLinks <
    // connections < tariffZones < stopAssignments < notices.
    //
    // No "serviceLinks" either: every one of them belongs to the journey patterns that reference
    // it, so they ride in the shard's group files instead — see journeyGroups. A link no pattern
    // references is not emitted at all.
    private static final Map<String, List<String>> NETWORK = orderedMap(
            "directions", "Direction",
            "lines", "Line,FlexibleLine",
            "destinationDisplays", "DestinationDisplay",
            "scheduledStopPoints", "ScheduledStopPoint",
            "connections", "Connection,SiteConnection,DefaultConnection",
            "tariffZones", "TariffZone",
            "stopAssignments", "PassengerStopAssignment",
            "notices", "Notice");
    // operatingPeriods holds UicOperatingPeriod only; plain OperatingPeriods reach shared-06 via
    // the closure.
    private static final Map<String, List<String>> CALENDAR = orderedMap(
            "dayTypes", "DayType",
            "operatingPeriods", "UicOperatingPeriod",
            "dayTypeAssignments", "DayTypeAssignment");

    private static Map<String, List<String>> orderedMap(String... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], List.of(kv[i + 1].split(",")));
        return m;
    }

    /// The classes whose outward references define the OTHER_REFERENCED general frame. The closure
    /// yields what these reference (directly and transitively) but never the classes themselves.
    ///
    /// So the list doubles as the exclusion set, and that is why a class another frame emits is
    /// named here too: naming it is how the closure is told not to emit it a second time. That is
    /// what puts TypeOfService and ValueSet here, both of which the ResourceFrame emits.
    private static final List<String> OTHER_REFERENCED_CLASSES = List.of(
            "Authority",
            "Connection",
            "DayType",
            "DayTypeAssignment",
            "DataSource",
            "DefaultConnection",
            "DestinationDisplay",
            "Direction",
            "FlexibleLine",
            "Line",
            "Network",
            "Notice",
            "Operator",
            "PassengerStopAssignment",
            "ResponsibilitySet",
            "ScheduledStopPoint",
            "ServiceCalendar",
            "ServiceJourney",
            "ServiceJourneyInterchange",
            "ServiceJourneyPattern",
            "ServiceLink",
            "SiteConnection",
            "StopPlace",
            "TariffZone",
            "TopographicPlace",
            "TransportAdministrativeZone",
            "TypeOfService",
            "UicOperatingPeriod",
            "ValueSet",
            "VehicleType");

    /// Whether this Codespace fragment declares `xmlns`. A byte scan: the fragment is stored XML and
    /// decoding it to ask one question would cost a JAXB bind.
    ///
    /// The `<Xmlns>` is what an id carries, so it is what decides the match. The `id` attribute does
    /// not: a custom codespace takes its xmlns as its id, where the EPIP one is `epip_data` for an
    /// xmlns of `epd`, and matching on `id` finds the second but never the first.
    private static boolean declaresXmlns(byte[] fragment, String xmlns) {
        String s = new String(fragment, StandardCharsets.UTF_8);
        return xmlns.equals(between(s, "<Xmlns>", "</Xmlns>"));
    }

    /// A Codespace declaration for `xmlns`, written when the store declares none. The EPIP data
    /// codespace has an id and a URL the profile fixes; any other takes its xmlns for an id, which
    /// is the row the transform inserts for it.
    private static byte[] codespaceDecl(String xmlns) {
        boolean epip = EpipConfig.CODESPACE.equals(xmlns);
        return ("<Codespace id=\"" + (epip ? EpipConfig.CODESPACE_ID : xmlns) + "\"><Xmlns>" + xmlns
                + "</Xmlns>" + (epip ? "<XmlnsUrl>" + EpipConfig.CODESPACE_URL + "</XmlnsUrl>" : "")
                + "</Codespace>").getBytes(StandardCharsets.UTF_8);
    }

    /// The ResourceFrame classes whose instances belong in the document only once something
    /// names them.
    ///
    /// The profile's data-relevance rule asks that a document carry no unused ancillary
    /// definition, and a class map has no opinion on which of its rows the data uses. A publisher
    /// declares a whole code list where its data names part of it.
    private static final Set<String> RESOURCE_ONLY_WHEN_REFERENCED = Set.of("TypeOfService");

    /// Whether a ResourceFrame row is worth emitting — true for everything but an unreferenced
    /// instance of a vocabulary class.
    ///
    /// The inverse CSR answers "does anything reference this". The store caches it and the
    /// OTHER_REFERENCED closure builds it either way, so asking here only moves the build
    /// earlier.
    private boolean keepResourceRow(long fullKey) {
        if (!vocabularyIdx.contains(NulKeyCodec.fullKeyClassIdx(fullKey))) return true;
        return store.csr(tx).in(fullKey).length > 0;
    }

    /// A VERSION FRAME identifier, per the profile's frame-id structure:
    /// `{codespace}:{country}:{local}:{FrameType}_{EPIP_TYPE}:{topic}`.
    ///
    /// Mandatory, unlike the structure for frame contents, which is why this is minted rather than
    /// taken from whatever the source frames were called. Country and local are empty by default —
    /// the merged corpus spans three countries and has no single one — and an empty field keeps its
    /// placeholder colon.
    ///
    /// The topic is sanitised, not trusted: the profile allows only letters, digits and hyphen, and
    /// bounds it at fifteen characters. The shard suffix reaches this as `00_0001`, so the
    /// underscore alone would breach it.
    private String frameId(String frameType, String epipType, String topic) {
        StringBuilder b = new StringBuilder(64);
        b.append(generatedCodespace).append(':').append(country).append(':').append(local)
                .append(':').append(frameType).append('_').append(epipType).append(':');
        int n = 0;
        for (int i = 0; i < topic.length() && n < 15; i++) {
            char c = topic.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (ok) {
                b.append(c);
                n++;
            } else if (n > 0 && b.charAt(b.length() - 1) != '-') {
                b.append('-');
                n++;
            }
        }
        if (n == 0) b.append("all");
        return b.toString();
    }

    // ------------------------------------------------------------ state
    private final Store store;
    private final Txn tx;
    private final ZipWriter out;
    private final NetexEnvelope.Options envelope; // the per-entry wrapper's values, from setup
    private final FragmentCodec codec = new FragmentCodec();
    /// The codespace a generated id falls back to, and so the one every frame id carries. The
    /// transform's `--codespace`, which the export has to be given too: the store may declare
    /// several codespaces and nothing in it says which one the generated ids were built over.
    private final String generatedCodespace;
    /// The frame-id country and local codes, empty unless a single-country export names them.
    private final String country;
    private final String local;
    /// The class indices of [#RESOURCE_ONLY_WHEN_REFERENCED] this store has a class map for.
    private final Set<Integer> vocabularyIdx = new HashSet<>();
    private NetexEnvelope env; // built in export(), once the codespaces and the locale are known
    private FrameWriter fw;    // built with env in export(); it repeats the envelope per entry
    private final List<byte[]> codespaces = new ArrayList<>();
    private byte[] defaultLocale; // the frame defaults' locale block, or null
    // Resolved once in journeyGroups and read by emitGroup; null when this store has no such
    // class map, which is also when the corresponding key lists are always empty.
    private Class<?> serviceLinkClass;
    private Class<?> interchangeClass;

    private EpipDbToZip(Store store, Txn tx, ZipWriter out, NetexEnvelope.Options envelope,
                        String country, String local, String generatedCodespace) {
        this.store = store;
        this.tx = tx;
        this.out = out;
        this.envelope = envelope;
        this.country = country;
        this.local = local;
        this.generatedCodespace = generatedCodespace;
        for (String n : RESOURCE_ONLY_WHEN_REFERENCED) {
            Class<?> c = store.classForName(n);
            if (c != null) vocabularyIdx.add(store.classIdxInt(c));
        }
    }

    // ------------------------------------------------------------ export driver

    private void export(int shards, int journeysPerFile) throws IOException {
        Class<?> cs = store.classForName("Codespace");
        boolean declared = false;
        if (cs != null) {
            for (RawRow r : store.iterRawObjects(tx, cs)) {
                byte[] frag = FragmentOrder.normalise(cs, codec.decompress(r.blob()));
                codespaces.add(frag);
                declared |= declaresXmlns(frag, generatedCodespace);
            }
        }
        // The transform inserts the codespace its fallback ids carry, so a store it wrote declares
        // one already. A store it did not -- a feed loaded and exported straight through -- gets one
        // added here, because the frame ids below carry that prefix whatever the contents do. Added
        // only when absent: several feeds publish it themselves.
        //
        // Logged either way. The codespace is the caller's to name on both the transform and the
        // export, and naming it on one alone leaves the frame ids and the ids they govern under
        // different prefixes -- a document that still validates, so nothing downstream would say so.
        if (!declared) codespaces.add(codespaceDecl(generatedCodespace));
        Log.info("[%s] generated ids take codespace %s, %s by the store",
                TAG, generatedCodespace, declared ? "declared" : "undeclared and added here");
        defaultLocale = deriveDefaultLocale(); // one envelope-level pass, shared by every entry
        // The composite frame's id is minted like every other frame's unless a caller named one.
        // It cannot be minted in setup(): the codespace is only known once the store is open.
        NetexEnvelope.Options opts = envelope.compositeFrameId() != null ? envelope
                : new NetexEnvelope.Options(envelope.participantRef(), envelope.description(),
                        frameId("CompositeFrame", "EU_PI_NETWORK_OFFER", "offer"),
                        envelope.typeOfFrameRef(), envelope.publicationTimestamp(),
                        envelope.frameVersion());
        env = new NetexEnvelope(opts, codespaces, defaultLocale);
        fw = new FrameWriter(store, tx, out, env, FragmentOrder::normalise);

        journeyGroups(shards, journeysPerFile);

        // shared entries
        fw.entry("shared-01-resource.xml");
        env.frameOpen(out, "ResourceFrame", frameId("ResourceFrame", "EU_PI_COMMON", "common"),
                "epip:EU_PI_COMMON");
        fw.containers(RESOURCE, this::keepResourceRow);
        out.write("</ResourceFrame>");
        fw.end();

        fw.entry("shared-02-sites.xml");
        env.frameOpen(out, "SiteFrame", frameId("SiteFrame", "EU_PI_STOP", "stops"),
                "epip:EU_PI_STOP");
        fw.containers(SITES);
        out.write("</SiteFrame>");
        fw.end();

        fw.entry("shared-03-network.xml");
        env.frameOpen(out, "ServiceFrame", frameId("ServiceFrame", "EU_PI_NETWORK", "network"),
                "epip:EU_PI_NETWORK");
        // Exactly ONE Network, a direct child with no container element — every extra
        // Network row is dropped. Deterministic "first" = the store's scan (key) order, which is
        // insertion order for the toolkit's local keys.
        fw.first("Network");
        fw.containers(NETWORK);
        out.write("</ServiceFrame>");
        fw.end();

        trainNumbers();

        fw.entry("shared-05-calendar.xml");
        env.frameOpen(out, "ServiceCalendarFrame",
                frameId("ServiceCalendarFrame", "EU_PI_CALENDAR", "calendar"),
                "epip:EU_PI_CALENDAR");
        // The one object the EXPORT synthesises, so it has no source id to derive from and takes
        // the codespace the store declares -- the same fallback the transform's generators use.
        out.write("<ServiceCalendar id=\"" + generatedCodespace
                + ":ServiceCalendar:ServiceCalendar\" version=\"" + env.version() + "\">");
        calendarValidity();
        fw.containers(CALENDAR);
        out.write("</ServiceCalendar></ServiceCalendarFrame>");
        fw.end();

        otherFrame();

        // How much of the export the source feeds had out of sequence, and how much of it
        // FragmentOrder declined to rank (unknown child element / unrankable class).
        Log.info("[%s] child order: %d fragments seen, %d reordered, %d left verbatim (unrankable)",
                TAG, FragmentOrder.seen(), FragmentOrder.reordered(), FragmentOrder.skipped());
    }

    /// The frame defaults' locale block: the union of the Authority+Operator locales (null locales
    /// dropped, deduplicated by fragment bytes), the first in Authority-then-Operator scan order
    /// wins; when its languages block holds exactly one LanguageUsage, its Language becomes the
    /// DefaultLanguage. Done as a byte-scan of the organisation fragments (no JAXB): the root
    /// element is renamed Locale -> DefaultLocale, which is the element name the frame defaults
    /// declare it under.
    private byte[] deriveDefaultLocale() {
        LinkedHashSet<String> locales = new LinkedHashSet<>();
        for (String cn : List.of("Authority", "Operator")) {
            Class<?> c = store.classForName(cn);
            if (c == null) continue;
            for (RawRow r : store.iterRawObjects(tx, c)) {
                String s = new String(codec.decompress(r.blob()), StandardCharsets.UTF_8);
                int i = s.indexOf("<Locale>");
                if (i < 0) continue;
                int j = s.indexOf("</Locale>", i);
                if (j < 0) continue;
                locales.add(s.substring(i + "<Locale>".length(), j));
            }
        }
        if (locales.isEmpty()) return null;
        if (locales.size() > 1) {
            Log.info("[%s] %d distinct Authority/Operator locales -- the first in "
                    + "Authority-then-Operator scan order becomes the DefaultLocale",
                    TAG, locales.size());
        }
        String body = locales.iterator().next();
        int usages = countOccurrences(body, "<LanguageUsage");
        if (usages == 1) {
            String lang = between(body, "<Language>", "</Language>");
            if (lang != null) {
                String dl = "<DefaultLanguage>" + lang + "</DefaultLanguage>";
                int existing = body.indexOf("<DefaultLanguage>");
                if (existing >= 0) {
                    int end = body.indexOf("</DefaultLanguage>", existing);
                    body = body.substring(0, existing) + dl + body.substring(end + "</DefaultLanguage>".length());
                } else {
                    // LocaleStructure's XSD sequence: DefaultLanguage BEFORE Languages
                    int langs = body.indexOf("<languages");
                    body = langs >= 0 ? body.substring(0, langs) + dl + body.substring(langs) : body + dl;
                }
            }
        }
        return ("<DefaultLocale>" + body + "</DefaultLocale>").getBytes(StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /// ServiceCalendar FromDate/ToDate from a decode-free byte scan of the UicOperatingPeriods.
    /// With ZERO periods the max/min sentinels survive the scan and are still emitted:
    /// "9999-12-31" for the from-bound and "0001-01-01" for the to-bound. The degenerate inverted
    /// range is emitted as-is, per side — each sentinel survives independently when its own bound
    /// never updates.
    private void calendarValidity() throws IOException {
        Class<?> c = store.classForName("UicOperatingPeriod");
        String from = null, to = null;
        if (c != null) {
            for (RawRow r : store.iterRawObjects(tx, c)) {
                String s = new String(codec.decompress(r.blob()), StandardCharsets.UTF_8);
                String f = between(s, "<FromDate>", "</FromDate>");
                String t = between(s, "<ToDate>", "</ToDate>");
                if (f != null && (from == null || f.compareTo(from) < 0)) from = f;
                if (t != null && (to == null || t.compareTo(to) > 0)) to = t;
            }
        }
        if (from == null) from = "9999-12-31";
        if (to == null) to = "0001-01-01";
        out.write("<FromDate>" + from.substring(0, Math.min(10, from.length())) + "</FromDate>"
                + "<ToDate>" + to.substring(0, Math.min(10, to.length())) + "</ToDate>");
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        int j = s.indexOf(b, i);
        return j < 0 ? null : s.substring(i + a.length(), j).trim();
    }

    /// The OTHER_REFERENCED frame: members are the reference-graph closure over
    /// [#OTHER_REFERENCED_CLASSES], skipping what other frames already emit, with TrainNumbers
    /// excluded because shared-04's TimetableFrame emits them explicitly. The whole entry is omitted
    /// when the filtered closure is empty — [PeekIterator] answers that without draining the
    /// stream, so an empty closure costs no entry rather than an empty one.
    private void otherFrame() throws IOException {
        Set<Class<?>> classes = new HashSet<>();
        for (String name : OTHER_REFERENCED_CLASSES) {
            Class<?> c = store.classForName(name);
            if (c == null) c = ModelRegistry.classForName(name); // class absent from this db: no rows/edges, harmless
            if (c != null) classes.add(c);
        }
        Iterator<Object> closure = store.fetchAllReferencesByClass(tx, classes, true);
        PeekIterator<Object> members = new PeekIterator<>(new Iterator<Object>() {
            Object next;

            @Override public boolean hasNext() {
                while (next == null && closure.hasNext()) {
                    Object o = closure.next();
                    if (!(o instanceof noi.netex.model.TrainNumber)) next = o;
                }
                return next != null;
            }

            @Override public Object next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Object o = next;
                next = null;
                return o;
            }
        });
        if (!members.hasAny()) return;
        fw.entry("shared-06-other.xml");
        out.write("<GeneralFrame id=\"OTHER_REFERENCED\" version=\"" + env.version() + "\"><members>");
        for (Object o : members) writeMember(o);
        out.write("</members></GeneralFrame>");
        fw.end();
    }

    private final Map<Class<?>, Boolean> classHasOrder = new HashMap<>();

    /// One closure member: the stored fragment bytes of the decoded object's row, F2-adjusted.
    private void writeMember(Object o) throws IOException {
        Class<?> clazz = o.getClass();
        String id = ((noi.netex.model.EntityStructure) o).getId();
        String version = o instanceof noi.netex.model.EntityInVersionStructure eiv ? eiv.getVersion() : null;
        long fk = store.lookupFullKey(tx, id, version, clazz);
        if (fk == -1 || NulKeyCodec.fullKeyClassIdx(fk) != store.classIdxInt(clazz)) {
            Log.info("[%s] shared-06 member %s %s has no id-index row — skipped",
                    TAG, clazz.getSimpleName(), id);
            return;
        }
        // The order="1" injection below is a second profile step, and only shared-06 takes it.
        byte[] frag = FragmentOrder.normalise(clazz,
                codec.decompress(store.loadRawObject(tx, clazz, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fk)))));
        out.write(withDefaultOrder(clazz, frag));
        out.countObject();
    }

    /// The order="N" attribute on members is not serializer numbering — it is the object's own
    /// `order` field, a NeTEx attribute on order-bearing classes, and the loader defaults it to 1
    /// when the feed omits it. Fragments carry a feed-present order verbatim; this injects
    /// order="1" into the root start tag when the class is order-bearing (a `getOrder` accessor
    /// exists) and the fragment lacks the attribute.
    private byte[] withDefaultOrder(Class<?> clazz, byte[] frag) {
        Boolean has = classHasOrder.computeIfAbsent(clazz, c -> {
            try {
                c.getMethod("getOrder");
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
        if (!has) return frag;
        String s = new String(frag, StandardCharsets.UTF_8);
        // root start tag = up to the first '>' outside quotes
        int end = -1;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == '>' && !q) {
                end = i;
                break;
            }
        }
        if (end < 0) return frag;
        String tag = s.substring(0, end);
        if (rootTagHasOrder(tag)) return frag;
        int insert = tag.endsWith("/") ? end - 1 : end;
        return (s.substring(0, insert) + " order=\"1\"" + s.substring(insert)).getBytes(StandardCharsets.UTF_8);
    }

    /// quote-aware probe for an `order` attribute NAME in a start tag (not inside a value).
    private static boolean rootTagHasOrder(String tag) {
        boolean q = false;
        for (int i = 0; i + 6 <= tag.length(); i++) {
            char ch = tag.charAt(i);
            if (ch == '"') q = !q;
            else if (!q && Character.isWhitespace(ch) && tag.startsWith("order=", i + 1)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ journey groups
    /// The partition/entry semantics, decode-free:
    ///
    /// - a pattern's shard is the shard of its line, and a line's shard is the shard of its
    ///    interchange component — the set of lines transitively joined by a
    ///    ServiceJourneyInterchange. Components are bin-packed largest-first by journey count, so a
    ///    component is never split across shards and the shards still come out even. Keyed by
    ///    pattern id; a duplicate id's last enumeration wins for both the shard and the emitted row;
    /// - journeys are grouped by pattern ref (== the resolved target pattern's id) in scan
    ///    order; a shard emits its patterns sorted by (link component, line, id), and only
    ///    patterns that have journeys, so journey-less patterns are never emitted;
    /// - journeys whose pattern ref exists but resolves to no known pattern are dropped
    ///    silently — only a journey with no pattern ref at all spills to the orphan group;
    ///    those are emitted plain (no ServiceFrame) by shard 0 only, after its pattern groups,
    ///    with the seq continuing;
    /// - a shard's ServiceLinks and ServiceJourneyInterchanges do not ride in the numbered files
    ///    at all: they go to gSS-shared.xml, written last for the shard.
    ///
    /// The unresolvable-vs-ref-less distinction is read off the stored indexes: a resolved
    /// SJ->pattern edge routes, and an unresolved (or resolved-to-a-non-SJP-pattern-class) pattern
    /// ref drops.
    private void journeyGroups(int shards, int journeysPerFile) throws IOException {
        Class<?> sjp = store.classForName("ServiceJourneyPattern");
        Class<?> sj = store.classForName("ServiceJourney");
        if (sjp == null && sj == null) return;
        int sjpIdx = sjp == null ? -1 : store.classIdxInt(sjp);
        int sjIdx = sj == null ? -1 : store.classIdxInt(sj);
        // The pattern classes a journey may name that this profile does not emit. ServicePattern is
        // among them: EPIP permits it beside ServiceJourneyPattern, so a feed built on it would have
        // every journey land in the silent-drop branch below without being counted.
        Set<Integer> otherPatternIdx = new HashSet<>();
        for (String n : List.of("JourneyPattern", "DeadRunJourneyPattern", "ServicePattern")) {
            Class<?> c = store.classForName(n);
            if (c != null) otherPatternIdx.add(store.classIdxInt(c));
        }
        serviceLinkClass = store.classForName("ServiceLink");
        interchangeClass = store.classForName("ServiceJourneyInterchange");
        int slIdx = serviceLinkClass == null ? -1 : store.classIdxInt(serviceLinkClass);
        int sjiIdx = interchangeClass == null ? -1 : store.classIdxInt(interchangeClass);
        // A pattern reaches its line through RouteView/LineRef, and the ref is declared on the
        // Line/FlexibleLine substitution pair, so both class maps count as "the line".
        Set<Integer> lineIdx = new HashSet<>();
        for (String n : List.of("Line", "FlexibleLine")) {
            Class<?> c = store.classForName(n);
            if (c != null) lineIdx.add(store.classIdxInt(c));
        }

        // The four independent indexes are read one per thread; the joins below run on this one.
        Scans scans = readParallel(sjp, sj, sjpIdx, sjIdx, otherPatternIdx, lineIdx, slIdx, sjiIdx);
        Log.info("[%s] prologue (4 readers): id-index %d rows, SJP %d, edges %d, "
                        + "unresolved %d, SJ %d, %.2f s",
                TAG, scans.idRows, scans.sjpRows, scans.edgeRows, scans.unresRows, scans.sjRows,
                scans.wall);
        Log.info("[%s] partition inputs: %d patterns with a line, %d pattern->link "
                        + "edges, %d interchanges",
                TAG, scans.patternLine.size(), scans.linkEdges, scans.interchangeJourneys.size());

        // ---- the joins. Single-threaded, in scan order.

        Map<String, Long> patternKey = new HashMap<>();
        for (int i = 0; i < scans.sjpKeys.size(); i++) {
            long fk = scans.sjpKeys.getLong(i);
            patternKey.put(scans.patternId.get(fk), fk); // last enumeration of a duplicate id wins
        }

        // Pattern ref -> journey keys, in scan order.
        Map<String, LongArrayList> byPattern = new HashMap<>();
        LongArrayList orphans = new LongArrayList();
        long droppedByPatternRef = 0;
        for (int i = 0; i < scans.sjKeys.size(); i++) {
            long fk = scans.sjKeys.getLong(i);
            long pat = scans.sjToPattern.get(fk);
            if (pat != -1) {
                byPattern.computeIfAbsent(scans.patternId.get(pat), k -> new LongArrayList(4)).add(fk);
            } else if (!scans.refDrop.contains(fk)) {
                orphans.add(fk); // no pattern ref at all -> orphan spill
            } else {
                droppedByPatternRef++; // named a pattern this profile does not emit
            }
        }
        // A journey deleted from the output, so it is reported the way every other such deletion is
        // rather than left to a reader noticing the export is short.
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", droppedByPatternRef, TAG,
                "ServiceJourneys named a pattern this profile does not emit and were dropped");

        // ---- the line partition: one union-find over line full_keys, joined by both relations.
        // An interchange edge is a correctness constraint (see the method doc). An edge from a
        // shared ServiceLink is an efficiency one: a link is written once per group file that uses
        // it, so lines sharing a link want the same shard.
        Union lines = new Union();
        for (LongArrayList js : scans.interchangeJourneys.values()) {
            long first = -1;
            for (long jk : js) {
                long line = lineOfJourney(scans, jk);
                if (line == -1) continue;
                if (first == -1) first = line;
                else lines.union(first, line);
            }
        }
        Long2LongOpenHashMap firstLineOfLink = new Long2LongOpenHashMap();
        firstLineOfLink.defaultReturnValue(-1);
        for (Long2ObjectMap.Entry<LongArrayList> e : scans.patternLinks.long2ObjectEntrySet()) {
            long line = scans.patternLine.get(e.getLongKey());
            if (line == -1) continue;
            for (long link : e.getValue()) {
                long prev = firstLineOfLink.putIfAbsent(link, line);
                if (prev != -1) lines.union(prev, line);
            }
        }

        // ---- shard assignment: bin-pack whole components by journey count.
        Long2LongOpenHashMap compJourneys = new Long2LongOpenHashMap();
        for (Map.Entry<String, LongArrayList> e : byPattern.entrySet()) {
            long comp = lines.find(lineOfPattern(scans, patternKey.get(e.getKey())));
            compJourneys.addTo(comp, e.getValue().size());
        }
        List<Long> comps = new ArrayList<>(compJourneys.keySet());
        // Largest first, ties broken by the component key: the packing must not depend on hash order.
        comps.sort((a, b) -> {
            int c = Long.compare(compJourneys.get((long) b), compJourneys.get((long) a));
            return c != 0 ? c : Long.compareUnsigned(a, b);
        });
        long[] binJourneys = new long[shards];
        Long2LongOpenHashMap compShard = new Long2LongOpenHashMap();
        for (long comp : comps) {
            int bin = 0;
            for (int i = 1; i < shards; i++) {
                if (binJourneys[i] < binJourneys[bin]) bin = i;
            }
            compShard.put(comp, bin);
            binJourneys[bin] += compJourneys.get(comp);
        }
        long lo = Long.MAX_VALUE, hi = 0;
        for (long b : binJourneys) {
            lo = Math.min(lo, b);
            hi = Math.max(hi, b);
        }
        Log.info("[%s] %d line components over %d shards: largest %,d journeys, "
                        + "shard spread %,d..%,d",
                TAG, comps.size(), shards, comps.isEmpty() ? 0 : compJourneys.get((long) comps.get(0)),
                lo, hi);

        // Interchanges ride with their component's shard. Enumerated from the class map rather
        // than the edge index, so an interchange whose journey refs are all unresolved still gets
        // emitted: it goes to shard 0.
        List<LongArrayList> shardInterchanges = new ArrayList<>();
        for (int s = 0; s < shards; s++) shardInterchanges.add(new LongArrayList());
        long interchangeRows = 0;
        if (interchangeClass != null) {
            for (byte[] localKey : store.scanObjects(tx, interchangeClass)) {
                long fk = store.fullKeyOf(interchangeClass, localKey);
                interchangeRows++;
                int s = 0;
                LongArrayList js = scans.interchangeJourneys.get(fk);
                if (js != null) {
                    for (long jk : js) {
                        long line = lineOfJourney(scans, jk);
                        if (line != -1) {
                            s = (int) compShard.get(lines.find(line));
                            break;
                        }
                    }
                }
                shardInterchanges.get(s).add(fk);
            }
        }

        // Shards stream in sorted-name order so the zip's entry sequence equals the entries.sort()
        // even at >= 100 shards ("g100-…" sorts before "g99-…"); within a shard the zero-padded
        // 4-digit seq makes generation order == sorted order. gSS-shared.xml is written after the
        // shard's numbered files and still sorts after them, because '0' is 0x30 and 's' is 0x73.
        List<Integer> shardOrder = new ArrayList<>();
        for (int s = 0; s < shards; s++) shardOrder.add(s);
        shardOrder.sort(java.util.Comparator.comparing(s -> String.format("g%02d-", s)));
        long emittedInterchanges = 0;
        for (int s : shardOrder) {
            // The sort keys are resolved once per pattern, outside the comparator: `find` walks and
            // rewrites the union-find, and the id -> key lookup is a string hash.
            record Pat(String id, long comp, long line) {}
            List<Pat> pats = new ArrayList<>();
            for (String pid : byPattern.keySet()) {
                long line = lineOfPattern(scans, patternKey.get(pid));
                if (compShard.get(lines.find(line)) != s) continue;
                pats.add(new Pat(pid, lines.find(line), line));
            }
            // Component, then line, then id: a component's lines stay adjacent, so a file is a
            // contiguous run of one or two components rather than a scatter.
            pats.sort((a, b) -> {
                int c = Long.compareUnsigned(a.comp(), b.comp());
                if (c != 0) return c;
                c = Long.compareUnsigned(a.line(), b.line());
                return c != 0 ? c : compareCodePoints(a.id(), b.id());
            });
            // The shard's own ServiceLinks: the union over all its patterns, resolved once, before
            // any chunk is cut. Only a LongArrayList of keys is held while the numbered files
            // stream — the fragments themselves are read in emitSharedGroup.
            LongArrayList shardPatterns = new LongArrayList(pats.size());
            for (Pat p : pats) shardPatterns.add(patternKey.get(p.id()).longValue());
            LongArrayList shardLinks = linksOf(scans, shardPatterns);

            int seq = 0;
            List<LongArrayList[]> chunks = new ArrayList<>();
            // The cut is a running total over the sorted pattern list, and journeysPerFile is a
            // bound. Nothing ties a file to a component boundary, and the only thing that can
            // overshoot is a single pattern with more journeys than the bound — which cannot be
            // split, because TripPatternMapper indexes a pattern's journeys from one file's
            // localValues(). The corpus mean is 8.3 journeys per pattern, so that is a guard, not
            // a live path.
            //
            // The budget is `bound` rather than journeysPerFile: it divides the shard evenly over
            // the file count journeysPerFile implies, so every file is the same size instead of a
            // runt at the end, and bound <= journeysPerFile.
            long shardJourneys = 0;
            for (Pat p : pats) shardJourneys += byPattern.get(p.id()).size();
            int files = (int) Math.max(1, (shardJourneys + journeysPerFile - 1) / journeysPerFile);
            long bound = Math.max(1, (shardJourneys + files - 1) / files);
            LongArrayList chunkPatterns = new LongArrayList();
            LongArrayList chunkJourneys = new LongArrayList();
            for (Pat p : pats) {
                LongArrayList js = byPattern.get(p.id());
                if (!chunkJourneys.isEmpty() && chunkJourneys.size() + js.size() > bound) {
                    chunks.add(new LongArrayList[] {chunkPatterns, chunkJourneys});
                    chunkPatterns = new LongArrayList();
                    chunkJourneys = new LongArrayList();
                }
                chunkPatterns.add(patternKey.get(p.id()).longValue());
                chunkJourneys.addAll(js);
            }
            if (!chunkJourneys.isEmpty()) {
                chunks.add(new LongArrayList[] {chunkPatterns, chunkJourneys});
            }
            // Orphans carry no pattern, so nothing constrains them but the bound; they are sliced
            // the same way.
            if (s == 0) {
                for (int i = 0; i < orphans.size(); i += journeysPerFile) {
                    int end = Math.min(i + journeysPerFile, orphans.size());
                    LongArrayList slice = new LongArrayList(end - i);
                    for (int j = i; j < end; j++) slice.add(orphans.getLong(j));
                    chunks.add(new LongArrayList[] {new LongArrayList(), slice});
                }
            }
            for (LongArrayList[] chunk : chunks) {
                emitGroup(s, ++seq, chunk[0], chunk[1], sjp, sj);
            }
            // Last for the shard: 'g07-0011.xml' < 'g07-shared.xml' < 'g08-0001.xml', so
            // generation order is still the name sort and nothing is buffered.
            LongArrayList shardIcs = shardInterchanges.get(s);
            emittedInterchanges += shardIcs.size();
            emitSharedGroup(s, shardLinks, shardIcs);
            Log.info("[%s] shard %d: %,d patterns, %,d journeys over %d files, "
                            + "%,d links, %,d interchanges",
                    TAG, s, pats.size(), shardJourneys, chunks.size(), shardLinks.size(),
                    shardIcs.size());
        }
        // Every stored interchange must have been placed; a shortfall is a transfer that silently
        // stopped existing.
        if (emittedInterchanges != interchangeRows) {
            throw new IOException("journey groups emitted " + emittedInterchanges + " of "
                    + interchangeRows + " ServiceJourneyInterchanges");
        }
    }

    /// A pattern's line full_key, or -1 — the pattern id may name no row (`patternKey.get` null)
    /// and the pattern may carry no line reference at all. Both collapse to the same "no line"
    /// bucket, which sorts last and forms one component of its own.
    ///
    /// The second case is a feed property, not a broken reference: some Austrian patterns arrive
    /// already formed, carrying the line only as text (`Name`/`ShortName`/`PrivateCode`) with no
    /// RouteView, LineRef or RouteRef. EPIP normally has one because the toolkit synthesises
    /// RouteView/LineRef from the journey's LineRef when it builds a pattern from calls. On the
    /// production store that is 44,192 of 565,807 patterns.
    ///
    /// None of them is emitted, so the "-1" branches below are a guard rather than a live path:
    /// almost all have no journey at all, and a pattern with no journeys is never emitted, while
    /// the handful that are the target of a ServiceJourney edge are no journey's first pattern
    /// edge, which is the one [Scans#sjToPattern] keeps.
    private static long lineOfPattern(Scans scans, Long patternFullKey) {
        return patternFullKey == null ? -1 : scans.patternLine.get(patternFullKey.longValue());
    }

    /// A journey's line, through its pattern. -1 when either hop is missing.
    private static long lineOfJourney(Scans scans, long journeyFullKey) {
        long pat = scans.sjToPattern.get(journeyFullKey);
        return pat == -1 ? -1 : scans.patternLine.get(pat);
    }

    /// The ServiceLinks a shard's patterns reference, deduplicated, in ascending full_key order —
    /// which is the store's scan order, so the emitted sequence is the one `fw.containers` would
    /// have produced from `shared-03`.
    private static LongArrayList linksOf(Scans scans, LongArrayList patterns) {
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long pk : patterns) {
            LongArrayList links = scans.patternLinks.get(pk);
            if (links != null) seen.addAll(links);
        }
        LongArrayList out = new LongArrayList(seen);
        // The cast picks fastutil's primitive overload: `sort(LongComparator)` and the inherited
        // `sort(Comparator<? super Long>)` are both applicable to an implicitly typed lambda.
        out.sort((LongComparator) (x, y) -> Long.compareUnsigned(x, y));
        return out;
    }

    /// Union-find over line full_keys, with the component named by its smallest member. The root
    /// rule is by value rather than by rank or union order: the root leaks into the emitted order
    /// through the sort, and two runs over the same store do not see the edges in the same order.
    private static final class Union {
        private final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();

        Union() {
            parent.defaultReturnValue(Long.MIN_VALUE);
        }

        long find(long x) {
            long p = parent.get(x);
            if (p == Long.MIN_VALUE) return x; // singleton: never unioned
            long root = p;
            while (true) {
                long up = parent.get(root);
                if (up == Long.MIN_VALUE) break;
                root = up;
            }
            for (long cur = x; cur != root; ) {
                long next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        void union(long a, long b) {
            long ra = find(a), rb = find(b);
            if (ra == rb) return;
            if (Long.compareUnsigned(ra, rb) < 0) parent.put(rb, ra);
            else parent.put(ra, rb);
        }
    }

    // ------------------------------------------------------------ the prologue's four readers

    /// The prologue's four index reads and nothing else — no join, no emitted byte.
    ///
    /// The block is dominated by cold device I/O. The four reads are independent, and one thread
    /// under-uses the device because an LMDB index scan is not sequential — its leaf pages are
    /// scattered through the file — so four concurrent scans fill it.
    ///
    /// Each field below is filled by exactly one reader, in that reader's own scan order, and every
    /// reader is internally sequential: the SJP/SJ key lists are cursor order, `sjToPattern`'s
    /// first-wins is one thread walking `iterEdges` once. The joins in `journeyGroups` run
    /// single-threaded over those lists in index order.
    ///
    /// Fields are written by four threads and read by one. Each writer owns a disjoint set of
    /// fields and the `Thread.join()` barrier in [#runAll] is the happens-before edge that
    /// publishes all of them; nothing is read before that barrier.
    private static final class Scans {
        /// A — `_id_idx`: pattern full_key -> id (decode-free; ids drive sort + grouping).
        final Long2ObjectOpenHashMap<String> patternId = new Long2ObjectOpenHashMap<>();
        /// B — the ServiceJourneyPattern class map, in cursor order. The order IS the shard rule.
        final LongArrayList sjpKeys = new LongArrayList();
        /// C — journey -> pattern, off the stored edge index.
        final Long2LongOpenHashMap sjToPattern = new Long2LongOpenHashMap();
        /// C — journeys whose pattern ref exists but is unresolvable; these are dropped.
        final LongOpenHashSet refDrop = new LongOpenHashSet();
        /// C — pattern -> line, off the same edge scan. The partition key.
        final Long2LongOpenHashMap patternLine = new Long2LongOpenHashMap();
        /// C — pattern -> the ServiceLinks it references. The pattern's own row owns these edges:
        /// the ref lives on a StopPointInJourneyPattern, which is an EMBEDDED entity with no row
        /// of its own, and the insert walk attributes a nested ref to the containing row.
        final Long2ObjectOpenHashMap<LongArrayList> patternLinks = new Long2ObjectOpenHashMap<>();
        /// C — interchange -> the journeys it names. Two, unless a ref was unresolvable.
        final Long2ObjectOpenHashMap<LongArrayList> interchangeJourneys = new Long2ObjectOpenHashMap<>();
        /// D — the ServiceJourney class map, in cursor order. The order IS the grouping order.
        final LongArrayList sjKeys = new LongArrayList();

        long idRows, sjpRows, edgeRows, unresRows, sjRows, linkEdges;
        double wall;

        Scans() {
            sjToPattern.defaultReturnValue(-1);
            patternLine.defaultReturnValue(-1);
        }
    }

    /// Reader A: the id index.
    private void readIdIndex(Iterable<IdRow> rows, int sjpIdx, Scans s) {
        long n = 0;
        for (IdRow r : rows) {
            n++;
            if (NulKeyCodec.classTrailer(r.encodedKey()) == sjpIdx) {
                s.patternId.put(r.fullKey(), NulKeyCodec.idPart(r.encodedKey()));
            }
        }
        s.idRows = n;
    }

    /// Reader B / D: a class map's local keys, widened to full_keys in cursor order.
    private void readClassKeys(Iterable<byte[]> rows, Class<?> clazz, LongArrayList keys) {
        for (byte[] localKey : rows) keys.add(store.fullKeyOf(clazz, localKey));
    }

    /// Reader C: the edge + unresolved indexes.
    ///
    /// This one takes the txn rather than a ready-made `Iterable`: `iterEdges` calls
    /// `ensureEdgeState()` eagerly, so building the iterable would run `V2Store.loadEdgeState` on
    /// the caller's thread.
    private void readEdgeState(Txn txn, int sjIdx, int sjpIdx, Set<Integer> otherPatternIdx,
                               Set<Integer> lineIdx, int slIdx, int sjiIdx, Scans s) {
        long edges = 0, unres = 0;
        for (long[] e : store.iterEdges(txn)) {
            edges++;
            int sc = NulKeyCodec.fullKeyClassIdx(e[0]);
            int tc = NulKeyCodec.fullKeyClassIdx(e[1]);
            if (sc == sjIdx) {
                if (tc == sjpIdx) s.sjToPattern.putIfAbsent(e[0], e[1]);
                else if (otherPatternIdx.contains(tc)) s.refDrop.add(e[0]); // ref present, not an SJP id
            } else if (sc == sjpIdx) {
                // A pattern names ONE line; first edge wins, as with the journey's pattern ref.
                if (lineIdx.contains(tc)) s.patternLine.putIfAbsent(e[0], e[1]);
                else if (tc == slIdx) {
                    s.patternLinks.computeIfAbsent(e[0], (long k) -> new LongArrayList(4)).add(e[1]);
                    s.linkEdges++;
                }
            } else if (sc == sjiIdx && tc == sjIdx) {
                s.interchangeJourneys.computeIfAbsent(e[0], (long k) -> new LongArrayList(2)).add(e[1]);
            }
        }
        for (UnresolvedRow r : store.iterUnresolved(txn)) {
            unres++;
            if (NulKeyCodec.fullKeyClassIdx(r.referrerFullKey()) != sjIdx) continue;
            Class<?> declared = store.classForIdx(NulKeyCodec.classTrailer(r.encodedTarget()));
            String n = declared == null ? "" : declared.getSimpleName();
            if (n.equals("ServiceJourneyPattern") || n.equals("JourneyPattern")
                    || n.equals("DeadRunJourneyPattern") || n.equals("ServicePattern")) {
                s.refDrop.add(r.referrerFullKey());
            }
        }
        s.edgeRows = edges;
        s.unresRows = unres;
    }

    /// The four readers on four threads, each with its own read txn (the env carries MDB_NOTLS, so
    /// a read txn is not thread-bound, and 4 + 1 readers sit well inside the 126-slot table).
    ///
    /// Every `env.openDbi` happens on this thread, before any reader starts: `mdb_dbi_open` must be
    /// serialised, and calling it from the scan threads makes them collect each other's handles.
    /// The mechanism is structural — `iterIdIndex`/`scanObjects` resolve their `Dbi` eagerly and
    /// capture it in the returned `Iterable`, so constructing the three iterables below is what
    /// opens the handles and the threads only step cursors. Reader C opens no handle at all
    /// (`_edge_blocks`/`_unres_keys` are opened in `V2Store`'s constructor).
    private Scans readParallel(Class<?> sjp, Class<?> sj, int sjpIdx, int sjIdx,
                               Set<Integer> otherPatternIdx, Set<Integer> lineIdx, int slIdx,
                               int sjiIdx) throws IOException {
        long t0 = System.nanoTime();
        Scans s = new Scans();
        try (Txn ta = store.roTxn(); Txn tb = store.roTxn();
             Txn tc = store.roTxn(); Txn td = store.roTxn()) {
            Iterable<IdRow> idRows = sjpIdx == -1 ? List.of() : store.iterIdIndex(ta);
            Iterable<byte[]> sjpRows = sjp == null ? List.of() : store.scanObjects(tb, sjp);
            Iterable<byte[]> sjRows = sj == null ? List.of() : store.scanObjects(td, sj);
            runAll(List.of(
                    () -> readIdIndex(idRows, sjpIdx, s),
                    () -> readClassKeys(sjpRows, sjp, s.sjpKeys),
                    () -> readEdgeState(tc, sjIdx, sjpIdx, otherPatternIdx, lineIdx, slIdx, sjiIdx, s),
                    () -> readClassKeys(sjRows, sj, s.sjKeys)));
        }
        s.sjpRows = s.sjpKeys.size();
        s.sjRows = s.sjKeys.size();
        s.wall = (System.nanoTime() - t0) / 1e9;
        return s;
    }

    /// Run each task on its own thread and join them all; the first failure aborts the export.
    ///
    /// Not promoted to a harness helper with the rest of the plumbing: this fan-out is safe only
    /// because of properties of the four readers above — each opens its `Dbi` on the calling thread
    /// before its task starts, reader C opens none, each writes a disjoint set of `Scans` fields —
    /// and none of those is enforceable by a generic "run these Runnables" helper.
    private static void runAll(List<Runnable> tasks) throws IOException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Runnable task = tasks.get(i);
            Thread t = new Thread(() -> {
                try {
                    task.run();
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "epip-prologue-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for prologue reader " + t.getName(), e);
            }
        }
        Throwable e = failure.get();
        if (e != null) throw new IOException("prologue reader failed: " + e, e);
    }

    /// Entry names sort by CODE POINTS; String.compareTo is UTF-16 code-unit order and disagrees
    /// on supplementary-plane characters.
    private static int compareCodePoints(String a, String b) {
        int la = a.length(), lb = b.length(), n = Math.min(la, lb);
        for (int i = 0; i < n; i++) {
            char ca = a.charAt(i), cb = b.charAt(i);
            if (ca != cb) {
                int fa = ca >= 0xE000 ? ca - 0x800 : (ca >= 0xD800 ? ca + 0x2000 : ca);
                int fb = cb >= 0xE000 ? cb - 0x800 : (cb >= 0xD800 ? cb + 0x2000 : cb);
                return Integer.compare(fa, fb);
            }
        }
        return Integer.compare(la, lb);
    }

    /// One numbered group file: the patterns and their journeys, nothing else. Its shard's
    /// ServiceLinks resolve out of gSS-shared.xml, one index level up.
    private void emitGroup(int shard, int seq, LongArrayList patterns, LongArrayList journeys,
                           Class<?> sjp, Class<?> sj) throws IOException {
        String name = String.format("g%02d-%04d.xml", shard, seq);
        String suffix = String.format("%02d_%04d", shard, seq);
        fw.entry(name);
        if (!patterns.isEmpty()) {
            env.frameOpen(out, "ServiceFrame", frameId("ServiceFrame", "EU_PI_NETWORK", suffix),
                    "epip:EU_PI_NETWORK");
            out.write("<journeyPatterns>");
            fw.byFullKey(patterns, sjp);
            out.write("</journeyPatterns>");
            out.write("</ServiceFrame>");
        }
        if (!journeys.isEmpty()) {
            env.frameOpen(out, "TimetableFrame", frameId("TimetableFrame", "EU_PI_TIMETABLE", suffix),
                    "epip:EU_PI_TIMETABLE");
            out.write("<vehicleJourneys>");
            fw.byFullKey(journeys, sj);
            out.write("</vehicleJourneys>");
            out.write("</TimetableFrame>");
        }
        fw.end();
    }

    /// One shard's shared group file: the ServiceLinks its patterns reference and the
    /// ServiceJourneyInterchanges its journeys take part in. OTP loads this before the shard's
    /// numbered files and keeps its index for the whole group, so a link resolves from any of them
    /// (`serviceLinkById.lookup` falls back to the parent index) and an interchange is still mapped
    /// at the group pop, by which point every one of the shard's journeys is a mapped trip.
    ///
    /// Skipped entirely when the shard has neither: `GroupEntries.sharedEntries()` is then empty
    /// and OTP loads the group's numbered files on their own. Emitting the entry anyway would put
    /// a PublicationDelivery with no frame in it into the zip.
    ///
    /// Container order inside each frame is the XSD sequence: ServiceFrame's `serviceLinks` is
    /// ServiceInFrameGroup, TimetableFrame's `journeyInterchanges` is InterchangeInFrameGroup, last
    /// of that frame's groups.
    private void emitSharedGroup(int shard, LongArrayList links, LongArrayList interchanges)
            throws IOException {
        if (links.isEmpty() && interchanges.isEmpty()) return;
        String suffix = String.format("%02d_shared", shard);
        fw.entry(String.format("g%02d-shared.xml", shard));
        if (!links.isEmpty()) {
            env.frameOpen(out, "ServiceFrame", frameId("ServiceFrame", "EU_PI_NETWORK", suffix),
                    "epip:EU_PI_NETWORK");
            out.write("<serviceLinks>");
            fw.byFullKey(links, serviceLinkClass);
            out.write("</serviceLinks>");
            out.write("</ServiceFrame>");
        }
        if (!interchanges.isEmpty()) {
            env.frameOpen(out, "TimetableFrame", frameId("TimetableFrame", "EU_PI_TIMETABLE", suffix),
                    "epip:EU_PI_TIMETABLE");
            out.write("<journeyInterchanges>");
            fw.byFullKey(interchanges, interchangeClass);
            out.write("</journeyInterchanges>");
            out.write("</TimetableFrame>");
        }
        fw.end();
    }

    // ------------------------------------------------------------ shared train numbers

    /// TrainNumbers, and nothing else.
    ///
    /// Globally shared rather than per-shard, because journeys in every group reference them and a
    /// group's index is dropped when that group is done. OTP does not map TrainNumbers at all
    /// (`TimeTableFrameParser` only warns about them); they are emitted for the artefact's
    /// completeness.
    private void trainNumbers() throws IOException {
        if (!fw.hasRows("TrainNumber")) return;
        fw.entry("shared-04-links.xml");
        env.frameOpen(out, "TimetableFrame",
                frameId("TimetableFrame", "EU_PI_TIMETABLE", "links"), "epip:EU_PI_TIMETABLE");
        // Container order = TimetableFrameGroup's xsd:sequence of groups:
        //   TimetablePropertiesGroup -> TimetableDefaultsGroup -> TimeDemandTypeInFrameGroup
        //   -> TimetableJourneyInFrameGroup (vehicleJourneys, then trainNumbers)
        //   -> NoticesInFrameGroup -> InterchangeInFrameGroup (journeyInterchanges)
        // which is also the order emitGroup writes its two containers in.
        out.write("<trainNumbers>");
        fw.all("TrainNumber");
        out.write("</trainNumbers>");
        out.write("</TimetableFrame>");
        fw.end();
    }

}
