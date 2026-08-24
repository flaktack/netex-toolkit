package inspect.nx;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.util.ValidationEventCollector;

import toolkit.codec.FragmentCodec;
import toolkit.model.ModelRegistry;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/// `nx lens` — one object, rendered identically across every layer it can reach, with one
/// verdict line per adjacent boundary.
///
/// ## What this is for
///
/// A defect is usually a divergence between two ADJACENT representations of one object, and the
/// diagnosis cost is in establishing which pair diverged. This is the view where the layers sit
/// next to each other.
///
/// The layers, and where each is read from:
///
/// | | layer | read from |
/// |---|---|---|
/// | `S` | source XML | the element as the feed wrote it ([XmlFind]) |
/// | `F` | stored fragment | LZ4 blob in the class map ([RawStore]) + `FragmentCodec.decompress` |
/// | `B` | bound bean | `FragmentCodec.decode` — DECLARED-BUT-NULL FIELDS PRINTED ([BeanRender]) |
/// | `B0` | naive bean | a plain JAXB unmarshal with no toolkit interception |
/// | `M` | re-marshal | `FragmentCodec.marshalFragment` — what a re-export would emit |
/// | `E` | export | the element in the exported zip ([XmlFind]) |
///
/// ## Three rendering rules
///
/// 1. Declared-but-null bean fields are printed. The defect shape lives in the gap between a
/// byte-perfect fragment and a null field, with no exception and no `ValidationEvent`; a renderer
/// that skipped nulls would render that object as correct.
///
/// 2. B0 exists because the fix would otherwise hide the defect it fixed. `FragmentCodec.decode`
/// wraps its reader in `EnumWhitespace`, so on a current jar B binds the padded value. B0 is the
/// same unmarshal WITHOUT interception, so the pair shows the defect and the repair holding on
/// current data. A B0/B divergence names an active interception; a B0 null under non-empty fragment
/// text is the defect shape. B0 runs with a `ValidationEventCollector` attached, so "nothing was
/// raised" is measured rather than asserted.
///
/// 3. An unreachable layer prints why, never nothing. For a transform-authored object there is no
/// S layer, and "transform-authored, so there is no source" is the finding.
///
/// ## A diagnostic, not a gate
///
/// It renders one object and tells you what differs. No pass/fail, no threshold, no baseline.
public final class LensCmd {

