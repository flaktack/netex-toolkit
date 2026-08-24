package toolkit.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/// Every configuration knob in this repository, declared once.
///
/// One pinned table in the compiled library that `toolkit/`, `scripts/` and `tools/` all answer to,
/// held from both ends by `toolkit/src/test/java/toolkit/util/TestConf.java` — every entry needs a
/// call site, and no undeclared property read may survive outside this class. `../noi-pipeline`
/// runs the same no-undeclared-read scan over its own tree as `tests/TestConfReads.java`.
///
/// ## Where a value comes from
///
/// Highest wins:
///
/// 1. [#override] — tests only.
/// 2. `-Dtoolkit.<name>` — the canonical system property.
/// 3. The flag's legacy system property, where declared (see [#GRAPHDUMP_CHUNK_MB]).
/// 4. The flag's environment variable, where declared (see [#DIFF_MIN_ROWS]).
/// 5. The config file.
/// 6. The declared default.
///
/// `-D` outranks the file deliberately: the pom, the gate scripts and the pipeline Makefile all
/// pin `-D` command lines.
///
/// ## The config file
///
/// A `java.util.Properties` file at `-Dtoolkit.conf=<path>`, else `./toolkit.conf`, else nowhere.
/// Keys are the property minus the `toolkit.` prefix:
///
/// ```
/// # toolkit.conf
/// refPatch        = true
/// strict.remarshal = true
/// load.zipWorkers = 4
/// ```
///
/// A key naming no declared flag is a hard error; [#checkUnknown()] does the same after the fact
/// for `-D`. A NAMED file that does not exist is a refusal; a missing default file is silent.
///
/// ## What booleans accept
///
/// `true|false|on|off|yes|no|1|0`, case-insensitive, plus a bare `-Dtoolkit.foo` for true.
/// Anything else is an error — `-Dtoolkit.refPatch=flase` would otherwise read as ON.
///
/// ## Reads stay constant-foldable
///
/// A call site is still `private static final boolean X = Conf.on(Conf.X);`. The table adds a
/// declaration, not an indirection at read time, so a disabled guard still folds away.
public final class Conf {

    private Conf() {}

    /// The system-property prefix every canonical knob carries.
    public static final String PREFIX = "toolkit.";

    /// How a flag's text is parsed.
    public enum Kind { BOOL, INT, LONG, STRING, PATH }

    /// Registration order, which is also [#report()] order. Populated by the factories below
    /// as the fields initialise, so it MUST be declared before the first flag field — static
    /// initialisers run in textual order.
    private static final List<Flag> TABLE = new ArrayList<>();

    /// One knob: its name, how to parse it, what it means, and which provenance capability it
    /// gates, for the few that decide a stored byte.
    public static final class Flag {
        private final String name;
        private final Kind kind;
        private final Object dflt;
        private final String doc;
        private final String capName;
        private final Boolean capWhen;
        private final String legacyProperty;
        private final String env;
        private final boolean bootstrap;

        private Flag(String name, Kind kind, Object dflt, String doc, String capName,
                     Boolean capWhen, String legacyProperty, String env, boolean bootstrap) {
            this.name = name;
            this.kind = kind;
            this.dflt = dflt;
            this.doc = doc;
            this.capName = capName;
            this.capWhen = capWhen;
            this.legacyProperty = legacyProperty;
            this.env = env;
            this.bootstrap = bootstrap;
        }

        /// The file key, i.e. the system property minus [#PREFIX].
        public String name() {
            return name;
        }

        /// The canonical system property: `toolkit.` + [#name()].
        public String property() {
            return PREFIX + name;
        }

        public Kind kind() {
            return kind;
        }

        public Object defaultValue() {
            return dflt;
        }

        public String doc() {
            return doc;
        }

        /// The provenance capability this flag gates, or null.
        public String capability() {
            return capName;
        }

        /// The value [#capability()] requires; meaningless when there is no capability.
        public Boolean capabilityWhen() {
            return capWhen;
        }

        /// A pre-existing system property still honoured for compatibility, or null.
        public String legacyProperty() {
            return legacyProperty;
        }

        /// An environment variable still honoured for compatibility, or null.
        public String env() {
            return env;
        }

