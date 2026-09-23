package inspect.nx;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.util.ValidationEventCollector;

import toolkit.codec.EnumWhitespace;
import toolkit.codec.FragmentCodec;
import toolkit.model.ModelRegistry;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import java.io.ByteArrayInputStream;
import java.io.BufferedWriter;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/// `nx bind` — the silent-null sweep.
///
/// The predicate: a value present in the fragment, a field null in the bean, and no error raised
/// anywhere — the JAXB RI raises no `ValidationEvent` when an enum lexical misses its map. Per
/// stored object, flatten the fragment to `path -> text`, render the bean to `path -> value` with
/// declared-but-null fields included, and join; a non-empty fragment text whose bean row is null is
/// a finding.
///
/// `FragmentCodec.decode` wraps its reader in [EnumWhitespace], so on a current jar the defect that
/// fix repairs is invisible because it is repaired. Every object is therefore bound twice:
///
/// - B0 — plain JAXB, no toolkit interception, with a `ValidationEventCollector` attached so
///    "nothing was raised" is a measurement. The fix-disabled arm, needing no pre-fix jar or store.
/// - B — `FragmentCodec.decode`, what the toolkit binds, carrying the production counting handler.
///
/// So the sweep prints TWO event counts over the same objects, this tool's collector on B0 and the
/// production instrument on B. They must agree; where they do not, one of the two is wrong.
///
/// The pair is the verdict:
///
/// | B0 | B | verdict | reading |
/// |---|---|---|---|
/// | null | null | `SILENT_NULL` | the value is in the fragment and in nobody's bean. |
/// | null | set | `REPAIRED` | the defect shape is present in the DATA and an interception catches
///   it. After a model regeneration, going to zero means either the data changed or the repair
///   stopped firing. |
/// | set | null | `TOOLKIT_NULL` | an interception LOSES a value naive JAXB kept — a regression. |
/// | \- | \- | `NO_FIELD` | the path vocabulary did not resolve. |
///
/// Enum-valued ATTRIBUTES carry the same whitespace-collapse defect and have no interception. They
/// are counted two independent ways: a name-keyed census, whose attribute names are rebuilt HERE by
/// reflection so a model regeneration that adds one is picked up, with the padded occurrences
/// counted separately; and the main sweep, where a padded enum attribute binds null and so appears
/// as a `SILENT_NULL` on an `@`-path.
///
/// Every matched path is tallied by the [BeanRender.Origin] that named it, so "0 unmatched" can be
/// read against the risky cases: choice fields going through `@XmlElements`/`@XmlElementRef`
/// alternatives, and `@XmlAnyElement` falling back to the value's class name.
///
/// [RawStore] does point access instead of `V2Store`. LMDB stays on one reader thread and the
/// CPU-bound half (decompress, parse, two binds, two renders) fans out to `--workers`.
///
/// What it cannot reach:
///
/// - `@XmlAnyElement` content. Extension blocks are DOM subtrees, not beans. Reported as
///    `DOM_SUBTREE` and counted, never as loss: the marshaller writes the DOM back verbatim.
/// - The SOURCE layer. This reads stored fragments. Where they are loader captures that is the
///    source byte for byte; where a re-marshalling driver has run it is not, and a repair that
///    already fired upstream is invisible.
/// - Value FIDELITY. The predicate is null, not "different". An adapter that binds a value wrongly
///    but non-null is out of scope — that is `nx lens`'s F→M pane and the round trip.
///
/// A diagnostic, not a gate: no pass/fail. A `REPAIRED` row is a fix working, not a defect; a
/// `SILENT_NULL` row is a question to answer.
public final class BindCmd {

    // ================================================================== verdicts

    static final String SILENT_NULL = "SILENT_NULL";
    static final String REPAIRED = "REPAIRED";
    static final String TOOLKIT_NULL = "TOOLKIT_NULL";
    static final String NO_FIELD = "NO_FIELD";
    static final String DEPTH_CAP = "DEPTH_CAP";
    static final String DOM_SUBTREE = "DOM_SUBTREE";

    private static final String RULE = "=".repeat(100);

    // ================================================================== CLI

