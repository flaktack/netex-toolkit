package inspect.nx;

import toolkit.codec.FragmentCodec;
import toolkit.graph.Csr;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.ModelRegistry;
import toolkit.store.AbstractLmdbStore;
import toolkit.store.Store;
import toolkit.store.Stores;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/// `nx db` — the store browser.
///
/// Command lines onto SPI capabilities: reverse-reference lookup through the CSR's `in()`, the
/// `_embedding_idx` that answers "what is embedded in what", and `_id_idx`'s enumeration of every
/// version of an id.
///
/// ## The tier split
///
/// Which store machinery a question needs is a property of the question; every command prints its
/// own tier.
///
/// | tier | commands | what it opens |
/// |---|---|---|
/// | POINT | `stat` `get` `versions` `emb` `embof` `grep` | [RawStore]: LMDB read-only, one
///   long-lived read txn, dbis opened lazily. Milliseconds at any store size; heap is whatever the
///   answer occupies. |
/// | SCAN | `refs` `rrefs` `unres` `stat --edges` | The SPI only: `lookupById` for the address,
///   then `scanEdges`/`scanUnresolved` for one streaming pass. 16 bytes per edge, read and
///   discarded; O(1) heap, no intern table. It opens `V2Store` but never `loadEdgeState`, which
///   is lazy on a read-only store. |
/// | CSR | `path`, and the opt-in `--csr` arm | `loadEdgeState()` plus `Csr.build` — gigabytes and
///   tens of seconds at national scale. |
///
/// `_edge_blocks` is not indexed by referrer on disk, so any answer about one key costs a pass
/// either way; that is why `refs`/`rrefs` are SCAN. The `--csr` arm is agreement only — the raw
/// reader against `Csr.out`/`Csr.in` on the same store.
///
/// [RawStore] serves the POINT tier and `check`'s first phase: the embedding index is not in the
/// SPI, `MDB_stat` entry counts and raw dbi names are internal to the engine, and `_meta` carries
/// the completeness marker and the store's own class table, which may name classes this JVM's
/// registry does not have.
///
/// A diagnostic, not a gate: no pass/fail, no threshold, no baseline. A zero here is a statement
/// about the store you pointed at, not about the corpus.
public final class DbCmd {