        /// True for a knob that is consumed before the table can be — the config file's own
        /// location, and the repository root the suite's tree scans read. These are declared so
        /// [#checkUnknown()] does not flag them, but they cannot be set FROM the file (the first
        /// is circular; the second is passed by `toolkit/pom.xml`).
        public boolean bootstrap() {
            return bootstrap;
        }

        @Override
        public String toString() {
            return property();
        }
    }

    // ================================================================ the table
    //
    // Grouped by the tree that reads them.

    // ---------------------------------------------------------------- store: reference repair

    /// `refPatch=false` reverts `resolve()` to the whole-blob JAXB re-marshal — the
    /// measurement/neutering knob; the two paths must produce the same graph dump.
    public static final Flag REF_PATCH = bool("refPatch", true,
            "resolve() patches reference attributes in place instead of re-marshalling the blob",
            cap("no-remarshal-resolve", true));

    /// `inventNameOfRefClass=false` stops `resolve()` from ADDING a `nameOfRefClass` attribute to
    /// references whose source never carried one.
    ///
    /// The capability is gated on FALSE: the attribute is added exactly when this flag is ON, so
    /// `no-invent-nameofrefclass` holds on the opt-in arm and a default run does not have it.
    public static final Flag INVENT_NAME_OF_REF_CLASS = bool("inventNameOfRefClass", true,
            "resolve() may ADD a nameOfRefClass attribute the source fragment never carried",
            cap("no-invent-nameofrefclass", false));

    /// Per-reference patch tracing. Volume: one line per reference.
    public static final Flag REF_PATCH_DEBUG = bool("refPatch.debug", false,
            "trace every reference the patcher considers");

    // ---------------------------------------------------------------- store: indexes and sweeps

    /// Insert-side: write embedding-index rows. Off skips them AND stalls the completion marker.
    public static final Flag EMB_INDEX = bool("embIndex", true,
            "inserts write embedding-index rows");

    /// Read-side: trust a complete embedding index instead of taking the corpus scan.
    public static final Flag EMB_INDEX_TRUST = bool("embIndexTrust", true,
            "resolveEmbeddings() trusts a complete embedding index");

    /// Resolve-tier memo, keyed by intern id. Declines to allocate below 2 rows per distinct key.
    public static final Flag RESOLVE_TIER_MEMO = bool("resolve.tierMemo", true,
            "memoise resolve tiers by intern id");

    /// Cross-check every memo hit against a direct probe. Measurement only; slow.
    public static final Flag RESOLVE_TIER_MEMO_VERIFY = bool("resolve.tierMemo.verify", false,
            "verify every tier-memo hit against a direct probe");

    /// Force every tier lookup to miss — the memo's null control arm.
    public static final Flag RESOLVE_TIER_NULL = bool("resolve.tierNull", false,
            "force every resolve-tier lookup to miss");

    /// The post-resolve blob repair sweep. Sound for pure-insert loaders; not after a skipping clone.
    public static final Flag RESOLVE_BLOB_SWEEP = bool("resolve.blobSweep", true,
            "run the post-resolve blob repair sweep");

    /// `blobSweep.scan=false` reverts the sweep to unmarshalling every referrer.
    public static final Flag BLOB_SWEEP_SCAN = bool("blobSweep.scan", true,
            "the sweep decides by decode-free scan instead of unmarshalling every referrer");

    /// Cross-check the decode-free scan against the unmarshal it replaces.
    public static final Flag BLOB_SWEEP_VERIFY = bool("blobSweep.verify", false,
            "verify the decode-free sweep scan against a full unmarshal");

    /// Per-object sweep tracing.
    public static final Flag BLOB_SWEEP_DEBUG = bool("blobSweep.debug", false,
            "trace every object the blob sweep considers");

    /// How many reference-fallback lines to log before falling silent; the total is still counted.
    public static final Flag STORE_REF_FALLBACK_LOG_CAP = integer("store.refFallbackLogCap", 100,
            "cap on logged reference-fallback lines (the counter is uncapped)");

