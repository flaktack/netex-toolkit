// Round-trip fidelity harness — the Java arm. The toolkit jar is used READ-ONLY.
//
// For every feed it runs TWO independent passes over the SAME bytes and writes two gzipped TSVs
// per feed, in document order, one row per interesting element:
//
//   <out>/<feed>.src.tsv.gz    label \t seq \t localName \t idAttr \t verAttr \t b64(source subtree)
//   <out>/<feed>.java.tsv.gz   label \t seq \t class \t id \t version \t b64(captured fragment) \t b64(re-marshal)
//   <out>/<feed>.a1.tsv.gz     label \t seq \t class \t id \t version \t b64(blob actually in the db)
//
// Three Java modes: "the stored fragment" has two different meanings and only one of them is what
// downstream reads.
//   A0  the fragment the loader CAPTURED at parse time      (is CopyingCapture sound?)
//   A1  the blob actually in the database AFTER the full
//       netex_to_db chain (load + resolve + resolveEmbeddings)   (what is really stored?)
//   Bj  FragmentCodec.marshalFragment(bean)                 (what a re-export would emit)
// A1 is enabled with --a1 <scratch-db-dir>; without it phase 3 is skipped and no db is created.
//
// Pass 1 (source) is independent of toolkit.load.CopyingCapture: a plain StAX event copy of the
// element subtree with in-scope namespace declarations hoisted onto the fragment root, and nothing
// else -- no id/version/dataSourceRef/responsibilitySetRef synthesis, no srsName injection.
//
// Pass 2 (loader) runs the production NetexLoader against a stub Store that never writes: for
// each object it records (a) the stored fragment bytes the loader captured -- Mode A -- and
// (b) FragmentCodec.marshalFragment(bean), the re-serialised bean -- Mode B.
//
// The two passes align by DOCUMENT ORDER, per XML stream: the loader emits one object per
// interesting element that has or is given an id, the source pass one per interesting element with
// a model class. The comparator re-checks that alignment per stream and reports drift as a finding
// rather than silently mis-pairing.