    static final String VERSION = "nx-db/1";
    private static final String RULE = "─".repeat(96);

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] argv) throws Exception {
        if (argv.length == 0) return usage();
        String cmd = argv[0];
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        try {
            return switch (cmd) {
                case "stat" -> stat(rest);
                case "get" -> get(rest);
                case "versions" -> versions(rest);
                case "emb" -> emb(rest);
                case "embof" -> embof(rest);
                case "refs" -> refs(rest, false);
                case "rrefs" -> refs(rest, true);
                case "unres" -> unres(rest);
                case "grep" -> grep(rest);
                case "path" -> path(rest);
                case "resident" -> resident(rest);
                case "check" -> check(rest);
                case "-h", "--help", "help" -> usage();
                default -> {
                    System.err.println("nx db: unknown command '" + cmd + "'");
                    yield usage();
                }
            };
        } catch (BadUsage e) {
            System.err.println("nx db " + cmd + ": " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The refusals are the answer — "not a store directory", "a v1 database holds pickles,
            // not fragments". Both RawStore's and the format sniff's land here.
            System.err.println("nx db " + cmd + ": " + e.getMessage());
            return 2;
        }
    }

    // ================================================================== usage

    private static int usage() {
        System.err.println("""
                nx db — the store browser.

                Commands are grouped by WHAT THEY HAVE TO OPEN, because that is a 50-second and
                3.77-gigabyte difference on the national store and it is not a preference you should
                have to remember.  Every command prints its tier in its own header line.

                POINT — RawStore point lookups.  Opens in milliseconds at any store size.
                  stat     --db S [--db S2 ...] [--diff OTHER] [--tsv] [--all] [--classes N] [--edges]
                           entries per sub-database, with CLASS NAMES not 0x7403.  --diff is two
                           stores side by side and what moved: EMBEDDING_SCOPE's #100 gate was
                           literally "of 351 sub-databases, exactly 9 moved".  --tsv emits
                           the older raw counter's exact rows, so that pin still
                           reproduces from here.  --edges counts the PAIRS inside _edge_blocks (its
                           entry count is BLOCKS) and is the SCAN tier.
                  get      <id>[@ver] [--class C] --db S [--pick N] [--paths|--hex] [--head N]
                           one object's STORED BYTES.  No bean binding -- that is `nx lens --db`,
                           and this is its thin front.
                  versions <id> --db S [--class C]
                           every stored version of an id.  NeTEx's primary key is (id, version) and
                           DUPLICATE IDS ARE LEGAL BY DESIGN -- the national corpus carries 1,484
                           duplicate ids over 35,135 Line elements.  This makes that inspectable.
                  emb      <id>[@ver] [--class C] --db S          what is this embedded IN
                           #96/#100's question, read off the index that answers it.  Nothing in the
                           shipped CLI could reach _embedding_idx before.
                  get      <id>[@ver] [--class C] --db S [--pick N] [--paths|--hex] [--head N]
                           one object's STORED BYTES.  No bean binding -- that is `nx lens --db`,
                           and this is its thin front.

                SCAN — ONE streaming pass over ONE map.  O(1) heap.
                       The four edge commands (refs, rrefs, unres, stat --edges) are ONE READER
                       since #149: Store.lookupById resolves the address and
                       Store.scanEdges/scanUnresolved (#145) make the pass, both on the same
                       handle.  They DO open V2Store -- but never loadEdgeState, which #144 made
                       lazy on a read-only store.  This tool decodes no _edge_blocks and resolves
                       no _id_idx prefix of its own.  The rest of this tier is still RawStore for
                       the address plus one pass, because _embedding_idx and the class maps' own
                       class table are not SPI questions.
                  refs     <addr> --db S [--limit N] [--no-labels] [--csr]     _edge_blocks kind 0+1
                  rrefs    <addr> --db S [--limit N] [--by-class] [--csr]      _edge_blocks kind 0
                           rrefs is the capability nothing else in this repository can do.  Csr.in()
                           has existed since A4 and has never been reachable from a command line.
                  unres    --db S [--referrer <addr>] [--target <id>] [--class C] [--limit N]
                           _edge_blocks kind 1 + _unres_keys, with the graph dump's target rendering.
                  versions --audit --db S [--class C] [--top N]                _id_idx
                           #105's duplicate-id audit over the whole id index in one pass.  The index
                           sorts by id, so the group-by needs O(one id group) of heap, not O(ids).
                  emb      --census --db S [--all]                             _embedding_idx
                  embof    <addr> --db S [--limit N]                           _embedding_idx
                           what a container EMBEDS.  This direction is a scan because the map has no
                           inverse on disk -- it is keyed by the embedded object.
                  grep     --db S [--class C] [--contains STR] [--path P] [--attr A] [--value RE]
                           [--limit N] [--count] [--progress SEC]              the class maps
                           over STORED FRAGMENT BYTES.  Decompress + a byte prefilter; XML only when
                           you ask for --path/--attr/--value; never a JAXB decode, so it is honest
                           about what is stored rather than what a binding reconstructs.

                CSR — csr(txn) -> loadEdgeState().  #124 MEASURED 51,456,282 edges + 75,327,284
                      unresolved rows = 3.77 GB and 50 s on the national store.  Since #144 that is
                      billed to the first edge call and not to the open, so both endpoints of a
                      `path` are resolved and reported BEFORE it is paid.  JAVA_XMX=8g or larger.
                      Only these need it:
                  path     --from <addr> --to <addr> --db S [--max-depth N] [--undirected]
                  resident --db S [--csr]
                           WHAT loadEdgeState() COSTS, on the store you point at: used heap before
                           and after, beside the live row counts of both kinds.  #124's 3.77 GB is
                           the figure everything else quotes; this is how it is re-measured when the
                           mix of edges to unresolved rows changes, which is exactly what wiring a
                           reference does -- it MOVES a row from one column to the other.  --csr
                           adds Csr.build on top, which is a second copy of the resolved half.
                  refs/rrefs --csr        the same answer via Csr.out/Csr.in, printed BESIDE the
                                          scan answer.  Its purpose is agreement.
                  check    --db S [--head N]
                           THE PIN.  Store.scanEdges/scanUnresolved (streaming, one block resident)
                           against Store.iterEdges/iterUnresolved (loadEdgeState's materialised
                           slot arrays), on an ORDER-SENSITIVE digest.  Order is the point: past
                           block 255 cursor order is not numeric order (a u32 LE block
                           number in a byte-ordered map) and the store SPI makes edge order a
                           recorded property.  It REFUSES to call itself a pass on a store with
                           <=255 blocks of both kinds, and carries a negative control that must
                           show this store's block numbers reorder under cursor order.

                ADDRESSING (anywhere <addr> appears)
                  <id>[@<version>]   version omitted = every stored version; all are listed.
                  --class C          the model class.  Prefer this over the spec's ':Class' suffix:
                                     NeTEx ids are colon-heavy and ':' is ambiguous.
                  0x<16 hex>         a full_key, as refs/rrefs print them.
                  --pick N           when an id has duplicates, render match N (default 0).  Every
                                     match is always listed first.

                THIS IS A DIAGNOSTIC, NOT A GATE.  No pass/fail; do not wire it into a chain.
                AND A ZERO IS A STATEMENT ABOUT THE STORE YOU POINTED AT, not about the corpus.""");
        return 2;
    }

    // ================================================================== stat

    /// Entry count per sub-database, and the two-store diff.
    ///
    /// `--tsv` reproduces the older raw counter's rows exactly — same labels, same text-or-hex name
    /// rendering, same sort, same trailers — so a transcript pinned on that output still
    /// reproduces. Without it, a hex class-map name is resolved to its class through `_meta`.
    private static int stat(String[] argv) {
        Args a = Args.parse(argv, Set.of("--tsv", "--all", "--edges"),
                Set.of("--db", "--diff", "--classes"));
        List<Path> dbs = a.paths("--db");
        if (dbs.isEmpty()) throw new BadUsage("--db <store> is required");
        Path diff = a.path("--diff");
        boolean tsv = a.flag("--tsv");
        boolean all = a.flag("--all");
        int topClasses = a.getInt("--classes", 10);
        PrintStream o = System.out;

        if (diff != null) {
            if (dbs.size() != 1) throw new BadUsage("--diff takes exactly one --db on the other side");
            return statDiff(dbs.get(0), diff, all, o);
        }
        for (Path db : dbs) {
            try (RawStore s = new RawStore(db)) {
                if (tsv) {
                    List<Row> rs = rows(s);
                    long total = 0;
                    for (Row r : rs) {
                        o.println(label(db) + "\t" + r.name + "\t" + r.entries);
                        total += r.entries;
                    }
                    o.println(label(db) + "\t#DBIS\t" + rs.size());
                    o.println(label(db) + "\t#TOTAL\t" + total);
                } else {
                    header(o, "stat", a.flag("--edges") ? "SCAN" : "POINT", db, s);
                    statHuman(s, o, all, topClasses);
                }
            }
            // Outside the RawStore block: --edges reads the edges through the SPI, and LMDB does
            // not support two environments over one file in one process.
            if (!tsv && a.flag("--edges")) {
                edgeCounts(db, o);
                o.println();
            } else if (!tsv) {
                o.println();
            }
        }
        return 0;
    }

    /// One sub-database as `stat` sees it.
    private record Row(String name, long entries, int classIdx, String className) {
        boolean isClassMap() { return classIdx >= 0; }
    }

    /// Every sub-database, rendered and sorted exactly as the older raw counter did.
    private static List<Row> rows(RawStore s) {
        List<Row> out = new ArrayList<>();
        for (byte[] n : s.dbiNames()) {
            int idx = -1;
            if (n.length >= 1 && n.length <= 2) {
                idx = n.length == 1 ? (n[0] & 0xFF) : NulKeyCodec.classIdxInt(n);
            }
            out.add(new Row(render(n), s.entriesOfDbi(n), idx, idx < 0 ? null : s.className(idx)));
        }
        out.sort(Comparator.comparing(Row::name));
        return out;
    }

    /// The older raw counter's name rendering, byte for byte: text if it is text, else `0x` plus
    /// lowercase hex.
    private static String render(byte[] n) {
        boolean text = n.length > 0;
        for (byte b : n) if (b < 0x20 || b >= 0x7F) { text = false; break; }
        if (text) return new String(n, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : n) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String label(Path db) {
        return db.getFileName().toString().replaceAll("\\.lmdb$", "");
    }

    private static void statHuman(RawStore s, PrintStream o, boolean all, int topClasses) {
        List<Row> rs = rows(s);
        long total = 0, classRows = 0;
        int classMaps = 0;
        List<Row> classes = new ArrayList<>();
        Map<String, Long> side = new LinkedHashMap<>();
        for (Row r : rs) {
            total += r.entries;
            if (r.isClassMap()) { classMaps++; classRows += r.entries; classes.add(r); }
            else side.put(r.name, r.entries);
        }
        o.printf("  %-16s %14s   %s%n", "sub-database", "entries", "what it is");
        for (Map.Entry<String, Long> e : side.entrySet()) {
            o.printf("  %-16s %,14d   %s%n", e.getKey(), e.getValue(), sideMapNote(e.getKey()));
        }
        o.printf("  %-16s %,14d   %d class maps%n", "(class maps)", classRows, classMaps);
        o.printf("  %-16s %,14d   #DBIS %d%n", "#TOTAL", total, rs.size());
        o.println();

        // The embedding index is the one map whose absence is a finding rather than a zero.
        if (!s.hasDbi("_embedding_idx")) {
            o.println("  NOTE  no _embedding_idx: this store predates A7's persisted embedding index.");
            o.println("        `nx db emb` has nothing to read here, and that is a property of the");
            o.println("        store, not of the object you were about to ask about.");
        } else if (s.metaRow("emb_idx_done") == null) {
            o.println("  NOTE  _embedding_idx present but emb_idx_done is NOT set: the index is");
            o.println("        INCOMPLETE, so resolve_embeddings falls back to the candidate-class");
            o.println("        corpus scan and a zero from `nx db emb` proves nothing.");
        }

        classes.sort(Comparator.comparingLong(Row::entries).reversed());
        int show = all ? classes.size() : Math.min(topClasses, classes.size());
        if (show > 0) {
            o.printf("  class maps%s:%n", all ? "" : " (top " + show + " of " + classes.size()
                    + "; --all for every one)");
            for (int i = 0; i < show; i++) {
                Row r = classes.get(i);
                o.printf("    %-10s idx %-6d %,14d   %s%n", r.name, r.classIdx, r.entries,
                        r.className == null ? "(NOT IN _meta -- an orphan map)" : r.className);
            }
        }
    }

    /// Live record counts per `_edge_blocks` kind. Opt-in: the one part of `stat` that reads the
    /// whole map rather than `MDB_stat`'s O(1) counters. The open is a `V2Store` open, but neither
    /// call triggers `loadEdgeState`, so the heap stays O(1).
    private static void edgeCounts(Path db, PrintStream o) {
        long[] live = new long[2];
        long t0 = System.nanoTime();
        try (Store st = Stores.open(db, true); toolkit.store.Txn tx = st.roTxn()) {
            for (long[] ignored : st.scanEdges(tx)) live[0]++;
            for (toolkit.store.UnresolvedRow ignored : st.scanUnresolved(tx)) live[1]++;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        o.println();
        o.printf("  --edges: one _edge_blocks pass through Store.scanEdges/scanUnresolved, %,d ms%n", ms);
        o.printf("    kind 0 resolved edges   %,14d%n", live[0]);
        o.printf("    kind 1 unresolved rows  %,14d%n", live[1]);
        o.println("    (tombstoned slots are excluded, as V2Store's own iterators exclude them)");
    }

    // ================================================================== resident

    /// CSR tier, and the only command whose subject is the cost rather than the answer.
    ///
    /// `loadEdgeState()` materialises both columns — resolved edges into `edgeSrc`/`edgeDst`,
    /// unresolved rows into `unresSrc`/`unresKeyId` over the `_unres_keys` intern table — plus a
    /// per-referrer slot index for each. All of it is heap for as long as the store is open, so a
    /// stage's working set includes it whether or not that stage ever reads an edge.
    ///
    /// The two columns do not cost the same per row: an edge is two longs, while an unresolved row
    /// is a long plus an intern id and the table behind it holds one byte[] per distinct target
    /// key. Wiring a reference moves a row from the second column to the first and may drop an
    /// intern entry with it, so the sign of the change is not readable off the row counts.
    ///
    /// Heap is read after `System.gc()`, which is a request and not a guarantee, so this is a
    /// measurement of one JVM on one run. Give it the `-Xmx` you are actually asking about: the
    /// collector's slack scales with the heap it was handed.
    private static int resident(String[] argv) {
        Args a = Args.parse(argv, Set.of("--csr"), Set.of("--db"));
        List<Path> dbs = a.paths("--db");
        if (dbs.size() != 1) throw new BadUsage("--db <store> is required (exactly one)");
        boolean withCsr = a.flag("--csr");
        PrintStream o = System.out;
        Path db = dbs.get(0);

        o.println(RULE);
        o.printf("nx db resident — CSR tier — %s%n", db);
        o.printf("  -Xmx %,d MB%n", Runtime.getRuntime().maxMemory() >> 20);
        o.println(RULE);

        long base = usedHeap();
        long edges = 0;
        long unres = 0;
        long t0 = System.nanoTime();
        try (Store st = Stores.open(db, true); toolkit.store.Txn tx = st.roTxn()) {
            // iterEdges/iterUnresolved are the materialised views: the first of them pays
            // loadEdgeState, which is what this command measures.
            for (long[] ignored : st.iterEdges(tx)) edges++;
            for (toolkit.store.UnresolvedRow ignored : st.iterUnresolved(tx)) unres++;
            long loaded = usedHeap();
            double secs = (System.nanoTime() - t0) / 1e9;
            long rows = edges + unres;
            o.printf("  live rows       edges %,14d   unresolved %,14d   total %,14d%n",
                    edges, unres, rows);
            o.printf("  loadEdgeState   %,14d MB   in %6.1f s%s%n",
                    (loaded - base) >> 20, secs,
                    rows == 0 ? "" : String.format("   (%.1f bytes/row blended)",
                            (double) (loaded - base) / rows));
            if (withCsr) {
                long c0 = System.nanoTime();
                Csr csr = st.csr(tx);
                long withBuilt = usedHeap();
                o.printf("  + Csr.build     %,14d MB   in %6.1f s   over %,d resolved edges%n",
                        (withBuilt - loaded) >> 20, (System.nanoTime() - c0) / 1e9, edges);
                // Referenced after the measurement so the build cannot be collected before it.
                if (csr == null) o.println("  (csr was null)");
            }
        }
        o.println();
        o.println("  A DIAGNOSTIC, NOT A GATE. The delta is this JVM's used heap around the load,");
        o.println("  so it includes the collector's slack and excludes the page cache over data.mdb.");
        return 0;
    }

    /// Used heap after asking for a collection. Three rounds because one `System.gc()` need not
    /// have finished tracing by the time it returns.
    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return r.totalMemory() - r.freeMemory();
    }

    private static String sideMapNote(String name) {
        return switch (name) {
            case "_meta" -> "format marker, seq, the 'c' class table, the two one-shot markers";
            case "_id_idx" -> "id NUL version NUL classIdx -> full_key";
            case "_embedding_idx" -> "embedded key -> container full_key (A7; DUPSORT)";
            case "_edge_blocks" -> "BLOCKS, not edges (<=65,536 pairs each) -- use --edges";
            case "_unres_keys" -> "intern id -> encoded target key";
            case "_class_idx", "_reference_outward", "_unresolved" -> "retired v1 map (not java-v2)";
            default -> "";
        };
    }

    /// Two stores, per-dbi, and what moved.
    private static int statDiff(Path aPath, Path bPath, boolean all, PrintStream o) {
        try (RawStore sa = new RawStore(aPath); RawStore sb = new RawStore(bPath)) {
            o.println(VERSION + "   " + java.time.Instant.now());
            o.println("stat --diff   tier=POINT (RawStore; no V2Store, no edge state)");
            o.println("  A  " + aPath.toAbsolutePath() + "   format=" + sa.formatMarker());
            o.println("  B  " + bPath.toAbsolutePath() + "   format=" + sb.formatMarker());
            o.println(RULE);
            Map<String, Row> ma = new TreeMap<>();
            Map<String, Row> mb = new TreeMap<>();
            for (Row r : rows(sa)) ma.put(r.name, r);
            for (Row r : rows(sb)) mb.put(r.name, r);
            Set<String> names = new java.util.TreeSet<>();
            names.addAll(ma.keySet());
            names.addAll(mb.keySet());
            long totA = 0, totB = 0;
            int moved = 0, onlyA = 0, onlyB = 0;
            List<String> lines = new ArrayList<>();
            for (String n : names) {
                Row ra = ma.get(n), rb = mb.get(n);
                long ea = ra == null ? 0 : ra.entries;
                long eb = rb == null ? 0 : rb.entries;
                totA += ea;
                totB += eb;
                String cn = ra != null && ra.className != null ? ra.className
                        : rb != null && rb.className != null ? rb.className : "";
                String mark = ra == null ? "ONLY-B" : rb == null ? "ONLY-A" : ea == eb ? "" : "MOVED";
                if (ra == null) onlyB++;
                else if (rb == null) onlyA++;
                else if (ea != eb) moved++;
                if (!mark.isEmpty() || all) {
                    lines.add(String.format("  %-6s %-16s %,14d %,14d %+,14d   %s",
                            mark, n, ea, eb, eb - ea, cn));
                }
            }
            o.printf("  %-6s %-16s %14s %14s %14s   %s%n", "", "sub-database", "A", "B", "delta", "class");
            for (String l : lines) o.println(l);
            o.println(RULE);
            o.printf("  %d of %d sub-databases moved (%d only in A, %d only in B); "
                    + "#TOTAL A %,d  B %,d  delta %+,d%n",
                    moved, names.size(), onlyA, onlyB, totA, totB, totB - totA);
            o.println();
            o.println("  A DIAGNOSTIC, NOT A GATE: this says what moved, never whether moving was");
            o.println("  right. #100's gate was 'the _embedding_idx delta is exactly the Entrance");
            o.println("  population AND NOTHING ELSE MOVED' -- the second half is the line above,");
            o.println("  the first half is a number only you know.");
            return 0;
        }
    }

    // ================================================================== get

    /// One object's stored bytes. The thin front onto `lookupFullKey` + `loadRawObject`.
    private static int get(String[] argv) throws Exception {
        Args a = Args.parse(argv, Set.of("--paths", "--hex"), Set.of("--db", "--class", "--pick", "--head"));
        Path db = one(a.paths("--db"));
        String addr = a.onePositional();
        Addr ad = parseAddr(addr, a.get("--class"));
        int pick = a.getInt("--pick", 0);
        int head = a.getInt("--head", 400);
        PrintStream o = System.out;
        try (RawStore s = new RawStore(db)) {
            header(o, "get", "POINT", db, s);
            FragmentCodec codec = new FragmentCodec();
            List<RawStore.Hit> hits = hits(s, ad, o);
            if (hits.isEmpty()) return 1;
            int use = Math.min(pick, hits.size() - 1);
            RawStore.Hit h = hits.get(use);
            byte[] blob = s.rawBlob(h);
            if (blob == null) {
                o.printf("  DANGLING INDEX ENTRY: _id_idx points at full_key 0x%016x but class map "
                        + "%s has no such row.%n", h.fullKey(), h.className());
                o.println("  That is itself a finding -- the id index and the class map disagree.");
                return 1;
            }
            byte[] frag = codec.decompress(blob);
            o.printf("  [%d] %s  full_key=0x%016x  local=%d  stored=%,d B  inflated=%,d B%n",
                    use, h.address(), h.fullKey(), h.localKey(), blob.length, frag.length);
            o.println(RULE);
            if (a.flag("--hex")) {
                hex(o, blob, head);
            } else if (a.flag("--paths")) {
                XmlDoc d = XmlDoc.parse(frag);
                int n = 0;
                for (Map.Entry<String, String> e : d.flatten().entrySet()) {
                    if (n++ >= head) { o.println("  ... (--head " + head + ")"); break; }
                    o.println("  " + e.getKey() + " = " + e.getValue());
                }
            } else {
                String[] ls = new String(frag, StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 0; i < ls.length && i < head; i++) o.println("  " + ls[i]);
                if (ls.length > head) o.println("  ... (" + (ls.length - head) + " more, --head N)");
            }
            o.println(RULE);
            o.println("  These are STORED BYTES. For the bound bean, the re-marshal, the source and");
            o.println("  the export beside them, and one verdict line per boundary: nx lens " + addr
                    + " --db " + db);
            return 0;
        }
    }

    // ================================================================== versions

    /// Every stored version of an id, and — with `--audit` — the whole store's duplicate-id census
    /// in one `_id_idx` pass.
    ///
    /// Duplicate ids are legal: the primary key is (id, version), `version` is an optional string
    /// whose ordering the schema delegates to "agreed-upon rules", and `ValidBetween` carries no
    /// uniqueness constraint.
    private static int versions(String[] argv) {
        Args a = Args.parse(argv, Set.of("--audit"), Set.of("--db", "--class", "--top"));
        Path db = one(a.paths("--db"));
        String cls = a.get("--class");
        PrintStream o = System.out;
        try (RawStore s = new RawStore(db)) {
            header(o, "versions", a.flag("--audit") ? "SCAN" : "POINT", db, s);
            if (a.flag("--audit")) return audit(s, o, cls, a.getInt("--top", 20));
            String id = a.onePositional();
            Addr ad = parseAddr(id, cls);
            if (ad.isFullKey()) throw new BadUsage("versions takes an id, not a full_key");
            List<RawStore.Hit> hits = s.lookup(ad.id(), null, ad.className());
            if (hits.isEmpty()) {
                o.println("  no _id_idx row at id prefix '" + ad.id() + "'"
                        + (ad.className() != null ? " (class " + ad.className() + ")" : ""));
                o.println("  An EMBEDDED object has no _id_idx row of its own -- try: nx db emb "
                        + ad.id() + " --db " + db);
                return 1;
            }
            FragmentCodec codec = new FragmentCodec();
            o.printf("  %-4s %-12s %-30s %-18s %10s  %s%n",
                    "#", "version", "class", "full_key", "stored B", "blob sha256-12");
            Map<String, List<Integer>> byDigest = new LinkedHashMap<>();
            for (int i = 0; i < hits.size(); i++) {
                RawStore.Hit h = hits.get(i);
                byte[] blob = s.rawBlob(h);
                String dg = blob == null ? "(NO ROW -- DANGLING)" : sha12(blob);
                byDigest.computeIfAbsent(dg, k -> new ArrayList<>()).add(i);
                o.printf("  %-4d %-12s %-30s 0x%016x %,10d  %s%n", i,
                        h.version().isEmpty() ? "(none)" : h.version(), h.className(), h.fullKey(),
                        blob == null ? 0 : blob.length, dg);
            }
            o.println();
            Set<String> vers = new LinkedHashSet<>();
            Set<String> clss = new LinkedHashSet<>();
            for (RawStore.Hit h : hits) { vers.add(h.version()); clss.add(h.className()); }
            o.printf("  %d row(s): %d distinct version(s), %d distinct class(es).%n",
                    hits.size(), vers.size(), clss.size());
            for (Map.Entry<String, List<Integer>> e : byDigest.entrySet()) {
                if (e.getValue().size() > 1) {
                    o.println("  rows " + e.getValue() + " are BYTE-IDENTICAL stored blobs.");
                }
            }
            if (hits.size() > 1) {
                o.println();
                o.println("  Duplicate ids are LEGAL BY DESIGN. NeTEx's primary key is (id, version);");
                o.println("  version is an optional STRING whose ordering is delegated to agreed-upon");
                o.println("  rules, and ValidBetween is annotated 'NO UNIQUENESS CONSTRAINT'.");
                o.println("  Measured on the national corpus: 1,484 duplicate ids over 35,135 Line");
                o.println("  elements. Sorting ascending is NOT a fix -- 100% of the 1,252 duplicate-id");
                o.println("  AT Lines carry ValidBetween, and 25.5% of their highest versions are");
                o.println("  single-calendar-day service overrides.");
                o.println("  Resolve on (id, version). Last-write-wins is what changes an Operator.");
            }
            if (!vers.contains("") && vers.size() == hits.size()) {
                o.println("  Every row here has a distinct version, i.e. this is the LEGAL shape.");
            }
            o.println("  For the full layer stack of one of them: nx lens " + ad.id()
                    + " --db " + db + " --pick N");
            return 0;
        }
    }

    /// The duplicate-id audit, whole store, one pass. `_id_idx` sorts by id bytes first, so an id's
    /// rows are adjacent and the group-by needs O(one id group) of memory.
    private static int audit(RawStore s, PrintStream o, String classFilter, int top) {
        Map<Integer, long[]> perClass = new HashMap<>(); // idx -> {rows, ids, dupIds, maxCopies}
        long[] totals = new long[4];
        List<String> examples = new ArrayList<>();
        long[] crossClassIds = new long[1];
        long t0 = System.nanoTime();

        final String[] curId = {null};
        final Map<Integer, Integer> curByClass = new LinkedHashMap<>();
        final long[] curRows = {0};

        Runnable flush = () -> {
            if (curId[0] == null) return;
            if (curByClass.size() > 1) crossClassIds[0]++;
            for (Map.Entry<Integer, Integer> e : curByClass.entrySet()) {
                long[] acc = perClass.computeIfAbsent(e.getKey(), k -> new long[4]);
                acc[0] += e.getValue();
                acc[1] += 1;
                if (e.getValue() > 1) acc[2] += 1;
                acc[3] = Math.max(acc[3], e.getValue());
                if (e.getValue() > 1 && examples.size() < 5) {
                    String cn = s.className(e.getKey());
                    examples.add(String.format("%s  %s  x%d", cn == null ? "?" : cn, curId[0], e.getValue()));
                }
            }
            totals[0] += curRows[0];
            totals[1] += 1;
            if (curRows[0] > 1) totals[2] += 1;
            totals[3] = Math.max(totals[3], curRows[0]);
            curByClass.clear();
            curRows[0] = 0;
        };

        long scanned = s.scanIdIndex((key, fk) -> {
            int stripLen = NulKeyCodec.stripClazzLen(key);
            byte[] idVer = new byte[stripLen];
            System.arraycopy(key, 0, idVer, 0, stripLen);
            String id = NulKeyCodec.idPart(idVer);
            if (!id.equals(curId[0])) { flush.run(); curId[0] = id; }
            curByClass.merge(NulKeyCodec.classTrailer(key), 1, Integer::sum);
            curRows[0]++;
            return true;
        });
        flush.run();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        o.printf("  _id_idx: %,d rows scanned in %,d ms%s%n", scanned, ms,
                classFilter == null ? "" : "   (--class " + classFilter + " filters the report only)");
        o.println();
        o.printf("  %-32s %12s %12s %12s %10s%n", "class", "rows", "ids", "dup ids", "max copies");
        List<Map.Entry<Integer, long[]>> es = new ArrayList<>(perClass.entrySet());
        es.sort((x, y) -> Long.compare(y.getValue()[2], x.getValue()[2]) != 0
                ? Long.compare(y.getValue()[2], x.getValue()[2])
                : Long.compare(y.getValue()[0], x.getValue()[0]));
        int shown = 0, hidden = 0;
        for (Map.Entry<Integer, long[]> e : es) {
            String cn = s.className(e.getKey());
            if (cn == null) cn = "?idx" + e.getKey();
            if (classFilter != null && !classFilter.equals(cn)) continue;
            if (classFilter == null && shown >= top) { hidden++; continue; }
            long[] v = e.getValue();
            o.printf("  %-32s %,12d %,12d %,12d %,10d%n", cn, v[0], v[1], v[2], v[3]);
            shown++;
        }
        if (hidden > 0) o.printf("  ... %d more class(es) (--top N)%n", hidden);
        o.println(RULE);
        o.printf("  store total: %,d rows over %,d distinct ids; %,d ids carry more than one row "
                + "(max %,d); %,d ids appear under more than one class.%n",
                totals[0], totals[1], totals[2], totals[3], crossClassIds[0]);
        if (!examples.isEmpty()) {
            o.println("  examples: ");
            for (String x : examples) o.println("    " + x);
        }
        o.println();
        if (totals[2] == 0) {
            o.println("  ZERO duplicate ids IN THIS STORE. That is a statement about this artefact,");
            o.println("  not about the corpus: EXP-2's 1,484 are AT Lines, so a single Italian feed");
            o.println("  store cannot show them. Point this at the national store to see them.");
        } else {
            o.println("  Duplicate ids are LEGAL: the key is (id, version). This is a census, not a");
            o.println("  defect list. What EXP-2 measured as consumer-visible was 8 routes whose");
            o.println("  last-emitted Operator differs, out of 1,484 duplicate ids.");
        }
        return 0;
    }

    // ================================================================== emb / embof

    /// "What is this embedded in" — the `_embedding_idx` point lookup, and its census.
    private static int emb(String[] argv) throws Exception {
        Args a = Args.parse(argv, Set.of("--census", "--all"), Set.of("--db", "--class"));
        Path db = one(a.paths("--db"));
        PrintStream o = System.out;
        try (RawStore s = new RawStore(db)) {
            header(o, "emb", a.flag("--census") ? "SCAN" : "POINT", db, s);
            if (!embIndexPresent(s, o)) return 1;
            FragmentCodec codec = new FragmentCodec();
            if (a.flag("--census")) return embCensus(s, o, codec, a.flag("--all"));
            String id = a.onePositional();
            Addr ad = parseAddr(id, a.get("--class"));
            if (ad.isFullKey()) throw new BadUsage("emb takes an id (the EMBEDDED object), not a full_key");
            List<RawStore.EmbRow> rows = s.embeddedIn(ad.id(), ad.version(), ad.className());
            if (rows.isEmpty()) {
                o.println("  no _embedding_idx row at id prefix '" + ad.id() + "'.");
                o.println("  Either this object is not embedded, or its class is OUT OF THE INDEX'S");
                o.println("  SCOPE. The scope is not every class -- see `nx db emb --census`, which");
                o.println("  prints it. #100 is exactly a class entering that scope.");
                return 1;
            }
            o.printf("  %d container(s):%n", rows.size());
            Map<Long, String> cache = new HashMap<>();
            for (RawStore.EmbRow r : rows) {
                String cn = s.className(r.embClassIdx());
                o.printf("    embedded %-28s %s@%s%n", cn == null ? "?" + r.embClassIdx() : cn,
                        r.embId(), r.embVersion().isEmpty() ? "(none)" : r.embVersion());
                o.printf("      in     %s%n", label(s, codec, r.containerFullKey(), cache));
            }
            o.println();
            o.println("  This is #96/#100's question read off the index that answers it. An embedded");
            o.println("  object has NO _id_idx row and NO class-map row of its own -- `nx db get` on");
            o.println("  it will find nothing, and the container above is where its bytes live.");
            return 0;
        }
    }

    private static boolean embIndexPresent(RawStore s, PrintStream o) {
        if (!s.hasDbi("_embedding_idx")) {
            o.println("  This store has NO _embedding_idx: it predates A7's persisted index.");
            o.println("  There is nothing to read, which is a property of the store. A zero here");
            o.println("  would have been a lie.");
            return false;
        }
        if (s.metaRow("emb_idx_done") == null) {
            o.println("  WARNING  _embedding_idx exists but emb_idx_done is NOT set: the index is");
            o.println("           INCOMPLETE (clone retracts the marker when a source");
            o.println("           lacks it). resolve_embeddings falls back to the corpus scan on");
            o.println("           this store, so an absence below proves nothing.");
        }
        return true;
    }

    /// The whole index as a class-pair table: what is embedded in what.
    private static int embCensus(RawStore s, PrintStream o, FragmentCodec codec, boolean all) {
        Map<Long, long[]> pairs = new HashMap<>(); // (embIdx<<32|containerIdx) -> {rows}
        Map<Integer, long[]> byEmb = new HashMap<>();
        long t0 = System.nanoTime();
        long n = s.scanEmbeddingIndex((key, container) -> {
            int embIdx = NulKeyCodec.classTrailer(key);
            int conIdx = NulKeyCodec.fullKeyClassIdx(container);
            pairs.computeIfAbsent(((long) embIdx << 32) | conIdx, k -> new long[1])[0]++;
            byEmb.computeIfAbsent(embIdx, k -> new long[1])[0]++;
            return true;
        });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        o.printf("  _embedding_idx: %,d rows scanned in %,d ms%n", n, ms);
        o.println();
        o.printf("  %-34s %-34s %12s%n", "embedded class", "container class", "rows");
        List<Map.Entry<Long, long[]>> es = new ArrayList<>(pairs.entrySet());
        es.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
        for (Map.Entry<Long, long[]> e : es) {
            int ei = (int) (e.getKey() >>> 32);
            int ci = (int) (e.getKey() & 0xFFFFFFFFL);
            o.printf("  %-34s %-34s %,12d%n", nameOf(s, ei), nameOf(s, ci), e.getValue()[0]);
        }
        o.println(RULE);
        // A class that should be in scope and is not shows up only in the difference between the
        // scope column and the observed column.
        Set<String> scope = new java.util.TreeSet<>(AbstractLmdbStore.embeddingIndexClassNames());
        Set<String> observed = new java.util.TreeSet<>();
        for (Integer i : byEmb.keySet()) observed.add(nameOf(s, i));
        o.println("  the index's SCOPE (AbstractLmdbStore.embeddingIndexClassNames() plus every");
        o.println("  registered TypeOf* not ending in Ref), against what this store actually holds:");
        for (String c : scope) {
            boolean seen = observed.contains(c);
            o.printf("    %-38s %s%n", c, seen ? "present" : "0 rows in this store");
        }
        List<String> extra = new ArrayList<>();
        for (String c : observed) if (!scope.contains(c) && !c.startsWith("TypeOf")) extra.add(c);
        if (!extra.isEmpty()) {
            o.println("    indexed but NOT in the curated list (i.e. matched by the TypeOf* rule or");
            o.println("    written by an older scope): " + String.join(", ", extra));
        }
        o.println();
        o.println("  #100 IS THE DIFFERENCE BETWEEN THESE TWO COLUMNS. 'Entrance' is listed in the");
        o.println("  scope but only its JAXB alias SiteEntrance_VersionStructure can ever match, so");
        o.println("  a store built before that alias was listed shows Entrance at 0 rows here while");
        o.println("  its containers still hold entrances. That is the whole defect, on one screen.");
        o.println("  A ZERO IS A STATEMENT ABOUT THIS STORE: BoardingPosition and HailAndRideArea");
        o.println("  have 0 corpus rows anywhere, and 0 here means only that.");
        return 0;
    }

    /// The read surface `nx db` needs, answerable from either handle: an address to `_id_idx` rows,
    /// a class idx to its name, a full_key to that row's stored bytes. [RawStore] answers all three
    /// from `_meta`, the id index and a class-map point lookup; the SPI answers them through
    /// `lookupById`, `classForIdx` and `loadRawObject`.
    ///
    /// The class-less address matters: `lookupFullKey` needs a class before it looks at anything,
    /// so without `lookupById` a SCAN command would have to resolve its address through [RawStore]
    /// and close that handle before opening the SPI store — LMDB refuses two environments over one
    /// file in one process.
    interface Meta {
        /// Every `_id_idx` row of this address; `className`/`version` filter, both nullable.
        List<RawStore.Hit> lookup(String id, String version, String className);

        String className(int classIdx);

        byte[] rawBlob(int classIdx, int localKey);
    }

    private static Meta meta(RawStore s) {
        return new Meta() {
            @Override public List<RawStore.Hit> lookup(String id, String v, String cn) {
                return s.lookup(id, v, cn);
            }

            @Override public String className(int idx) { return s.className(idx); }

            @Override public byte[] rawBlob(int idx, int local) { return s.rawBlob(idx, local); }
        };
    }

    private static Meta meta(Store st, toolkit.store.Txn tx) {
        return new Meta() {
            @Override public List<RawStore.Hit> lookup(String id, String v, String cn) {
                List<RawStore.Hit> out = new ArrayList<>();
                for (toolkit.store.IdRow r : st.lookupById(tx, id, v)) {
                    byte[] k = r.encodedKey();
                    byte[] body = new byte[NulKeyCodec.stripClazzLen(k)];
                    System.arraycopy(k, 0, body, 0, body.length);
                    int ci = NulKeyCodec.classTrailer(k);
                    String name = className(ci);
                    // lookupById returns every row at the prefix and leaves the choice among them
                    // to the caller, so the class filter is applied here.
                    if (cn != null && !cn.equals(name)) continue;
                    out.add(new RawStore.Hit(NulKeyCodec.idPart(body), NulKeyCodec.versionPart(body),
                            ci, name == null ? "?idx" + ci : name, r.fullKey(),
                            NulKeyCodec.fullKeyLocal(r.fullKey())));
                }
                return out;
            }

            @Override public String className(int idx) {
                Class<?> c = st.classForIdx(idx);
                return c == null ? null : c.getSimpleName();
            }

            @Override public byte[] rawBlob(int idx, int local) {
                Class<?> c = st.classForIdx(idx);
                return c == null ? null : st.loadRawObject(tx, c, NulKeyCodec.u32le(local));
            }
        };
    }

    /// The SPI store, with [RawStore]'s first refusal kept. `Stores.sniffFormat` refuses a v1 or
    /// non-toolkit database with a good message, but a container with no `data.mdb` at all fails
    /// inside lmdbjava with a message about a missing file rather than about what the operator
    /// pointed at, so that check comes first.
    private static Store openSpi(Path db) {
        if (!java.nio.file.Files.exists(db.resolve("data.mdb"))) {
            throw new IllegalArgumentException(db + ": no data.mdb (not a store directory)");
        }
        return Stores.open(db, true);
    }

    private static String nameOf(RawStore s, int idx) {
        return nameOf(meta(s), idx);
    }

    private static String nameOf(Meta m, int idx) {
        String n = m.className(idx);
        return n == null ? "?idx" + idx : n;
    }

    /// "What does this container embed" — the reverse of `_embedding_idx`, which has no
    /// inverse on disk (the map is keyed by the embedded object), so it is one streaming pass.
    private static int embof(String[] argv) throws Exception {
        Args a = Args.parse(argv, Set.of(), Set.of("--db", "--class", "--pick", "--limit"));
        Path db = one(a.paths("--db"));
        String addr = a.onePositional();
        int limit = a.getInt("--limit", 200);
        PrintStream o = System.out;
        try (RawStore s = new RawStore(db)) {
            header(o, "embof", "SCAN", db, s);
            if (!embIndexPresent(s, o)) return 1;
            FragmentCodec codec = new FragmentCodec();
            long fk = resolveOne(s, parseAddr(addr, a.get("--class")), a.getInt("--pick", 0), o);
            if (fk < 0) return 1;
            List<String> found = new ArrayList<>();
            long t0 = System.nanoTime();
            long scanned = s.scanEmbeddingIndex((key, container) -> {
                if (container == fk && found.size() < limit) {
                    int ci = NulKeyCodec.classTrailer(key);
                    byte[] body = new byte[NulKeyCodec.stripClazzLen(key)];
                    System.arraycopy(key, 0, body, 0, body.length);
                    String v = NulKeyCodec.versionPart(body);
                    found.add(String.format("%-30s %s@%s", nameOf(s, ci), NulKeyCodec.idPart(body),
                            v.isEmpty() ? "(none)" : v));
                }
                return true;
            });
            long ms = (System.nanoTime() - t0) / 1_000_000;
            o.printf("  scanned %,d _embedding_idx rows in %,d ms; %d embedded object(s) in this "
                    + "container%s%n", scanned, ms, found.size(),
                    found.size() >= limit ? " (--limit " + limit + " reached)" : "");
            for (String f : found) o.println("    " + f);
            if (found.isEmpty()) {
                o.println("  This container embeds nothing THAT THE INDEX COVERS. The index is scoped");
                o.println("  (`nx db emb --census` prints the scope); an out-of-scope embedded object");
                o.println("  is in the container's fragment but not in this map.");
                o.println("  To see every child element regardless of scope: nx db get " + addr
                        + " --db " + db + " --paths");
            }
            return 0;
        }
    }

    // ================================================================== refs / rrefs

    /// Forward and reverse reference lookup.
    ///
    /// A single reverse lookup does not need an adjacency structure, so `rrefs` answers without
    /// paying `loadEdgeState`: the pass is `Store.scanEdges`/`Store.scanUnresolved`, on the same
    /// handle that `Store.lookupById` resolved the address on. `--csr` runs the `Csr` arm beside
    /// it, for agreement.
    private static int refs(String[] argv, boolean reverse) throws Exception {
        Args a = Args.parse(argv, Set.of("--no-labels", "--csr", "--by-class"),
                Set.of("--db", "--class", "--pick", "--limit"));
        Path db = one(a.paths("--db"));
        String addr = a.onePositional();
        int limit = a.getInt("--limit", 200);
        boolean labels = !a.flag("--no-labels");
        String name = reverse ? "rrefs" : "refs";
        PrintStream o = System.out;
        List<Long> scanHits = new ArrayList<>();
        List<String> unresolvedOfReferrer = new ArrayList<>();

        try (Store st = openSpi(db); toolkit.store.Txn tx = st.roTxn()) {
            header(o, name, "SCAN", db, st);
            Meta m = meta(st, tx);
            final long fk = resolveOne(m, parseAddr(addr, a.get("--class")), a.getInt("--pick", 0), o);
            if (fk < 0) return 1;
            o.printf("  full_key 0x%016x  (class idx %d = %s, local %d)%n", fk,
                    NulKeyCodec.fullKeyClassIdx(fk), nameOf(m, NulKeyCodec.fullKeyClassIdx(fk)),
                    NulKeyCodec.fullKeyLocal(fk));
            long scanned = 0;
            long t0 = System.nanoTime();
            for (long[] e : st.scanEdges(tx)) {
                scanned++;
                if (reverse ? e[1] == fk : e[0] == fk) scanHits.add(reverse ? e[0] : e[1]);
            }
            // scanUnresolved resolves each row's interned target key by a point lookup into
            // _unres_keys, which dominates the run at scale. rrefs is a kind-0 question and never
            // looks at a target, so it does not take this pass.
            if (!reverse) {
                for (toolkit.store.UnresolvedRow r : st.scanUnresolved(tx)) {
                    scanned++;
                    if (r.referrerFullKey() == fk) {
                        unresolvedOfReferrer.add(decodeTarget(m, r.encodedTarget()));
                    }
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            o.printf("  one _edge_blocks pass via Store.%s: %,d live records in %,d ms"
                    + " (O(1) heap, no loadEdgeState)%n",
                    reverse ? "scanEdges (kind 0 only)" : "scanEdges/scanUnresolved", scanned, ms);
            o.println(RULE);
            FragmentCodec codec = new FragmentCodec();
            Map<Long, String> cache = new HashMap<>();
            if (a.flag("--by-class")) {
                Map<String, long[]> byClass = new TreeMap<>();
                for (long k : scanHits) {
                    byClass.computeIfAbsent(nameOf(m, NulKeyCodec.fullKeyClassIdx(k)),
                            x -> new long[1])[0]++;
                }
                o.printf("  %d %s, by class:%n", scanHits.size(), reverse ? "referrer(s)" : "target(s)");
                for (Map.Entry<String, long[]> e : byClass.entrySet()) {
                    o.printf("    %-34s %,10d%n", e.getKey(), e.getValue()[0]);
                }
            } else {
                o.printf("  %d %s%s:%n", scanHits.size(), reverse ? "referrer(s)" : "resolved target(s)",
                        scanHits.size() > limit ? " (showing " + limit + "; --limit N, --by-class)" : "");
                int n = 0;
                for (long k : scanHits) {
                    if (n++ >= limit) break;
                    o.printf("    0x%016x  %s%n", k, labels ? label(m, codec, k, cache) : "");
                }
            }
            if (!reverse) {
                o.println();
                o.printf("  %d UNRESOLVED reference(s) from this object (kind-1 rows):%n",
                        unresolvedOfReferrer.size());
                int n = 0;
                for (String u : unresolvedOfReferrer) {
                    if (n++ >= limit) break;
                    o.println("    -> " + u);
                }
                if (unresolvedOfReferrer.isEmpty()) {
                    o.println("    none. Note that resolve() moves rows from kind 1 to kind 0, so a");
                    o.println("    zero here is a statement about this store's resolve state.");
                }
            }
            if (scanHits.isEmpty() && !reverse) {
                o.println();
                o.println("  No outward edges. Either this object references nothing, or every");
                o.println("  reference it carries is still unresolved (listed above).");
            }
            if (scanHits.isEmpty() && reverse) {
                o.println();
                o.println("  Nothing references this object IN THIS STORE. In a single-feed store");
                o.println("  that is routine; the referrers may live in a feed you did not load.");
            }
            // The --csr arm runs on this handle, and loadEdgeState still happens no earlier than
            // the csr(tx) call below.
            if (a.flag("--csr")) return csrCrossCheck(st, tx, fk, reverse, scanHits, o);
            return 0;
        }
    }

    /// The `Csr` arm, opt-in, printed beside the scan arm and run on the caller's already-open
    /// handle. The only place where `refs`/`rrefs` pay `loadEdgeState`.
    private static int csrCrossCheck(Store st, toolkit.store.Txn tx, long fk, boolean reverse,
                                     List<Long> scanHits, PrintStream o) {
        o.println();
        o.println(RULE);
        o.println("  --csr: the same question again, through V2Store -> Csr."
                + (reverse ? " Csr.in()." : " Csr.out()."));
        csrWarning(o);
        long t1 = System.nanoTime();
        Csr csr = st.csr(tx);
        long build = (System.nanoTime() - t1) / 1_000_000;
        long[] got = reverse ? csr.in(fk) : csr.out(fk);
        o.printf("  loadEdgeState + Csr.build %,d ms; %,d nodes, %,d edges%n", build, csr.n, csr.e);
        Set<Long> mine = new LinkedHashSet<>(scanHits);
        Set<Long> theirs = new LinkedHashSet<>();
        for (long g : got) theirs.add(g);
        boolean agree = mine.equals(theirs);
        o.printf("  scan arm %d, Csr arm %d -> %s%n", mine.size(), theirs.size(),
                agree ? "AGREE" : "DISAGREE");
        if (!agree) {
            Set<Long> onlyScan = new LinkedHashSet<>(mine);
            onlyScan.removeAll(theirs);
            Set<Long> onlyCsr = new LinkedHashSet<>(theirs);
            onlyCsr.removeAll(mine);
            for (long k : onlyScan) o.printf("    only in the streaming scan: 0x%016x%n", k);
            for (long k : onlyCsr) o.printf("    only in the Csr:            0x%016x%n", k);
            o.println("  A disagreement means Store.scanEdges and loadEdgeState read the same bytes");
            o.println("  differently -- which is `nx db check`'s subject. Csr.build additionally");
            o.println("  DROPS DUPLICATE PAIRS (DUPSORT semantics), so a scan-side surplus of a");
            o.println("  repeated (src,dst) is expected and is not a defect.");
            return 1;
        }
        return 0;
    }

    // ================================================================== unres

    /// The unresolved rows — `_edge_blocks` kind 1 plus `_unres_keys`, with the `Class|id|version`
    /// target rendering, so a label from here and a label from a graph dump are the same string.
    private static int unres(String[] argv) throws Exception {
        Args a = Args.parse(argv, Set.of(),
                Set.of("--db", "--referrer", "--target", "--class", "--limit", "--pick"));
        Path db = one(a.paths("--db"));
        int limit = a.getInt("--limit", 50);
        String targetId = a.get("--target");
        String classFilter = a.get("--class");
        PrintStream o = System.out;
        try (Store st = openSpi(db); toolkit.store.Txn tx = st.roTxn()) {
            header(o, "unres", "SCAN", db, st);
            Meta m = meta(st, tx);
            long referrer = -1;
            if (a.get("--referrer") != null) {
                referrer = resolveOne(m, parseAddr(a.get("--referrer"), null), a.getInt("--pick", 0), o);
                if (referrer < 0) return 1;
            }
            final long refFk = referrer;
            Map<String, long[]> byPair = new TreeMap<>();
            List<String> shown = new ArrayList<>();
            long matched = 0;
            long scanned = 0;
            long t0 = System.nanoTime();
            for (toolkit.store.UnresolvedRow row : st.scanUnresolved(tx)) {
                scanned++;
                long x = row.referrerFullKey();
                byte[] tk = row.encodedTarget();
                if (refFk >= 0 && x != refFk) continue;
                String tClass = tk == null ? "?" : nameOf(m, NulKeyCodec.classTrailer(tk));
                if (classFilter != null && !classFilter.equals(tClass)) continue;
                String tid = null;
                if (tk != null) {
                    byte[] body = new byte[NulKeyCodec.stripClazzLen(tk)];
                    System.arraycopy(tk, 0, body, 0, body.length);
                    tid = NulKeyCodec.idPart(body);
                }
                if (targetId != null && (tid == null || !tid.equals(targetId))) continue;
                matched++;
                String rClass = nameOf(m, NulKeyCodec.fullKeyClassIdx(x));
                byPair.computeIfAbsent(rClass + "  ->  " + tClass, k -> new long[1])[0]++;
                if (shown.size() < limit) {
                    shown.add(String.format("0x%016x %-28s -> %s", x, rClass, decodeTarget(m, tk)));
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            o.printf("  one Store.scanUnresolved pass: %,d live rows in %,d ms; %,d matched%n",
                    scanned, ms, matched);
            o.println(RULE);
            if (matched == 0) {
                o.println("  No unresolved rows match.");
                if (scanned == 0) {
                    o.println("  This store has NO kind-1 rows at all -- either resolve() has run to");
                    o.println("  completion, or nothing was ever unresolved. A zero here is a");
                    o.println("  statement about this store's resolve state, not about the data.");
                }
                return 0;
            }
            o.println("  rows (Class|id|version is the graph dump's target rendering):");
            for (String r : shown) o.println("    " + r);
            if (matched > shown.size()) {
                o.printf("    ... %,d more (--limit N)%n", matched - shown.size());
            }
            o.println();
            o.println("  by (referrer class -> target class):");
            List<Map.Entry<String, long[]>> es = new ArrayList<>(byPair.entrySet());
            es.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
            int n = 0;
            for (Map.Entry<String, long[]> e : es) {
                if (n++ >= 40) { o.println("    ..."); break; }
                o.printf("    %-60s %,10d%n", e.getKey(), e.getValue()[0]);
            }
            o.println();
            o.println("  An unresolved row is not necessarily a defect: resolve() and");
            o.println("  resolve_embeddings() are the passes that consume them, and a store");
            o.println("  captured before those ran is expected to be full of them.");
            return 0;
        }
    }

    private static String decodeTarget(RawStore s, byte[] v) {
        return decodeTarget(meta(s), v);
    }

    /// The graph dump's target decoding, byte for byte, so labels from the two tools compare.
    private static String decodeTarget(Meta s, byte[] v) {
        if (v == null) return "(no _unres_keys row for this intern id)";
        byte[] body = Arrays.copyOf(v, v.length - 2);
        int cls = NulKeyCodec.classIdxInt(new byte[] {v[v.length - 2], v[v.length - 1]});
        String[] parts = new String(body, StandardCharsets.UTF_8).split("\\u0000", -1);
        String tid = parts[0];
        String tver = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "-";
        String cn = s.className(cls);
        if (cn == null) cn = String.format("?%02x%02x", v[v.length - 2] & 0xff, v[v.length - 1] & 0xff);
        return cn + "|" + tid + "|" + tver;
    }

    // ================================================================== grep

    /// Byte-level search over stored fragments. `--contains` is a prefilter on the inflated bytes;
    /// `--path`/`--attr`/`--value` parse the XML. Never a JAXB decode: what is stored, not what a
    /// binding reconstructs.
    private static int grep(String[] argv) throws Exception {
        Args a = Args.parse(argv, Set.of("--count"),
                Set.of("--db", "--class", "--contains", "--path", "--attr", "--value", "--limit",
                        "--progress"));
        Path db = one(a.paths("--db"));
        String cls = a.get("--class");
        String contains = a.get("--contains");
        String pathPat = a.get("--path");
        String attr = a.get("--attr");
        String valueRe = a.get("--value");
        int limit = a.getInt("--limit", 50);
        int progress = a.getInt("--progress", 10);
        boolean countOnly = a.flag("--count");
        if (contains == null && pathPat == null && attr == null && valueRe == null) {
            throw new BadUsage("give at least one of --contains / --path / --attr / --value");
        }
        Pattern valueP = valueRe == null ? null : Pattern.compile(valueRe);
        byte[] needle = contains == null ? null : contains.getBytes(StandardCharsets.UTF_8);
        boolean needXml = pathPat != null || attr != null || valueP != null;
        PrintStream o = System.out;
        try (RawStore s = new RawStore(db)) {
            header(o, "grep", "SCAN", db, s);
            o.printf("  filter: %s%s%s%s%s%n",
                    cls == null ? "every class map" : "class=" + cls,
                    contains == null ? "" : "  contains=" + q(contains),
                    pathPat == null ? "" : "  path~" + q(pathPat),
                    attr == null ? "" : "  attr=" + q(attr),
                    valueRe == null ? "" : "  value~" + q(valueRe));
            o.println("  reading STORED BYTES (decompress" + (needXml ? " + XML parse" : "")
                    + "); no bean binding anywhere.");
            o.println(RULE);
            FragmentCodec codec = new FragmentCodec();
            long[] objects = {0}, matches = {0}, parsed = {0};
            long t0 = System.nanoTime();
            long[] lastReport = {t0};
            for (Integer idx : s.populatedClassIdxs()) {
                String cn = s.className(idx);
                if (cls != null && !cls.equals(cn)) continue;
                s.scanClass(idx, (local, blob) -> {
                    objects[0]++;
                    if (progress > 0 && System.nanoTime() - lastReport[0] > progress * 1_000_000_000L) {
                        lastReport[0] = System.nanoTime();
                        System.err.printf("  ... %,d objects, %,d matches, in %s%n",
                                objects[0], matches[0], cn);
                    }
                    byte[] frag;
                    try {
                        frag = codec.decompress(blob);
                    } catch (RuntimeException e) {
                        return true;
                    }
                    if (needle != null && indexOf(frag, needle) < 0) return true;
                    if (!needXml) {
                        matches[0]++;
                        if (!countOnly && matches[0] <= limit) {
                            report(o, s, codec, idx, local, frag, null, null);
                        }
                        return true;
                    }
                    Map<String, String> flat;
                    try {
                        parsed[0]++;
                        flat = XmlDoc.parse(frag).flatten();
                    } catch (Exception e) {
                        return true;
                    }
                    for (Map.Entry<String, String> e : flat.entrySet()) {
                        String p = e.getKey();
                        if (pathPat != null && !p.contains(pathPat)) continue;
                        if (attr != null && !p.endsWith("/@" + attr)) continue;
                        if (valueP != null && !valueP.matcher(e.getValue()).find()) continue;
                        matches[0]++;
                        if (!countOnly && matches[0] <= limit) {
                            report(o, s, codec, idx, local, frag, p, e.getValue());
                        }
                        break;
                    }
                    return true;
                });
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            o.println(RULE);
            o.printf("  %,d objects read, %,d XML-parsed, %,d matched, %,d ms%n",
                    objects[0], parsed[0], matches[0], ms);
            if (matches[0] > limit && !countOnly) {
                o.printf("  (%,d matches beyond --limit %d not shown)%n", matches[0] - limit, limit);
            }
            if (matches[0] == 0) {
                o.println("  Zero matches IN THIS STORE. If the store was written by a re-marshalling");
                o.println("  driver the source spelling may already have been normalised away -- the");
                o.println("  same caveat that makes `nx bind`'s national REPAIRED count 0.");
            }
            return 0;
        }
    }

    private static void report(PrintStream o, RawStore s, FragmentCodec codec, int idx, int local,
                               byte[] frag, String path, String value) {
        String id = "?", ver = "";
        try {
            XmlDoc d = XmlDoc.parse(frag);
            for (String[] at : d.root.attrs) {
                if (at[0].equals("id")) id = at[1];
                if (at[0].equals("version")) ver = at[1];
            }
        } catch (Exception ignored) {
            // a fragment that will not parse is itself worth showing, with the address it has
        }
        o.printf("  %-28s %s@%s  local=%d%s%n", nameOf(s, idx), id, ver.isEmpty() ? "(none)" : ver,
                local, path == null ? "" : "   " + path + " = " + q(value));
    }

    // ================================================================== path

    /// "Why is this reachable" — BFS over the CSR. The one command here that pays `loadEdgeState`,
    /// because a search wants random-access adjacency and a scan per hop does not give it that.
    private static int path(String[] argv) {
        Args a = Args.parse(argv, Set.of("--undirected"),
                Set.of("--db", "--from", "--to", "--from-class", "--to-class", "--max-depth"));
        Path db = one(a.paths("--db"));
        String from = a.get("--from"), to = a.get("--to");
        if (from == null || to == null) throw new BadUsage("--from <addr> and --to <addr> are required");
        int maxDepth = a.getInt("--max-depth", 8);
        boolean undirected = a.flag("--undirected");
        PrintStream o = System.out;
        o.println(VERSION + "   " + java.time.Instant.now());
        o.println("path   tier=CSR   " + db.toAbsolutePath());
        // Both endpoints are resolved before csr(tx) is called, so a typo in an id costs the open
        // and not the whole edge state. loadEdgeState is lazy on a read-only store.
        long t0 = System.nanoTime();
        try (Store st = openSpi(db); toolkit.store.Txn tx = st.roTxn()) {
            long open = (System.nanoTime() - t0) / 1_000_000;
            Meta m = meta(st, tx);
            o.println("  --from:");
            long a0 = resolveOne(m, parseAddr(from, a.get("--from-class")), 0, o);
            o.println("  --to:");
            long b0 = resolveOne(m, parseAddr(to, a.get("--to-class")), 0, o);
            if (a0 < 0) { o.println("  --from: no row for '" + from + "'"); return 1; }
            if (b0 < 0) { o.println("  --to:   no row for '" + to + "'"); return 1; }
            csrWarning(o);
            long t1 = System.nanoTime();
            Csr csr = st.csr(tx);
            long build = (System.nanoTime() - t1) / 1_000_000;
            o.printf("  open %,d ms; loadEdgeState + Csr.build %,d ms; %,d nodes, %,d edges%n",
                    open, build, csr.n, csr.e);
            o.printf("  from 0x%016x  to 0x%016x  max-depth %d  %s%n", a0, b0, maxDepth,
                    undirected ? "(undirected: out() and in())" : "(directed: out() only)");
            o.println(RULE);
            Map<Long, Long> prev = new LinkedHashMap<>();
            prev.put(a0, a0);
            List<Long> frontier = new ArrayList<>(List.of(a0));
            boolean found = a0 == b0;
            for (int d = 0; d < maxDepth && !found && !frontier.isEmpty(); d++) {
                List<Long> next = new ArrayList<>();
                for (long k : frontier) {
                    for (long n : csr.out(k)) {
                        if (prev.putIfAbsent(n, k) == null) next.add(n);
                        if (n == b0) { found = true; break; }
                    }
                    if (!found && undirected) {
                        for (long n : csr.in(k)) {
                            if (prev.putIfAbsent(n, k) == null) next.add(n);
                            if (n == b0) { found = true; break; }
                        }
                    }
                    if (found) break;
                }
                o.printf("  depth %d: frontier %,d, visited %,d%n", d + 1, next.size(), prev.size());
                frontier = next;
            }
            if (!found) {
                o.printf("  NO PATH within %d hop(s) (%,d nodes visited).%n", maxDepth, prev.size());
                o.println("  Unresolved references are NOT edges -- the CSR is built from kind-0 rows");
                o.println("  only, so a chain that is still unresolved is invisible here. Check");
                o.println("  `nx db unres --referrer " + from + "` before concluding disconnection.");
                return 1;
            }
            List<Long> chain = new ArrayList<>();
            for (long k = b0; ; k = prev.get(k)) {
                chain.add(k);
                if (k == a0) break;
            }
            java.util.Collections.reverse(chain);
            o.printf("  PATH of %d hop(s):%n", chain.size() - 1);
            for (long k : chain) {
                Class<?> c = st.classForIdx(NulKeyCodec.fullKeyClassIdx(k));
                String id = "?";
                try {
                    Object obj = st.loadObjectByFullKey(tx, k);
                    if (obj instanceof noi.netex.model.EntityStructure e && e.getId() != null) {
                        id = e.getId();
                    }
                } catch (RuntimeException ignored) {
                    id = "(undecodable)";
                }
                o.printf("    0x%016x  %-30s %s%n", k, c == null ? "?" : c.getSimpleName(), id);
            }
            return 0;
        }
    }

    private static void csrWarning(PrintStream o) {
        o.println("  *** The next call is csr(txn), which runs loadEdgeState(). #124 MEASURED that on");
        o.println("      the national store: 51,456,282 edges + 75,327,284 unresolved rows = 3.77 GB");
        o.println("      and 50 s. It is no longer billed to the OPEN -- #144 made it lazy on a");
        o.println("      read-only store -- which is why the address above was already resolved and");
        o.println("      a typo costs the open, not this. Set JAVA_XMX=8g or larger. No other nx db");
        o.println("      command pays this. ***");
    }

    // ================================================================== check

    /// The pin: the SPI against itself. `Store.scanEdges`/`scanUnresolved` hold one block at a time
    /// and recover block order by galloping binary search; `Store.iterEdges`/`iterUnresolved`
    /// concatenate every block through `loadEdgeState`'s `TreeMap`. `Store.scanEdges` specifies the
    /// two as order-identical, and this says whether they are.
    ///
    /// The hazard is block ordering: a block number is u32 LE in a byte-ordered map, so past block
    /// 255 cursor order stops being numeric order. `V2Store.loadEdgeState` buckets by parsed number
    /// for that reason, and edge order is part of `fetchAllReferencesByClass`'s recorded
    /// determinism.
    ///
    /// Both readers are reduced to an order-sensitive rolling digest plus a count and the first
    /// rows of each. The streaming arm runs first, before anything can materialise edge state for
    /// it to be answered out of.
    ///
    /// Two refusals. It refuses to call itself a pass on a store with less than 256 blocks of both
    /// kinds, where the two orders coincide and the check is vacuous. And it carries a negative
    /// control: the block numbers this store holds, permuted into LMDB cursor order, must be a
    /// different sequence from their numeric order. That is read off the 5-byte keys
    /// ([RawStore#edgeBlockKeys]) and never off a pair.
    private static int check(String[] argv) {
        Args a = Args.parse(argv, Set.of("--no-negative"), Set.of("--db", "--head"));
        Path db = one(a.paths("--db"));
        int head = a.getInt("--head", 3);
        boolean negative = !a.flag("--no-negative");
        PrintStream o = System.out;
        o.println(VERSION + "   " + java.time.Instant.now());
        o.println("check   tier=SCAN then CSR   " + db.toAbsolutePath());
        o.println("  Store.scanEdges/scanUnresolved (streaming, one block resident) against");
        o.println("  Store.iterEdges/iterUnresolved (loadEdgeState's materialised slot arrays),");
        o.println("  IN ORDER. Order is the thing being checked: past block 255 cursor order is not");
        o.println("  numeric order (a u32 LE block number in a byte-ordered map), and");
        o.println("  the store SPI makes edge order a recorded property.");
        o.println(RULE);

        // Phase 1, keys only: does this store exercise the hazard at all? RawStore is closed
        // before the SPI store opens -- LMDB does not support two envs over one file per process.
        int[][] blockNos;
        try (RawStore s = new RawStore(db)) {
            blockNos = s.edgeBlockKeys();
        }
        int[] blocks = {blockNos[0].length, blockNos[1].length};
        boolean exercised = blocks[0] > 255 || blocks[1] > 255;
        o.printf("  _edge_blocks: %,d kind-0 blocks, %,d kind-1 blocks%n", blocks[0], blocks[1]);
        if (negative) {
            boolean differs = cursorOrderDiffers(blockNos[0]) || cursorOrderDiffers(blockNos[1]);
            o.println("  NEGATIVE CONTROL: this store's block numbers in LMDB CURSOR order -> "
                    + (differs ? "a DIFFERENT sequence, as they must be" : "the SAME sequence"));
            if (!differs) {
                o.println("  ...so nothing downstream can distinguish the two orders and the digest");
                o.println("  comparison below would agree whichever reader were wrong. Use a store");
                o.println("  with more than 255 blocks of one kind.");
                return 1;
            }
        }

        long[] scanEdge = {0, 0};   // count, digest
        long[] scanUnres = {0, 0};
        long[] iterEdge = {0, 0};
        long[] iterUnres = {0, 0};
        List<String> scanHead0 = new ArrayList<>();
        List<String> iterHead0 = new ArrayList<>();
        List<String> scanHead1 = new ArrayList<>();
        long streamMs;
        long loadMs;
        csrWarning(o);
        try (Store st = Stores.open(db, true); toolkit.store.Txn tx = st.roTxn()) {
            Meta m = meta(st, tx);
            long t0 = System.nanoTime();
            for (long[] e : st.scanEdges(tx)) {
                scanEdge[0]++;
                scanEdge[1] = mix(scanEdge[1], e[0], e[1]);
                if (scanHead0.size() < head) scanHead0.add(String.format("0x%016x -> 0x%016x", e[0], e[1]));
            }
            for (toolkit.store.UnresolvedRow r : st.scanUnresolved(tx)) {
                scanUnres[0]++;
                scanUnres[1] = mix(scanUnres[1], r.referrerFullKey(), hash(r.encodedTarget()));
                if (scanHead1.size() < head) {
                    scanHead1.add(String.format("0x%016x -> %s", r.referrerFullKey(),
                            decodeTarget(m, r.encodedTarget())));
                }
            }
            streamMs = (System.nanoTime() - t0) / 1_000_000;
            o.printf("  scan  (streaming):    %,d edges, %,d unresolved, %,d ms%n",
                    scanEdge[0], scanUnres[0], streamMs);

            long t1 = System.nanoTime();
            for (long[] e : st.iterEdges(tx)) {
                iterEdge[0]++;
                iterEdge[1] = mix(iterEdge[1], e[0], e[1]);
                if (iterHead0.size() < head) iterHead0.add(String.format("0x%016x -> 0x%016x", e[0], e[1]));
            }
            for (toolkit.store.UnresolvedRow r : st.iterUnresolved(tx)) {
                iterUnres[0]++;
                iterUnres[1] = mix(iterUnres[1], r.referrerFullKey(), hash(r.encodedTarget()));
            }
            loadMs = (System.nanoTime() - t1) / 1_000_000;
            o.printf("  iter  (materialised): %,d edges, %,d unresolved, %,d ms including"
                    + " loadEdgeState%n", iterEdge[0], iterUnres[0], loadMs);
        }
        o.println(RULE);
        boolean edgesOk = scanEdge[0] == iterEdge[0] && scanEdge[1] == iterEdge[1];
        boolean unresOk = scanUnres[0] == iterUnres[0] && scanUnres[1] == iterUnres[1];
        o.printf("  kind 0 resolved   count %,d/%,d   order-digest %016x/%016x   %s%n",
                scanEdge[0], iterEdge[0], scanEdge[1], iterEdge[1], edgesOk ? "IDENTICAL" : "DIFFER");
        o.printf("  kind 1 unresolved count %,d/%,d   order-digest %016x/%016x   %s%n",
                scanUnres[0], iterUnres[0], scanUnres[1], iterUnres[1], unresOk ? "IDENTICAL" : "DIFFER");
        for (int i = 0; i < Math.max(scanHead0.size(), iterHead0.size()); i++) {
            o.printf("    edge[%d]  scan %-42s iter %s%n", i,
                    i < scanHead0.size() ? scanHead0.get(i) : "-",
                    i < iterHead0.size() ? iterHead0.get(i) : "-");
        }
        for (int i = 0; i < scanHead1.size(); i++) o.println("    unres[" + i + "] scan " + scanHead1.get(i));
        o.println();
        if (!exercised) {
            o.println("  NOT A PASS: this store has 255 or fewer blocks of BOTH kinds, so");
            o.println("  lexicographic and numeric block order coincide and the ordering hazard is");
            o.println("  UNEXERCISED. Re-run against a store with more blocks -- ch.lmdb has 630,");
            o.println("  it.lmdb 297, at.lmdb 272 -- or this check has proved nothing about the");
            o.println("  thing it exists to check.");
            return edgesOk && unresOk ? 0 : 1;
        }
        o.printf("  EXERCISED: %,d blocks, past 255 of one kind, so the two orders are only equal%n",
                Math.max(blocks[0], blocks[1]));
        o.println("  if the streaming reader really does bucket by parsed block number.");
        return edgesOk && unresOk ? 0 : 1;
    }

    /// True when `nos` (ascending) is not the order a byte-ordered map would return the same block
    /// numbers in — i.e. when this store can tell a numeric-order reader from a cursor-order one.
    /// The key is `[kind][block# u32 LE]`, so the comparison is low byte first.
    private static boolean cursorOrderDiffers(int[] nos) {
        Integer[] c = new Integer[nos.length];
        for (int i = 0; i < nos.length; i++) c[i] = nos[i];
        Arrays.sort(c, (x, y) -> {
            for (int i = 0; i < 4; i++) {
                int r = Integer.compare((x >>> (8 * i)) & 0xFF, (y >>> (8 * i)) & 0xFF);
                if (r != 0) return r;
            }
            return 0;
        });
        for (int i = 0; i < nos.length; i++) if (c[i] != nos[i]) return true;
        return false;
    }

    private static long mix(long acc, long a, long b) {
        acc = acc * 0x100000001b3L ^ (a + 0x9E3779B97F4A7C15L);
        acc = Long.rotateLeft(acc, 27);
        acc = acc * 0x100000001b3L ^ (b + 0xC2B2AE3D27D4EB4FL);
        return Long.rotateLeft(acc, 31);
    }

    private static long hash(byte[] b) {
        if (b == null) return 0;
        long h = 0xcbf29ce484222325L;
        for (byte x : b) h = (h ^ (x & 0xFF)) * 0x100000001b3L;
        return h;
    }

    // ================================================================== shared

    /// An address: an id (+ optional version + class), or a literal full_key.
    record Addr(String id, String version, String className, long fullKey) {
        boolean isFullKey() { return fullKey >= 0; }
    }

    static Addr parseAddr(String s, String classOpt) {
        if (s == null) throw new BadUsage("an address is required");
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return new Addr(null, null, null, Long.parseUnsignedLong(s.substring(2), 16));
        }
        String id = s, ver = null;
        int at = s.lastIndexOf('@');
        if (at > 0) { id = s.substring(0, at); ver = s.substring(at + 1); }
        String cn = classOpt;
        if (cn == null) {
            // The spec's ':Class' suffix, accepted only when the trailing segment really resolves
            // to a model class -- NeTEx ids are colon-heavy. Same rule as LensCmd.
            int c = id.lastIndexOf(':');
            if (c > 0) {
                String tail = id.substring(c + 1);
                if (ModelRegistry.isRegistered(tail) && !tail.equals(id)) {
                    cn = tail;
                    id = id.substring(0, c);
                }
            }
        }
        return new Addr(id, ver, cn, -1);
    }

    private static List<RawStore.Hit> hits(RawStore s, Addr ad, PrintStream o) {
        return hits(meta(s), ad, o);
    }

    /// Every `_id_idx` match, printed. Duplicates are never silently collapsed.
    private static List<RawStore.Hit> hits(Meta s, Addr ad, PrintStream o) {
        if (ad.isFullKey()) {
            int idx = NulKeyCodec.fullKeyClassIdx(ad.fullKey());
            int local = NulKeyCodec.fullKeyLocal(ad.fullKey());
            String cn = s.className(idx);
            return List.of(new RawStore.Hit("(by full_key)", "", idx, cn == null ? "?idx" + idx : cn,
                    ad.fullKey(), local));
        }
        List<RawStore.Hit> hs = s.lookup(ad.id(), ad.version(), ad.className());
        if (hs.isEmpty()) {
            o.println("  no _id_idx row for id=" + ad.id()
                    + (ad.version() == null ? "" : "@" + ad.version())
                    + (ad.className() == null ? "" : " class=" + ad.className()));
            o.println("  An EMBEDDED object has no row of its own; try `nx db emb " + ad.id() + "`.");
            return hs;
        }
        o.printf("  _id_idx: %d match%s%s%n", hs.size(), hs.size() == 1 ? "" : "es",
                hs.size() > 1 ? "   *** DUPLICATES -- --pick N selects, `nx db versions` explains ***" : "");
        for (int i = 0; i < hs.size(); i++) {
            RawStore.Hit h = hs.get(i);
            o.printf("    [%d] version=%-10s class=%-28s full_key=0x%016x local=%d%n", i,
                    h.version().isEmpty() ? "(none)" : h.version(), h.className(), h.fullKey(),
                    h.localKey());
        }
        return hs;
    }

    private static long resolveOne(RawStore s, Addr ad, int pick, PrintStream o) {
        return resolveOne(meta(s), ad, pick, o);
    }

    private static long resolveOne(Meta s, Addr ad, int pick, PrintStream o) {
        List<RawStore.Hit> hs = hits(s, ad, o);
        if (hs.isEmpty()) return -1;
        return hs.get(Math.min(pick, hs.size() - 1)).fullKey();
    }

    /// `Class|id|version` for a full_key, by point lookup: read that one class-map row, inflate it,
    /// take the root element's `@id`/`@version`.
    private static String label(RawStore s, FragmentCodec codec, long fk, Map<Long, String> cache) {
        return label(meta(s), codec, fk, cache);
    }

    private static String label(Meta s, FragmentCodec codec, long fk, Map<Long, String> cache) {
        return cache.computeIfAbsent(fk, k -> {
            int idx = NulKeyCodec.fullKeyClassIdx(k);
            int local = NulKeyCodec.fullKeyLocal(k);
            String cn = nameOf(s, idx);
            byte[] blob = s.rawBlob(idx, local);
            if (blob == null) return cn + "|<NO CLASS-MAP ROW -- DANGLING EDGE>|-  local=" + local;
            try {
                XmlDoc d = XmlDoc.parse(codec.decompress(blob));
                String id = "?", ver = "-";
                for (String[] at : d.root.attrs) {
                    if (at[0].equals("id")) id = at[1];
                    if (at[0].equals("version")) ver = at[1];
                }
                return cn + "|" + id + "|" + ver;
            } catch (Exception e) {
                return cn + "|<unparseable fragment: " + e.getClass().getSimpleName() + ">|-";
            }
        });
    }

    /// Every command says which tier it is on, in its own output.
    private static void header(PrintStream o, String cmd, String tier, Path db, RawStore s) {
        o.println(VERSION + "   " + java.time.Instant.now());
        o.printf("%s   tier=%s   %s   format=%s%n", cmd, tier, db.toAbsolutePath(), s.formatMarker());
        o.println("  tier " + tier + ": " + (tier.equals("POINT")
                ? "RawStore point lookups. V2Store is NOT opened."
                : "RawStore for the address, then ONE streaming pass over one map, O(1) heap."
                        + " loadEdgeState is NOT run."));
    }

    /// The same header for a command that opens the SPI store and nothing else.
    private static void header(PrintStream o, String cmd, String tier, Path db, Store st) {
        o.println(VERSION + "   " + java.time.Instant.now());
        o.printf("%s   tier=%s   %s   format=%s%n", cmd, tier, db.toAbsolutePath(), st.formatMarker());
        o.println("  tier " + tier + ": ONE reader -- Store.lookupById for the address, then ONE"
                + " streaming pass over one map, O(1) heap. loadEdgeState is NOT run.");
    }

    private static void hex(PrintStream o, byte[] b, int lines) {
        for (int i = 0; i < b.length && i / 16 < lines; i += 16) {
            StringBuilder h = new StringBuilder();
            StringBuilder t = new StringBuilder();
            for (int j = i; j < Math.min(i + 16, b.length); j++) {
                h.append(String.format("%02x ", b[j]));
                t.append(b[j] >= 0x20 && b[j] < 0x7F ? (char) b[j] : '.');
            }
            o.printf("  %08x  %-48s %s%n", i, h, t);
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

    private static String sha12(byte[] b) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String q(String s) {
        return s == null ? "" : "'" + s + "'";
    }

    private static Path one(List<Path> ps) {
        if (ps.size() != 1) throw new BadUsage("exactly one --db <store> is required");
        return ps.get(0);
    }

    /// Usage errors are a message and exit 2, never a stack trace.
    static final class BadUsage extends RuntimeException {
        BadUsage(String m) { super(m); }
    }

    /// The option parser. Strict: an unknown option is an error rather than a silently ignored
    /// word, so a mistyped `--db` cannot leave a command reading a different store.
    static final class Args {
        private final Map<String, List<String>> opt = new LinkedHashMap<>();
        private final Set<String> flags = new LinkedHashSet<>();
        private final List<String> pos = new ArrayList<>();

        static Args parse(String[] argv, Set<String> flagNames, Set<String> valueNames) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                if (flagNames.contains(s)) {
                    a.flags.add(s);
                } else if (valueNames.contains(s)) {
                    if (i + 1 >= argv.length) throw new BadUsage(s + " needs a value");
                    a.opt.computeIfAbsent(s, k -> new ArrayList<>()).add(argv[++i]);
                } else if (s.startsWith("--")) {
                    throw new BadUsage("unknown option '" + s + "'");
                } else {
                    a.pos.add(s);
                }
            }
            return a;
        }

        boolean flag(String k) { return flags.contains(k); }

        String get(String k) {
            List<String> v = opt.get(k);
            return v == null || v.isEmpty() ? null : v.get(v.size() - 1);
        }

        int getInt(String k, int def) {
            String v = get(k);
            return v == null ? def : Integer.parseInt(v);
        }

        Path path(String k) {
            String v = get(k);
            return v == null ? null : Path.of(v);
        }

        List<Path> paths(String k) {
            List<Path> out = new ArrayList<>();
            for (String v : opt.getOrDefault(k, List.of())) out.add(Path.of(v));
            return out;
        }

        String onePositional() {
            if (pos.size() != 1) {
                throw new BadUsage(pos.isEmpty() ? "an address is required"
                        : "expected one address, got " + pos.size() + ": " + pos);
            }
            return pos.get(0);
        }
    }

    private DbCmd() {}
}
