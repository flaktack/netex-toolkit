// Format-agnostic graph-equivalence dump of a toolkit Store — one side of a cross-implementation
// A/B protocol, so the line format is fixed by that protocol and not by this tool:
//
//     COUNT <Class> <n>
//     OBJ <Class>|<id>|<version-or-->
//     EDGE <Class>|<id>|<version> -> <Class>|<id>|<version>
//     UNRES <Class>|<id>|<version> -> <TargetClass>|<id>|<version>
//
// SORT ORDER: sorts the UTF-8 line BYTES (Arrays.compareUnsigned), which for UTF-8 is code-point
// order — what the reference sort is defined in.
//
// SCALE: a dump that does not fit in heap spills as sorted chunks and k-way merges. The sort is
// in-process because the build container ships uutils, not GNU sort. The OBJ label map stays
// resident.
//
// Options / knobs:
//   --no-sort                 emit lines UNSORTED (escape hatch; sort later with LC_ALL=C sort)
//   SHARD MODE (two phases; per-shard output is UNSORTED, --no-sort implied). EDGE/UNRES endpoint
//   labels need the DECODED (class, id, version) of BOTH endpoints — the _id_idx key alone is
//   lossy, since version null and version "any" encode identically — so labels travel through
//   sidecar files.
//   --shard I/N [--labels-out F]   phase 1: decode ONLY objects with full_key % N == I. Local keys
//                             come from one global sequence, so the slices are uniform. Shard 0
//                             also emits every COUNT line, key-only and without decoding. Writes a
//                             "#graphdump-labels<TAB>I<TAB>N" header plus "<fk>\t<label>" sidecar
//                             lines for its objects.
//   --edges --labels F ... [--shard I/N]   phase 2, after ALL phase-1 shards: load the sidecars —
//                             their union is exactly the unsharded label map — and emit every
//                             EDGE/UNRES line with no decode. Optional --shard splits this phase
//                             by SOURCE full_key. The empty-dump marker belongs to this phase.
//                             Union of the phase-1 files and the phase-2 files is the full
//                             unsorted stream, no dupes and no gaps; LC_ALL=C sort over all of
//                             them is the unsharded sorted dump, byte-identical.
//   -Dgraphdump.chunkMB=512   in-memory chunk budget before a sorted spill (payload estimate)
//   -Dgraphdump.tmpdir=DIR    where spill chunks go (default: beside the output). Peak extra disk
//                             is about the final dump size, removed afterwards and on failure.
//
// SIDECAR CONTRACT. Every clause loadLabels checks is FATAL: an incomplete or corrupt set
// otherwise produces a wrong-but-well-formed dump at exit 0. Every '?' label substitution — an
// endpoint with no sidecar entry — is counted and printed in the summary; a real dump has zero.
// SAME-LANGUAGE ONLY: the full key is written with signed Long.toString here and as an unbounded
// integer on the other arm; the two agree only because full keys are < 2^48. Do not mix phases
// across arms.

import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.EntityStructure;
import toolkit.keycodec.NulKeyCodec;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;
import toolkit.util.Conf;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;

public class Graph {

    static final byte[] OBJ_ = b("OBJ ");
    static final byte[] EDGE_ = b("EDGE ");
    static final byte[] UNRES_ = b("UNRES ");
    static final byte[] ARROW = b(" -> ");
    static final byte[] QMARK = b("?");
    /// First line of every phase-1 sidecar: MAGIC TAB shardI TAB shardN.
    static final String LABELS_MAGIC = "#graphdump-labels";

    /// Count of '?' label substitutions (an endpoint with no entry in the label map).
    static long qmarks;

    /// A loud, user-facing failure: caught in main, reported, exit 2 (after spill cleanup).
    static final class Fail extends RuntimeException {
        Fail(String m) { super(m); }
    }

    static Fail fail(String msg) {
        return new Fail(msg);
    }

    /// Label for a full key, counting the '?' fallback instead of hiding it.
    static byte[] label(Map<Long, byte[]> m, long fk) {
        byte[] l = m.get(fk);
        if (l != null) return l;
        qmarks++;
        return QMARK;
    }