import jakarta.xml.bind.JAXBElement;
import org.codehaus.stax2.XMLStreamReader2;
import org.codehaus.stax2.XMLStreamWriter2;
import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.EntityStructure;
import toolkit.codec.FragmentCodec;
import toolkit.graph.Csr;
import toolkit.load.NetexLoader;
import toolkit.model.ModelRegistry;
import toolkit.store.IdRow;
import toolkit.store.ObjectRow;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;
import toolkit.store.WithFragment;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLInputFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RoundTrip {

    static final String NETEX_NS = ModelRegistry.NETEX_NS;
    static final Base64.Encoder B64 = Base64.getEncoder();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RoundTrip [--a1 <dbdir>] [--format v1|v2] <outdir> <feed> ...");
            System.exit(2);
        }
        Path a1Root = null;
        String format = "v1";
        int a = 0;
        while (a < args.length && args[a].startsWith("--")) {
            if (args[a].equals("--a1")) a1Root = Paths.get(args[++a]);
            else if (args[a].equals("--format")) format = args[++a];
            else throw new IllegalArgumentException("unknown flag " + args[a]);
            a++;
        }
        Path out = Paths.get(args[a++]);
        Files.createDirectories(out);
        if (a1Root != null) Files.createDirectories(a1Root);
        // pre-warm the two lazy singletons once per JVM
        FragmentCodec.packageContext();
        NetexLoader.NameSets sets = NetexLoader.NameSets.fromResources();
        NetexLoader loader = new NetexLoader();
        FragmentCodec codec = new FragmentCodec();

        for (int i = a; i < args.length; i++) {
            Path feed = Paths.get(args[i]);
            String name = feed.getFileName().toString();
            Path srcOut = out.resolve(name + ".src.tsv.gz");
            Path javaOut = out.resolve(name + ".java.tsv.gz");
            if (Files.exists(out.resolve(name + ".done"))) {
                System.err.println("[rt] skip (done) " + name);
                continue;
            }
            long t0 = System.nanoTime();
            long nSrc, nObj, nA1 = -1;
            try (Writer sw = gzWriter(srcOut)) {
                nSrc = scanSource(feed, sets, sw);
            }
            Path db = a1Root == null ? null : a1Root.resolve(name + ".lmdb");
            Store real = null;
            try {
                if (db != null) {
                    Stores.destroy(db);
                    Files.createDirectories(db);
                    real = Stores.create(db, format);
                }
                try (Writer jw = gzWriter(javaOut)) {
                    nObj = loadFeed(feed, loader, codec, jw, real);
                }
                if (real != null) {
                    long t1 = System.nanoTime();
                    long nres = real.resolve();
                    long nemb = real.resolveEmbeddings();
                    System.err.printf("[rt]   resolve=%d embed=%d (%.1fs)%n",
                            nres, nemb, (System.nanoTime() - t1) / 1e9);
                    try (Writer aw = gzWriter(out.resolve(name + ".a1.tsv.gz"))) {
                        nA1 = dumpStored(javaOut, real, codec, aw);
                    }
                }
            } finally {
                if (real != null) {
                    real.close();
                    Stores.destroy(db);
                }
            }
            Files.writeString(out.resolve(name + ".done"),
                    nSrc + "\t" + nObj + "\t" + nA1 + "\t" + (System.nanoTime() - t0) / 1_000_000 + "\n");
            System.err.printf("[rt] %-60s src=%d obj=%d a1=%d %.1fs%n",
                    name, nSrc, nObj, nA1, (System.nanoTime() - t0) / 1e9);
        }
    }

    /// Phase 3 (Mode A1): re-read the document-ordered key list from the java TSV and pull each
    /// object's blob back out of the database AFTER resolve()/resolveEmbeddings(), so the rows
    /// stay positionally aligned with the source stream and no join is needed. The blob is
    /// decompressed but NOT decoded — these are the exact bytes downstream reads.
    static long dumpStored(Path javaTsv, Store store, FragmentCodec codec, Writer w) throws Exception {
        long n = 0, missing = 0;
        Map<String, Class<?>> cc = new HashMap<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(javaTsv), 1 << 20)),
                StandardCharsets.UTF_8), 1 << 20);
             Txn txn = store.roTxn()) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                Class<?> c = cc.computeIfAbsent(p[2], ln -> {
                    try {
                        return Class.forName(ModelRegistry.MODEL_PKG + "." + ln);
                    } catch (ClassNotFoundException e) {
                        return null;
                    }
                });
                String blob = "";
                if (c != null) {
                    long fk = store.lookupFullKey(txn, p[3], p[4].isEmpty() ? null : p[4], c);
                    if (fk >= 0) {
                        byte[] raw = store.loadRawObject(txn,
                                store.classForIdx(toolkit.keycodec.NulKeyCodec.fullKeyClassIdx(fk)),
                                toolkit.keycodec.NulKeyCodec.u32le(
                                        toolkit.keycodec.NulKeyCodec.fullKeyLocal(fk)));
                        if (raw != null) blob = B64.encodeToString(codec.decompress(raw));
                    }
                }
                if (blob.isEmpty()) missing++;
                w.write(p[0]);
                w.write('\t');
                w.write(p[1]);
                w.write('\t');
                w.write(p[2]);
                w.write('\t');
                w.write(p[3]);
                w.write('\t');
                w.write(p[4]);
                w.write('\t');
                w.write(blob);
                w.write('\n');
                n++;
            }
        }
        if (missing > 0) System.err.printf("[rt]   A1: %d/%d rows not found in db%n", missing, n);
        return n;
    }

    static Writer gzWriter(Path p) throws IOException {
        OutputStream os = new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(p), 1 << 20), 1 << 16);
        return new OutputStreamWriter(os, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ stream enumeration
    // Mirrors NetexLoader.load exactly, so both passes see the same streams in the same order.

    interface StreamSink {
        void accept(String label, InputStream in) throws Exception;
    }

    static void forEachStream(Path input, StreamSink sink) throws Exception {
        String name = input.getFileName().toString();
        String lower = name.toLowerCase();
        if (lower.endsWith(".zip")) {
            try (ZipFile zf = new ZipFile(input.toFile())) {
                Iterator<? extends ZipEntry> en = zf.entries().asIterator();
                while (en.hasNext()) {
                    ZipEntry e = en.next();
                    if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".xml")) continue;
                    try (InputStream in = new BufferedInputStream(zf.getInputStream(e), 1 << 20)) {
                        sink.accept(name + "!" + e.getName(), in);
                    }
                }
            }
        } else if (lower.endsWith(".gz")) {
            try (InputStream in = new BufferedInputStream(
                    new GZIPInputStream(Files.newInputStream(input), 1 << 16), 1 << 20)) {
                sink.accept(name, in);
            }
        } else {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(input), 1 << 20)) {
                sink.accept(name, in);
            }
        }
    }

    // ------------------------------------------------------------------ pass 1: source scan

    static long scanSource(Path feed, NetexLoader.NameSets sets, Writer w) throws Exception {
        long[] total = {0};
        Map<String, Boolean> hasClass = new HashMap<>();
        forEachStream(feed, (label, in) -> total[0] += scanStream(label, in, sets, hasClass, w));
        return total[0];
    }

    static long scanStream(String label, InputStream in, NetexLoader.NameSets sets,
                           Map<String, Boolean> hasClass, Writer w) throws Exception {
        com.ctc.wstx.stax.WstxInputFactory xif = new com.ctc.wstx.stax.WstxInputFactory();
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xif.setProperty(XMLInputFactory.IS_COALESCING, false);
        xif.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        com.ctc.wstx.stax.WstxOutputFactory wof = new com.ctc.wstx.stax.WstxOutputFactory();
        XMLStreamReader2 r = (XMLStreamReader2) xif.createXMLStreamReader(in);

        ArrayDeque<List<String[]>> nsStack = new ArrayDeque<>();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(16 << 10);
        XMLStreamWriter2 out = null;
        boolean capturing = false;
        int depth = 0;
        long seq = 0;
        String capLn = null, capId = null, capVer = null;

        int ev = r.getEventType();
        while (true) {
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (capturing) {
                    out.copyEventFromReader(r, true);
                    depth++;
                } else {
                    String ln = r.getLocalName();
                    boolean cand = NETEX_NS.equals(r.getNamespaceURI())
                            && sets.interesting.contains(ln)
                            && hasClass.computeIfAbsent(ln, RoundTrip::modelClassExists);
                    if (cand) {
                        capLn = ln;
                        capId = r.getAttributeValue(null, "id");
                        capVer = r.getAttributeValue(null, "version");
                        buf.reset();
                        out = (XMLStreamWriter2) wof.createXMLStreamWriter(buf, "UTF-8");
                        out.copyEventFromReader(r, true);
                        hoist(out, r, nsStack);
                        depth = 1;
                        capturing = true;
                    } else {
                        nsStack.push(nsDecls(r));
                    }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (capturing) {
                    out.copyEventFromReader(r, true);
                    if (--depth == 0) {
                        out.close();
                        capturing = false;
                        w.write(label);
                        w.write('\t');
                        w.write(Long.toString(seq++));
                        w.write('\t');
                        w.write(capLn);
                        w.write('\t');
                        w.write(capId == null ? "" : capId);
                        w.write('\t');
                        w.write(capVer == null ? "" : capVer);
                        w.write('\t');
                        w.write(B64.encodeToString(buf.toByteArray()));
                        w.write('\n');
                        out = null;
                    }
                } else if (!nsStack.isEmpty()) {
                    nsStack.pop();
                }
            } else if (capturing) {
                out.copyEventFromReader(r, true);
            }
            if (!r.hasNext()) break;
            ev = r.next();
        }
        r.close();
        return seq;
    }

    static boolean modelClassExists(String ln) {
        try {
            Class.forName(ModelRegistry.MODEL_PKG + "." + ln);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static List<String[]> nsDecls(XMLStreamReader2 r) {
        int n = r.getNamespaceCount();
        if (n == 0) return List.of();
        List<String[]> o = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String p = r.getNamespacePrefix(i);
            String u = r.getNamespaceURI(i);
            o.add(new String[] {p == null ? "" : p, u == null ? "" : u});
        }
        return o;
    }

    /// Make the captured subtree standalone: declare every in-scope prefix the root does not.
    static void hoist(XMLStreamWriter2 w, XMLStreamReader2 r, ArrayDeque<List<String[]>> nsStack)
            throws XMLStreamException {
        Set<String> own = new HashSet<>();
        for (int i = 0; i < r.getNamespaceCount(); i++) {
            String p = r.getNamespacePrefix(i);
            own.add(p == null ? "" : p);
        }
        Map<String, String> inScope = new java.util.LinkedHashMap<>();
        for (List<String[]> decls : nsStack) {          // innermost first
            for (String[] d : decls) inScope.putIfAbsent(d[0], d[1]);
        }
        for (Map.Entry<String, String> e : inScope.entrySet()) {
            String prefix = e.getKey(), uri = e.getValue();
            if (own.contains(prefix) || "xml".equals(prefix) || uri == null || uri.isEmpty()) continue;
            if (prefix.isEmpty()) w.writeDefaultNamespace(uri);
            else w.writeNamespace(prefix, uri);
        }
    }

    // ------------------------------------------------------------------ pass 2: loader

    static long loadFeed(Path feed, NetexLoader loader, FragmentCodec codec, Writer w, Store real)
            throws Exception {
        CapStore store = new CapStore(codec, w, real);
        forEachStream(feed, (label, in) -> {
            store.label = label;
            store.seq = 0;
            loader.loadStream(in, store, label);
        });
        if (store.noFragment > 0 || store.marshalFailed > 0) {
            System.err.printf("[rt]   noFragment=%d marshalFailed=%d%n",
                    store.noFragment, store.marshalFailed);
        }
        return store.total;
    }

    /// The harness's tap on the insert path. With `real == null` nothing is written anywhere (Mode
    /// A0 / Bj only); with a real store it delegates every call, so the load is the production load
    /// and Mode A1 can read the blobs back afterwards.
    static final class CapStore implements Store {
        final FragmentCodec codec;
        final Writer w;
        final Store d;
        String label = "?";
        long seq, total, noFragment, marshalFailed;

        CapStore(FragmentCodec codec, Writer w, Store delegate) {
            this.codec = codec;
            this.w = w;
            this.d = delegate;
        }

        @Override
        public void insertAnyObjects(Txn txn, Iterator<?> objects) {
            Iterator<Object> tee = new Iterator<>() {
                @Override public boolean hasNext() { return objects.hasNext(); }
                @Override public Object next() {
                    Object it = objects.next();
                    emit(it);
                    return it;
                }
            };
            if (d != null) {
                d.insertAnyObjects(txn, tee);
            } else {
                while (tee.hasNext()) tee.next();
            }
        }

        private void emit(Object it) {
            try {
                Object o = it;
                byte[] frag = null;
                if (it instanceof WithFragment wf) {
                    o = wf.object();
                    frag = wf.fragmentXml();
                }
                if (frag == null) noFragment++;
                byte[] rem;
                try {
                    rem = codec.marshalFragment(o);
                } catch (RuntimeException e) {
                    rem = null;
                    marshalFailed++;
                }
                String id = o instanceof EntityStructure es ? es.getId() : null;
                String ver = o instanceof EntityInVersionStructure v ? v.getVersion() : null;
                w.write(label);
                w.write('\t');
                w.write(Long.toString(seq++));
                w.write('\t');
                w.write(o.getClass().getSimpleName());
                w.write('\t');
                w.write(id == null ? "" : id);
                w.write('\t');
                w.write(ver == null ? "" : ver);
                w.write('\t');
                w.write(frag == null ? "" : B64.encodeToString(frag));
                w.write('\t');
                w.write(rem == null ? "" : B64.encodeToString(rem));
                w.write('\n');
                total++;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override public Txn rwTxn() { return d != null ? d.rwTxn() : NOOP_TXN; }
        @Override public Txn roTxn() { return d != null ? d.roTxn() : NOOP_TXN; }
        @Override public Path path() { return d != null ? d.path() : Paths.get("/dev/null"); }
        @Override public String formatMarker() { return d != null ? d.formatMarker() : "roundtrip:1"; }
        @Override public String formatId() { return d != null ? d.formatId() : "roundtrip"; }
        @Override public boolean isReadonly() { return false; }
        @Override public void close() { if (d != null) d.close(); }

        private <T> T no() { throw new UnsupportedOperationException("capture-only store"); }
        @Override public List<Class<?>> dbNames(Txn t) { return d != null ? d.dbNames(t) : no(); }
        @Override public byte[] classIdx(Class<?> c) { return d != null ? d.classIdx(c) : no(); }
        @Override public int classIdxInt(Class<?> c) { return d != null ? d.classIdxInt(c) : no(); }
        @Override public Class<?> classForIdx(int i) { return d != null ? d.classForIdx(i) : no(); }
        @Override public Class<?> classForName(String n) { return d != null ? d.classForName(n) : no(); }
        @Override public Iterable<ObjectRow> iterObjects(Txn t, Class<?> c) { return d != null ? d.iterObjects(t, c) : no(); }
        @Override public Iterable<Object> iterOnlyObjects(Txn t, Class<?> c) { return d != null ? d.iterOnlyObjects(t, c) : no(); }
        @Override public Iterable<byte[]> scanObjects(Txn t, Class<?> c) { return d != null ? d.scanObjects(t, c) : no(); }
        @Override public Iterable<RawRow> iterRawObjects(Txn t, Class<?> c) { return d != null ? d.iterRawObjects(t, c) : no(); }
        @Override public byte[] loadRawObject(Txn t, Class<?> c, byte[] k) { return d != null ? d.loadRawObject(t, c, k) : no(); }
        @Override public Object loadObject(Txn t, Class<?> c, byte[] k) { return d != null ? d.loadObject(t, c, k) : no(); }
        @Override public Object loadObjectByFullKey(Txn t, long k) { return d != null ? d.loadObjectByFullKey(t, k) : no(); }
        @Override public Object loadObjectByReference(Txn t,
                noi.netex.model.VersionOfObjectRefStructure r, boolean m) {
            return d != null ? d.loadObjectByReference(t, r, m) : no();
        }
        @Override public long lookupFullKey(Txn t, String i, String v, Class<?> c) { return d != null ? d.lookupFullKey(t, i, v, c) : no(); }
        @Override public Iterable<IdRow> iterIdIndex(Txn t) { return d != null ? d.iterIdIndex(t) : no(); }
        @Override public long lookupFullKeyRaw(Txn t, byte[] k) { return d != null ? d.lookupFullKeyRaw(t, k) : no(); }
        @Override public void insertObjects(Txn t, Class<?> c, Iterator<?> o, boolean e) { if (d != null) d.insertObjects(t, c, o, e); else no(); }
        @Override public void setMergePolicy(Class<?> c, MergeFn f) { if (d != null) d.setMergePolicy(c, f); else no(); }
        @Override public long sequence(Txn t, long a) { return d != null ? d.sequence(t, a) : no(); }
        @Override public long cloneFrom(Store s, Txn a, Txn b, LongPredicate p, long o) { return d != null ? d.cloneFrom(s, a, b, p, o) : no(); }
        @Override public long resolve() { return d != null ? d.resolve() : no(); }
        @Override public long resolveEmbeddings() { return d != null ? d.resolveEmbeddings() : no(); }
        @Override public boolean blobRepairMarkerPresent(Txn t) { return d != null ? d.blobRepairMarkerPresent(t) : no(); }
        @Override public void setBlobRepairMarker(Txn t) { if (d != null) d.setBlobRepairMarker(t); else no(); }
        @Override public Iterable<long[]> iterEdges(Txn t) { return d != null ? d.iterEdges(t) : no(); }
        @Override public Iterable<UnresolvedRow> iterUnresolved(Txn t) { return d != null ? d.iterUnresolved(t) : no(); }
        @Override public Csr csr(Txn t) { return d != null ? d.csr(t) : no(); }
        @Override public Iterator<Object> fetchAllReferencesByClass(Txn t, Set<Class<?>> c, boolean s) {
            return d != null ? d.fetchAllReferencesByClass(t, c, s) : no();
        }
    }

    static final Txn NOOP_TXN = new Txn() {
        @Override public void commit() { }
        @Override public boolean isReadOnly() { return false; }
        @Override public void close() { }
    };
}