    /// Map-size growth increment in MiB; 0 uses the store's own sizing.
    public static final Flag STORE_GROW_CHUNK = integer("store.growChunk", 0,
            "LMDB map-size growth increment in MiB (0 = the store's own sizing)");

    /// v2 edge-block size in pairs. The suite runs 128 so blocks are crossed at fixture scale.
    public static final Flag V2_BLOCK_PAIRS = integer("v2.blockPairs", 65_536,
            "v2 edge-block size, in pairs");

    // ---------------------------------------------------------------- load

    /// Zip members per write transaction.
    public static final Flag LOAD_ZIP_TXN_BATCH = integer("load.zipTxnBatch", 1,
            "zip members per write transaction");

    /// Parse workers for a zip load; 0 is the serial arm and the default.
    public static final Flag LOAD_ZIP_WORKERS = integer("load.zipWorkers", 0,
            "zip-load parse workers (0 = serial)");

    /// Bounded hand-off queue between the parse workers and the single writer.
    public static final Flag LOAD_ZIP_QUEUE = integer("load.zipQueue", 2048,
            "zip-load parse/write hand-off queue capacity");

    /// Per-leg insert timing: parse, walk, encode, lmdb. The Amdahl instrument.
    public static final Flag LOAD_PROFILE = bool("load.profile", false,
            "time each insert leg (parse / walk / encode / lmdb)");

    /// How often the load profile reports, in objects.
    public static final Flag LOAD_PROFILE_EVERY = longFlag("load.profileEvery", 250_000L,
            "objects between load-profile reports");

    /// Distinct ValidationEvent sites retained before the tally stops recording new ones.
    public static final Flag BIND_SITE_CAP = integer("bind.siteCap", 512,
            "distinct JAXB ValidationEvent sites retained");

    // ---------------------------------------------------------------- codec

    /// `jaxbPin=false` restores the unpinned JAXB `Coordinator` (A/B measurement only;
    /// it changes no output byte either way).
    public static final Flag JAXB_PIN = bool("jaxbPin", true,
            "pin a per-thread JAXB Coordinator sentinel");

    // ---------------------------------------------------------------- provenance

    /// `prov=off` disables all stamping and merging, so a byte-identity A/B can run with the
    /// production paths exactly as they were before provenance landed. Checks still read what exists.
    public static final Flag PROV = bool("prov", true,
            "stamp and merge the provenance sidecar");

    /// Lineage entries kept in the sidecar before the oldest are elided.
    public static final Flag PROV_MAX_WRITERS = integer("prov.maxWriters", 200,
            "lineage entries kept in the provenance sidecar");

    // ---------------------------------------------------------------- strictness
    //
    // The concerns themselves and their defaults live in Strictness; only the declarations are here.

    /// Master switch: makes every concern below fatal.
    public static final Flag STRICT = bool("strict", false,
            "make every strictness concern fatal");

    public static final Flag STRICT_REMARSHAL = bool("strict.remarshal", true,
            "refuse a run that stored any object as a JAXB re-marshal");

    public static final Flag STRICT_EMPTY_OUTPUT = bool("strict.emptyOutput", true,
            "refuse a stage that produced nothing");

    public static final Flag STRICT_BIND_EVENTS = bool("strict.bindEvents", false,
            "refuse a run that raised any JAXB ValidationEvent");

    public static final Flag STRICT_UNRESOLVED_REFS = bool("strict.unresolvedRefs", false,
            "refuse a run that left references unresolved");

    public static final Flag STRICT_REF_FALLBACK = bool("strict.refFallback", false,
            "refuse a run that answered a reference by prefix-scan fallback");

    public static final Flag STRICT_UNPARSED_TIME = bool("strict.unparsedTime", false,
            "refuse a run that held an unparseable date, time or duration");

    public static final Flag STRICT_DROPPED_OUTPUT = bool("strict.droppedOutput", false,
            "refuse a run that dropped objects from its own output");

    // ---------------------------------------------------------------- harness

    /// Worker count per generator stage; &le; 0 is the serial arm and the control.
    public static final Flag EPIP_PARALLEL_JOURNEYS = integer("epip.parallelJourneys", 0,
            "generator workers per stage (0 = serial)");

    // ---------------------------------------------------------------- transform: epip