    public static void main(String[] args) throws Exception {
        boolean noSort = false;
        boolean edgesMode = false;
        int shardI = -1, shardN = -1;
        List<Path> labelsIn = new ArrayList<>();
        Path labelsOut = null;
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--no-sort")) {
                noSort = true;
            } else if (a.equals("--edges")) {
                edgesMode = true;
                noSort = true;
            } else if (a.equals("--labels")) {
                labelsIn.add(Path.of(args[++i]));
            } else if (a.equals("--labels-out")) {
                labelsOut = Path.of(args[++i]);
            } else if (a.equals("--shard") || a.startsWith("--shard=")) {
                String spec = a.startsWith("--shard=") ? a.substring(8)
                        : (i + 1 < args.length ? args[++i] : "");
                String[] p = spec.split("/");
                try {
                    shardI = Integer.parseInt(p[0]);
                    shardN = Integer.parseInt(p[1]);
                } catch (RuntimeException e) {
                    shardI = shardN = -1;
                }
                if (shardN < 1 || shardI < 0 || shardI >= shardN) {
                    System.err.println("bad --shard spec '" + spec + "' (want I/N with 0 <= I < N)");
                    System.exit(2);
                }
                noSort = true; // per-shard output is inherently unsorted
            } else {
                pos.add(a);
            }
        }
        if (pos.size() != 2) {
            System.err.println("usage: Graph [--no-sort] [--shard I/N [--labels-out F]]"
                    + " [--edges --labels F ...] <db> <out.txt>");
            System.exit(2);
        }
        if (edgesMode && labelsIn.isEmpty()) {
            System.err.println("--edges requires ALL N --labels sidecars (from the phase-1 shards)");
            System.exit(2);
        }
        if (!labelsIn.isEmpty() && !edgesMode) {
            System.err.println("--labels is only meaningful with --edges");
            System.exit(2);
        }
        Path dbPath = Path.of(pos.get(0));
        Path outPath = Path.of(pos.get(1)).toAbsolutePath();
        long chunkBytes = Conf.longOf(Conf.GRAPHDUMP_CHUNK_MB) << 20;
        Path tmpDir = Objects.requireNonNullElseGet(
                Conf.pathOf(Conf.GRAPHDUMP_TMPDIR), outPath::getParent);

        boolean sharded = shardN > 0;
        LineWriter out = noSort ? new PassthroughWriter(outPath, !sharded && !edgesMode)
                                : new SortingWriter(outPath, tmpDir, chunkBytes);
        boolean failed = false;
        try {
            try (Store db = Stores.open(dbPath, true); Txn tx = db.roTxn()) {
                if (edgesMode) {
                    runEdges(db, tx, out, shardI, shardN, labelsIn);
                } else if (sharded) {
                    Path labelsPath = labelsOut != null ? labelsOut
                            : outPath.resolveSibling(outPath.getFileName() + ".labels");
                    runShardObjects(db, tx, out, shardI, shardN, labelsPath);
                } else {
                    runFull(db, tx, out);
                }
            }
            long total = out.finish();
            System.out.println("[Graph] " + dbPath + ": " + total + " lines -> " + outPath
                    + (noSort ? " (UNSORTED)" : "")
                    + (sharded ? " (shard " + shardI + "/" + shardN + (edgesMode ? ", edges" : "") + ")"
                               : (edgesMode ? " (edges)" : ""))
                    + " ?-labels=" + qmarks);
        } catch (Fail f) {
            System.err.println("[Graph] FATAL: " + f.getMessage());
            failed = true;
        } finally {
            out.cleanup();
        }
        if (failed) System.exit(2);
    }

    /// The single-process dump.
    private static void runFull(Store db, Txn tx, LineWriter out) throws IOException {
        Map<Long, byte[]> fkLabel = new HashMap<>();
        for (Class<?> clazz : db.dbNames(tx)) {
            String cn = simpleName(clazz);
            long n = 0;
            for (ObjectRow row : db.iterObjects(tx, clazz)) {
                EntityStructure e = (EntityStructure) row.object();
                String ver = (e instanceof EntityInVersionStructure eiv && eiv.getVersion() != null)
                        ? eiv.getVersion() : "-";
                byte[] label = b(cn + "|" + labelId(e) + "|" + ver);
                fkLabel.put(db.fullKeyOf(clazz, row.localKey()), label);
                out.add(cat(OBJ_, label));
                n++;
            }
            out.add(b("COUNT " + cn + " " + n));
        }
        for (long[] edge : db.iterEdges(tx)) {
            out.add(cat(EDGE_, label(fkLabel, edge[0]), ARROW, label(fkLabel, edge[1])));
        }
        for (UnresolvedRow r : db.iterUnresolved(tx)) {
            out.add(cat(UNRES_, label(fkLabel, r.referrerFullKey()),
                    ARROW, b(decodeTarget(db, r.encodedTarget()))));
        }
    }

    /// Phase 1, shard I of N: decode ONLY the objects with fullKey % N == I and emit their OBJ
    /// lines, shard 0 also emitting every COUNT line from a key-only total, plus this shard's
    /// sidecar. Both readers split a sidecar line on the FIRST TAB; ids and versions come from XML
    /// attribute values, where normalization turns a literal TAB into a space, so the separator is
    /// unambiguous.
    private static void runShardObjects(Store db, Txn tx, LineWriter out, int shardI, int shardN,
                                        Path labelsPath) throws IOException {
        try (OutputStream lab = new BufferedOutputStream(Files.newOutputStream(labelsPath), 1 << 20)) {
            lab.write(b(LABELS_MAGIC + "\t" + shardI + "\t" + shardN + "\n"));
            for (Class<?> clazz : db.dbNames(tx)) {
                String cn = simpleName(clazz);
                long n = 0;
                for (byte[] localKey : db.scanObjects(tx, clazz)) { // keys only: no decode
                    long fk = db.fullKeyOf(clazz, localKey);
                    n++;
                    if (Long.remainderUnsigned(fk, shardN) != shardI) continue;
                    EntityStructure e = (EntityStructure) db.loadObject(tx, clazz, localKey);
                    String ver = (e instanceof EntityInVersionStructure eiv && eiv.getVersion() != null)
                            ? eiv.getVersion() : "-";
                    byte[] label = b(cn + "|" + labelId(e) + "|" + ver);
                    lab.write(b(Long.toString(fk)));
                    lab.write('\t');
                    lab.write(label);
                    lab.write('\n');
                    out.add(cat(OBJ_, label));
                }
                if (shardI == 0) out.add(b("COUNT " + cn + " " + n));
            }
        }
        // No empty-dump marker here: the --edges phase owns it (see runEdges).
    }

    /// Phase 2 (--edges, after ALL phase-1 shards): rebuild the full label map from the
    /// sidecars — their union is exactly the unsharded fkLabel map, which [#loadLabels]
    /// enforces — and emit every EDGE/UNRES line without decoding anything. With --shard I/N
    /// only the rows whose SOURCE (referrer) fullKey % N == I are emitted.
    private static void runEdges(Store db, Txn tx, LineWriter out, int shardI, int shardN,
                                 List<Path> labelFiles) throws IOException {
        boolean sharded = shardN > 0;
        Map<Long, byte[]> fkLabel = loadLabels(labelFiles);

        long nMaps = db.dbNames(tx).size();
        long nEdges = 0, nUnres = 0;
        for (long[] edge : db.iterEdges(tx)) {
            nEdges++;
            if (sharded && Long.remainderUnsigned(edge[0], shardN) != shardI) continue;
            out.add(cat(EDGE_, label(fkLabel, edge[0]), ARROW, label(fkLabel, edge[1])));
        }
        for (UnresolvedRow r : db.iterUnresolved(tx)) {
            nUnres++;
            if (sharded && Long.remainderUnsigned(r.referrerFullKey(), shardN) != shardI) continue;
            out.add(cat(UNRES_, label(fkLabel, r.referrerFullKey()),
                    ARROW, b(decodeTarget(db, r.encodedTarget()))));
        }

        // The empty-dump marker appears iff the WHOLE dump is empty: no class maps, since each
        // would emit a COUNT line, no edges, no unresolved rows. Emitted here, by shard 0 when
        // sharded, so it lands in the union exactly once.
        if ((!sharded || shardI == 0) && nMaps == 0 && nEdges == 0 && nUnres == 0) {
            ((PassthroughWriter) out).forceEmptyMarker();
        }
    }

    /// Loads and VALIDATES the phase-1 sidecars. A degraded label map yields a
    /// wrong-but-well-formed dump at exit 0, so each of these is fatal:
    /// - a file that is empty, lacks the header, or whose header is malformed;
    /// - a file whose last byte is not `\n`, i.e. a truncated writer;
    /// - a body line with no TAB, i.e. truncated mid-key;
    /// - a sidecar set that is not exactly {0..N-1}: wrong count, mixed N, duplicate or missing
    ///     shard.
    private static Map<Long, byte[]> loadLabels(List<Path> labelFiles) throws IOException {
        Map<Long, byte[]> fkLabel = new HashMap<>();
        int expectN = -1;
        Map<Integer, Path> shards = new TreeMap<>();
        for (Path lf : labelFiles) {
            try (ChunkReader r = new ChunkReader(lf)) {
                if (r.line == null) throw fail("sidecar " + lf + ": empty (no " + LABELS_MAGIC + " header)");
                if (r.unterminated) throw fail("sidecar " + lf + ": truncated (last byte is not a newline)");
                String hdr = new String(r.line, StandardCharsets.UTF_8);
                String[] h = hdr.split("\t", -1);
                int si, sn;
                try {
                    if (h.length != 3 || !h[0].equals(LABELS_MAGIC)) throw new NumberFormatException();
                    si = Integer.parseInt(h[1]);
                    sn = Integer.parseInt(h[2]);
                } catch (NumberFormatException e) {
                    throw fail("sidecar " + lf + ": bad header '" + hdr + "' (want '"
                            + LABELS_MAGIC + "<TAB>I<TAB>N' as written by --shard I/N)");
                }
                if (sn < 1 || si < 0 || si >= sn)
                    throw fail("sidecar " + lf + ": bad header shard spec " + si + "/" + sn);
                if (expectN < 0) expectN = sn;
                else if (sn != expectN)
                    throw fail("sidecar " + lf + ": shard count N=" + sn + ", but an earlier sidecar"
                            + " says N=" + expectN + " — the sidecars come from different runs");
                Path dup = shards.put(si, lf);
                if (dup != null)
                    throw fail("sidecar " + lf + ": shard " + si + "/" + sn + " already supplied by " + dup);

                long ln = 1;
                while (r.readLine()) {
                    ln++;
                    if (r.unterminated)
                        throw fail("sidecar " + lf + ": truncated (last byte is not a newline; line " + ln + ")");
                    byte[] line = r.line;
                    int t = 0;
                    while (t < line.length && line[t] != '\t') t++;
                    if (t == line.length)
                        throw fail("sidecar " + lf + " line " + ln + ": no TAB separator (truncated?): '"
                                + new String(line, StandardCharsets.UTF_8) + "'");
                    long fk;
                    try {
                        fk = Long.parseLong(new String(line, 0, t, StandardCharsets.US_ASCII));
                    } catch (NumberFormatException e) {
                        throw fail("sidecar " + lf + " line " + ln + ": full key is not an integer");
                    }
                    fkLabel.put(fk, Arrays.copyOfRange(line, t + 1, line.length));
                }
            }
        }
        if (shards.size() != expectN) {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < expectN; i++) if (!shards.containsKey(i)) missing.add(i);
            throw fail("incomplete sidecar set: got " + shards.size() + " of " + expectN
                    + " shards, missing " + missing + " — phase 2 needs ALL phase-1 sidecars "
                    + "(an incomplete set silently yields a wrong-but-well-formed dump)");
        }
        return fkLabel;
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /// Dump-label class name. The two arms spell the underivable-ref-class fallback differently —
    /// `DataManagedObject` against `DataManagedObjectStructure` — with identical semantics, so the
    /// reference arm's spelling is printed wherever a class name is rendered and dumps diff clean.
    static String simpleName(Class<?> c) {
        String n = c.getSimpleName();
        return n.equals("DataManagedObjectStructure") ? "DataManagedObject" : n;
    }

    /// Dump-label id: `null` renders as the reference arm's `None`, the literal four bytes that
    /// arm persists, rather than as Java string concatenation's `null`.
    static String labelId(EntityStructure e) {
        String id = e.getId();
        return id != null ? id : "None";
    }

    static byte[] cat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] r = new byte[len];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    /// NulKeyCodec row: id NUL version NUL class2 (empty version = None).
    private static String decodeTarget(Store db, byte[] v) {
        byte[] body = Arrays.copyOf(v, v.length - 2);
        int cls = NulKeyCodec.classIdxInt(new byte[] {v[v.length - 2], v[v.length - 1]});
        String[] parts = new String(body, StandardCharsets.UTF_8).split("\\u0000", -1);
        String tid = parts[0];
        String tver = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "-";
        Class<?> c = db.classForIdx(cls);
        // Unknown class idx: the reference arm prints '?' plus the RAW 2 class bytes as hex, so
        // print the same 4 lowercase digits (an unpadded Integer.toHexString would not match).
        String cn = c != null ? simpleName(c)
                : String.format("?%02x%02x", v[v.length - 2] & 0xff, v[v.length - 1] & 0xff);
        return cn + "|" + tid + "|" + tver;
    }

    // ---------------------------------------------------------------- line sinks

    abstract static class LineWriter {
        long n;

        abstract void add(byte[] line) throws IOException;

        /// Writes/completes the output; returns the line count.
        abstract long finish() throws IOException;

        /// Best-effort release of temp state, called from main's `finally` on every path. Must be
        /// idempotent and must not throw — after a clean [#finish] there is nothing left to do.
        void cleanup() {}

        static void writeAll(OutputStream o, List<byte[]> lines) throws IOException {
            for (byte[] l : lines) {
                o.write(l);
                o.write('\n');
            }
        }
    }

    /// --no-sort: stream lines straight to the output file. `emptyMarker` controls the "empty dump
    /// == one newline" quirk: true for whole-dump mode (marker iff this process emitted 0 lines);
    /// false for shard mode, where only shard 0 may write it and only when the WHOLE dump is empty
    /// ([#forceEmptyMarker]).
    static final class PassthroughWriter extends LineWriter {
        private final OutputStream o;
        private boolean emptyMarker;

        PassthroughWriter(Path out, boolean emptyMarker) throws IOException {
            o = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20);
            this.emptyMarker = emptyMarker;
        }

        void forceEmptyMarker() {
            emptyMarker = true;
        }

        @Override void add(byte[] line) throws IOException {
            o.write(line);
            o.write('\n');
            n++;
        }

        @Override long finish() throws IOException {
            if (n == 0 && emptyMarker) o.write('\n'); // an empty dump is a single newline
            o.close();
            return n;
        }
    }

    /// External merge sort over UTF-8 line bytes: buffer lines up to a budget, spill each buffer
    /// as a SORTED chunk file, then k-way merge the chunks into the output. If everything fits in
    /// one buffer, sorts in memory and writes directly (no temp files).
    static final class SortingWriter extends LineWriter {
        private final Path out;
        private final Path tmpDir;
        private final long budget;
        private ArrayList<byte[]> buf = new ArrayList<>();
        private long bufBytes;
        private final List<Path> chunks = new ArrayList<>();
        private Path spillDir;

        SortingWriter(Path out, Path tmpDir, long budget) {
            this.out = out;
            this.tmpDir = tmpDir;
            this.budget = budget;
        }

        @Override void add(byte[] line) throws IOException {
            buf.add(line);
            bufBytes += line.length + 32; // payload + rough array/list-slot overhead
            n++;
            if (bufBytes >= budget) spill();
        }

        private void spill() throws IOException {
            if (spillDir == null) {
                spillDir = Files.createDirectories(
                        tmpDir.resolve(out.getFileName() + ".sorttmp." + ProcessHandle.current().pid()));
            }
            buf.sort((a, c) -> Arrays.compareUnsigned(a, c));
            Path chunk = spillDir.resolve("chunk-" + chunks.size());
            try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(chunk), 1 << 20)) {
                writeAll(o, buf);
            }
            chunks.add(chunk);
            buf = new ArrayList<>();
            bufBytes = 0;
        }

        @Override long finish() throws IOException {
            if (chunks.isEmpty()) { // fits in memory
                buf.sort((a, c) -> Arrays.compareUnsigned(a, c));
                try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
                    if (buf.isEmpty()) o.write('\n'); // the same empty-dump marker
                    writeAll(o, buf);
                }
                return n;
            }
            if (!buf.isEmpty()) spill();
            buf = null;
            PriorityQueue<ChunkReader> pq =
                    new PriorityQueue<>((a, c) -> Arrays.compareUnsigned(a.line, c.line));
            try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
                for (Path c : chunks) {
                    ChunkReader r = new ChunkReader(c);
                    if (r.line != null) pq.add(r);
                    else r.close();
                }
                while (!pq.isEmpty()) {
                    ChunkReader r = pq.poll();
                    o.write(r.line);
                    o.write('\n');
                    if (r.readLine()) pq.add(r);
                    else r.close();
                }
            }
            for (Path c : chunks) Files.deleteIfExists(c);
            Files.deleteIfExists(spillDir);
            spillDir = null;
            chunks.clear();
            return n;
        }

        /// A failure after the first spill would otherwise leak ~1x the dump size on disk.
        @Override void cleanup() {
            if (spillDir == null) return;
            try {
                for (Path c : chunks) Files.deleteIfExists(c);
                Files.deleteIfExists(spillDir);
                System.err.println("[Graph] removed spill dir " + spillDir);
            } catch (IOException e) {
                System.err.println("[Graph] WARNING: could not remove spill dir " + spillDir
                        + " (" + e + ") — delete it by hand");
            }
            spillDir = null;
            chunks.clear();
        }
    }

    /// Buffered byte-line reader over a spilled chunk or a phase-1 sidecar; `line` is the
    /// current line. Spill chunks always end in '\n'; a sidecar might not (truncated writer), so
    /// `unterminated` flags a final line that hit EOF without one — see loadLabels.
    static final class ChunkReader implements Closeable {
        private final InputStream in;
        private final byte[] buf = new byte[1 << 20];
        private int pos, lim;
        byte[] line;
        boolean unterminated;

        ChunkReader(Path p) throws IOException {
            in = Files.newInputStream(p);
            readLine();
        }

        /// Advances to the next line; false at EOF.
        boolean readLine() throws IOException {
            byte[] carry = null;
            while (true) {
                if (pos >= lim) {
                    lim = in.read(buf);
                    pos = 0;
                    if (lim <= 0) {
                        line = carry; // chunks always end in '\n', so carry is null at clean EOF
                        unterminated = carry != null;
                        return line != null;
                    }
                }
                int i = pos;
                while (i < lim && buf[i] != '\n') i++;
                if (i < lim) {
                    byte[] seg = Arrays.copyOfRange(buf, pos, i);
                    pos = i + 1;
                    line = carry == null ? seg : cat(carry, seg);
                    return true;
                }
                byte[] seg = Arrays.copyOfRange(buf, pos, lim);
                carry = carry == null ? seg : cat(carry, seg);
                pos = lim;
            }
        }

        @Override public void close() throws IOException {
            in.close();
        }
    }
}
