package inspect.nx;

import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.KeyRange;
import org.lmdbjava.Txn;

import toolkit.keycodec.NulKeyCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/// Read-only point access to a v2 store's class maps and id index, bypassing `V2Store`.
///
/// The SPI store's constructor materialises the whole resolved and unresolved edge set plus the
/// intern table on the heap before the first read.
///
/// What it therefore knows about the on-disk layout:
///
/// - `_meta` row `'c' idxLo idxHi` -&gt; the class's registered simple name; row
///    `"format"` -&gt; `"java-v2:1"`.
/// - `_id_idx` key `id NUL version NUL classIdx(2, LE)` -&gt; 8-byte LE full_key,
///    where full_key = `classIdx << 32 | localKey` ([NulKeyCodec]).
/// - a class map's dbi name is the 2-byte LE class idx, which LMDB truncates at the first NUL,
///    so idx &lt; 256 appears in `getDbiNames()` as one byte — the exact reason
///    `AbstractLmdbStore.assignIdx` skips multiples of 256.
/// - a class-map row is `localKey(4, LE)` -&gt; `[4-byte LE inflated length][LZ4 block]`
///    (A5's fragment codec value format).
///
/// One long-lived read transaction is held open for the life of the handle. That holds only while
/// the env is opened read-only and read from one thread, with no writer to starve. Nothing here
/// enforces it.
public final class RawStore implements AutoCloseable {

    /// One `_id_idx` row that matched an address.
    public record Hit(String id, String version, int classIdx, String className,
                      long fullKey, int localKey) {
        public String address() {
            return id + "@" + (version.isEmpty() ? "(none)" : version) + " :" + className;
        }
    }

    private final Path path;
    private final Env<byte[]> env;
    private final Dbi<byte[]> meta;
    private final Dbi<byte[]> idIdx;
    private final Txn<byte[]> txn;
    private final Map<Integer, String> idxToName = new TreeMap<>();
    private final Map<String, Integer> nameToIdx = new TreeMap<>();
    private final Map<Integer, Dbi<byte[]>> classDbis = new HashMap<>();
    private final Map<String, Dbi<byte[]>> sideDbis = new HashMap<>();
    private final Set<Integer> existingMaps = new LinkedHashSet<>();
    private final Set<String> namedDbis = new LinkedHashSet<>();
    private final List<byte[]> allDbiNames = new ArrayList<>();
    private final String formatMarker;