    public static int run(String[] args) throws Exception {
        if (args.length == 0) { usage(System.err); return 2; }
        String sub = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "sweep" -> sweep(rest);
            case "explain" -> explain(rest);
            case "table" -> table(rest);
            case "probe" -> BindProbe.run(rest);
            case "-h", "--help", "help" -> { usage(System.out); yield 0; }
            default -> {
                System.err.println("nx bind: unknown subcommand '" + sub + "'");
                usage(System.err);
                yield 2;
            }
        };
    }

    static void usage(PrintStream o) {
        o.println("""
                nx bind — the silent-null sweep

                  THE PREDICATE:  a non-empty value in the stored fragment, a null in the bean,
                                  and no error raised anywhere.  That is #104's shape, and it is
                                  invisible to every other instrument in this stack: the fragment
                                  is byte-perfect so the round trip passes, the census counts the
                                  element on both arms, and the JAXB RI raises nothing.

                  A DIAGNOSTIC, NEVER A GATE.  It has no pass/fail.  A REPAIRED row is a fix
                  working, not a defect; a SILENT_NULL row is a question to answer, not a verdict.

                nx bind sweep --db <store> [--db <store>...]
                      --class C[,C..]     only these class maps (default: all populated)
                      --skip-class C[,..] exclude these
                      --sample N          bind about N objects per class map (every Nth row of a
                                          cursor walk).  Saves the CPU half — two binds and two
                                          renders per object — not the I/O half.
                      --workers N         CPU workers (default: availableProcessors-1)
                      --max-depth N       bean walk depth cap (default 40)
                      --values K          distinct raw values kept per finding (default 6)
                      --head N            findings printed per verdict (default 40)
                      --min-count N       suppress findings below this occurrence count
                      --report f.tsv      every finding, one row each
                      --paths f.tsv       the per-(class,path) vocabulary ledger
                      --progress SEC      progress line every SEC seconds (default 30, 0=off)
                      --no-attrs          skip the enum-attribute census
                      --quiet-nofield     do not print NO_FIELD detail (still counted / in TSV)

                    ARMS.  Every object is bound twice — B0 = plain JAXB, NO toolkit interception
                    (the fix-disabled arm; you do not need a pre-fix jar or a pre-fix store to get
                    it), and B = FragmentCodec.decode (EnumWhitespace + adapters).  The pair is the
                    verdict.  Section 1 also RECONCILES this tool's own ValidationEventCollector on
                    B0 against toolkit.codec.BindEvents on B — the handler production installs
                    since #131, which this sweep is what argued for.  Two instruments, same
                    objects; a disagreement means one of them is wrong:

                      B0 null, B null   SILENT_NULL    live: the value is in nobody's bean
                      B0 null, B set    REPAIRED       the defect shape IS in the data and an
                                                       interception is catching it.  #104 is this
                                                       row.  Watch it after a model regeneration:
                                                       it going to 0 means the data changed OR the
                                                       repair stopped firing, and nothing else in
                                                       the stack tells those two apart.
                      B0 set,  B null   TOOLKIT_NULL   our own interception lost a value
                      no bean row       NO_FIELD       the path vocabulary did not resolve
                                        DOM_SUBTREE    inside @XmlAnyElement: no bean field BY
                                                       DESIGN, kept and re-marshalled verbatim,
                                                       so not loss.  What this tool cannot reach.
                                        DEPTH_CAP      below --max-depth; raise it, not a finding

                    WHICH STORE.  #104 is a DECODE-side defect, so only a store whose fragments
                    are LOADER CAPTURES of the source bytes can still exhibit it.  Every store
                    that has been through a re-marshalling driver — ItRapDbToDb, the EPIP
                    transform — already carries the collapsed "bus" and has nothing left to find.
                    Measured: task109/it_java_109, task109/coupled_java_109 and every
                    task*/gate*/marche.lmdb hold exactly 460 Lines and 0 padded ones; the same
                    five feeds loaded fresh with conv/NetexToDb.java hold 460 padded ones.  A
                    zero from this tool is only a statement about the store you pointed it at.

                nx bind explain <name> [<value>]
                      what the model binds this element/attribute name to, the declared lexical set,
                      and what each arm would do with <value>.

                nx bind probe [--tsv f.tsv] [--only id,id..] [--list]
                      THE VERSION ARM (#155).  No store: it drives the same B0 / B arms over
                      SYNTHETIC one-object fragments for the five shapes a compile count cannot
                      see, plus an M arm — re-marshal the bean and ask whether the element is
                      still there, which is the question that actually matters and which no bind
                      arm can answer.  Version-agnostic BY CONSTRUCTION: every class and field is
                      resolved reflectively, so the same code runs under a 1.15.2 jar and under
                      a 2.0 one.  ci/bind20-check.sh runs it at both and diffs.
                      It REFUSES (exit 2) on a class or field that moved, on a model whose
                      version markers do not resolve, and on either POSITIVE CONTROL failing —
                      a probe that always answers "null" must not be able to pass.

                nx bind table [--attrs] [--elements]
                      the reflection-built enum name tables.  --attrs is the recorded-not-fixed
                      hole: enum-valued ATTRIBUTES have #104's defect and no repair, because
                      intercepting attribute reads would sit on the loader's hottest accessor.

                EXAMPLES   (from the repository root; see tools/nx/acceptance/bind_acceptance.sh)
                  # national EPIP: 9,075,114 objects / 824,603,640 fragment paths in ~7 min
                  nx bind sweep --db scratch/task109/epip_java_109.lmdb --workers 12 \\
                                --report bind.tsv --paths paths.tsv

                  # the #104 proof by construction: five loader-capture stores, no filter of any
                  # kind, and it finds Line/TransportMode "bus " x460 without being told to look
                  nx bind sweep --db marche-ADRIABUS.lmdb --db marche-ATMA.lmdb \\
                                --db marche-CONTRAM.lmdb --db marche-START.lmdb \\
                                --db marche-TRASFER.lmdb

                  # one probe, no store needed
                  nx bind explain TransportMode 'bus '
                  nx bind explain mode 'bus '          # the same value, the unrepaired hole""");
    }

    // ================================================================== sweep

    private static int sweep(String[] args) throws Exception {
        List<Path> dbs = new ArrayList<>();
        Set<String> only = new LinkedHashSet<>(), skip = new LinkedHashSet<>();
        long sample = 0;
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int maxDepth = 40, values = 6, head = 40;
        long minCount = 0;
        Path report = null, pathsTsv = null;
        int progressSec = 30;
        boolean attrs = true, quietNoField = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--db" -> dbs.add(Path.of(args[++i]));
                case "--class" -> only.addAll(List.of(args[++i].split(",")));
                case "--skip-class" -> skip.addAll(List.of(args[++i].split(",")));
                case "--sample" -> sample = Long.parseLong(args[++i].replace("_", ""));
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--max-depth" -> maxDepth = Integer.parseInt(args[++i]);
                case "--values" -> values = Integer.parseInt(args[++i]);
                case "--head" -> head = Integer.parseInt(args[++i]);
                case "--min-count" -> minCount = Long.parseLong(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                case "--paths" -> pathsTsv = Path.of(args[++i]);
                case "--progress" -> progressSec = Integer.parseInt(args[++i]);
                case "--no-attrs" -> attrs = false;
                case "--quiet-nofield" -> quietNoField = true;
                default -> {
                    if (a.startsWith("-")) {
                        System.err.println("nx bind sweep: unknown option " + a);
                        return 2;
                    }
                    dbs.add(Path.of(a));
                }
            }
        }
        if (dbs.isEmpty()) {
            System.err.println("nx bind sweep: need --db <store>");
            return 2;
        }

        PrintStream o = System.out;
        o.println(RULE);
        o.println("nx bind sweep — the silent-null predicate over a corpus");
        o.println(RULE);
        o.printf("stores      %s%n", dbs);
        o.printf("workers     %d      max-depth %d      sample %s%n", workers, maxDepth,
                sample == 0 ? "(all rows)" : String.format("%,d per class", sample));
        o.printf("model       %d registered classes, %d model classes%n",
                ModelRegistry.classes().size(), ModelRegistry.allModelClasses().size());
        Map<String, Set<String>> attrTable = enumAttrTable();
        o.printf("enum names  %,d element names, %d ATTRIBUTE names (%s)%n",
                EnumWhitespace.table().size(), attrTable.size(),
                String.join(" ", attrTable.keySet()));
        o.println();

        // The production handler is a process-global tally, so clear it first: the number printed
        // must be about THESE objects and nothing else.
        toolkit.codec.BindEvents.reset();

        Acc total = new Acc();
        long t0 = System.nanoTime();
        for (Path db : dbs) {
            Acc a = sweepOne(db, only, skip, sample, workers, maxDepth, values, progressSec,
                    attrs, attrTable, o);
            if (a != null) total.merge(a);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        o.println();
        report(o, total, dbs, ms, head, minCount, values, quietNoField, attrTable);
        if (report != null) writeReport(report, total, o);
        if (pathsTsv != null) writePaths(pathsTsv, total, o);

        o.println();
        o.println("nx bind is a DIAGNOSTIC, not a gate: no pass/fail, and it must not gate a chain.");
        return 0;
    }

    // ---------------------------------------------------------------- one store

    private static Acc sweepOne(Path db, Set<String> only, Set<String> skip, long sample,
                                int workers, int maxDepth, int values, int progressSec,
                                boolean doAttrs, Map<String, Set<String>> attrTable, PrintStream o)
            throws Exception {
        RawStore store;
        try {
            store = new RawStore(db);
        } catch (RuntimeException e) {
            o.println("SKIP " + db + ": " + e.getMessage());
            return null;
        }
        String storeName = db.getFileName().toString();
        try (store) {
            // ---- plan
            record Plan(int idx, String name, Class<?> clazz, long entries, long stride) {}
            List<Plan> plan = new ArrayList<>();
            long planned = 0, present = 0, unknown = 0, unknownRows = 0;
            for (int idx : store.populatedClassIdxs()) {
                String name = store.className(idx);
                if (!only.isEmpty() && !only.contains(name)) continue;
                if (skip.contains(name)) continue;
                long n = store.entries(idx);
                if (n == 0) continue;
                present += n;
                Class<?> c = ModelRegistry.classForName(name);
                if (c == null) { unknown++; unknownRows += n; continue; }
                // Sampling skips the BIND, not the SCAN: local keys are block-allocated and
                // strided, not dense from 0, so a point-lookup sample over [0,maxKey] misses almost
                // everything.
                long stride = sample > 0 && n > sample ? Math.max(1, n / sample) : 1;
                plan.add(new Plan(idx, name, c, n, stride));
                planned += stride == 1 ? n : n / stride;
            }
            o.printf("%-42s format=%-12s %d class map(s), %,d row(s) present, %,d to bind%s%n",
                    storeName, store.formatMarker(), plan.size(), present, planned,
                    unknown > 0 ? String.format("%n%-42s   [%d class(es) / %,d rows NOT IN THE RUNNING"
                            + " MODEL — model/store skew, skipped]", "", unknown, unknownRows) : "");

            // ---- run
            Acc acc = new Acc();
            record Task(String cls, Class<?> clazz, byte[] blob) {}
            BlockingQueue<Object> q = new ArrayBlockingQueue<>(Math.max(64, workers * 64));
            Object POISON = new Object();
            AtomicLong done = new AtomicLong();
            List<Acc> locals = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                Acc local = new Acc();
                locals.add(local);
                Thread t = new Thread(() -> {
                    Worker wk = new Worker(maxDepth, values, doAttrs, attrTable, storeName);
                    try {
                        while (true) {
                            Object it = q.take();
                            if (it == POISON) break;
                            Task tk = (Task) it;
                            wk.process(tk.cls(), tk.clazz(), tk.blob(), local);
                            done.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "nx-bind-" + w);
                t.start();
                threads.add(t);
            }

            Thread prog = null;
            if (progressSec > 0) {
                final long target = planned;
                prog = new Thread(() -> {
                    long start = System.nanoTime();
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(progressSec * 1000L);
                            long d = done.get();
                            long el = Math.max(1, (System.nanoTime() - start) / 1_000_000_000L);
                            o.printf("  … %,d / %,d objects  (%,d/s, %d%% )%n",
                                    d, target, d / el, target == 0 ? 100 : (int) (100 * d / target));
                        }
                    } catch (InterruptedException ignored) { }
                }, "nx-bind-progress");
                prog.setDaemon(true);
                prog.start();
            }

            for (Plan p : plan) {
                long[] seen = {0};
                store.scanClass(p.idx(), (k, blob) -> {
                    if (seen[0]++ % p.stride() != 0) return true;
                    try { q.put(new Task(p.name(), p.clazz(), blob)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                    return true;
                });
            }
            for (int w = 0; w < workers; w++) q.put(POISON);
            for (Thread t : threads) t.join();
            if (prog != null) prog.interrupt();

            for (Acc l : locals) acc.merge(l);
            o.printf("%-42s %,d objects bound%n", "", acc.objects);
            return acc;
        }
    }

    // ================================================================== the worker

    /// Per-thread state. `FragmentCodec` is single-threaded by contract (it caches an
    /// `Unmarshaller` and pins a JAXB coordinator on the calling thread), so each worker gets
    /// its own; the `JAXBContext` behind them is shared and built once.
    static final class Worker {
        private final FragmentCodec codec = new FragmentCodec();
        private final XMLInputFactory xif = new com.fasterxml.aalto.stax.InputFactoryImpl();
        private final int maxDepth, values;
        private final boolean doAttrs;
        private final Map<String, Set<String>> attrTable;
        private final String storeName;
        private Unmarshaller naive;

        Worker(int maxDepth, int values, boolean doAttrs, Map<String, Set<String>> attrTable,
               String storeName) {
            this.maxDepth = maxDepth;
            this.values = values;
            this.doAttrs = doAttrs;
            this.attrTable = attrTable;
            this.storeName = storeName;
            codec.scope(storeName); // #131: label this arm's events with the store they came from
            xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        }

        void process(String cls, Class<?> clazz, byte[] blob, Acc acc) {
            acc.objects++;
            byte[] frag;
            XmlDoc doc;
            try {
                frag = codec.decompress(blob);
                doc = XmlDoc.parse(frag);
            } catch (Exception e) {
                acc.fragErrors++;
                return;
            }
            Map<String, String> flat = doc.flatten();

            // ---- B0: plain JAXB, no toolkit interception. The fix-disabled arm.
            Object b0 = null;
            int events = 0;
            try {
                if (naive == null) naive = FragmentCodec.packageContext().createUnmarshaller();
                ValidationEventCollector vec = new ValidationEventCollector();
                naive.setEventHandler(vec);
                XMLStreamReader r = xif.createXMLStreamReader(new ByteArrayInputStream(frag));
                r.nextTag();
                JAXBElement<?> el = naive.unmarshal(r, clazz);
                r.close();
                b0 = el.getValue();
                if (vec.hasEvents()) events = vec.getEvents().length;
            } catch (Exception e) {
                acc.b0Errors++;
            }
            acc.validationEvents += events;

            // ---- B: FragmentCodec.decode, i.e. what the toolkit actually binds.
            Object b = null;
            try {
                b = codec.decode(blob, clazz);
            } catch (RuntimeException e) {
                acc.bErrors++;
            }
            // Both arms or nothing: a one-armed object cannot produce a B0/B verdict.
            if (b == null || b0 == null) {
                acc.notAnalysed.merge(cls, 1L, Long::sum);
                return;
            }
            acc.analysed++;

            Map<String, BeanRender.Row> m0 = BeanRender.byPath(BeanRender.render(b0, maxDepth, true));
            List<BeanRender.Row> bRows = BeanRender.render(b, maxDepth, true);
            Map<String, BeanRender.Row> m1 = BeanRender.byPath(bRows);

            // Paths the bean walk stopped short of, so their descendants are "not rendered", not
            // "not bound". Without this a depth cap manufactures NO_FIELD rows.
            List<String> capped = null, domRoots = null;
            for (BeanRender.Row r : bRows) {
                if (r.origin() == BeanRender.Origin.DEPTH_CAP
                        || r.origin() == BeanRender.Origin.CYCLE) {
                    if (capped == null) capped = new ArrayList<>(2);
                    capped.add(r.path());
                }
                // @XmlAnyElement content lives as a DOM subtree: its descendants have no bean field
                // BY DESIGN and are not lost, the marshaller writing the DOM back verbatim.
                if (r.origin() == BeanRender.Origin.ANY && r.complex()) {
                    if (domRoots == null) domRoots = new ArrayList<>(2);
                    domRoots.add(r.path());
                }
                // Bean-side vocabulary tally, over POPULATED rows only. This is where CHOICE / ANY
                // / SUBSTITUTION show up: a choice-named node is usually a CONTAINER
                // (<StopPointInJourneyPattern>), and XmlDoc.flatten emits no entry for a container
                // beyond its attributes, so the fragment-side tally below cannot see one.
                if (!r.isNull()) {
                    acc.originBean.computeIfAbsent(r.origin(), k -> new long[1])[0]++;
                }
            }

            String objId = attr(doc, "id");
            String objVer = attr(doc, "version");

            for (Map.Entry<String, String> e : flat.entrySet()) {
                String path = e.getKey(), val = e.getValue();
                if (val == null || val.isBlank()) { acc.blankPaths++; continue; }
                acc.fragPaths++;
                BeanRender.Row r0 = m0.get(path);
                BeanRender.Row r1 = m1.get(path);
                boolean have0 = m0.containsKey(path), have1 = m1.containsKey(path);

                // Namespace-prefixed attributes: a feed may spell the same namespace two ways, so
                // the prefix carries no identity. JAXB binds by {namespace, localName} and the bean
                // row is unprefixed, so alias on the local name, and count how often that was
                // needed.
                if (!have0 && !have1) {
                    int at = path.lastIndexOf("/@");
                    int colon = at < 0 ? -1 : path.indexOf(':', at);
                    if (colon > 0) {
                        String alias = path.substring(0, at + 2) + path.substring(colon + 1);
                        if (m0.containsKey(alias) || m1.containsKey(alias)) {
                            acc.prefixAliased++;
                            r0 = m0.get(alias); r1 = m1.get(alias);
                            have0 = m0.containsKey(alias); have1 = m1.containsKey(alias);
                            path = alias;
                        }
                    }
                }

                // Character data into a complex type. <Name>foo</Name> is a leaf in the fragment,
                // so its key is `.../Name` with the text as value; on the bean `.../Name` is a
                // CONTAINER and the text lives one level down at `.../Name/text()`. Joining text
                // against the container row asks "is there an object here", which there always is,
                // so follow the redirect and ask whether the TEXT landed anywhere.
                String tp = path + "/text()";
                if ((have1 && r1 != null && r1.complex()) || (!have1 && have0 && r0 != null && r0.complex())) {
                    if (m1.containsKey(tp) || m0.containsKey(tp)) {
                        r0 = m0.get(tp); r1 = m1.get(tp);
                        have0 = m0.containsKey(tp); have1 = m1.containsKey(tp);
                        path = tp;
                    } else {
                        // The bean models this element as complex and the type declares no
                        // @XmlValue: there is nowhere for the characters to go. A genuine drop,
                        // not a vocabulary miss.
                        have0 = have1 = false;
                        path = tp;
                    }
                }
                // An @XmlValue String that came back empty against non-empty fragment text is the
                // same loss as a null, and reads the same on a re-marshal.
                boolean empty1 = have1 && r1 != null && !r1.isNull() && "\"\"".equals(r1.value());
                boolean empty0 = have0 && r0 != null && !r0.isNull() && "\"\"".equals(r0.value());

                if (!have0 && !have1) {
                    String verdict;
                    if (under(path, domRoots)) { verdict = DOM_SUBTREE; acc.domSubtree++; }
                    else if (under(path, capped)) { verdict = DEPTH_CAP; acc.depthCapped++; }
                    else { verdict = NO_FIELD; acc.unmatched++; }
                    acc.find(verdict, cls, norm(path), "", "", "-", val, objId, objVer,
                            storeName, values);
                    continue;
                }
                acc.matched++;
                BeanRender.Row shape = r1 != null ? r1 : r0;
                acc.origin.computeIfAbsent(shape.origin(), k -> new long[1])[0]++;
                boolean n0 = !have0 || r0.isNull() || empty0;
                boolean n1 = !have1 || r1.isNull() || empty1;
                if (!n0 && !n1) continue;
                String verdict = n0 && n1 ? SILENT_NULL : (n0 ? REPAIRED : TOOLKIT_NULL);
                acc.find(verdict, cls, norm(path), shape.javaField(), shape.declaredType(),
                        shape.origin().name(), val, objId, objVer, storeName, values);
            }

            // ---- the enum-ATTRIBUTE census (recorded-not-fixed hole)
            if (doAttrs) {
                Set<String> enumTypes = enumAttrTypeNames();
                for (Map.Entry<String, String> e : flat.entrySet()) {
                    String path = e.getKey();
                    int at = path.lastIndexOf("/@");
                    if (at < 0) continue;
                    String name = path.substring(at + 2);
                    if (!attrTable.containsKey(name)) continue;
                    String v = e.getValue();
                    // Name-keyed occurrence AND the object-precise one: a name like @ref is an
                    // enum attribute on a handful of classes and a plain String on thousands, so a
                    // name-keyed count alone over-reports by orders of magnitude.
                    BeanRender.Row row = m1.containsKey(path) ? m1.get(path) : m0.get(path);
                    boolean enumBound = row != null && row.declaredType() != null
                            && enumTypes.contains(row.declaredType());
                    long[] c = acc.attrCensus.computeIfAbsent(name, k -> new long[4]);
                    c[0]++;
                    if (enumBound) c[1]++;
                    String col = EnumWhitespace.collapse(v);
                    if (!col.equals(v)) {
                        c[2]++;
                        if (enumBound) c[3]++;
                        acc.attrPadded.computeIfAbsent(name + "\t" + cls + "\t" + norm(path)
                                        + "\t" + (enumBound ? "ENUM-BOUND" : "not enum-bound"),
                                k -> new LinkedHashMap<>()).merge(quote(v), 1L, Long::sum);
                    }
                }
            }
        }

        private static boolean under(String path, List<String> prefixes) {
            if (prefixes == null) return false;
            for (String p : prefixes) if (path.startsWith(p + "/") || path.equals(p)) return true;
            return false;
        }

        private static String attr(XmlDoc d, String n) {
            for (String[] a : d.root.attrs) if (a[0].equals(n)) return a[1];
            return "";
        }
    }

    /// `capacities/PassengerCapacity[3]/@id` → `capacities/PassengerCapacity/@id`.
    static String norm(String path) {
        int br = path.indexOf('[');
        if (br < 0) return path;
        StringBuilder sb = new StringBuilder(path.length());
        boolean in = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '[') { in = true; continue; }
            if (c == ']') { in = false; continue; }
            if (!in) sb.append(c);
        }
        return sb.toString();
    }

    // ================================================================== accumulator

    static final class Finding {
        final String verdict, cls, path;
        String javaField, declaredType, origin;
        long count, objects;
        final Map<String, Long> values = new LinkedHashMap<>();
        final Set<String> examples = new LinkedHashSet<>();
        final Set<String> stores = new TreeSet<>();

        Finding(String verdict, String cls, String path) {
            this.verdict = verdict; this.cls = cls; this.path = path;
        }

        String key() { return verdict + "\t" + cls + "\t" + path; }
    }

    static final class Acc {
        long objects, analysed, fragPaths, matched, unmatched, depthCapped, blankPaths;
        long domSubtree, prefixAliased;
        long fragErrors, b0Errors, bErrors, validationEvents;
        /// class -> objects that could not be bound on BOTH arms, so produced no verdict.
        final Map<String, Long> notAnalysed = new TreeMap<>();
        final Map<String, Finding> findings = new HashMap<>();
        final Map<BeanRender.Origin, long[]> origin = new EnumMap<>(BeanRender.Origin.class);
        final Map<BeanRender.Origin, long[]> originBean = new EnumMap<>(BeanRender.Origin.class);
        /// attribute name -> {occurrences, enumBoundOccurrences, padded, paddedAndEnumBound}
        final Map<String, long[]> attrCensus = new TreeMap<>();
        final Map<String, Map<String, Long>> attrPadded = new TreeMap<>();

        void find(String verdict, String cls, String path, String javaField, String declaredType,
                  String origin, String value, String id, String ver, String store, int keep) {
            String k = verdict + "\t" + cls + "\t" + path;
            Finding f = findings.computeIfAbsent(k, x -> new Finding(verdict, cls, path));
            f.count++;
            if (f.javaField == null || f.javaField.isEmpty()) f.javaField = javaField;
            if (f.declaredType == null || f.declaredType.isEmpty()) f.declaredType = declaredType;
            if (f.origin == null) f.origin = origin;
            f.stores.add(store);
            if (f.values.size() < keep || f.values.containsKey(quote(value))) {
                f.values.merge(quote(value), 1L, Long::sum);
            }
            if (f.examples.size() < 3 && !id.isEmpty()) {
                f.examples.add(id + (ver.isEmpty() ? "" : "@" + ver));
            }
        }

        void merge(Acc o) {
            objects += o.objects; analysed += o.analysed; fragPaths += o.fragPaths;
            matched += o.matched;
            unmatched += o.unmatched; depthCapped += o.depthCapped; blankPaths += o.blankPaths;
            domSubtree += o.domSubtree; prefixAliased += o.prefixAliased;
            fragErrors += o.fragErrors; b0Errors += o.b0Errors; bErrors += o.bErrors;
            validationEvents += o.validationEvents;
            for (var e : o.notAnalysed.entrySet()) notAnalysed.merge(e.getKey(), e.getValue(), Long::sum);
            for (var e : o.findings.entrySet()) {
                Finding a = findings.get(e.getKey());
                Finding b = e.getValue();
                if (a == null) { findings.put(e.getKey(), b); continue; }
                a.count += b.count;
                if (a.javaField == null || a.javaField.isEmpty()) a.javaField = b.javaField;
                if (a.declaredType == null || a.declaredType.isEmpty()) a.declaredType = b.declaredType;
                if (a.origin == null) a.origin = b.origin;
                a.stores.addAll(b.stores);
                for (var v : b.values.entrySet()) {
                    if (a.values.size() < 24 || a.values.containsKey(v.getKey())) {
                        a.values.merge(v.getKey(), v.getValue(), Long::sum);
                    }
                }
                for (String x : b.examples) if (a.examples.size() < 3) a.examples.add(x);
            }
            for (var e : o.origin.entrySet()) origin.computeIfAbsent(e.getKey(), k -> new long[1])[0] += e.getValue()[0];
            for (var e : o.originBean.entrySet()) originBean.computeIfAbsent(e.getKey(), k -> new long[1])[0] += e.getValue()[0];
            for (var e : o.attrCensus.entrySet()) {
                long[] c = attrCensus.computeIfAbsent(e.getKey(), k -> new long[4]);
                for (int i = 0; i < 4; i++) c[i] += e.getValue()[i];
            }
            for (var e : o.attrPadded.entrySet()) {
                Map<String, Long> m = attrPadded.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                for (var v : e.getValue().entrySet()) m.merge(v.getKey(), v.getValue(), Long::sum);
            }
        }

        List<Finding> byVerdict(String v) {
            List<Finding> out = new ArrayList<>();
            for (Finding f : findings.values()) if (f.verdict.equals(v)) out.add(f);
            out.sort(Comparator.<Finding>comparingLong(f -> -f.count).thenComparing(f -> f.key()));
            return out;
        }

        long total(String v) {
            long n = 0;
            for (Finding f : findings.values()) if (f.verdict.equals(v)) n += f.count;
            return n;
        }
    }

    // ================================================================== report

    private static void report(PrintStream o, Acc a, List<Path> dbs, long ms, int head,
                               long minCount, int values, boolean quietNoField,
                               Map<String, Set<String>> attrTable) {
        o.println(RULE);
        o.println("1. COVERAGE");
        o.println(RULE);
        o.printf("  objects walked            %,15d   in %,d ms (%,d obj/s)%n",
                a.objects, ms, ms == 0 ? 0 : a.objects * 1000 / ms);
        long na = 0;
        for (long v : a.notAnalysed.values()) na += v;
        o.printf("  objects bound on BOTH arms%,15d   %s%n", a.analysed,
                na == 0 ? "(every object produced a verdict)"
                        : String.format("*** %,d produced NO verdict: %s ***", na, a.notAnalysed));
        o.printf("  fragment paths joined     %,15d   (+%,d blank/whitespace-only, not a predicate)%n",
                a.fragPaths, a.blankPaths);
        o.printf("  fragment parse failures   %,15d%n", a.fragErrors);
        o.printf("  B0 (plain JAXB)  failures %,15d%n", a.b0Errors);
        o.printf("  B  (decode)      failures %,15d%n", a.bErrors);
        o.printf("  ValidationEvents raised   %,15d   (B0 arm, ValidationEventCollector)%n",
                a.validationEvents);
        long handler = toolkit.codec.BindEvents.total();
        o.printf("  BindEvents counted        %,15d   (B arm, the production handler — task #131)%n",
                handler);
        if (a.validationEvents == 0 && handler == 0) {
            o.println("        Silence, and #104's signature. The RI reports NOTHING when an enum lexical");
            o.println("        misses its map, so 'no errors' is not evidence of no loss — which is why");
            o.println("        this sweep asks the bean what it holds rather than asking JAXB if it minded.");
        } else {
            o.println("        TWO INSTRUMENTS, ONE NUMBER — and that reconciliation is the point. The");
            o.println("        first row is this tool's own collector on the fix-DISABLED arm. The second");
            o.println("        is toolkit.codec.BindEvents, the handler #131 installed in production, as");
            o.println("        it counted the SAME objects on the FragmentCodec.decode arm. Before #131");
            o.println("        the second row did not exist: decode installed no handler at all, so every");
            o.println("        one of these was raised and dropped on the floor. If the two rows disagree,");
            o.println("        one of the two instruments is wrong and finding out which matters more than");
            o.println("        the finding. Compare both against the SILENT_NULL total below — a third,");
            o.println("        independent predicate that asks the BEAN what it holds rather than asking");
            o.println("        JAXB whether it minded. Where all three match, JAXB was not silent; nobody");
            o.println("        was listening.");
            for (var e : toolkit.codec.BindEvents.sites()) {
                var s = e.getKey();
                o.printf("          %s · %s · %s · %s · %s ×%,d %s%n", s.side(), s.scope(),
                        s.clazz(), s.element(), s.cause(), e.getValue(),
                        toolkit.codec.BindEvents.samples(s));
            }
        }
        o.println();

        o.println(RULE);
        o.println("2. THE SILENT-NULL SWEEP  —  non-empty fragment value, null bean field");
        o.println(RULE);
        long sn = a.total(SILENT_NULL), rep = a.total(REPAIRED), tn = a.total(TOOLKIT_NULL);
        o.printf("  SILENT_NULL   %,12d occurrence(s) over %,d (class,path) pair(s)%n",
                sn, a.byVerdict(SILENT_NULL).size());
        o.printf("  REPAIRED      %,12d occurrence(s) over %,d (class,path) pair(s)%n",
                rep, a.byVerdict(REPAIRED).size());
        o.printf("  TOOLKIT_NULL  %,12d occurrence(s) over %,d (class,path) pair(s)%n",
                tn, a.byVerdict(TOOLKIT_NULL).size());
        o.println();
        printFindings(o, "REPAIRED — B0 null / B bound.  The defect shape IS present in this data and"
                + " an\n           interception is catching it.  THIS IS #104's ROW.",
                a.byVerdict(REPAIRED), head, minCount, values);
        printFindings(o, "SILENT_NULL — B0 null / B null.  The value is in the fragment and in nobody's"
                + " bean.\n           JAXB may or may not have raised — compare the two event counts in\n"
                + "           section 1.  Answer each of these; some are genuinely"
                + " unbindable\n           content (wrappers, xsi:type, mixed text), some are not.",
                a.byVerdict(SILENT_NULL), head, minCount, values);
        printFindings(o, "TOOLKIT_NULL — B0 bound / B null.  Our own interception lost a value that"
                + " naive\n           JAXB kept.  Nothing should produce this.",
                a.byVerdict(TOOLKIT_NULL), head, minCount, values);

        o.println(RULE);
        o.println("3. THE ENUM-ATTRIBUTE HOLE  (#104 recorded-not-fixed)");
        o.println(RULE);
        o.println("  Enum-valued ATTRIBUTES have the identical whitespace-collapse defect and NO");
        o.println("  repair: intercepting attribute reads would put a set lookup on the loader's");
        o.println("  hottest accessor (id/version on every element), so #104 recorded it instead.");
        o.println("  The original scan found zero padded occurrences.  Two independent restatements:");
        o.println();
        o.println("  (a) census over every attribute occurrence in every fragment walked.");
        o.println("      'occurrences' is NAME-KEYED, as #104's own scan was.  'enum-bound' is");
        o.println("      object-precise — the bean field this occurrence actually landed on is an");
        o.println("      enum — and the two differ enormously for @ref, which is an enum on a few");
        o.println("      classes and a plain String on thousands.  Only the enum-bound column can");
        o.println("      go silently null.");
        o.printf("      %-22s %14s %14s %10s %12s%n",
                "attribute", "occurrences", "enum-bound", "PADDED", "PADDED&enum");
        long totOcc = 0, totEnum = 0, totPad = 0, totPadEnum = 0;
        for (String n : attrTable.keySet()) {
            long[] c = a.attrCensus.getOrDefault(n, new long[4]);
            totOcc += c[0]; totEnum += c[1]; totPad += c[2]; totPadEnum += c[3];
            o.printf("      @%-21s %,14d %,14d %,10d %,12d%n", n, c[0], c[1], c[2], c[3]);
        }
        o.printf("      %-22s %,14d %,14d %,10d %,12d%n", "TOTAL", totOcc, totEnum, totPad, totPadEnum);
        o.println();
        if (totPadEnum == 0 && totPad == 0) {
            o.println("      PADDED = 0.  The hole is still theoretical in this corpus — the recorded");
            o.println("      risk remains a monitored one, which is the whole value of the row.");
        } else if (totPadEnum == 0) {
            o.printf("      PADDED&enum = 0.  The %,d padded occurrence(s) are all on STRING-bound%n", totPad);
            o.println("      attributes, which have no enum lookup to miss.  The hole is still closed.");
            for (var e : a.attrPadded.entrySet()) {
                o.println("      · " + e.getKey().replace('\t', ' ') + "   " + e.getValue());
            }
        } else {
            o.println("      *** PADDED&enum > 0.  THE RECORDED-NOT-FIXED HOLE HAS OPENED. ***");
            o.println("      The symptom is identical to #104: a silent null, no ValidationEvent, and");
            o.println("      the value gone from the next re-marshal.  There is NO repair for these.");
            for (var e : a.attrPadded.entrySet()) {
                o.println("      ! " + e.getKey().replace('\t', ' ') + "   " + e.getValue());
            }
        }
        o.println();
        long attrNulls = 0;
        for (Finding f : a.findings.values()) {
            if (f.path.contains("/@") && (f.verdict.equals(SILENT_NULL) || f.verdict.equals(REPAIRED))) {
                attrNulls += f.count;
            }
        }
        o.printf("  (b) the main sweep, which needs no attribute table at all: %,d attribute path(s)%n",
                attrNulls);
        o.println("      bound null from non-empty text.  A padded enum attribute would appear here");
        o.println("      whether or not (a) existed.  The two numbers must agree, and they are");
        o.println("      produced by different mechanisms.");
        o.println();

        o.println(RULE);
        o.println("4. PATH-VOCABULARY PROOF  —  does every fragment path find a bean field?");
        o.println(RULE);
        o.printf("  fragment paths joined     %,15d%n", a.fragPaths);
        o.printf("  matched a bean row        %,15d   (%.6f%%)%n", a.matched,
                a.fragPaths == 0 ? 0 : 100.0 * a.matched / a.fragPaths);
        o.printf("    of which prefix-aliased %,15d   (gml:id / ns2:id -> @id; the SAME namespace is%n",
                a.prefixAliased);
        o.println("                                          written under two different prefixes in");
        o.println("                                          this corpus, so a prefix cannot be matched)");
        o.printf("  inside an @XmlAnyElement  %,15d   (DOM_SUBTREE — kept verbatim and re-marshalled%n",
                a.domSubtree);
        o.println("                                          verbatim, so NOT loss.  It has no bean");
        o.println("                                          field by design, and reflection cannot");
        o.println("                                          reach into a DOM.)");
        o.printf("  below the depth cap       %,15d   (not a vocabulary failure — raise --max-depth)%n",
                a.depthCapped);
        o.printf("  UNMATCHED (NO_FIELD)      %,15d   (%.6f%%)   <- the residue that is the proof%n",
                a.unmatched, a.fragPaths == 0 ? 0 : 100.0 * a.unmatched / a.fragPaths);
        o.println();
        o.println("  A '0 unmatched' is only evidence if the risky resolutions were actually");
        o.println("  exercised.  CHOICE / CHOICE_REF are @XmlElements/@XmlElementRefs alternatives;");
        o.println("  ANY is the @XmlAnyElement (or all-##default choice) fall-back to the VALUE'S");
        o.println("  CLASS NAME; SUBSTITUTION is a JAXBElement whose element name differs from the");
        o.println("  one its field declares (#115's shape).  Two tallies, because they see");
        o.println("  different things:");
        o.println();
        o.printf("      %-14s %18s %18s%n", "named by", "populated B rows", "fragment paths");
        for (BeanRender.Origin g : BeanRender.Origin.values()) {
            long[] cb = a.originBean.get(g), cf = a.origin.get(g);
            if (cb == null && cf == null) continue;
            o.printf("      %-14s %,18d %,18d%n", g, cb == null ? 0 : cb[0], cf == null ? 0 : cf[0]);
        }
        o.println();
        o.println("      'populated B rows' is the one to read for CHOICE/ANY/SUBSTITUTION: a");
        o.println("      choice-named node is almost always a CONTAINER, and a container contributes");
        o.println("      no fragment path of its own beyond its attributes — so those resolutions");
        o.println("      are invisible in the right-hand column by construction, not by absence.");
        o.println();
        if (!quietNoField) {
            printFindings(o, "NO_FIELD — a fragment path with no bean row at all, and not a DOM"
                    + " subtree and not\n           below the depth cap.  Each of these is either a"
                    + " vocabulary gap in this tool\n           or a value the bean layer genuinely"
                    + " cannot hold.",
                    a.byVerdict(NO_FIELD), head, minCount, values);
            printFindings(o, "DOM_SUBTREE — @XmlAnyElement content (Extensions blocks and the like)."
                    + "  Listed for\n           completeness: this is what the bean layer CANNOT"
                    + " REACH.  It is not lost — the\n           marshaller writes the DOM back"
                    + " verbatim — but no bean field holds it, so no\n           transformer can"
                    + " read it and this sweep cannot check it.",
                    a.byVerdict(DOM_SUBTREE), Math.min(head, 12), minCount, values);
        }
    }

    private static void printFindings(PrintStream o, String title, List<Finding> fs, int head,
                                      long minCount, int values) {
        o.println("  " + title);
        if (fs.isEmpty()) { o.println("      (none)"); o.println(); return; }
        o.printf("      %-34s %-46s %10s  %s%n", "class", "path", "count", "values / field");
        int n = 0;
        for (Finding f : fs) {
            if (f.count < minCount) continue;
            if (n++ >= head) { o.printf("      … %d more (see --report)%n", fs.size() - head); break; }
            o.printf("      %-34s %-46s %,10d  %s%n", f.cls, f.path, f.count, valueList(f, values));
            String field = (f.javaField == null ? "" : f.javaField);
            String type = (f.declaredType == null ? "" : f.declaredType);
            if (!field.isEmpty() || !type.isEmpty()) {
                o.printf("      %-34s   ↳ field '%s' : %s   [named by %s]%s%n", "", field, type,
                        f.origin, f.examples.isEmpty() ? "" : "   e.g. " + f.examples);
            }
        }
        o.println();
    }

    private static String valueList(Finding f, int keep) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var e : f.values.entrySet()) {
            if (i++ >= keep) { sb.append(" …"); break; }
            if (i > 1) sb.append("  ");
            sb.append(e.getKey()).append("×").append(e.getValue());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- TSV

    private static void writeReport(Path p, Acc a, PrintStream o) throws Exception {
        List<Finding> all = new ArrayList<>(a.findings.values());
        all.sort(Comparator.comparing((Finding f) -> f.verdict)
                .thenComparingLong(f -> -f.count).thenComparing(f -> f.key()));
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write("verdict\tclass\tpath\tjava_field\tdeclared_type\tnamed_by\tcount\tstores\tvalues\texamples\n");
            for (Finding f : all) {
                w.write(String.join("\t", f.verdict, f.cls, f.path,
                        nz(f.javaField), nz(f.declaredType), nz(f.origin),
                        Long.toString(f.count), String.join(",", f.stores),
                        valueList(f, 24).replace('\t', ' '),
                        String.join(",", f.examples)));
                w.write('\n');
            }
        }
        o.printf("  wrote %,d finding row(s) to %s%n", all.size(), p.toAbsolutePath());
    }

    private static void writePaths(Path p, Acc a, PrintStream o) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write("# nx bind path-vocabulary ledger\n");
            w.write("metric\tvalue\n");
            w.write("objects\t" + a.objects + "\n");
            w.write("fragment_paths\t" + a.fragPaths + "\n");
            w.write("matched\t" + a.matched + "\n");
            w.write("matched_prefix_aliased\t" + a.prefixAliased + "\n");
            w.write("dom_subtree\t" + a.domSubtree + "\n");
            w.write("depth_capped\t" + a.depthCapped + "\n");
            w.write("unmatched\t" + a.unmatched + "\n");
            for (BeanRender.Origin g : BeanRender.Origin.values()) {
                long[] c = a.origin.get(g), cb = a.originBean.get(g);
                w.write("fragpath_named_by_" + g + "\t" + (c == null ? 0 : c[0]) + "\n");
                w.write("beanrow_named_by_" + g + "\t" + (cb == null ? 0 : cb[0]) + "\n");
            }
            w.write("\nattribute\toccurrences\tenum_bound\tpadded\tpadded_and_enum_bound\n");
            for (var e : a.attrCensus.entrySet()) {
                long[] c = e.getValue();
                w.write(e.getKey() + "\t" + c[0] + "\t" + c[1] + "\t" + c[2] + "\t" + c[3] + "\n");
            }
            w.write("\nverdict\tclass\tpath\tcount\n");
            for (String v : new String[] {NO_FIELD, DOM_SUBTREE, DEPTH_CAP}) {
                for (Finding f : a.byVerdict(v)) {
                    w.write(v + "\t" + f.cls + "\t" + f.path + "\t" + f.count + "\n");
                }
            }
        }
        o.printf("  wrote the path ledger to %s%n", p.toAbsolutePath());
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static String quote(String s) {
        String t = s.length() > 60 ? s.substring(0, 60) + "…" : s;
        return "\"" + t.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "\"";
    }

    // ================================================================== explain / table

    private static int explain(String[] args) {
        if (args.length == 0) {
            System.err.println("nx bind explain <element-or-attribute-name> [<value>]");
            return 2;
        }
        String name = args[0];
        String value = args.length > 1 ? args[1] : null;
        PrintStream o = System.out;
        o.println(RULE);
        o.println("nx bind explain  " + name + (value == null ? "" : "   value=" + quote(value)));
        o.println(RULE);

        List<String> sites = new ArrayList<>();
        int elemSites = 0, attrSites = 0;
        for (Class<?> c : ModelRegistry.allModelClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String n = BeanRender.xmlName(f);
                if (n == null) continue;
                if (!n.equals(name) && !n.equals("@" + name)) continue;
                Class<?> t = valueType(f);
                boolean isEnum = t != null && t.isEnum();
                boolean isAttr = f.isAnnotationPresent(XmlAttribute.class);
                if (isEnum) { if (isAttr) attrSites++; else elemSites++; }
                sites.add(String.format("    %-46s %-22s %-30s %s", c.getSimpleName(), f.getName(),
                        t == null ? f.getType().getSimpleName() : t.getSimpleName(),
                        BeanRender.declaredOrigin(f)));
            }
        }
        Set<String> elemLex = elemSites > 0 ? EnumWhitespace.table().get(name) : null;
        Set<String> attrLex = enumAttrTable().get(name);
        o.printf("  enum-valued ELEMENT   : %s%n", elemSites == 0 ? "no — no element field binds this name to an enum"
                : elemSites + " field(s), " + (elemLex == null ? 0 : elemLex.size()) + " declared lexical value(s)");
        o.printf("  enum-valued ATTRIBUTE : %s%n", attrSites == 0 ? "no"
                : attrSites + " field(s), " + (attrLex == null ? 0 : attrLex.size()) + " declared lexical value(s)");
        if (elemSites == 0 && EnumWhitespace.table().containsKey(name)) {
            o.println("        NOTE: EnumWhitespace's repair table carries this name anyway. Its builder keys");
            o.println("        on elementName(f) without excluding @XmlAttribute fields, so the eight");
            o.println("        attribute-only names are in the 374-name element table too. Harmless — the");
            o.println("        wrapper only ever rewrites ELEMENT text, and no element of this name exists —");
            o.println("        but it means the table's size is not the count of repairable element names.");
        }
        o.println();
        o.printf("  %d declaration site(s) in the model:%n", sites.size());
        for (String s : sites.size() > 40 ? sites.subList(0, 40) : sites) o.println(s);
        if (sites.size() > 40) o.println("    … " + (sites.size() - 40) + " more");
        o.println();
        if (sites.size() > 1) {
            o.println("  More than one site: this name is the shape RefElementIndex records — the same");
            o.println("  element name declared with different types in different parents.  A name-keyed");
            o.println("  repair is sound only when a collapse cannot change a NON-enum value's meaning,");
            o.println("  which is EnumWhitespace's condition 2.");
            o.println();
        }

        if (value != null) {
            String col = EnumWhitespace.collapse(value);
            o.printf("  raw        %s%n", quote(value));
            o.printf("  collapsed  %s   %s%n", quote(col),
                    col.equals(value) ? "(unchanged)" : "*** DIFFERS — this is the #104 trigger ***");
            for (String kind : new String[] {"element", "attribute"}) {
                Set<String> lex = kind.equals("element") ? elemLex : attrLex;
                if (lex == null || (kind.equals("element") ? elemSites : attrSites) == 0) continue;
                boolean rawOk = lex.contains(value), colOk = lex.contains(col);
                o.printf("  as %-9s B0 (plain JAXB, exact match on raw text)  -> %s%n",
                        kind, rawOk ? "binds" : "*** NULL, silently ***");
                if (kind.equals("element")) {
                    o.printf("  as %-9s B  (FragmentCodec.decode, EnumWhitespace)  -> %s%n",
                            kind, colOk ? "binds" : "*** NULL, silently ***");
                } else {
                    o.printf("  as %-9s B  (no interception exists for attributes) -> %s%n",
                            kind, rawOk ? "binds" : "*** NULL, silently — the recorded hole ***");
                }
            }
        }
        return 0;
    }

    private static int table(String[] args) {
        boolean wantAttrs = true, wantElems = false;
        for (String a : args) {
            if (a.equals("--attrs")) { wantAttrs = true; wantElems = false; }
            else if (a.equals("--elements")) { wantElems = true; wantAttrs = false; }
            else if (a.equals("--all")) { wantElems = true; wantAttrs = true; }
        }
        PrintStream o = System.out;
        if (wantAttrs) {
            Map<String, Set<String>> t = enumAttrTable();
            o.println(RULE);
            o.printf("ENUM-VALUED ATTRIBUTE NAMES — %d, rebuilt by reflection over the running model%n",
                    t.size());
            o.println(RULE);
            o.println("These are #104's recorded-not-fixed hole: the identical whitespace-collapse");
            o.println("defect with no repair, because interception would sit on the loader's hottest");
            o.println("accessor.  Rebuilt rather than copied so a model regeneration that adds one is");
            o.println("picked up instead of going quiet.");
            for (var e : t.entrySet()) {
                List<String> v = new ArrayList<>(new TreeSet<>(e.getValue()));
                o.printf("  @%-18s %3d lexical(s)  %s%n", e.getKey(), v.size(),
                        v.size() <= 12 ? v : v.subList(0, 12) + " …");
            }
        }
        if (wantElems) {
            Map<String, Set<String>> t = EnumWhitespace.table();
            o.println(RULE);
            o.printf("ENUM-VALUED ELEMENT NAMES — %d (EnumWhitespace.table(), the #104 repair's table)%n",
                    t.size());
            o.println(RULE);
            for (String k : new TreeSet<>(t.keySet())) {
                o.printf("  %-40s %3d lexical(s)%n", k, t.get(k).size());
            }
        }
        return 0;
    }

    // ================================================================== the attribute table

    private static volatile Map<String, Set<String>> ATTR_TABLE;

    /// Attribute local name -&gt; declared lexical values of every enum bound to that attribute name.
    ///
    /// The mirror image of `EnumWhitespace.build()` with `@XmlAttribute` in place of
    /// `@XmlElement`, derived rather than transcribed so a `netex.schema.version` flip cannot rot
    /// it.
    static Map<String, Set<String>> enumAttrTable() {
        Map<String, Set<String>> t = ATTR_TABLE;
        if (t != null) return t;
        synchronized (BindCmd.class) {
            if (ATTR_TABLE != null) return ATTR_TABLE;
            Map<String, Set<String>> out = new TreeMap<>();
            for (Class<?> c : ModelRegistry.allModelClasses()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    XmlAttribute a = f.getAnnotation(XmlAttribute.class);
                    if (a == null) continue;
                    Class<?> vt = valueType(f);
                    if (vt == null || !vt.isEnum()) continue;
                    String name = a.name() == null || "##default".equals(a.name())
                            ? f.getName() : a.name();
                    Set<String> set = out.computeIfAbsent(name, k -> new TreeSet<>());
                    for (Object k : vt.getEnumConstants()) set.add(lexical(vt, (Enum<?>) k));
                }
            }
            ATTR_TABLE = out;
            return out;
        }
    }

    private static volatile Set<String> ATTR_ENUM_TYPES;

    /// Simple names of every enum type reachable as an `@XmlAttribute` value.
    ///
    /// The object-precise half of the attribute census: a rendered row whose declared type is one
    /// of these is genuinely enum-bound, whatever the attribute is called elsewhere.
    static Set<String> enumAttrTypeNames() {
        Set<String> s = ATTR_ENUM_TYPES;
        if (s != null) return s;
        synchronized (BindCmd.class) {
            if (ATTR_ENUM_TYPES != null) return ATTR_ENUM_TYPES;
            Set<String> out = new java.util.HashSet<>();
            for (Class<?> c : ModelRegistry.allModelClasses()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!f.isAnnotationPresent(XmlAttribute.class)) continue;
                    Class<?> vt = valueType(f);
                    if (vt != null && vt.isEnum()) out.add(vt.getSimpleName());
                }
            }
            ATTR_ENUM_TYPES = out;
            return out;
        }
    }

    private static String lexical(Class<?> enumType, Enum<?> constant) {
        try {
            XmlEnumValue v = enumType.getField(constant.name()).getAnnotation(XmlEnumValue.class);
            return v != null ? v.value() : constant.name();
        } catch (NoSuchFieldException e) {
            return constant.name();
        }
    }

    /// The bound value type behind `List<..>` and `JAXBElement<..>` wrappers.
    static Class<?> valueType(Field f) {
        Class<?> t = f.getType();
        Type g = f.getGenericType();
        if (List.class.isAssignableFrom(t) || t == JAXBElement.class) {
            return g instanceof ParameterizedType p ? erase(p.getActualTypeArguments()[0]) : null;
        }
        return t;
    }

    private static Class<?> erase(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof WildcardType w) return erase(w.getUpperBounds()[0]);
        if (t instanceof ParameterizedType p) {
            return p.getRawType() == JAXBElement.class
                    ? erase(p.getActualTypeArguments()[0]) : erase(p.getRawType());
        }
        return null;
    }

    private BindCmd() {}
}
