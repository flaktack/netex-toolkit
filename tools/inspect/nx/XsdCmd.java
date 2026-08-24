package inspect.nx;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/// `nx xsd` — XSD validation of a NeTEx export zip, aggregated to a baseline-and-diff ledger.
///
/// ## Not a correctness gate
///
/// Both arms carry hundreds of thousands of schema issues, so a pass/fail threshold is dead on
/// arrival. NeTEx makes nearly everything optional — a missing `TransportMode` is `minOccurs="0"`,
/// a timezone in `@srsName` validates because the type is `anyURI`, `@version` is `use="optional"`
/// — so a schema validator is blind to data LOSS. It sees malformation.
///
/// ## What it is for
///
/// One orthogonal class no other gate can see: ELEMENT SEQUENCE ORDER — the export A/B
/// canonicalises sibling order away, the round trip absorbs it, and JAXB readers accept it
/// silently — plus lexical validity of ids and simple types, and the "both arms agree and both are
/// wrong" case an A/B comparator can never reach.
///
/// ## Shape
///
/// ```
/// nx xsd scan --zip <export.zip> [--schema PATH|1.15.2|2.0.0] [--workers N] --out FILE
/// nx xsd diff <baseline.tsv> <new.tsv>      # exit 1 on a NEW row or a count increase
/// ```
///
/// One TSV row per (arm, entry, cvc-class, message-signature) with a count and one verbatim
/// sample, never per occurrence. Locations are not carried: an arm that writes one line of XML
/// reports every error at line 2 with a column in the hundreds of millions.
///
/// ## Implementation constraints
///
/// - STREAMING, never `byte[]`. Both arms have an entry larger than `Integer.MAX_VALUE`, which
///    rules out any validator whose API takes a byte array.
/// - The NO-CONSTRAINT schema root by default. The constrained root carries hundreds of key,
///    keyref and unique constraints, costs an order of magnitude in wall, grows memory with
///    document size, and is wrong per entry: the export is split across many files, so every
///    cross-entry reference reports as a dangling keyref.
/// - No new dependencies: the JDK validator against the schemas vendored beside the model, which
///    are the tree the model was generated from, so a finding is a statement about the EMITTER
///    rather than about schema/model version skew.
///
/// Launchable standalone with no classpath at all: it touches nothing outside the JDK.
public final class XsdCmd {

    static final String VERSION = "nx-xsd/1";
    private static final int SIG_CARDINALITY_CAP = 5000;
    private static final int SAMPLE_MAX = 400;