    public static final Flag EPIP_PEEK_JOURNEY_PATTERN = bool("epip.peekJourneyPattern", true,
            "peek the journey pattern instead of decoding it");

    public static final Flag EPIP_PEEK_INTERCHANGE_INDEX = bool("epip.peekInterchangeIndex", true,
            "peek the interchange index instead of decoding it");

    public static final Flag EPIP_ID_INDEX_JOURNEYS = bool("epip.idIndexJourneys", true,
            "resolve interchange journeys through the id index");

    public static final Flag EPIP_ID_INDEX_JOURNEYS_FORCE = bool("epip.idIndexJourneys.force", false,
            "use the id index for interchange journeys even when the preconditions fail");

    public static final Flag EPIP_ID_INDEX_JOURNEYS_VERIFY = bool("epip.idIndexJourneys.verify", false,
            "cross-check id-index journey resolution against the scan");

    /// The EPIP day-offset rewrite. **Default false** — it changes the encoding a feed used, so
    /// enabling it is a decision, not a bug fix.
    public static final Flag EPIP_DAY_OFFSET_REWRITE = bool("epip.dayOffsetRewrite", false,
            "rewrite past-midnight passing times into an explicit day offset");

    /// Where a rewrite run dumps every rewritten value; unset = no dump.
    public static final Flag EPIP_DAY_OFFSET_REWRITE_DUMP = path("epip.dayOffsetRewrite.dump", null,
            "file the day-offset rewrite dumps every rewritten value into");

    // ---------------------------------------------------------------- scripts: crossborder

    /// The Pass-2 pre-filter: roughly halves peak live heap. Not a capability; no byte differs.
    public static final Flag XB_PASS2_FILTER = bool("xb.pass2Filter", true,
            "pre-filter Pass-2 candidates on the counterpart's country");

    public static final Flag XB_COUNT = bool("xb.count", false,
            "instrument the crossborder pass with load counts and heap samples");

    public static final Flag XB_COUNT_GC = bool("xb.count.gc", false,
            "force a full GC and report the live set at each crossborder measurement point");

    public static final Flag XB_COUNT_HISTOGRAM = path("xb.count.histogram", null,
            "file to write a GC.class_histogram into after Pass 2");

    public static final Flag XB_COUNT_EVERY = integer("xb.count.every", 100_000,
            "Pass-2 loads between progress and heap samples (0 = off)");

    public static final Flag XB_COUNT_STOP_AFTER_PASS2 = bool("xb.count.stopAfterPass2", false,
            "exit(3) once the Pass-2 peak is reported -- MEASUREMENT ONLY, produces no store");

    public static final Flag XB_COUNT_DROP_SJ = bool("xb.count.dropSj", false,
            "drop the ServiceJourney cache after Pass 1");

    // ---------------------------------------------------------------- tools
    //
    // These three predate the prefix; their old spellings stay honoured as legacy aliases.

    /// Sort-chunk size for the graph dump, in MiB.
    public static final Flag GRAPHDUMP_CHUNK_MB = longFlag("graphdump.chunkMB", 512L,
            "graph-dump external-sort chunk size, in MiB",
            legacy("graphdump.chunkMB"));

    /// Scratch directory for the graph dump's external sort; unset = beside the output.
    public static final Flag GRAPHDUMP_TMPDIR = path("graphdump.tmpdir", null,
            "graph-dump external-sort scratch directory (unset = beside the output)",
            legacy("graphdump.tmpdir"));

    /// Below this row count StoreNames refuses rather than reporting identity over nothing.
    public static final Flag DIFF_MIN_ROWS = longFlag("diff.minRows", 1L,
            "minimum rows StoreNames requires before it will report a comparison",
            env("SNC_MIN_ROWS"));

    // ---------------------------------------------------------------- bootstrap

    /// The config file itself. Declared so [#checkUnknown()] knows it; not file-settable.
    public static final Flag CONF = bootstrapPath("conf",
            "path to the config file (default: ./toolkit.conf if present)");

    /// How the suite's tree scans locate the repository. Passed by the pom, because surefire's
    /// working directory is the module and three of the scanned roots are its siblings.
    public static final Flag REPO_DIR = bootstrapPath("repo.dir",
            "the repository root, for the source-tree scans in the test suite");