    static final String VERSION = "nx-lens/1";
    private static final String RULE = "─".repeat(96);

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) return usage();
        if (args[0].equals("find")) return FindCmd.run(java.util.Arrays.copyOfRange(args, 1, args.length));
        if (args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) return usage();
        return lens(args);
    }

    // ================================================================== options

    private static int usage() {
        System.err.println("""
                nx lens — one object, rendered identically across every layer it can reach.

                  nx lens <id>[@<version>] [--class <Class>] [layer options]
                  nx lens find --db <store> --class <Class> [--contains STR] [--regex RE]

                LAYERS (each is opt-in; whatever you do not ask for is reported as such)
                  --db PATH               F stored fragment, B bound bean, M re-marshal.
                                          Repeatable: two --db render F/B/M twice, which is the
                                          pre-store/post-store view #102 needed.
                  --source PATH...        S source XML. A feed, a zip, or a directory of them.
                                          There is NO source index, deliberately; this is a
                                          linear scan of exactly what you name.
                                          MEASURED: one feed 0.3 s; all 317 feeds -- 4,413 members,
                                          40.2 GB uncompressed -- 25 s at --workers 16. The index
                                          §2.1.1 budgets 2-3 days for is not needed for point
                                          lookup, only for a whole-corpus census.
                  --export PATH           E the element in an export zip. Use --export-entry when you
                                          know the member: VehicleTypes live in a 3 MB entry, so
                                          that E costs 0.4 s instead of scanning 43 GB.
                  --export-entry NAME     restrict E to one zip member (repeatable).

                ADDRESSING
                  <id>[@<version>]        version omitted = every stored version, all shown.
                  --class C               the model class. Prefer this over the spec's ':Class'
                                          suffix: NeTEx ids are colon-heavy and ':' is ambiguous.
                  --pick N                render match N (default 0). ALL matches are always listed
                                          first -- T5_OTP_GATE §6 measured 1,484 duplicate-id Lines,
                                          so silently taking the first row is a real way to be wrong.

                OUTPUT
                  --focus SUBSTR          show only pane lines containing SUBSTR (repeatable).
                                          The way to get a defect onto one screen.
                  --diff A:B              print only this boundary's verdict (e.g. --diff F:B).
                  --verdicts-only         skip the panes.
                  --b-nonnull             suppress null bean fields. OFF BY DEFAULT, and turning it
                                          on is turning off the only view in which #104 was visible.
                  --no-b0                 skip the naive-JAXB bean.
                  --pretty                reindent XML panes (default: verbatim bytes).
                  --head N                cap each pane at N lines (default 400).
                  --max-depth N           bean recursion cap (default 8).
                  --workers N             S/E scan parallelism (default 8).

                THIS IS A DIAGNOSTIC, NOT A GATE. It has no pass/fail and must not be wired into a
                chain. Use it when abcmp.py, rtcmp.py or nx prov says a number moved and you need to
                know which pair of layers moved it.""");
        return 2;
    }

    // ================================================================== main flow

    private static int lens(String[] argv) throws Exception {
        String address = null, className = null;
        List<Path> dbs = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        Path export = null;
        Set<String> exportEntries = new LinkedHashSet<>();
        List<String> focus = new ArrayList<>();
        String diff = null;
        boolean verdictsOnly = false, bNonNull = false, noB0 = false, pretty = false;
        int pick = 0, head = 400, maxDepth = 8, workers = 8;

        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--db" -> dbs.add(Path.of(argv[++i]));
                case "--source" -> sources.addAll(XmlFind.expand(Path.of(argv[++i])));
                case "--export" -> export = Path.of(argv[++i]);
                case "--export-entry" -> exportEntries.add(argv[++i]);
                case "--class" -> className = argv[++i];
                case "--pick" -> pick = Integer.parseInt(argv[++i]);
                case "--focus" -> focus.add(argv[++i]);
                case "--diff" -> diff = argv[++i];
                case "--verdicts-only" -> verdictsOnly = true;
                case "--b-nonnull" -> bNonNull = true;
                case "--no-b0" -> noB0 = true;
                case "--pretty" -> pretty = true;
                case "--head" -> head = Integer.parseInt(argv[++i]);
                case "--max-depth" -> maxDepth = Integer.parseInt(argv[++i]);
                case "--workers" -> workers = Integer.parseInt(argv[++i]);
                default -> {
                    if (a.startsWith("-")) {
                        System.err.println("nx lens: unknown option '" + a + "'");
                        return usage();
                    }
                    if (address != null) {
                        System.err.println("nx lens: two addresses given ('" + address + "', '" + a + "')");
                        return 2;
                    }
                    address = a;
                }
            }
        }
        if (address == null) return usage();

        String id = address, version = null;
        int at = address.lastIndexOf('@');
        if (at > 0) { id = address.substring(0, at); version = address.substring(at + 1); }
        if (className == null) {
            // The spec's ':Class' suffix, accepted only when the trailing segment actually resolves
            // to a model class -- NeTEx ids are colon-heavy so this is otherwise a trap.
            int c = id.lastIndexOf(':');
            if (c > 0) {
                String tail = id.substring(c + 1);
                if (ModelRegistry.isRegistered(tail) && !tail.equals(id)) {
                    className = tail;
                    id = id.substring(0, c);
                }
            }
        }

        PrintStream o = System.out;
        o.println(VERSION + "   " + java.time.Instant.now());
        o.printf("address   id=%s  version=%s  class=%s%n", id,
                version == null ? "(any -- every stored version listed)" : version,
                className == null ? "(any)" : className);
        o.println();

        List<Pane> panes = new ArrayList<>();
        FragmentCodec codec = new FragmentCodec();

        // ---------------------------------------------------------------- S
        if (!sources.isEmpty()) {
            XmlFind.Result r = XmlFind.find(sources, id, version, className, 8, workers, o);
            if (r.hits().isEmpty()) {
                panes.add(Pane.unreachable("S", "source XML", String.format(
                        "no element with id=%s in %d file(s) / %d member(s) (%,d B, %,d elements, %,d ms).%n"
                        + "        If this object is TRANSFORM-AUTHORED there is no S layer and this is the answer,%n"
                        + "        not a miss: EXPORT_AB §5.6a's source census found modification/status/publication%n"
                        + "        occur ZERO times in the corpus -- 100%% of those 3,566,763 attributes are created%n"
                        + "        by the EPIP transform. Otherwise: widen --source, or check the id.",
                        id, sources.size(), r.members(), r.bytes(), r.elements(), r.millis())));
            } else {
                for (XmlFind.Found f : r.hits()) {
                    panes.add(Pane.xml("S", "source XML", f.where() + "  (element #" + f.seq()
                            + ", " + f.xml().length + " B, scan " + r.millis() + " ms)", f.xml()));
                }
            }
        } else {
            panes.add(Pane.unreachable("S", "source XML", "not requested (pass --source <feed|dir>)"));
        }

        // ---------------------------------------------------------------- F / B0 / B / M
        if (dbs.isEmpty()) {
            panes.add(Pane.unreachable("F", "stored fragment", "not requested (pass --db <store>)"));
            panes.add(Pane.unreachable("B", "bound bean", "no F layer to bind"));
            panes.add(Pane.unreachable("M", "re-marshal", "no B layer to marshal"));
        }
        for (Path db : dbs) {
            String tag = dbs.size() > 1 ? "@" + db.getFileName() : "";
            RawStore store;
            try {
                store = new RawStore(db);
            } catch (RuntimeException e) {
                panes.add(Pane.unreachable("F" + tag, "stored fragment", e.getMessage()));
                continue;
            }
            try (store) {
                List<RawStore.Hit> hits = store.lookup(id, version, className);
                o.printf("%s  format=%s%n", db.toAbsolutePath(), store.formatMarker());
                if (hits.isEmpty()) {
                    o.println("  _id_idx: no row at the id prefix");
                    o.println();
                    panes.add(Pane.unreachable("F" + tag, "stored fragment",
                            "no _id_idx row for id=" + id + " in " + db
                            + (className != null ? " (class filter " + className + ")" : "")
                            + ".\n        Either this store predates the object, or the object is"
                            + " EMBEDDED (no own row) --\n        embedded objects are reachable only"
                            + " through their container (EMBEDDING_SCOPE #96/#100)."));
                    panes.add(Pane.unreachable("B" + tag, "bound bean", "no F layer to bind"));
                    panes.add(Pane.unreachable("M" + tag, "re-marshal", "no B layer to marshal"));
                    continue;
                }
                o.printf("  _id_idx: %d match%s at the id prefix%s%n", hits.size(),
                        hits.size() == 1 ? "" : "es",
                        hits.size() > 1 ? "   *** DUPLICATES -- see --pick ***" : "");
                for (int i = 0; i < hits.size(); i++) {
                    RawStore.Hit h = hits.get(i);
                    o.printf("    [%d]%s %s  version=%-8s class=%-28s full_key=0x%016x local=%d%n",
                            i, i == pick ? " <-" : "  ", h.id(),
                            h.version().isEmpty() ? "(none)" : h.version(), h.className(),
                            h.fullKey(), h.localKey());
                }
                if (hits.size() > 1) {
                    // "Are the duplicates the same object twice, or two different objects?" is a
                    // real question. Answer it here rather than making the operator run --pick
                    // twice and eyeball two panes.
                    byte[] first = store.rawBlob(hits.get(0));
                    int same = 0, differ = 0;
                    for (int i = 1; i < hits.size(); i++) {
                        byte[] b = store.rawBlob(hits.get(i));
                        if (java.util.Arrays.equals(first, b)) same++; else differ++;
                    }
                    o.printf("    copies vs [0]: %d byte-identical, %d differ%s%n", same, differ,
                            differ > 0 ? "   (--pick N to render another)" : "");
                }
                o.println();
                int use = pick;
                if (use >= hits.size()) {
                    o.println("  --pick " + pick + " is out of range for this store; using 0");
                    use = 0;
                }
                RawStore.Hit h = hits.get(use);
                byte[] blob = store.rawBlob(h);
                if (blob == null) {
                    panes.add(Pane.unreachable("F" + tag, "stored fragment",
                            "_id_idx points at full_key 0x" + Long.toHexString(h.fullKey())
                            + " but the class map has no such row -- a DANGLING INDEX ENTRY, which"
                            + " is itself a finding"));
                    continue;
                }
                byte[] frag = codec.decompress(blob);
                panes.add(Pane.xml("F" + tag, "stored fragment",
                        db.getFileName() + "  class=" + h.className() + " local=" + h.localKey()
                        + "  (" + blob.length + " B stored / " + frag.length + " B inflated)", frag));

                Class<?> clazz = ModelRegistry.classForName(h.className());
                if (clazz == null) {
                    panes.add(Pane.unreachable("B" + tag, "bound bean",
                            "class '" + h.className() + "' is registered in the store but absent from the"
                            + " RUNNING model.\n        That is a model/store skew and is the abort"
                            + " criterion ModelGate exists to catch."));
                    panes.add(Pane.unreachable("M" + tag, "re-marshal", "no B layer to marshal"));
                    continue;
                }

                if (!noB0) {
                    try {
                        NaiveBind nb = naiveBind(frag, clazz);
                        panes.add(Pane.bean("B0" + tag, "naive bean (plain JAXB, NO toolkit interception)",
                                clazz.getName() + "   ValidationEvents raised: " + nb.events().size()
                                + (nb.events().isEmpty() ? "  <- silence is the #104 signature" : ""),
                                BeanRender.render(nb.bean(), maxDepth, !bNonNull)));
                    } catch (Exception e) {
                        panes.add(Pane.unreachable("B0" + tag, "naive bean", "plain unmarshal threw: " + e));
                    }
                }

                Object bean;
                try {
                    bean = codec.decode(blob, clazz);
                } catch (RuntimeException e) {
                    panes.add(Pane.unreachable("B" + tag, "bound bean",
                            "FragmentCodec.decode threw: " + e.getMessage()));
                    panes.add(Pane.unreachable("M" + tag, "re-marshal", "no B layer to marshal"));
                    continue;
                }
                panes.add(Pane.bean("B" + tag, "bound bean (FragmentCodec.decode -- declared-but-null fields INCLUDED)",
                        clazz.getName(), BeanRender.render(bean, maxDepth, !bNonNull)));

                try {
                    byte[] m = codec.marshalFragment(bean);
                    panes.add(Pane.xml("M" + tag, "re-marshal (what a re-export would emit)",
                            "FragmentCodec.marshalFragment  (" + m.length + " B)", m));
                } catch (RuntimeException e) {
                    panes.add(Pane.unreachable("M" + tag, "re-marshal",
                            "marshalFragment threw: " + e.getMessage()));
                }
            }
        }

        // ---------------------------------------------------------------- E
        if (export != null) {
            XmlFind.Result r = XmlFind.find(List.of(export), exportEntries, id, version,
                    className, 4, workers, o);
            if (r.hits().isEmpty()) {
                panes.add(Pane.unreachable("E", "export", String.format(
                        "not present in %s (%d member(s), %,d B, %,d elements, %,d ms).%n"
                        + "        An object stored but not exported is a real class of defect"
                        + " (EXPORT_AB §5.12 residues).",
                        export.getFileName(), r.members(), r.bytes(), r.elements(), r.millis())));
            } else {
                for (XmlFind.Found f : r.hits()) {
                    panes.add(Pane.xml("E", "export", f.where() + "  (" + f.xml().length + " B, scan "
                            + r.millis() + " ms)", f.xml()));
                }
            }
        } else {
            panes.add(Pane.unreachable("E", "export", "not requested (pass --export <zip>)"));
        }

        // ---------------------------------------------------------------- render
        if (diff == null && !verdictsOnly) {
            for (Pane p : panes) p.print(o, focus, head, pretty);
        } else {
            // Even with the panes suppressed, WHY a layer is missing is printed: hiding the reason
            // behind a formatting flag turns a retired-format store, a stale db and a
            // transform-authored object into the same blank.
            for (Pane p : panes) {
                if (!p.ok()) o.printf("%-6s UNREACHABLE: %s%n", p.code, p.unreachable);
            }
            o.println();
        }
        o.println(RULE);
        o.println("VERDICTS   one line per boundary; '~' = value differs, '±' = present on one side only");
        o.println(RULE);
        Verdicts.emit(o, panes, diff);
        o.println();
        o.println("nx lens is a DIAGNOSTIC, not a gate: it has no pass/fail and must not gate a chain.");
        return 0;
    }

    // ================================================================== B0

    record NaiveBind(Object bean, List<String> events) {}

    /// A plain JAXB unmarshal of the fragment: the package context, no `EnumWhitespace` wrapper,
    /// no marshaller listener, and a [ValidationEventCollector] attached so that "nothing was
    /// raised" is a measurement.
    static NaiveBind naiveBind(byte[] xml, Class<?> clazz) throws Exception {
        Unmarshaller u = FragmentCodec.packageContext().createUnmarshaller();
        ValidationEventCollector vec = new ValidationEventCollector();
        u.setEventHandler(vec);
        XMLInputFactory xif = XMLInputFactory.newInstance();
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader r = xif.createXMLStreamReader(new ByteArrayInputStream(xml));
        r.nextTag();
        JAXBElement<?> el = u.unmarshal(r, clazz);
        r.close();
        List<String> ev = new ArrayList<>();
        if (vec.hasEvents()) for (var e : vec.getEvents()) ev.add(e.getMessage());
        return new NaiveBind(el.getValue(), ev);
    }

    // ================================================================== panes

    /// One rendered layer, or the reason there isn't one.
    static final class Pane {
        final String code;          // S, F, B, B0, M, E (+ "@db" when several stores)
        final String title;
        final String detail;
        final byte[] xml;           // XML layers
        final List<BeanRender.Row> bean;  // bean layers
        final String unreachable;
        XmlDoc doc;                 // lazily parsed

        private Pane(String code, String title, String detail, byte[] xml,
                     List<BeanRender.Row> bean, String unreachable) {
            this.code = code; this.title = title; this.detail = detail;
            this.xml = xml; this.bean = bean; this.unreachable = unreachable;
        }

        static Pane xml(String c, String t, String d, byte[] x) { return new Pane(c, t, d, x, null, null); }
        static Pane bean(String c, String t, String d, List<BeanRender.Row> b) { return new Pane(c, t, d, null, b, null); }
        static Pane unreachable(String c, String t, String why) { return new Pane(c, t, null, null, null, why); }

        boolean ok() { return unreachable == null; }
        String base() { int i = code.indexOf('@'); return i < 0 ? code : code.substring(0, i); }

        XmlDoc doc() {
            if (doc == null && xml != null) {
                try { doc = XmlDoc.parse(xml); } catch (Exception e) { doc = null; }
            }
            return doc;
        }

        void print(PrintStream o, List<String> focus, int head, boolean pretty) {
            o.println(RULE);
            o.printf("%-6s %s%n", code, title);
            if (unreachable != null) {
                o.println("       UNREACHABLE: " + unreachable);
                o.println();
                return;
            }
            o.println("       " + detail);
            o.println(RULE);
            List<String> lines = new ArrayList<>();
            if (xml != null) {
                String text = pretty && doc() != null ? doc().pretty()
                        : new String(xml, StandardCharsets.UTF_8);
                for (String l : text.split("\n", -1)) lines.add(l);
            } else {
                for (BeanRender.Row r : bean) lines.add(r.render());
            }
            int shown = 0, hidden = 0;
            for (String l : lines) {
                if (!focus.isEmpty()) {
                    boolean hit = false;
                    for (String f : focus) if (l.contains(f)) { hit = true; break; }
                    if (!hit) { hidden++; continue; }
                }
                if (shown++ >= head) { hidden++; continue; }
                o.println(l);
            }
            if (hidden > 0) o.println("       ... " + hidden + " line(s) not shown (--focus/--head)");
            o.println();
        }
    }

    // ================================================================== find

    /// The locator, for when the operator has a predicate rather than an id. A raw byte scan of one
    /// class map, no decode, so it says what is STORED. The general form is `nx db grep`.
    static final class FindCmd {
        static int run(String[] argv) throws Exception {
            Path db = null;
            String className = null, contains = null, regex = null;
            int limitArg = 20;
            long scanLimitArg = Long.MAX_VALUE;
            for (int i = 0; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--db" -> db = Path.of(argv[++i]);
                    case "--class" -> className = argv[++i];
                    case "--contains" -> contains = argv[++i];
                    case "--regex" -> regex = argv[++i];
                    case "--limit" -> limitArg = Integer.parseInt(argv[++i]);
                    case "--scan-limit" -> scanLimitArg = Long.parseLong(argv[++i]);
                    default -> {
                        System.err.println("nx lens find: unknown option '" + argv[i] + "'");
                        return 2;
                    }
                }
            }
            if (db == null) {
                System.err.println("""
                        nx lens find --db <store> [--class C] [--contains STR] [--regex RE] [--limit N]
                            Raw byte scan of a class map -- no decode, so it reports STORED bytes.
                            With no --class, lists the classes that have a map.""");
                return 2;
            }
            FragmentCodec codec = new FragmentCodec();
            try (RawStore s = new RawStore(db)) {
                if (className == null) {
                    System.out.printf("%s  format=%s%n", db.toAbsolutePath(), s.formatMarker());
                    System.out.printf("%d registered classes, %d with a class map:%n",
                            s.classes().size(), s.populatedClassIdxs().size());
                    List<String> names = new ArrayList<>();
                    for (int idx : s.populatedClassIdxs()) names.add(s.className(idx));
                    names.sort(Comparator.naturalOrder());
                    for (String n : names) System.out.println("  " + n);
                    return 0;
                }
                int idx = s.classIdx(className);
                if (idx < 0) { System.err.println("class not registered here: " + className); return 1; }
                if (!s.hasMap(idx)) { System.err.println("no class map for " + className); return 1; }
                final int limit = limitArg;
                final long scanLimit = scanLimitArg;
                byte[] needle = contains == null ? null : contains.getBytes(StandardCharsets.UTF_8);
                Pattern pat = regex == null ? null : Pattern.compile(regex);
                long[] seen = {0}, matched = {0};
                List<String> out = new ArrayList<>();
                long t0 = System.nanoTime();
                s.scanClass(idx, (localKey, blob) -> {
                    if (seen[0]++ >= scanLimit) return false;
                    byte[] frag = codec.decompress(blob);
                    boolean hit = true;
                    if (needle != null) hit = indexOf(frag, needle) >= 0;
                    if (hit && pat != null) {
                        hit = pat.matcher(new String(frag, StandardCharsets.UTF_8)).find();
                    }
                    if (!hit) return true;
                    matched[0]++;
                    if (out.size() < limit) {
                        String rid = rootAttr(frag, "id");
                        String rv = rootAttr(frag, "version");
                        out.add(String.format("  %-52s @%-10s local=%-9d %d B",
                                rid == null ? "(no id)" : rid, rv == null ? "" : rv, localKey, frag.length));
                    }
                    return true;
                });
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("%s  class=%s  scanned %,d rows in %,d ms  matched %,d%n",
                        db.getFileName(), className, seen[0], ms, matched[0]);
                out.forEach(System.out::println);
                if (matched[0] > out.size()) System.out.printf("  ... %,d more%n", matched[0] - out.size());
                return 0;
            }
        }

        private static int indexOf(byte[] hay, byte[] needle) {
            outer:
            for (int i = 0; i + needle.length <= hay.length; i++) {
                for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
                return i;
            }
            return -1;
        }
    }

    /// `name="..."` on the fragment's root element, without a parse.
    static String rootAttr(byte[] xml, String name) {
        int end = Math.min(xml.length, 2048);
        String head = new String(xml, 0, end, StandardCharsets.UTF_8);
        int gt = head.indexOf('>');
        if (gt > 0) head = head.substring(0, gt);
        int i = head.indexOf(" " + name + "=\"");
        if (i < 0) return null;
        int from = i + name.length() + 3;
        int to = head.indexOf('"', from);
        return to < 0 ? null : head.substring(from, to);
    }

    private LensCmd() {}

    // ================================================================== verdicts

    /// One line per boundary. The boundaries are named explicitly rather than derived from pane
    /// adjacency, because the ones that carry findings are not all adjacent:
    ///
    /// - `S→F` loader capture fidelity: attribute denormalisation shows here as `+`, a whole-blob
    ///    re-marshal as wholesale change.
    /// - `F→B0` the naive JAXB bind. THE silent-null predicate: fragment text non-empty, bean field
    ///    null, nothing raised.
    /// - `B0→B` what a toolkit interception repairs. An empty line here after a model regeneration
    ///    means the interceptions stopped firing.
    /// - `F→B` the same predicate against the bean the pipeline actually uses.
    /// - `F→M` the store-and-re-export round trip.
    /// - `M→E`, `S→E` the export and its reading against source.
    /// - `F@a→F@b` the same object in two stores — the case with no divergent layer at all, only a
    ///    store that predated the code.
    static final class Verdicts {

        private static final String[][] BOUNDARIES = {
            {"S", "F"}, {"F", "B0"}, {"B0", "B"}, {"F", "B"}, {"F", "M"},
            {"M", "E"}, {"S", "E"},
        };

        static void emit(PrintStream o, List<Pane> panes, String only) {
            Map<String, List<Pane>> byBase = new LinkedHashMap<>();
            for (Pane p : panes) byBase.computeIfAbsent(p.base(), k -> new ArrayList<>()).add(p);

            // Per store: the F/B0/B/M chain belongs to one --db, so pair like with like.
            int stores = Math.max(1, byBase.getOrDefault("F", List.of()).size());
            for (String[] be : BOUNDARIES) {
                List<Pane> as = byBase.get(be[0]), bs = byBase.get(be[1]);
                if (as == null || bs == null) continue;
                int n = Math.max(as.size(), bs.size());
                for (int i = 0; i < n; i++) {
                    Pane a = as.get(Math.min(i, as.size() - 1));
                    Pane b = bs.get(Math.min(i, bs.size() - 1));
                    String label = a.code + "→" + b.code;
                    if (only != null && !matches(only, a, b)) continue;
                    boundary(o, label, a, b);
                    if (n == 1) break;
                }
            }
            // Two stores side by side: compare F@a with F@b, B@a with B@b.
            if (stores > 1) {
                for (String code : new String[] {"F", "B", "M"}) {
                    List<Pane> ps = byBase.get(code);
                    if (ps == null || ps.size() < 2) continue;
                    for (int i = 0; i + 1 < ps.size(); i++) {
                        Pane a = ps.get(i), b = ps.get(i + 1);
                        if (only != null && !matches(only, a, b)) continue;
                        boundary(o, a.code + "→" + b.code, a, b);
                    }
                }
            }
        }

        private static boolean matches(String only, Pane a, Pane b) {
            String want = only.replace(':', '>').replace("→", ">");
            String have = a.code + ">" + b.code;
            String bare = a.base() + ">" + b.base();
            return want.equalsIgnoreCase(have) || want.equalsIgnoreCase(bare);
        }

        private static void boundary(PrintStream o, String label, Pane a, Pane b) {
            if (!a.ok() || !b.ok()) {
                o.printf("%-10s not comparable: %s%n", label,
                        !a.ok() ? a.code + " unreachable" : b.code + " unreachable");
                return;
            }
            if (a.bean == null && b.bean != null) { fragmentToBean(o, label, a, b); return; }
            if (a.bean != null && b.bean != null) { beanToBean(o, label, a, b); return; }
            if (a.bean != null) {            // B → M
                o.printf("%-10s (bean → XML; compare F→M and B0→B for the substantive reads)%n", label);
                return;
            }
            xmlToXml(o, label, a, b);
        }

        // -------------------------------------------------------------- XML ↔ XML

        private static void xmlToXml(PrintStream o, String label, Pane a, Pane b) {
            XmlDoc da = a.doc(), db = b.doc();
            if (da == null || db == null) {
                o.printf("%-10s not comparable (unparseable pane)%n", label);
                return;
            }
            if (java.util.Arrays.equals(a.xml, b.xml)) {
                o.printf("%-10s BYTE-IDENTICAL (%,d B)%n", label, a.xml.length);
                return;
            }
            Map<String, String> fa = da.flatten(), fb = db.flatten();
            List<String> removed = new ArrayList<>(), added = new ArrayList<>(), changed = new ArrayList<>();
            for (var e : fa.entrySet()) {
                String v = fb.get(e.getKey());
                if (!fb.containsKey(e.getKey())) removed.add(e.getKey() + " = " + q(e.getValue()));
                else if (!java.util.Objects.equals(v, e.getValue())) {
                    changed.add(e.getKey() + "  " + q(e.getValue()) + " → " + q(v));
                }
            }
            for (var e : fb.entrySet()) {
                if (!fa.containsKey(e.getKey())) added.add(e.getKey() + " = " + q(e.getValue()));
            }
            List<String> order = new ArrayList<>();
            Map<String, List<String>> oa = da.childOrder(), ob = db.childOrder();
            for (var e : oa.entrySet()) {
                List<String> other = ob.get(e.getKey());
                if (other == null) continue;
                if (other.equals(e.getValue())) continue;
                if (new java.util.HashSet<>(other).equals(new java.util.HashSet<>(e.getValue()))
                        && other.size() == e.getValue().size()) {
                    order.add(e.getKey() + "\n              " + a.code + ": " + e.getValue()
                            + "\n              " + b.code + ": " + other);
                }
            }
            if (removed.isEmpty() && added.isEmpty() && changed.isEmpty() && order.isEmpty()) {
                o.printf("%-10s equivalent (%,d B vs %,d B; differs only in layout/namespace form)%n",
                        label, a.xml.length, b.xml.length);
                return;
            }
            o.printf("%-10s %d removed, %d added, %d value-changed, %d out-of-order  (%,d B → %,d B)%n",
                    label, removed.size(), added.size(), changed.size(), order.size(),
                    a.xml.length, b.xml.length);
            for (String s : cap(removed)) o.println("           − " + s);
            for (String s : cap(added))   o.println("           + " + s);
            for (String s : cap(changed)) o.println("           ~ " + s);
            for (String s : order) o.println("           ↕ ORDER DIFFERS at " + s);
        }

        // -------------------------------------------------------------- F → B  (the null read)

        private static void fragmentToBean(PrintStream o, String label, Pane frag, Pane bean) {
            XmlDoc d = frag.doc();
            if (d == null) { o.printf("%-10s not comparable (unparseable fragment)%n", label); return; }
            Map<String, BeanRender.Row> byPath = BeanRender.byPath(bean.bean);
            List<String> nullBinds = new ArrayList<>(), noField = new ArrayList<>();
            int matched = 0;
            for (var e : d.flatten().entrySet()) {
                String path = e.getKey(), val = e.getValue();
                if (val == null || val.isBlank()) continue;
                BeanRender.Row r = byPath.get(path);
                // The two join rules the corpus sweep uses, repeated here so a lens verdict and a
                // sweep verdict cannot disagree.
                //
                // 1. A namespace-prefixed attribute. One corpus spells the same namespace two
                //    ways, so the prefix carries no identity; JAXB binds on the local name.
                if (r == null) {
                    int at = path.lastIndexOf("/@");
                    int colon = at < 0 ? -1 : path.indexOf(':', at);
                    if (colon > 0) {
                        String alias = path.substring(0, at + 2) + path.substring(colon + 1);
                        BeanRender.Row a = byPath.get(alias);
                        if (a != null) { r = a; path = alias; }
                    }
                }
                // 2. Character data into a complex type. A text-bearing element is a leaf in the
                //    fragment while the bean models it as a container with the text one level down.
                //    Joining against the container asks "is there an object here", which there
                //    always is, so the pane would report a value bound for an object whose geometry
                //    is empty.
                if (r != null && r.complex()) {
                    r = byPath.get(path + "/text()");
                    path = path + "/text()";
                }
                if (r == null) { noField.add(path + " = " + q(val)); continue; }
                if (r.isNull() || "\"\"".equals(r.value())) {
                    nullBinds.add(String.format("%s  ←  %s   (%s; declared field '%s')",
                            path, q(val), r.declaredType(), r.javaField()));
                } else {
                    matched++;
                }
            }
            int nulls = 0, invented = 0;
            Map<String, String> fragFlat = d.flatten();
            for (BeanRender.Row r : bean.bean) {
                if (r.isNull()) { nulls++; continue; }
                if (r.complex()) continue;
                String fv = fragFlat.get(r.path());
                if (fv == null || fv.isBlank()) invented++;
            }
            o.printf("%-10s %d fragment value(s) bound, %d declared field(s) null, %d bean value(s) with no fragment element%n",
                    label, matched, nulls, invented);
            if (!nullBinds.isEmpty()) {
                o.printf("           *** %d FIELD(S) BOUND NULL FROM NON-EMPTY FRAGMENT TEXT ***%n",
                        nullBinds.size());
                for (String s : cap(nullBinds)) o.println("           ! " + s);
                o.println("           This is the #104 shape: a value in the fragment, a null in the bean,");
                o.println("           and no error raised anywhere. Nothing but this pane can see it.");
            }
            if (!noField.isEmpty()) {
                o.printf("           %d fragment element(s) with no matching bean path (wrappers, wildcards, or a real drop):%n",
                        noField.size());
                for (String s : cap(noField)) o.println("           ? " + s);
            }
        }

        // -------------------------------------------------------------- B0 → B

        private static void beanToBean(PrintStream o, String label, Pane a, Pane b) {
            Map<String, BeanRender.Row> ma = BeanRender.byPath(a.bean), mb = BeanRender.byPath(b.bean);
            Set<String> keys = new LinkedHashSet<>(ma.keySet());
            keys.addAll(mb.keySet());
            List<String> diffs = new ArrayList<>();
            List<String> repaired = new ArrayList<>();
            for (String k : keys) {
                BeanRender.Row ra = ma.get(k), rb = mb.get(k);
                String va = ra == null ? "<absent>" : (ra.isNull() ? "null" : ra.value());
                String vb = rb == null ? "<absent>" : (rb.isNull() ? "null" : rb.value());
                if (java.util.Objects.equals(va, vb)) continue;
                if (ra != null && ra.isNull() && rb != null && !rb.isNull()) {
                    repaired.add(String.format("%s   %s=null  →  %s=%s", k, a.code, b.code, vb));
                } else {
                    diffs.add(String.format("%s   %s=%s  →  %s=%s", k, a.code, va, b.code, vb));
                }
            }
            if (repaired.isEmpty() && diffs.isEmpty()) {
                o.printf("%-10s identical -- no toolkit interception changes this object's binding%n", label);
                return;
            }
            o.printf("%-10s %d field(s) REPAIRED by a toolkit interception, %d otherwise different%n",
                    label, repaired.size(), diffs.size());
            for (String s : cap(repaired)) o.println("           ✓ " + s);
            for (String s : cap(diffs))    o.println("           ~ " + s);
            if (!repaired.isEmpty()) {
                o.println("           A repair here means the naive JAXB binding LOSES this value. That loss is");
                o.println("           #104; the repair is EnumWhitespace/SchemaDefaults holding. Watch this line");
                o.println("           after any model regeneration -- TASKS.md's NeTEx 2.0 flip is exactly when");
                o.println("           element→class and lexical→enum bindings move silently.");
            }
        }

        private static List<String> cap(List<String> l) {
            if (l.size() <= 25) return l;
            List<String> out = new ArrayList<>(l.subList(0, 25));
            out.add("... " + (l.size() - 25) + " more");
            return out;
        }

        private static String q(String s) {
            if (s == null) return "<absent>";
            return "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\"";
        }
    }
}
