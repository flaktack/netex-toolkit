package inspect.nx;

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamReader2;
import org.codehaus.stax2.XMLStreamWriter2;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLInputFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Layers S (source feed) and E (export zip): find one element by `@id` in a pile of XML and hand
/// back its subtree.
///
/// The capture is a StAX event copy: text, attribute order, attribute quoting and child order all
/// survive.
///
/// There is no index, so S and E are linear scans of exactly the files the operator names.
/// Scanning is parallel per zip member and stops as soon as the requested hits are in hand.
public final class XmlFind {

    /// One matched element.
    public record Found(String file, String member, long seq, String localName,
                        String id, String version, byte[] xml) {
        public String where() {
            return member == null || member.isEmpty() ? file : file + "!" + member;
        }
    }

    /// What a scan found, and what it cost.
    public record Result(List<Found> hits, long elements, long members, long bytes, long millis,
                         List<String> scanned) {}

    private XmlFind() {}

    // ------------------------------------------------------------------ units of work

    private record Unit(Path file, String member, long size) {}

    private static List<Unit> units(List<Path> files, Set<String> members) throws IOException {
        List<Unit> out = new ArrayList<>();
        for (Path f : files) {
            String n = f.getFileName().toString();
            if (n.endsWith(".zip")) {
                try (ZipFile zf = new ZipFile(f.toFile())) {
                    for (var it = zf.entries(); it.hasMoreElements(); ) {
                        ZipEntry ze = it.nextElement();
                        if (ze.isDirectory()) continue;
                        String en = ze.getName();
                        if (!en.endsWith(".xml") && !en.endsWith(".xml.gz")) continue;
                        if (members != null && !members.isEmpty()
                                && members.stream().noneMatch(en::contains)) continue;
                        out.add(new Unit(f, en, ze.getSize()));
                    }
                }
            } else {
                out.add(new Unit(f, null, Files.size(f)));
            }
        }
        // Largest first: best packing for a fixed pool, same rule as nx xsd.
        out.sort(Comparator.comparingLong(Unit::size).reversed());
        return out;
    }

    /// Expand a path that may be a directory into the feed/zip files under it.
    public static List<Path> expand(Path p) throws IOException {
        if (!Files.isDirectory(p)) return List.of(p);
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(p)) {
            s.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return n.endsWith(".xml") || n.endsWith(".xml.gz") || n.endsWith(".zip");
                    })
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ scan

    /// @param localName restrict to elements with this local name (the class simple name), or null
    /// @param version   restrict to this `@version`, or null for any
    /// @param maxHits   stop the whole scan once this many matches exist
    public static Result find(List<Path> files, String id, String version, String localName,
                              int maxHits, int workers, PrintStream log) throws Exception {
        return find(files, null, id, version, localName, maxHits, workers, log);
    }

    /// @param members when non-empty, only zip entries whose name contains one of these are read.
    public static Result find(List<Path> files, Set<String> members, String id, String version,
                              String localName, int maxHits, int workers, PrintStream log)
            throws Exception {
        long t0 = System.nanoTime();
        List<Unit> units = units(files, members);
        long totalBytes = units.stream().mapToLong(Unit::size).sum();
        if (log != null) {
            log.printf("  [scan] %d file(s), %d member(s), %,d B uncompressed, %d workers%n",
                    files.size(), units.size(), totalBytes, workers);
        }
        List<Found> hits = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong elements = new AtomicLong();
        AtomicLong scannedBytes = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(workers, units.size())));
        List<Future<?>> futures = new ArrayList<>();
        for (Unit u : units) {
            futures.add(pool.submit(() -> {
                if (stop.get()) return null;
                try (InputStream in = open(u)) {
                    long n = scan(in, u, id, version, localName, hits, maxHits, stop);
                    elements.addAndGet(n);
                    scannedBytes.addAndGet(u.size());
                } catch (Exception e) {
                    if (log != null) log.printf("  [scan] %s: %s%n", u.member == null ? u.file : u.member, e);
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        List<String> scannedNames = new ArrayList<>();
        for (Unit u : units) scannedNames.add(u.member == null ? u.file.toString() : u.file + "!" + u.member);
        List<Found> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparing(Found::where).thenComparingLong(Found::seq));
        return new Result(sorted, elements.get(), units.size(), scannedBytes.get(),
                (System.nanoTime() - t0) / 1_000_000, scannedNames);
    }

    private static InputStream open(Unit u) throws IOException {
        if (u.member != null) {
            ZipFile zf = new ZipFile(u.file.toFile());
            ZipEntry ze = zf.getEntry(u.member);
            InputStream raw = zf.getInputStream(ze);
            InputStream body = u.member.endsWith(".gz")
                    ? new GZIPInputStream(raw, 1 << 16) : raw;
            // Closing the wrapper must close the ZipFile handle too.
            return new java.io.FilterInputStream(new BufferedInputStream(body, 1 << 20)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { zf.close(); }
                }
            };
        }
        InputStream raw = Files.newInputStream(u.file);
        InputStream body = u.file.getFileName().toString().endsWith(".gz")
                ? new GZIPInputStream(raw, 1 << 16) : raw;
        return new BufferedInputStream(body, 1 << 20);
    }

    private static long scan(InputStream in, Unit u, String id, String version, String localName,
                             List<Found> hits, int maxHits, AtomicBoolean stop) throws Exception {
        WstxInputFactory xif = new WstxInputFactory();
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xif.setProperty(XMLInputFactory.IS_COALESCING, false);
        xif.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        WstxOutputFactory wof = new WstxOutputFactory();
        XMLStreamReader2 r = (XMLStreamReader2) xif.createXMLStreamReader(in);

        ArrayDeque<List<String[]>> nsStack = new ArrayDeque<>();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(16 << 10);
        XMLStreamWriter2 out = null;
        boolean capturing = false;
        int depth = 0;
        long seq = 0, elements = 0;
        String capLn = null, capId = null, capVer = null;

        int ev = r.getEventType();
        while (true) {
            if (stop.get() && !capturing) break;
            if (ev == XMLStreamConstants.START_ELEMENT) {
                elements++;
                if (capturing) {
                    out.copyEventFromReader(r, true);
                    depth++;
                } else {
                    String ln = r.getLocalName();
                    String eid = r.getAttributeValue(null, "id");
                    boolean cand = id.equals(eid)
                            && (localName == null || localName.equals(ln));
                    if (cand && version != null) {
                        String ver = r.getAttributeValue(null, "version");
                        cand = version.equals(ver);
                    }
                    if (cand) {
                        capLn = ln;
                        capId = eid;
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
                        hits.add(new Found(u.file.toString(), u.member, seq++, capLn, capId,
                                capVer == null ? "" : capVer, buf.toByteArray()));
                        if (hits.size() >= maxHits) stop.set(true);
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
        return elements;
    }

    static List<String[]> nsDecls(XMLStreamReader2 r) {
        int n = r.getNamespaceCount();
        if (n == 0) return List.of();
        List<String[]> o = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String p = r.getNamespacePrefix(i);
            String uri = r.getNamespaceURI(i);
            o.add(new String[] {p == null ? "" : p, uri == null ? "" : uri});
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
        Map<String, String> inScope = new LinkedHashMap<>();
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
}