    // ================================================================ the table, sealed

    /// Every declared knob, in declaration order.
    public static final List<Flag> ALL = Collections.unmodifiableList(TABLE);

    private static final Map<String, Flag> BY_NAME;

    static {
        Map<String, Flag> m = new LinkedHashMap<>();
        for (Flag f : TABLE) {
            if (m.put(f.name(), f) != null) {
                throw new IllegalStateException("duplicate flag declaration: " + f.property());
            }
        }
        BY_NAME = Collections.unmodifiableMap(m);
    }

    /// The flag with this name (no `toolkit.` prefix), or null.
    public static Flag byName(String name) {
        return BY_NAME.get(name);
    }

    // ================================================================ the file

    /// The default file name, looked for in the working directory when no path is given.
    public static final String DEFAULT_FILE = "toolkit.conf";

    /// Test overrides, consulted above everything. Empty in production.
    private static final Map<String, String> OVERRIDES = new LinkedHashMap<>();

    /// The file's contents, already validated against the table. Empty when there is no file.
    private static final Map<String, String> FILE_VALUES;

    /// The file that was read, or null. Reported so a transcript names its own configuration.
    private static final Path FILE_PATH;

    static {
        // Read through the raw property rather than through resolve(): this IS the file, so
        // asking the file where the file is would not terminate.
        String named = System.getProperty(CONF.property());
        Path p;
        if (named != null && !named.isBlank()) {
            p = Path.of(named.trim());
            if (!Files.isRegularFile(p)) {
                // A named file that is not there is a typo; defaulting past it runs the pipeline
                // under a configuration nobody chose.
                throw new IllegalStateException(
                        "-D" + CONF.property() + "=" + named + " names no readable file");
            }
        } else {
            p = Path.of(DEFAULT_FILE);
            if (!Files.isRegularFile(p)) p = null;      // the ordinary no-config case, silent
        }
        FILE_PATH = p;
        FILE_VALUES = p == null ? Map.of() : Collections.unmodifiableMap(readFile(p));
    }

    private static Map<String, String> readFile(Path p) {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config file " + p, e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        List<String> bad = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            // Tolerate a fully-qualified key, since that is what someone copying a -D flag out of
            // the Makefile will write. It means the same knob.
            String name = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
            Flag f = BY_NAME.get(name);
            if (f == null) {
                bad.add(key + suggestion(name));
            } else if (f.bootstrap()) {
                bad.add(key + " (read before the file is; pass it as -D" + f.property() + ")");
            } else {
                out.put(name, props.getProperty(key).trim());
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalStateException(p + ": no such knob: " + String.join(", ", bad)
                    + ". Every knob is declared in toolkit.util.Conf.");
        }
        // Parse everything now, not at first read: a malformed value should fail at the top of the
        // run, not an hour in when some rare branch finally asks.
        for (Map.Entry<String, String> e : out.entrySet()) {
            parse(BY_NAME.get(e.getKey()), e.getValue(), p.toString());
        }
        return out;
    }