    public RawStore(Path path) {
        this.path = path;
        if (!Files.exists(path.resolve("data.mdb"))) {
            throw new IllegalArgumentException(path + ": no data.mdb (not a store directory)");
        }
        this.env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(1L << 42)
                .setMaxDbs(2048)
                .setMaxReaders(126)
                .open(path.toFile(), EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS);
        Set<String> names = new LinkedHashSet<>();
        for (byte[] n : env.getDbiNames()) {
            if (n.length > 2) names.add(new String(n, StandardCharsets.UTF_8));
        }
        if (!names.contains("_meta") || !names.contains("_id_idx")) {
            env.close();
            throw new IllegalArgumentException(path + ": not a toolkit store (no _meta/_id_idx)");
        }
        this.meta = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8));
        this.idIdx = env.openDbi("_id_idx".getBytes(StandardCharsets.UTF_8));
        this.txn = env.txnRead();
        byte[] fmt = meta.get(txn, "format".getBytes(StandardCharsets.UTF_8));
        this.formatMarker = fmt == null ? null : new String(fmt, StandardCharsets.UTF_8);
        if (formatMarker == null && names.contains("_class_idx")) {
            // The retired v1 map layout: the class table is not in _meta there and the values are
            // pickled, not fragment blobs, so there is nothing to decode.
            String have = String.join(", ", names);
            txn.close();
            env.close();
            throw new IllegalArgumentException(path + ": this is the retired v1 map layout, not"
                    + " java-v2 (dbis: " + have + ").\n        nx lens reads the v2 format --"
                    + " _meta 'c' rows, _edge_blocks, LZ4 fragment blobs. A v1 database holds"
                    + " pickled values,\n        so there is no fragment to show and no bean to"
                    + " bind. v1 support was retired (see Store's javadoc).");
        }
        if (formatMarker != null && !formatMarker.startsWith("java-v2")) {
            String f = formatMarker;
            txn.close();
            env.close();
            throw new IllegalArgumentException(path + ": unsupported format marker '" + f + "'");
        }
        try (CursorIterable<byte[]> ci = meta.iterate(txn)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                byte[] k = kv.key();
                if (k.length == 3 && k[0] == 'c') {
                    int idx = (k[1] & 0xFF) | ((k[2] & 0xFF) << 8);
                    String name = new String(kv.val(), StandardCharsets.UTF_8);
                    idxToName.put(idx, name);
                    nameToIdx.put(name, idx);
                }
            }
        }
        for (byte[] n : env.getDbiNames()) {
            allDbiNames.add(n);
            if (n.length >= 1 && n.length <= 2) {
                existingMaps.add(n.length == 1 ? (n[0] & 0xFF) : NulKeyCodec.classIdxInt(n));
            }
        }
        namedDbis.addAll(names);
    }

    public Path path() { return path; }

    public String formatMarker() { return formatMarker; }

    /// Registered class simple names, in class-idx order.
    public Map<Integer, String> classes() { return idxToName; }

    /// Class idx of a registered simple name, or -1.
    public int classIdx(String simpleName) {
        Integer i = nameToIdx.get(simpleName);
        return i == null ? -1 : i;
    }

    public String className(int idx) { return idxToName.get(idx); }

    /// Does this database have a class map (not merely a registration) for this idx?
    public boolean hasMap(int idx) { return existingMaps.contains(idx); }

    /// Class idxs that actually carry a map here, in idx order.
    public Set<Integer> populatedClassIdxs() {
        Set<Integer> out = new LinkedHashSet<>();
        for (Integer i : idxToName.keySet()) if (existingMaps.contains(i)) out.add(i);
        return out;
    }

    /// Every `_id_idx` row at the `id NUL` prefix, i.e. every version and every class stored under
    /// this id. Duplicate ids are legal, so all of them are returned.
    public List<Hit> lookup(String id, String version, String className) {
        byte[] prefix = NulKeyCodec.encodePrefix(id, version, null, false);
        List<Hit> out = new ArrayList<>();
        try (CursorIterable<byte[]> ci = idIdx.iterate(txn, KeyRange.atLeast(prefix))) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                byte[] k = kv.key();
                if (!NulKeyCodec.startsWith(k, prefix)) break;
                int stripLen = NulKeyCodec.stripClazzLen(k);
                byte[] idVer = new byte[stripLen];
                System.arraycopy(k, 0, idVer, 0, stripLen);
                String rowId = NulKeyCodec.idPart(idVer);
                String rowVer = NulKeyCodec.versionPart(idVer);
                int cIdx = NulKeyCodec.classTrailer(k);
                String cName = idxToName.get(cIdx);
                if (className != null && !className.equals(cName)) continue;
                long full = NulKeyCodec.u64le(kv.val());
                out.add(new Hit(rowId, rowVer, cIdx, cName == null ? "?idx" + cIdx : cName,
                        full, NulKeyCodec.fullKeyLocal(full)));
            }
        }
        return out;
    }

    /// The raw stored value (the `[len][LZ4]` blob), or null when the row is absent.
    public byte[] rawBlob(int classIdx, int localKey) {
        Dbi<byte[]> d = classDbi(classIdx);
        if (d == null) return null;
        return d.get(txn, NulKeyCodec.u32le(localKey));
    }

    public byte[] rawBlob(Hit h) { return rawBlob(h.classIdx(), h.localKey()); }

    /// Scan one class map's raw rows, calling `sink(localKey, blob)`; stop early when the sink
    /// returns false. No decode anywhere — these are the stored bytes.
    public long scanClass(int classIdx, BiPredicate<Integer, byte[]> sink) {
        Dbi<byte[]> d = classDbi(classIdx);
        if (d == null) return 0;
        long n = 0;
        try (CursorIterable<byte[]> ci = d.iterate(txn)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                n++;
                if (!sink.test(NulKeyCodec.u32le(kv.key()), kv.val())) break;
            }
        }
        return n;
    }

    /// Row count of a class map, by cursor walk.
    public long countClass(int classIdx) {
        return scanClass(classIdx, (k, v) -> true);
    }

    /// Row count from LMDB's own `MDB_stat` — O(1), no cursor walk and no value copies.
    public long entries(int classIdx) {
        Dbi<byte[]> d = classDbi(classIdx);
        return d == null ? 0 : d.stat(txn).entries;
    }


    // ================================================================ the side maps

    /// Raw sub-database names exactly as `Env.getDbiNames()` returns them (for `stat`).
    public List<byte[]> dbiNames() { return allDbiNames; }

    /// Is a text-named sub-database present? (`_embedding_idx` is absent on pre-A7 stores.)
    public boolean hasDbi(String name) { return namedDbis.contains(name); }

    /// `MDB_stat` entry count of any sub-database by raw name — O(1), no cursor walk.
    public long entriesOfDbi(byte[] rawName) {
        return env.openDbi(txn, rawName, null, false).stat(txn).entries;
    }

    /// A `_meta` row by literal key, or null. Used for the `emb_idx_done` marker.
    public byte[] metaRow(String key) {
        return meta.get(txn, key.getBytes(StandardCharsets.UTF_8));
    }

    /// One `_embedding_idx` row: the encoded key of the EMBEDDED object (id NUL version NUL
    /// classIdx) and the full_key of the object that CONTAINS it (A7; written by
    /// `AbstractLmdbStore.insertOne`'s fused `refsAndEmbeddings` walk).
    public record EmbRow(byte[] embKey, long containerFullKey) {
        public String embId() { return NulKeyCodec.idPart(body()); }
        public String embVersion() { return NulKeyCodec.versionPart(body()); }
        public int embClassIdx() { return NulKeyCodec.classTrailer(embKey); }
        private byte[] body() {
            byte[] b = new byte[NulKeyCodec.stripClazzLen(embKey)];
            System.arraycopy(embKey, 0, b, 0, b.length);
            return b;
        }
    }

    /// `_embedding_idx` rows at the `id NUL` prefix — "what is this embedded in", as a DUPSORT
    /// point lookup, read directly off the index that answers it.
    public List<EmbRow> embeddedIn(String id, String version, String className) {
        Dbi<byte[]> d = side("_embedding_idx", true);
        List<EmbRow> out = new ArrayList<>();
        if (d == null) return out;
        byte[] prefix = NulKeyCodec.encodePrefix(id, version, null, false);
        try (CursorIterable<byte[]> ci = d.iterate(txn, KeyRange.atLeast(prefix))) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                byte[] k = kv.key();
                if (!NulKeyCodec.startsWith(k, prefix)) break;
                if (className != null && !className.equals(idxToName.get(NulKeyCodec.classTrailer(k)))) continue;
                out.add(new EmbRow(k.clone(), NulKeyCodec.u64le(kv.val())));
            }
        }
        return out;
    }

    /// Every `_embedding_idx` row, streaming; stop early when the sink returns false.
    /// The map is keyed by the EMBEDDED object and has no inverse on disk, so the reverse direction
    /// ("what does this container embed") and the whole-index census both scan.
    public long scanEmbeddingIndex(BiPredicate<byte[], Long> sink) {
        Dbi<byte[]> d = side("_embedding_idx", true);
        if (d == null) return 0;
        long n = 0;
        try (CursorIterable<byte[]> ci = d.iterate(txn)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                n++;
                if (!sink.test(kv.key(), NulKeyCodec.u64le(kv.val()))) break;
            }
        }
        return n;
    }

    /// The `_edge_blocks` block NUMBERS present, per kind, ascending — `[0]` kind 0,
    /// `[1]` kind 1. A keys-only cursor pass: no block value is read and no pair is decoded.
    ///
    /// Keys are `[kind][block# u32 LE]`, so `[k,00,01,00,00]` = block 256 sorts before
    /// `[k,01,00,00,00]` = block 1. `nx db check` compares `Store.scanEdges` against
    /// `Store.iterEdges` on an order-sensitive digest, and that comparison is vacuous on a store
    /// with ≤255 blocks of both kinds, where lexicographic and numeric block order coincide: the
    /// cursor-order permutation of these numbers must differ from their numeric order, or the check
    /// has proved nothing.
    public int[][] edgeBlockKeys() {
        Dbi<byte[]> d = side("_edge_blocks", false);
        int[][] out = new int[2][];
        if (d == null) return new int[][] {new int[0], new int[0]};
        Set<Integer> b0 = new java.util.TreeSet<>();
        Set<Integer> b1 = new java.util.TreeSet<>();
        try (CursorIterable<byte[]> ci = d.iterate(txn)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                byte[] k = kv.key();
                if (k.length != 5) continue;
                int b = (k[1] & 0xFF) | ((k[2] & 0xFF) << 8) | ((k[3] & 0xFF) << 16) | ((k[4] & 0xFF) << 24);
                (k[0] == 0 ? b0 : b1).add(b);
            }
        }
        out[0] = toArray(b0);
        out[1] = toArray(b1);
        return out;
    }

    private static int[] toArray(Set<Integer> s) {
        int[] a = new int[s.size()];
        int i = 0;
        for (int v : s) a[i++] = v;
        return a;
    }

    /// A text-named side map, opened inside the live read txn (see [#classDbi] for why that
    /// matters). `dupsort` mirrors `V2Store`'s open flags for `_embedding_idx`; LMDB takes a stored
    /// database's flags from its own record, so this is documentation as much as instruction.
    private Dbi<byte[]> side(String name, boolean dupsort) {
        if (!namedDbis.contains(name)) return null;
        return sideDbis.computeIfAbsent(name, n -> dupsort
                ? env.openDbi(txn, n.getBytes(StandardCharsets.UTF_8), null, false,
                        DbiFlags.MDB_DUPSORT, DbiFlags.MDB_DUPFIXED)
                : env.openDbi(txn, n.getBytes(StandardCharsets.UTF_8), null, false));
    }

    /// Every `_id_idx` row in key order, streaming: `(id, version, classIdx, fullKey)`.
    ///
    /// Key order is `id` bytes first, so all rows of one id are adjacent — the duplicate-id audit
    /// runs in one pass with O(one id group) memory.
    public long scanIdIndex(BiPredicate<byte[], Long> sink) {
        long n = 0;
        try (CursorIterable<byte[]> ci = idIdx.iterate(txn)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                n++;
                if (!sink.test(kv.key(), NulKeyCodec.u64le(kv.val()))) break;
            }
        }
        return n;
    }

    /// A class map's dbi, opened **inside the live read transaction**.
    ///
    /// `Env.openDbi(name)` opens and ends a transaction of its own, and a handle obtained that way
    /// while another read txn is already open on this thread comes back unusable — `mdb_get` then
    /// returns `EINVAL`. `AbstractLmdbStore.classDbi` threads the caller's txn through for the same
    /// reason.
    private Dbi<byte[]> classDbi(int idx) {
        if (!existingMaps.contains(idx)) return null;
        return classDbis.computeIfAbsent(idx,
                i -> env.openDbi(txn, NulKeyCodec.classIdxBytes(i), null, false));
    }

    @Override
    public void close() {
        try { txn.close(); } catch (RuntimeException ignored) { }
        env.close();
    }
}