    /// Default schema root — see the class comment on why `-NoConstraint` is not optional.
    private static final String DEFAULT_SCHEMA_VERSION = "1.15.2";
    private static final String SCHEMA_LEAF = "NeTEx_publication-NoConstraint.xsd";

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) return usage();
        String sub = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "scan" -> scan(rest);
            case "diff" -> diff(rest);
            case "-h", "--help", "help" -> usage();
            default -> {
                System.err.println("nx xsd: unknown subcommand '" + sub + "'");
                yield usage();
            }
        };
    }

    private static int usage() {
        System.err.println("""
                nx xsd — XSD validation of a NeTEx export zip, aggregated to a baseline-and-diff ledger.

                  nx xsd scan --zip <export.zip> --out <file.tsv> [options]
                      --zip PATH          the export to validate (read streaming; never read into memory)
                      --out PATH          TSV ledger to write ('-' for stdout)
                      --arm NAME          label written into the arm column (default: derived from the zip name)
                      --schema SPEC       1.15.2 (default) | 2.0.0 | an explicit .xsd path
                      --constrained       use NeTEx_publication.xsd instead of -NoConstraint.
                                          DO NOT. 13x wall, memory grows with document size, and it is
                                          semantically wrong per entry on a split export: every
                                          cross-entry reference reports as a dangling keyref.
                      --workers N         entry-level parallelism, share-nothing (default 8)
                      --entries a,b,c     validate only these entries
                      --limit N           validate only the N largest entries
                      --no-hash           skip the sha256 of the input zip (it is ~5 s/GB and it is
                                          the only thing that makes a number in the ledger citable)
                      --top N             classes to print in the stdout rollup (default 25)

                  nx xsd diff <baseline.tsv> <new.tsv> [options]
                      exit 1 on a NEW (arm,entry,class,signature) row or on a COUNT INCREASE.
                      A decrease or a disappearance prints and passes: this instrument ratchets, it
                      does not gate.
                      --ignore-arm        compare ignoring the arm column (use when comparing a
                                          re-scan labelled differently from the baseline section)
                      --no-entry          drop the entry from the key and sum over entries.
                                          REQUIRED for any cross-ARM comparison: the two arms
                                          partition their g* entries differently (86 files vs 85),
                                          so the same source defect lands in a different entry on
                                          each side and an entry-keyed cross-arm diff reports that
                                          partitioning as 10 new rows and 26 increases that are not
                                          defects at all. Summed, the real delta is 6 rows. Per-arm
                                          TOTALS are comparable across arms; per-entry counts never.
                      --arm NAME          restrict both sides to one arm
                      --quiet             print only failures and the verdict

                THIS IS NOT A CORRECTNESS GATE. Both arms are schema-invalid by ~10^6 issues, and XSD
                caught 0 of the 3 defects it was pointed at (a padded TransportMode, a timezone in
                @srsName, a missing @version) because NeTEx declares nearly everything optional and a
                validator cannot see loss. Its value is the orthogonal class: element ORDER, LEXICAL
                validity, and the "both arms agree and both are wrong" case an A/B comparator is
                structurally blind to. Read it that way or do not read it.""");
        return 2;
    }

    // ================================================================== scan

    private static int scan(String[] argv) throws Exception {
        Path zip = null, out = null;
        String arm = null, schemaSpec = DEFAULT_SCHEMA_VERSION;
        boolean constrained = false, noHash = false;
        int workers = 8, limit = -1, top = 25;
        Set<String> only = null;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--zip" -> zip = Path.of(argv[++i]);
                case "--out" -> out = Path.of(argv[++i]);
                case "--arm" -> arm = argv[++i];
                case "--schema" -> schemaSpec = argv[++i];
                case "--constrained" -> constrained = true;
                case "--no-hash" -> noHash = true;
                case "--workers" -> workers = Integer.parseInt(argv[++i]);
                case "--limit" -> limit = Integer.parseInt(argv[++i]);
                case "--top" -> top = Integer.parseInt(argv[++i]);
                case "--entries" -> {
                    only = new LinkedHashSet<>();
                    for (String s : argv[++i].split(",")) if (!s.isBlank()) only.add(s.trim());
                }
                default -> {
                    System.err.println("nx xsd scan: unknown option '" + argv[i] + "'");
                    return usage();
                }
            }
        }
        if (zip == null || out == null) return usage();
        if (!Files.isRegularFile(zip)) {
            System.err.println("nx xsd scan: no such zip: " + zip);
            return 2;
        }
        Path schemaFile = resolveSchema(schemaSpec, constrained);
        if (schemaFile == null) return 2;
        if (arm == null) arm = deriveArm(zip);

        PrintStream log = System.err;
        log.printf("%s scan%n  zip     %s (%,d B)%n  schema  %s%n  arm     %s%n  workers %d%n",
                VERSION, zip.toAbsolutePath(), Files.size(zip), schemaFile, arm, workers);
        if (constrained) {
            log.println("  !! --constrained: identity constraints ON. 13x wall, unbounded memory, and");
            log.println("     per-entry FALSE dangling keyrefs on a split export. NETEX_VALIDATION §3.1.");
        }

        MemSampler mem = new MemSampler();
        mem.start();

        long t0 = System.nanoTime();
        String sha = noHash ? "(skipped)" : sha256(zip);
        long hashMs = (System.nanoTime() - t0) / 1_000_000;
        if (!noHash) log.printf("  sha256  %s  (%,d ms)%n", sha, hashMs);

        // ---- compile once, share across workers (Schema is thread-safe; Validator is not).
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        List<String> schemaErrors = new ArrayList<>();
        sf.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e) { }
            public void error(SAXParseException e) { schemaErrors.add(e.getMessage()); }
            public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
        long c0 = System.nanoTime();
        Schema schema = sf.newSchema(schemaFile.toFile());
        long compileMs = (System.nanoTime() - c0) / 1_000_000;
        log.printf("  compile %,d ms, %d schema-load complaints%n", compileMs, schemaErrors.size());
        for (String s : schemaErrors.subList(0, Math.min(3, schemaErrors.size())))
            log.println("          " + s);

        // ---- entry list, largest first (best packing for a fixed pool).
        List<EntryRef> entries = new ArrayList<>();
        long totalBytes = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (var it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry ze = it.nextElement();
                if (ze.isDirectory()) continue;
                if (only != null && !only.contains(ze.getName())) continue;
                entries.add(new EntryRef(ze.getName(), ze.getSize()));
            }
        }
        entries.sort(Comparator.comparingLong((EntryRef e) -> e.size).reversed());
        if (limit > 0 && entries.size() > limit) entries = entries.subList(0, limit);
        for (EntryRef e : entries) totalBytes += e.size;
        log.printf("  entries %d, %,d B uncompressed%n%n", entries.size(), totalBytes);

        // ---- validate.
        long w0 = System.nanoTime();
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytesDone = new AtomicLong();
        final int n = entries.size();
        final long tb = totalBytes;
        List<EntryResult> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, workers));
        try {
            List<Future<EntryResult>> futs = new ArrayList<>();
            for (EntryRef e : entries) {
                final EntryRef ref = e;
                final Path zpath = zip;
                futs.add(pool.submit((Callable<EntryResult>) () -> {
                    EntryResult r = validateEntry(schema, zpath, ref);
                    int k = done.incrementAndGet();
                    long b = bytesDone.addAndGet(ref.size);
                    synchronized (log) {
                        log.printf("[%3d/%3d] %-28s %,15d B %,8d ms %6.1f MB/s %,10d issues%s  (%.1f%% of bytes)%n",
                                k, n, r.entry, r.bytes, r.ms, mbps(r.bytes, r.ms), r.total,
                                r.fatal == null ? "" : "  FATAL=" + r.fatal,
                                100.0 * b / Math.max(1, tb));
                    }
                    return r;
                }));
            }
            for (Future<EntryResult> f : futs) results.add(f.get());
        } finally {
            pool.shutdown();
        }
        long wallMs = (System.nanoTime() - w0) / 1_000_000;
        mem.stop();

        results.sort(Comparator.comparing(r -> r.entry));

        long totalIssues = 0, overflowed = 0;
        for (EntryResult r : results) { totalIssues += r.total; if (r.overflowed) overflowed++; }

        // ---- write the ledger.
        List<Row> rows = new ArrayList<>();
        for (EntryResult r : results)
            for (Map.Entry<SigKey, Agg> e : r.byKey.entrySet())
                rows.add(new Row(arm, r.entry, e.getKey().sev, e.getKey().cls, e.getKey().sig,
                        e.getValue().count, e.getValue().sample, "", ""));
        rows.sort(Comparator.comparing((Row x) -> x.entry).thenComparing(x -> x.cls)
                .thenComparing(Comparator.comparingLong((Row x) -> -x.count)).thenComparing(x -> x.sig));

        Map<String, long[]> classRollup = new TreeMap<>();
        for (Row r : rows) classRollup.computeIfAbsent(r.cls, k -> new long[1])[0] += r.count;

        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("#!").append(VERSION).append('\n');
        sb.append("# NOT A GATE. Both arms are schema-invalid by ~1e6 issues and XSD caught 0 of the 3\n");
        sb.append("# defects it was pointed at (minOccurs=0, anyURI, use=optional). NeTEx declares\n");
        sb.append("# nearly everything optional, so a validator cannot see LOSS -- only malformation.\n");
        sb.append("# This ledger exists for the orthogonal class: element ORDER, LEXICAL validity, and\n");
        sb.append("# the 'both arms agree and both are wrong' case an A/B comparator cannot reach.\n");
        sb.append("#\n");
        sb.append("# arm             ").append(arm).append('\n');
        sb.append("# zip             ").append(zip.toAbsolutePath()).append('\n');
        sb.append("# zip_bytes       ").append(Files.size(zip)).append('\n');
        sb.append("# zip_sha256      ").append(sha).append('\n');
        sb.append("# zip_mtime       ").append(Files.getLastModifiedTime(zip)).append('\n');
        sb.append("# schema          ").append(schemaFile).append('\n');
        sb.append("# identity_constraints ").append(constrained ? "ON (see NETEX_VALIDATION 3.1 -- this is wrong per-entry)" : "off").append('\n');
        sb.append("# entries         ").append(results.size()).append('\n');
        sb.append("# uncompressed_B  ").append(totalBytes).append('\n');
        sb.append("# workers         ").append(workers).append('\n');
        sb.append("# wall_ms         ").append(wallMs).append('\n');
        sb.append("# aggregate_MBps  ").append(String.format(Locale.ROOT, "%.1f", mbps(totalBytes, wallMs))).append('\n');
        sb.append("# schema_compile_ms ").append(compileMs).append('\n');
        sb.append("# peak_heap_MB    ").append(mem.peakHeapMb()).append('\n');
        sb.append("# peak_rss_MB     ").append(mem.peakRssMb()).append('\n');
        sb.append("# total_issues    ").append(totalIssues).append('\n');
        sb.append("# distinct_rows   ").append(rows.size()).append('\n');
        if (overflowed > 0)
            sb.append("# SIGNATURE_OVERFLOW ").append(overflowed).append(" entries exceeded ")
              .append(SIG_CARDINALITY_CAP).append(" distinct signatures; excess collapsed\n");
        sb.append("#\n# class rollup (arm total):\n");
        classRollup.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> sb.append(String.format(Locale.ROOT, "#   %-34s %,12d%n", e.getKey(), e.getValue()[0])));
        sb.append("#\n");
        sb.append("# arm\tentry\tseverity\tcvc_class\tsignature\tcount\tsample\tconsumer_that_could_care\tnote\n");
        for (Row r : rows) sb.append(r.toTsv()).append('\n');

        if ("-".equals(out.toString())) System.out.print(sb);
        else {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        }

        // ---- stdout rollup.
        PrintStream o = System.out;
        o.printf("%n=== nx xsd scan: %s%n", arm);
        o.printf("zip            %s%n", zip.toAbsolutePath());
        o.printf("zip_sha256     %s%n", sha);
        o.printf("entries        %d, %,d B uncompressed%n", results.size(), totalBytes);
        o.printf("wall           %,d ms at %d workers  (%.1f MB/s aggregate)%n", wallMs, workers, mbps(totalBytes, wallMs));
        o.printf("peak heap/rss  %,d MB / %,d MB%n", mem.peakHeapMb(), mem.peakRssMb());
        o.printf("issues         %,d in %,d distinct (entry,class,signature) rows%n", totalIssues, rows.size());
        o.printf("ledger         %s%n%n", out);
        o.printf("%-36s %14s%n", "cvc class", "count");
        classRollup.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(top)
                .forEach(e -> o.printf("%-36s %,14d%n", e.getKey(), e.getValue()[0]));
        o.println();
        o.println("Reminder: this is advisory. It is not a pass/fail gate and it cannot see data loss.");
        return 0;
    }

    // ------------------------------------------------------------ one entry

    /// Validates one zip entry with its own [ZipFile] handle and its own [Validator].
    /// Share-nothing: a shared `ZipFile` serialises every reader on one monitor, and a reused
    /// `Validator` retains the previous document's `xsd:ID` table.
    private static EntryResult validateEntry(Schema schema, Path zip, EntryRef ref) {
        EntryResult r = new EntryResult(ref.name, ref.size);
        Validator v = schema.newValidator();
        v.setErrorHandler(new ErrorHandler() {
            void rec(SAXParseException e, char sev) {
                r.total++;
                String msg = e.getMessage() == null ? "(null)" : e.getMessage();
                String[] cs = classify(msg);
                String cls = cs[0], sig = cs[1];
                if (r.byKey.size() >= SIG_CARDINALITY_CAP) {
                    r.overflowed = true;
                    sig = "(SIGNATURE-OVERFLOW: >" + SIG_CARDINALITY_CAP + " distinct signatures in this entry)";
                }
                SigKey k = new SigKey(sev, cls, sig);
                Agg a = r.byKey.get(k);
                if (a == null) r.byKey.put(k, new Agg(1, scrub(trunc(msg))));
                else a.count++;
            }
            public void warning(SAXParseException e) { rec(e, 'W'); }
            public void error(SAXParseException e) { rec(e, 'E'); }
            public void fatalError(SAXParseException e) { rec(e, 'F'); }
        });
        long t0 = System.nanoTime();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry ze = zf.getEntry(ref.name);
            if (ze == null) { r.fatal = "entry vanished"; return r; }
            try (InputStream in = zf.getInputStream(ze)) {
                v.validate(new StreamSource(in, "zip:" + ref.name));
            }
        } catch (Throwable t) {
            // A fatalError already recorded above will usually also arrive here as a throw; keep both,
            // the class column tells them apart and the ledger must never silently swallow a stop.
            r.fatal = t.getClass().getSimpleName() + ": " + trunc(String.valueOf(t.getMessage()));
        } finally {
            r.ms = (System.nanoTime() - t0) / 1_000_000;
        }
        return r;
    }

    // ------------------------------------------------------- classification

    /// Splits a Xerces message into `[cvc-class, signature]`.
    ///
    /// The signature is the message with VALUES redacted and NAMES kept, so many "not a valid
    /// value" messages collapse to one row while the same defect on a different ELEMENT stays
    /// distinguishable. Redaction is positional against the Xerces message templates rather than
    /// "anything in quotes", since the discriminating content is nearly always itself quoted.
    static String[] classify(String msg) {
        String cls, body;
        Matcher m = CVC_HEAD.matcher(msg);
        if (m.find()) {
            cls = m.group(1);
            body = msg.substring(m.end());
        } else {
            // not a cvc-* message: a well-formedness fatal, or an entity/limit error.
            cls = "xml-not-schema";
            body = msg;
        }
        for (Redaction rd : REDACTIONS) {
            Matcher r = rd.p.matcher(body);
            if (r.find()) body = r.replaceAll(rd.repl);
        }
        return new String[] { cls, scrub(body) };
    }

    private static final Pattern CVC_HEAD =
            Pattern.compile("^(cvc-[A-Za-z0-9]+(?:[.\\-][A-Za-z0-9]+)*)\\s*:\\s*");

    private record Redaction(Pattern p, String repl) { }

    /// Value positions in the Xerces `XMLSchemaMessages` bundle. Order matters: the more
    /// specific context wins. `$1`/`$2` keep the surrounding template text so the
    /// signature still reads as English.
    private static final List<Redaction> REDACTIONS = List.of(
            // cvc-attribute.3 / cvc-elt.4.1 : "The value 'X' of attribute 'A' ..."
            // cvc-type.3.1.3               : "The value 'X' of element 'E' is not valid."
            new Redaction(Pattern.compile("(The value ')[^']*(' of (?:attribute|element))"), "$1{V}$2"),
            // cvc-*-valid facets : "Value 'X' is not facet-valid ..." / "Value 'X' with length = 'N'"
            new Redaction(Pattern.compile("(Value ')[^']*(' (?:is not|with length|with maxLength|with minLength))"), "$1{V}$2"),
            new Redaction(Pattern.compile("(with length = ')[^']*(')"), "$1{N}$2"),
            // cvc-datatype-valid.1.2.* : "'X' is not a valid value ..."  (anchored at body start)
            new Redaction(Pattern.compile("^'[^']*(' is not a valid value)"), "'{V}$1"),
            // cvc-id.2 : "There are multiple occurrences of ID value 'X'."
            new Redaction(Pattern.compile("(ID value ')[^']*(')"), "$1{V}$2"),
            // cvc-id.1 : "There is no ID/IDREF binding for IDREF 'X'."
            new Redaction(Pattern.compile("(IDREF ')[^']*(')"), "$1{V}$2"),
            // cvc-identity-constraint (only reachable under --constrained): key/keyref values.
            new Redaction(Pattern.compile("(key(?:ref)? value ')[^']*(')"), "$1{V}$2"),
            new Redaction(Pattern.compile("(Duplicate (?:key|unique) value \\[)[^]]*(\\])"), "$1{V}$2"),
            new Redaction(Pattern.compile("(value \\[)[^]]*(\\] declared for identity constraint)"), "$1{V}$2"),
            // Safety net for a template this table does not know: a bare run of 4+ digits is a
            // count or an offset, never a name. Anything inside a URI or a dotted/hyphenated token
            // is excluded, since such a URI is part of the template rather than a value.
            new Redaction(Pattern.compile("(?<![\\w.'/:-])\\d{4,}(?![\\w./:-])"), "{N}")
    );

    private static String scrub(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String trunc(String s) {
        return s.length() <= SAMPLE_MAX ? s : s.substring(0, SAMPLE_MAX) + "...";
    }

    // ================================================================== diff

    private static int diff(String[] argv) throws Exception {
        Path base = null, cur = null;
        boolean ignoreArm = false, quiet = false, noEntry = false;
        String armFilter = null;
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--ignore-arm" -> ignoreArm = true;
                case "--no-entry" -> noEntry = true;
                case "--quiet" -> quiet = true;
                case "--arm" -> armFilter = argv[++i];
                default -> {
                    if (argv[i].startsWith("--")) { System.err.println("nx xsd diff: unknown option " + argv[i]); return usage(); }
                    if (base == null) base = Path.of(argv[i]);
                    else if (cur == null) cur = Path.of(argv[i]);
                    else return usage();
                }
            }
        }
        if (base == null || cur == null) return usage();

        Map<String, Row> b = load(base, ignoreArm, noEntry, armFilter);
        Map<String, Row> c = load(cur, ignoreArm, noEntry, armFilter);
        Set<String> baseClasses = new HashSet<>();
        for (Row r : b.values()) baseClasses.add((ignoreArm ? "" : r.arm) + "\0" + r.cls);
        Set<String> baseEntries = new HashSet<>();
        for (Row r : b.values()) baseEntries.add(r.entry);

        List<String> newClass = new ArrayList<>(), newSig = new ArrayList<>(), up = new ArrayList<>();
        List<String> down = new ArrayList<>(), gone = new ArrayList<>();
        long baseTotal = 0, curTotal = 0;
        for (Row r : b.values()) baseTotal += r.count;
        for (Row r : c.values()) curTotal += r.count;

        for (Map.Entry<String, Row> e : c.entrySet()) {
            Row nr = e.getValue();
            Row br = b.get(e.getKey());
            if (br == null) {
                String line = String.format(Locale.ROOT, "  %-12s %-28s %-30s %,10d  %s",
                        nr.arm, nr.entry, nr.cls, nr.count, nr.sig);
                if (!baseClasses.contains((ignoreArm ? "" : nr.arm) + "\0" + nr.cls)) newClass.add(line);
                else newSig.add(line + (baseEntries.contains(nr.entry) ? "" : "   [entry is new too]"));
            } else if (nr.count > br.count) {
                up.add(String.format(Locale.ROOT, "  %-12s %-28s %-30s %,10d -> %,10d  (+%,d)  %s",
                        nr.arm, nr.entry, nr.cls, br.count, nr.count, nr.count - br.count, nr.sig));
            } else if (nr.count < br.count) {
                down.add(String.format(Locale.ROOT, "  %-12s %-28s %-30s %,10d -> %,10d  (-%,d)  %s",
                        nr.arm, nr.entry, nr.cls, br.count, nr.count, br.count - nr.count, nr.sig));
            }
        }
        for (Map.Entry<String, Row> e : b.entrySet())
            if (!c.containsKey(e.getKey())) {
                Row br = e.getValue();
                gone.add(String.format(Locale.ROOT, "  %-12s %-28s %-30s %,10d -> %,10d  %s",
                        br.arm, br.entry, br.cls, br.count, 0L, br.sig));
            }

        PrintStream o = System.out;
        o.printf("nx xsd diff%n  baseline %s   (%,d rows, %,d issues)%n  current  %s   (%,d rows, %,d issues)%n",
                base, b.size(), baseTotal, cur, c.size(), curTotal);
        if (ignoreArm) o.println("  (--ignore-arm: the arm column is not part of the key)");
        if (noEntry) o.println("  (--no-entry: summed over entries; this is the only cross-arm-safe key)");
        if (armFilter != null) o.println("  (--arm " + armFilter + ")");
        o.println();
        section(o, "NEW CLASS   (fails)", newClass, false);
        section(o, "NEW ROW     (fails)", newSig, false);
        section(o, "INCREASE    (fails)", up, false);
        if (!quiet) {
            section(o, "decrease    (passes)", down, true);
            section(o, "gone        (passes)", gone, true);
        } else {
            o.printf("decrease: %d rows, gone: %d rows (suppressed by --quiet)%n%n", down.size(), gone.size());
        }
        int fails = newClass.size() + newSig.size() + up.size();
        if (fails > 0) {
            o.printf("FAIL: %d new class(es), %d new row(s), %d increase(s).%n",
                    newClass.size(), newSig.size(), up.size());
            o.println("This is an ADVISORY ratchet, not a correctness verdict. A regression here means the");
            o.println("emitter started producing a shape it did not produce before; it does NOT mean the");
            o.println("export is wrong, and a green diff does NOT mean the export is right (0 of 3).");
            return 1;
        }
        o.println("PASS: no new class, no new row, no count increase.");
        return 0;
    }

    private static void section(PrintStream o, String title, List<String> lines, boolean cap) {
        o.printf("%s: %d%n", title, lines.size());
        int n = 0;
        for (String l : lines) {
            o.println(l);
            if (cap && ++n >= 40) { o.printf("  ... %d more%n", lines.size() - n); break; }
        }
        o.println();
    }

    private static Map<String, Row> load(Path p, boolean ignoreArm, boolean noEntry, String armFilter)
            throws IOException {
        Map<String, Row> m = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.charAt(0) == '#') continue;
                String[] f = line.split("\t", -1);
                if (f.length < 6) throw new IOException(p + ":" + lineNo + ": expected >=6 tab fields, got " + f.length);
                Row r = new Row(f[0], f[1], f[2], f[3], f[4], Long.parseLong(f[5].trim()),
                        f.length > 6 ? f[6] : "", f.length > 7 ? f[7] : "", f.length > 8 ? f[8] : "");
                if (armFilter != null && !armFilter.equals(r.arm)) continue;
                if (noEntry) r = new Row(r.arm, "(all entries)", r.sev, r.cls, r.sig, r.count,
                        r.sample, r.consumer, r.note);
                String k = (ignoreArm ? "" : r.arm) + "\0" + r.entry + "\0" + r.cls + "\0" + r.sig;
                Row prev = m.put(k, r);
                if (prev != null)
                    m.put(k, new Row(r.arm, r.entry, r.sev, r.cls, r.sig, prev.count + r.count,
                            prev.sample, prev.consumer, prev.note));
            }
        }
        return m;
    }

    // ================================================================ pieces

    private record EntryRef(String name, long size) { }

    private static final class SigKey {
        final char sev; final String cls, sig;
        SigKey(char sev, String cls, String sig) { this.sev = sev; this.cls = cls; this.sig = sig; }
        @Override public boolean equals(Object o) {
            return o instanceof SigKey k && k.sev == sev && k.cls.equals(cls) && k.sig.equals(sig);
        }
        @Override public int hashCode() { return (cls.hashCode() * 31 + sig.hashCode()) * 31 + sev; }
    }

    private static final class Agg {
        long count; final String sample;
        Agg(long c, String s) { count = c; sample = s; }
    }

    private static final class EntryResult {
        final String entry; final long bytes;
        final Map<SigKey, Agg> byKey = new HashMap<>();
        long total, ms; String fatal; boolean overflowed;
        EntryResult(String e, long b) { entry = e; bytes = b; }
    }

    private record Row(String arm, String entry, String sev, String cls, String sig, long count,
                       String sample, String consumer, String note) {
        Row(String arm, String entry, char sev, String cls, String sig, long count, String sample,
            String consumer, String note) {
            this(arm, entry, String.valueOf(sev), cls, sig, count, sample, consumer, note);
        }
        String toTsv() {
            return String.join("\t", arm, entry, sev, cls, sig, Long.toString(count), sample, consumer, note);
        }
    }

    private static double mbps(long bytes, long ms) {
        return ms <= 0 ? 0 : bytes / 1048576.0 / (ms / 1000.0);
    }

    private static String deriveArm(Path zip) {
        String n = zip.getFileName().toString().replaceAll("\\.zip$", "");
        return n.contains("epip-merged") ? "python" : n;
    }

    private static Path resolveSchema(String spec, boolean constrained) {
        String leaf = constrained ? "NeTEx_publication.xsd" : SCHEMA_LEAF;
        Path p;
        if (spec.endsWith(".xsd")) {
            p = Path.of(spec);
        } else {
            // resolve relative to this source file's tree: scratch/nx/ -> ../../model/schemas
            Path root = Path.of("model", "schemas", spec);
            if (!Files.isDirectory(root)) root = Path.of("..", "..", "model", "schemas", spec);
            p = root.resolve(leaf);
        }
        if (!Files.isRegularFile(p)) {
            System.err.println("nx xsd: no schema at " + p.toAbsolutePath());
            System.err.println("        run from the repository root, or pass --schema <path to .xsd>");
            System.err.println("        note: the EPIP profile XSD is NOT vendored here, and per");
            System.err.println("        NETEX_VALIDATION §3.2 it is unmaintained and pinned to NeTEx 1.3.1 —");
            System.err.println("        advisory only, never a gate, and label the skew if you use it.");
            return null;
        }
        return p;
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(p)) {
            int k;
            while ((k = in.read(buf)) > 0) md.update(buf, 0, k);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte x : md.digest()) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    /// Peak heap and peak RSS, sampled — the two numbers the report has to quote honestly.
    private static final class MemSampler {
        private volatile boolean run = true;
        private volatile long peakHeap, peakRss;
        private Thread t;
        void start() {
            t = new Thread(() -> {
                Runtime rt = Runtime.getRuntime();
                while (run) {
                    long used = rt.totalMemory() - rt.freeMemory();
                    if (used > peakHeap) peakHeap = used;
                    long rss = rssKb();
                    if (rss > peakRss) peakRss = rss;
                    try { Thread.sleep(250); } catch (InterruptedException e) { return; }
                }
            }, "nx-xsd-mem");
            t.setDaemon(true);
            t.start();
        }
        void stop() { run = false; if (t != null) t.interrupt(); }
        long peakHeapMb() { return peakHeap / 1048576; }
        long peakRssMb() { return peakRss / 1024; }
        private static long rssKb() {
            try {
                for (String l : Files.readAllLines(Path.of("/proc/self/status")))
                    if (l.startsWith("VmHWM:")) return Long.parseLong(l.replaceAll("\\D+", ""));
            } catch (Exception ignored) { }
            return 0;
        }
    }

    private XsdCmd() { }
}