    /// `" (did you mean x?)"`, or empty. Cheap edit distance over a table of ~50.
    private static String suggestion(String name) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String candidate : BY_NAME.keySet()) {
            int d = distance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (d < bestD) {
                bestD = d;
                best = candidate;
            }
        }
        // A third of the name may differ before the guess stops being a guess.
        return best != null && bestD <= Math.max(2, name.length() / 3)
                ? " (did you mean " + best + "?)" : "";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ================================================================ reads

    /// The raw text a flag resolves to, or null for "nothing set it".
    private static String raw(Flag f) {
        String v = OVERRIDES.get(f.name());
        if (v != null) return v;
        v = System.getProperty(f.property());
        if (v != null) return v;
        if (f.legacyProperty() != null) {
            v = System.getProperty(f.legacyProperty());
            if (v != null) return v;
        }
        if (f.env() != null) {
            v = System.getenv(f.env());
            if (v != null) return v;
        }
        return FILE_VALUES.get(f.name());
    }

    /// Where `f`'s effective value came from, for [#report()].
    private static String source(Flag f) {
        if (OVERRIDES.containsKey(f.name())) return "override";
        if (System.getProperty(f.property()) != null) return "-D";
        if (f.legacyProperty() != null && System.getProperty(f.legacyProperty()) != null) {
            return "-D" + f.legacyProperty();
        }
        if (f.env() != null && System.getenv(f.env()) != null) return "$" + f.env();
        if (FILE_VALUES.containsKey(f.name())) return "file";
        return "default";
    }

    private static Object value(Flag f) {
        String v = raw(f);
        return v == null ? f.defaultValue() : parse(f, v, describeSource(f));
    }

    private static String describeSource(Flag f) {
        String s = source(f);
        return "file".equals(s) ? String.valueOf(FILE_PATH) : s;
    }

    private static Object parse(Flag f, String v, String where) {
        String t = v.trim();
        try {
            return switch (f.kind()) {
                case BOOL -> parseBool(t);
                case INT -> Integer.valueOf(t);
                case LONG -> Long.valueOf(t);
                case STRING -> t;
                case PATH -> t.isEmpty() ? null : Path.of(t);
            };
        } catch (RuntimeException e) {
            throw new IllegalStateException(f.property() + "=" + v + " (from " + where + ") is not a "
                    + f.kind().name().toLowerCase(Locale.ROOT)
                    + (f.kind() == Kind.BOOL ? " -- accepted: true|false|on|off|yes|no|1|0" : ""), e);
        }
    }

    /// The union of every spelling the replaced idioms accepted, and nothing else. Empty is true so
    /// that a bare `-Dtoolkit.xb.count` means what it looks like it means.
    private static Boolean parseBool(String t) {
        if (t.isEmpty()) return Boolean.TRUE;
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(t);
        };
    }

    /// A [Kind#BOOL] flag's effective value.
    public static boolean on(Flag f) {
        require(f, Kind.BOOL);
        return (Boolean) value(f);
    }

    /// A [Kind#INT] flag's effective value.
    public static int intOf(Flag f) {
        require(f, Kind.INT);
        return (Integer) value(f);
    }

    /// A [Kind#LONG] flag's effective value.
    public static long longOf(Flag f) {
        require(f, Kind.LONG);
        return (Long) value(f);
    }

    /// A [Kind#STRING] flag's effective value, or null.
    public static String strOf(Flag f) {
        require(f, Kind.STRING);
        return (String) value(f);
    }

    /// A [Kind#PATH] flag's effective value, or null.
    public static Path pathOf(Flag f) {
        require(f, Kind.PATH);
        return (Path) value(f);
    }

    private static void require(Flag f, Kind k) {
        if (f.kind() != k) {
            throw new IllegalArgumentException(
                    f.property() + " is a " + f.kind() + ", read as " + k);
        }
    }

    // ================================================================ capabilities

    /// Provenance capabilities the running configuration does NOT have, because the flag gating each
    /// one does not hold. `toolkit.prov.Capabilities.current()` subtracts this. The gate lives on
    /// the flag, so the only thing crossing between `toolkit.prov` and `toolkit.util` is a set of
    /// names.
    public static Set<String> withheldCaps() {
        Set<String> out = new TreeSet<>();
        for (Flag f : ALL) {
            if (f.capability() != null && on(f) != f.capabilityWhen()) out.add(f.capability());
        }
        return Collections.unmodifiableSet(out);
    }

    // ================================================================ reporting and typo detection

    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private static final AtomicBoolean CHECKED = new AtomicBoolean();

    /// Log the effective configuration once per JVM: the file, and every knob off its default.
    /// Once, not per driver — same reasoning and same guard as [Strictness#report()].
    public static void report() {
        if (!REPORTED.compareAndSet(false, true)) return;
        List<String> set = new ArrayList<>();
        for (Flag f : ALL) {
            if (f.bootstrap()) continue;
            Object v = value(f);
            if (!Objects.equals(v, f.defaultValue())) {
                set.add(f.name() + "=" + v + "(" + source(f) + ")");
            }
        }
        Log.info("[conf] file=%s %s", FILE_PATH == null ? "(none)" : FILE_PATH,
                set.isEmpty() ? "all defaults" : String.join(" ", set));
    }

    /// Refuse or warn about every `-Dtoolkit.*` the table does not declare.
    ///
    /// The half the config file cannot cover: the JVM's property table is not ours, and it accepts
    /// `-Dtoolkit.refPach=false` silently. Warn by default; refuse under `-Dtoolkit.strict=true`.
    public static void checkUnknown() {
        // Once per JVM, like report(): a chained build runs several harnesses in one process.
        if (!CHECKED.compareAndSet(false, true)) return;
        List<String> unknown = new ArrayList<>();
        // stringPropertyNames(), not keySet(): Properties is a Map<Object,Object>, and a non-String
        // key put there by anything else in the JVM breaks a naive cast.
        for (String key : new TreeSet<>(System.getProperties().stringPropertyNames())) {
            if (!key.startsWith(PREFIX)) continue;
            String name = key.substring(PREFIX.length());
            if (BY_NAME.containsKey(name)) continue;
            unknown.add("-D" + key + suggestion(name));
        }
        if (unknown.isEmpty()) return;
        String msg = "no such knob: " + String.join(", ", unknown)
                + ". Every knob is declared in toolkit.util.Conf.";
        if (on(STRICT)) throw new IllegalStateException("REFUSED: " + msg);
        Log.warn("[conf] %s", msg);
    }

    // ================================================================ test support

    /// Set `f` for the rest of this JVM, above every other source. For tests.
    ///
    /// **It does not make a `static final` call site re-readable.** Those are resolved at class-init
    /// and fold away. This is for flags read on every call (`GenParallel.workers()`) and for setting
    /// a value before the class that reads it has initialised (`toolkit.test.Runner`).
    public static void override(Flag f, Object v) {
        String s = String.valueOf(v);
        parse(f, s, "override");                       // fail here, not at the read
        OVERRIDES.put(f.name(), s);
    }

    /// Drop an override, restoring the ordinary resolution order.
    public static void clearOverride(Flag f) {
        OVERRIDES.remove(f.name());
    }

    // ================================================================ declaration factories

    /// A declaration modifier. A record, not an `Object[]`: the latter is a varargs trap.
    private record Opt(String kind, String value, Boolean when) {}

    private static Flag bool(String name, boolean dflt, String doc, Opt... opts) {
        return declare(name, Kind.BOOL, dflt, doc, opts);
    }

    private static Flag integer(String name, int dflt, String doc, Opt... opts) {
        return declare(name, Kind.INT, dflt, doc, opts);
    }

    private static Flag longFlag(String name, long dflt, String doc, Opt... opts) {
        return declare(name, Kind.LONG, dflt, doc, opts);
    }

    private static Flag path(String name, Path dflt, String doc, Opt... opts) {
        return declare(name, Kind.PATH, dflt, doc, opts);
    }

    private static Flag bootstrapPath(String name, String doc) {
        Flag f = new Flag(name, Kind.PATH, null, doc, null, null, null, null, true);
        TABLE.add(f);
        return f;
    }

    /// Marks a capability gate: `cap(n, true)` = the capability holds when the flag is on.
    private static Opt cap(String name, boolean when) {
        return new Opt("cap", name, when);
    }

    /// Marks a pre-prefix system property still honoured.
    private static Opt legacy(String property) {
        return new Opt("legacy", property, null);
    }

    /// Marks an environment variable still honoured.
    private static Opt env(String name) {
        return new Opt("env", name, null);
    }

    private static Flag declare(String name, Kind kind, Object dflt, String doc, Opt... opts) {
        String capName = null;
        Boolean capWhen = null;
        String legacy = null;
        String env = null;
        for (Opt o : opts) {
            switch (o.kind()) {
                case "cap" -> {
                    capName = o.value();
                    capWhen = o.when();
                }
                case "legacy" -> legacy = o.value();
                case "env" -> env = o.value();
                default -> throw new IllegalArgumentException("unknown option " + o.kind());
            }
        }
        Flag f = new Flag(name, kind, dflt, doc, capName, capWhen, legacy, env, false);
        TABLE.add(f);
        return f;
    }
}
