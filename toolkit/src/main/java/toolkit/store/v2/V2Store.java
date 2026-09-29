package toolkit.store.v2;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import toolkit.graph.Csr;
import toolkit.keycodec.NulKeyCodec;
import toolkit.store.AbstractLmdbStore;
import toolkit.store.BytesKey;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongPredicate;

/// The Java-native v2 format:
///
/// ```
/// class maps    name = raw 2-byte LE class idx; plain; key = 4-byte LE local;
///               value = FRAGMENT codec blob (LZ4 length-prefixed XML)
/// _id_idx       plain; NulKeyCodec key -> 8-byte LE full_key
/// _meta         "format" -> "java-v2:1"; "seq" -> 8-byte LE sequence;
///               'c'+2-byte-LE-idx rows -> class name (the class table)
/// _edge_blocks  chunked long[] blocks: key = [kind byte][4-byte LE block#];
///               value = up to 65,536 (referrer,target) pairs of 8-byte LE longs.
///               kind 0 = resolved edges; kind 1 = unresolved pairs (referrer, target-key id).
///               A pair whose first long is -1 (0xFFFF... — class idx 0xFFFF is reserved, so no
///               real full_key collides) is a TOMBSTONE: a deleted slot kept in place so
///               untouched blocks never need rewriting.
/// _unres_keys   4-byte LE id -> encoded target key bytes (interning table so unresolved rows
///               fit the long-pair block shape; targets are var-length NulKeyCodec keys)
/// ```
///
/// Blocks live inside LMDB, so edge persistence commits in the same txn as the objects.
///
/// Edges and unresolved rows live IN MEMORY while the store is open, slot-aligned with the on-disk
/// blocks (slot i in block i / blockPairs). Mutation strategy:
///
/// - **append-new**: inserts append slots at the tail; only the (partial) tail block and
///    genuinely new blocks are written at commit;
/// - **tombstone**: deletes overwrite the slot's referrer with -1 in place and mark only
///    that slot's block dirty; a commit rewrites dirty blocks verbatim, tombstones included;
/// - **compact**: when a kind's dead slots reach [#COMPACT_DEAD_FRACTION] of its slots at commit
///    time, that kind is rewritten from live slots (stale block keys deleted; for kind 1 the
///    interning table is rebuilt pruned and renumbered).
///
/// Deletion is indexed rather than scanned: a per-referrer slot index backs deleteUnresolved /
/// clearRefRows / purgeReferrerRows in O(out-degree), and gives DUPSORT pair-dedup semantics at
/// insert time. resolve() mutates in memory (id-index lookups stay in LMDB) and persists at commit;
/// aborted write txns reload from disk. The CSR is built lazily from live slots and invalidated by
/// any edge mutation.
///
/// Blocks chunked otherwise than expected (a foreign writer, a different blockPairs) are detected
/// at load and force a full compaction on the next commit.
public final class V2Store extends AbstractLmdbStore {

    public static final String FORMAT = "java-v2:1";
    /// The short format id this store answers to on the CLI, and what `Stores.V2` is defined as.
    public static final String FORMAT_ID = "v2";
    private static final long DEFAULT_MAP_SIZE = 64L << 30;
    private static final int MAX_DBS = 4096;
    private static final byte[] FORMAT_KEY = "format".getBytes(StandardCharsets.UTF_8);
    /// Dead-slot marker: -1 has class idx 0xFFFF (reserved marker range), never a real full_key.
    private static final long TOMBSTONE = -1L;
    /// Compact a kind at commit when its dead slots reach this fraction of its slots.
    private static final double COMPACT_DEAD_FRACTION = 0.25;

    /// Pairs per block; overridable (tests) via -Dtoolkit.v2.blockPairs.
    private final int blockPairs = Conf.intOf(Conf.V2_BLOCK_PAIRS);

    /// The append-path growth policy for the four slot arrays.
    ///
    /// fastutil and `ArrayList` grow by `max(len + len/2, needed)`, so an appending array's slack
    /// is up to 0.5x its own size. This caps the growth increment to `min(size/2, GROW_CHUNK)`, so
    /// slack stops scaling with the store.
    ///
    /// It costs copy volume on a build-from-empty: above `2 * GROW_CHUNK` rows growth is linear, so
    /// total elements copied goes from `~2 N` to `~N^2 / (2 * GROW_CHUNK)`. It does not lower the
    /// transient peak of a reallocation.
    ///
    /// Default 0 disables the rule, and the `static final` folds the whole mechanism away when it
    /// is off.
    public static final int GROW_CHUNK = Conf.intOf(Conf.STORE_GROW_CHUNK);

    private final Dbi<byte[]> edgeBlocks;
    private final Dbi<byte[]> unresKeys;

    // ---- kind 0: resolved edges; slot-aligned with disk blocks, TOMBSTONE slots are dead
    private final LongArrayList edgeSrc = new LongArrayList();
    private final LongArrayList edgeDst = new LongArrayList();
    private final Long2ObjectOpenHashMap<IntArrayList> edgeSlotsByReferrer = new Long2ObjectOpenHashMap<>();
    private final IntOpenHashSet edgeDirty = new IntOpenHashSet();
    private int edgeDead;
    private int edgeDiskBlocks;
    private boolean edgeForceCompact;

    // ---- kind 1: unresolved (referrer, intern id); same slot discipline
    private final LongArrayList unresSrc = new LongArrayList();
    private final IntArrayList unresKeyId = new IntArrayList();
    private final Long2ObjectOpenHashMap<IntArrayList> unresSlotsByReferrer = new Long2ObjectOpenHashMap<>();
    private final IntOpenHashSet unresDirty = new IntOpenHashSet();
    private int unresDead;
    private int unresDiskBlocks;
    private boolean unresForceCompact;

    // ---- interning table for the var-length unresolved target keys
    private final ArrayList<byte[]> unresKeyTable = new ArrayList<>();
    private final Object2IntOpenHashMap<BytesKey> unresKeyLookup = new Object2IntOpenHashMap<>();
    private int internOnDisk; // rows currently persisted in _unres_keys

    private boolean dirty;
    private Csr cachedCsr;

    /// False until [#loadEdgeState()] has run.
    private boolean edgeStateLoaded;

    /// @param embScope which classes `_embedding_idx` covers. Mandatory: the format knows how to
    ///                 maintain an index, not which classes a profile wants in one. `Stores`
    ///                 supplies the profile's.
    public V2Store(Path path, boolean readonly, EmbeddingScope embScope) {
        super(path, readonly, DEFAULT_MAP_SIZE, MAX_DBS, embScope);
        unresKeyLookup.defaultReturnValue(-1);
        boolean fresh = env.getDbiNames().isEmpty();
        if (fresh && readonly) throw new IllegalStateException(path + " is empty; cannot open readonly");
        if (readonly) {
            meta = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8));
            idIdx = env.openDbi("_id_idx".getBytes(StandardCharsets.UTF_8));
            edgeBlocks = env.openDbi("_edge_blocks".getBytes(StandardCharsets.UTF_8));
            unresKeys = env.openDbi("_unres_keys".getBytes(StandardCharsets.UTF_8));
            embIdx = openExistingEmbIdx();
        } else {
            meta = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8), DbiFlags.MDB_CREATE);
            idIdx = env.openDbi("_id_idx".getBytes(StandardCharsets.UTF_8), DbiFlags.MDB_CREATE);
            edgeBlocks = env.openDbi("_edge_blocks".getBytes(StandardCharsets.UTF_8), DbiFlags.MDB_CREATE);
            unresKeys = env.openDbi("_unres_keys".getBytes(StandardCharsets.UTF_8), DbiFlags.MDB_CREATE);
            embIdx = env.openDbi("_embedding_idx".getBytes(StandardCharsets.UTF_8),
                    DbiFlags.MDB_CREATE, DbiFlags.MDB_DUPSORT, DbiFlags.MDB_DUPFIXED);
        }
        initTables(fresh);
        // A read-write store loads eagerly: putEdge/putUnresolved dedupe against the whole
        // per-referrer index, so no write path may run against partial state. A READ-ONLY store
        // reaches none of those, so it defers to the first caller that asks for edges.
        if (!readonly) loadEdgeState();
    }

    /// Materialise the edge/unresolved state if it is not already in memory. Called from every read
    /// path that touches it; a no-op on a read-write store, which loaded in the constructor.
    private void ensureEdgeState() {
        if (!edgeStateLoaded) loadEdgeState();
    }

    /// The embedding-index map of an existing readonly db; null when the db PREDATES it.
    ///
    /// Decided from `getDbiNames()`, not from a failed open: absence from the name list is the only
    /// thing that means "predates", and null degrades to the full corpus scan.
    ///
    /// Note LMDB 0.9 does not validate `mdb_dbi_open` flags against an existing named db — it
    /// loads the stored `MDB_db` over them — so a DUPSORT mismatch never reaches here.
    private Dbi<byte[]> openExistingEmbIdx() {
        byte[] name = "_embedding_idx".getBytes(StandardCharsets.UTF_8);
        boolean present = false;
        for (byte[] n : env.getDbiNames()) {
            if (java.util.Arrays.equals(n, name)) {
                present = true;
                break;
            }
        }
        if (!present) return null;
        try {
            return env.openDbi(name, DbiFlags.MDB_DUPSORT, DbiFlags.MDB_DUPFIXED);
        } catch (RuntimeException e) {
            throw new IllegalStateException(path + ": _embedding_idx is present but will not open "
                    + "as a DUPSORT/DUPFIXED map — the index is unusable, and treating it as an "
                    + "absent one would silently fall back to a full corpus scan", e);
        }
    }

    private static final byte[] BLOB_REPAIR_DONE_KEY = "blob_repair_done".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMBEDDING_IDX_DONE_KEY = "emb_idx_done".getBytes(StandardCharsets.UTF_8);

    @Override
    protected String markerValue(LmdbTxn t, Marker m) {
        byte[] key = m == Marker.BLOB_REPAIR_DONE ? BLOB_REPAIR_DONE_KEY : EMBEDDING_IDX_DONE_KEY;
        byte[] v = meta.get(t.tx, key);
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    @Override
    protected void putMarker(LmdbTxn t, Marker m, String value) {
        byte[] key = m == Marker.BLOB_REPAIR_DONE ? BLOB_REPAIR_DONE_KEY : EMBEDDING_IDX_DONE_KEY;
        if (value != null) {
            meta.put(t.tx, key, value.getBytes(StandardCharsets.UTF_8));
        } else {
            meta.delete(t.tx, key);
        }
    }

    @Override
    public String formatMarker() {
        return FORMAT;
    }

    @Override
    public String formatId() {
        return FORMAT_ID;
    }

    // ---------------------------------------------------------------- class table (_meta rows)

    @Override
    protected void populateClassTable() {
        try (LmdbTxn t = (LmdbTxn) rwTxn()) {
            meta.put(t.tx, FORMAT_KEY, FORMAT.getBytes(StandardCharsets.UTF_8));
            // Fresh db: the embedding index is complete-by-construction (inserts maintain it) --
            // complete FOR THIS STORE'S SCOPE, which is what the marker's value records.
            if (EMB_INDEX_ENABLED) putMarker(t, Marker.EMBEDDING_IDX_DONE, embeddingScopeTag());
            for (Map.Entry<String, Integer> e : assignIdx().entrySet()) {
                meta.put(t.tx, classRowKey(e.getValue()), e.getKey().getBytes(StandardCharsets.UTF_8));
            }
            t.commit();
        }
    }

    @Override
    protected void restoreClassTable() {
        try (LmdbTxn t = (LmdbTxn) roTxn()) {
            byte[] marker = meta.get(t.tx, FORMAT_KEY);
            String fmt = marker == null ? null : new String(marker, StandardCharsets.UTF_8);
            if (!FORMAT.equals(fmt)) {
                throw new IllegalStateException(path + ": unsupported v2 format marker " + fmt);
            }
            try (CursorIterable<byte[]> ci = meta.iterate(t.tx)) {
                for (CursorIterable.KeyVal<byte[]> kv : ci) {
                    byte[] k = kv.key();
                    if (k.length == 3 && k[0] == 'c') {
                        int idx = (k[1] & 0xFF) | ((k[2] & 0xFF) << 8);
                        register(idx, new String(kv.val(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
    }

    private static byte[] classRowKey(int idx) {
        return new byte[] {'c', (byte) idx, (byte) (idx >>> 8)};
    }

    // ---------------------------------------------------------------- edge state <-> blocks

    private void loadEdgeState() {
        edgeSrc.clear();
        edgeDst.clear();
        edgeSlotsByReferrer.clear();
        edgeDirty.clear();
        edgeDead = 0;
        edgeForceCompact = false;
        unresSrc.clear();
        unresKeyId.clear();
        unresSlotsByReferrer.clear();
        unresDirty.clear();
        unresDead = 0;
        unresForceCompact = false;
        unresKeyTable.clear();
        unresKeyLookup.clear();
        cachedCsr = null;
        // Blocks arrive in LEXICOGRAPHIC key order and the u32 LE block numbers do not sort
        // numerically past block 255 -> bucket by parsed number, then concatenate in order.
        TreeMap<Integer, byte[]> kind0 = new TreeMap<>();
        TreeMap<Integer, byte[]> kind1 = new TreeMap<>();
        try (LmdbTxn t = (LmdbTxn) roTxn()) {
            // The intern table's row count is O(1) from the dbi stat, so size the spine exactly.
            // READ-ONLY ONLY: an array whose capacity equals its size reallocates to 1.5x on its
            // very next add, and a read-write store interns further keys through putUnresolved.
            if (readonly) {
                long internRows = unresKeys.stat(t.tx).entries;
                if (internRows > 0 && internRows <= Integer.MAX_VALUE) {
                    unresKeyTable.ensureCapacity((int) internRows);
                }
            }
            try (CursorIterable<byte[]> ci = unresKeys.iterate(t.tx)) {
                for (CursorIterable.KeyVal<byte[]> kv : ci) {
                    int id = NulKeyCodec.u32le(kv.key());
                    while (unresKeyTable.size() <= id) unresKeyTable.add(null);
                    unresKeyTable.set(id, kv.val());
                    // The reverse lookup exists ONLY to intern a target key on putUnresolved and to
                    // find one on deleteUnresolved/compactUnresolved, none of which a readonly
                    // store reaches.
                    if (!readonly) unresKeyLookup.put(new BytesKey(kv.val()), id);
                }
            }
            try (CursorIterable<byte[]> ci = edgeBlocks.iterate(t.tx)) {
                for (CursorIterable.KeyVal<byte[]> kv : ci) {
                    byte[] k = kv.key();
                    int blockNo = (k[1] & 0xFF) | ((k[2] & 0xFF) << 8) | ((k[3] & 0xFF) << 16) | ((k[4] & 0xFF) << 24);
                    (k[0] == 0 ? kind0 : kind1).put(blockNo, kv.val());
                }
            }
        }
        internOnDisk = unresKeyTable.size();
        edgeDiskBlocks = kind0.size();
        unresDiskBlocks = kind1.size();
        edgeForceCompact = loadKind(kind0, true);
        unresForceCompact = loadKind(kind1, false);
        dirty = edgeForceCompact || unresForceCompact; // misaligned foreign files: heal at next commit
        edgeStateLoaded = true;
    }

    /// Concatenate one kind's blocks into the slot arrays; returns true when misaligned (heal by compaction).
    private boolean loadKind(TreeMap<Integer, byte[]> blocks, boolean edges) {
        boolean misaligned = false;
        int i = 0;
        // The exact row count is known here — the blocks are in memory and every row is 16 B — so
        // size the slot arrays once instead of letting fastutil grow into them at 1.5x. An array
        // sized exactly still reallocates to 1.5x on its very next add(); GROW_CHUNK caps that.
        long rows = 0;
        for (byte[] v : blocks.values()) rows += v.length / 16;
        if (rows > 0 && rows <= Integer.MAX_VALUE) {
            if (edges) {
                edgeSrc.ensureCapacity((int) rows);
                edgeDst.ensureCapacity((int) rows);
            } else {
                unresSrc.ensureCapacity((int) rows);
                unresKeyId.ensureCapacity((int) rows);
            }
        }
        for (Map.Entry<Integer, byte[]> e : blocks.entrySet()) {
            byte[] v = e.getValue();
            int pairs = v.length / 16;
            // expected chunking: block b starts at slot b*blockPairs; every block except the
            // last holds exactly blockPairs pairs
            if (e.getKey() != i++ || (pairs != blockPairs && i < blocks.size()) || pairs > blockPairs) {
                misaligned = true;
            }
            for (int off = 0; off + 16 <= v.length; off += 16) {
                long a = leLong(v, off);
                long b = leLong(v, off + 8);
                if (edges) {
                    int slot = edgeSrc.size();
                    edgeSrc.add(a);
                    edgeDst.add(b);
                    if (a == TOMBSTONE) edgeDead++;
                    else if (!readonly) slotsFor(edgeSlotsByReferrer, a).add(slot);
                } else {
                    int slot = unresSrc.size();
                    unresSrc.add(a);
                    unresKeyId.add((int) b);
                    if (a == TOMBSTONE) unresDead++;
                    else if (!readonly) slotsFor(unresSlotsByReferrer, a).add(slot);
                }
            }
        }
        return misaligned;
    }

    /// The capacity [#GROW_CHUNK] would grow an exactly-full array of `size` to, or `size` when
    /// the rule does not bind and fastutil's 1.5x applies.
    public static int growTo(int size) {
        if (GROW_CHUNK <= 0 || (long) size < 2L * GROW_CHUNK) return size; // fastutil's 1.5x stands
        return (int) Math.min(Integer.MAX_VALUE - 8L, (long) size + GROW_CHUNK);
    }

    /// Cap the next growth increment of an exactly-full slot array. A no-op otherwise.
    private static void reserve(LongArrayList l) {
        int n = l.size();
        if (n == l.elements().length) {
            int want = growTo(n);
            if (want > n) l.ensureCapacity(want);
        }
    }

    /// [#reserve(LongArrayList)] for the 4-byte slot arrays.
    private static void reserve(IntArrayList l) {
        int n = l.size();
        if (n == l.elements().length) {
            int want = growTo(n);
            if (want > n) l.ensureCapacity(want);
        }
    }

    private static IntArrayList slotsFor(Long2ObjectOpenHashMap<IntArrayList> map, long referrer) {
        IntArrayList slots = map.get(referrer);
        if (slots == null) {
            slots = new IntArrayList(2);
            map.put(referrer, slots);
        }
        return slots;
    }

    private static long leLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[off + i] & 0xFFL) << (8 * i);
        return v;
    }

    private static void putLeLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    private interface SecondColumn {
        long get(int i);
    }

    /// Persist the in-memory state inside the committing txn (called by LmdbTxn.commit()): per
    /// kind, either rewrite ONLY the dirty blocks verbatim (tombstones kept in place, appended
    /// slots forming rewritten-tail + new blocks) or — past the dead-slot threshold — compact.
    @Override
    protected void beforeCommit(LmdbTxn t) {
        if (t.isReadOnly() || !dirty) return;
        // kind 0 (resolved edges)
        if (edgeForceCompact || compactionDue(edgeDead, edgeSrc.size())) {
            compactEdges(t);
        } else {
            writeDirtyBlocks(t, 0, edgeDirty, edgeSrc, edgeDst::getLong);
            edgeDiskBlocks = Math.max(edgeDiskBlocks, blockCount(edgeSrc.size()));
        }
        // kind 1 (unresolved) + interning table
        if (unresForceCompact || compactionDue(unresDead, unresSrc.size())) {
            compactUnresolved(t);
        } else {
            writeDirtyBlocks(t, 1, unresDirty, unresSrc, i -> unresKeyId.getInt(i));
            unresDiskBlocks = Math.max(unresDiskBlocks, blockCount(unresSrc.size()));
            for (int id = internOnDisk; id < unresKeyTable.size(); id++) {
                unresKeys.put(t.tx, NulKeyCodec.u32le(id), unresKeyTable.get(id));
            }
            internOnDisk = unresKeyTable.size();
        }
        // Dirty flags are cleared in afterCommit(): if tx.commit() fails, dirty stays true and
        // onAbort() reloads — the compaction above may have renumbered every in-memory slot.
    }

    @Override
    protected void afterCommit(LmdbTxn t) {
        if (t.isReadOnly()) return;
        edgeDirty.clear();
        edgeForceCompact = false;
        unresDirty.clear();
        unresForceCompact = false;
        dirty = false;
    }

    private static boolean compactionDue(int dead, int slots) {
        return dead > 0 && dead >= slots * COMPACT_DEAD_FRACTION;
    }

    private int blockCount(int slots) {
        return (slots + blockPairs - 1) / blockPairs;
    }

    private byte[] blockKey(int kind, int blockNo) {
        byte[] key = new byte[5];
        key[0] = (byte) kind;
        byte[] bn = NulKeyCodec.u32le(blockNo);
        System.arraycopy(bn, 0, key, 1, 4);
        return key;
    }

    /// Serialize slots [b*blockPairs, min((b+1)\*blockPairs, n)) verbatim; tombstones as (-1,-1).
    private void writeBlock(LmdbTxn t, int kind, int blockNo, LongArrayList first, SecondColumn second) {
        int start = blockNo * blockPairs;
        int end = Math.min(start + blockPairs, first.size());
        if (end <= start) return;
        byte[] value = new byte[(end - start) * 16];
        for (int i = start; i < end; i++) {
            long a = first.getLong(i);
            int off = (i - start) * 16;
            putLeLong(value, off, a);
            putLeLong(value, off + 8, a == TOMBSTONE ? TOMBSTONE : second.get(i));
        }
        edgeBlocks.put(t.tx, blockKey(kind, blockNo), value);
    }

    private void writeDirtyBlocks(LmdbTxn t, int kind, IntOpenHashSet dirtyBlocks,
                                  LongArrayList first, SecondColumn second) {
        int[] blocks = dirtyBlocks.toIntArray();
        java.util.Arrays.sort(blocks);
        for (int b : blocks) writeBlock(t, kind, b, first, second);
    }

    /// Full rewrite of one kind from slot 0 (post-compaction: no tombstones); returns block count.
    private int writeAllBlocks(LmdbTxn t, int kind, int oldDiskBlocks, LongArrayList first, SecondColumn second) {
        int n = blockCount(first.size());
        for (int b = 0; b < n; b++) writeBlock(t, kind, b, first, second);
        for (int b = n; b < oldDiskBlocks; b++) edgeBlocks.delete(t.tx, blockKey(kind, b)); // stale keys
        return n;
    }

    /// In-place removal of dead edge slots + per-referrer index rebuild + full block rewrite.
    private void compactEdges(LmdbTxn t) {
        edgeSlotsByReferrer.clear();
        int w = 0;
        for (int r = 0; r < edgeSrc.size(); r++) {
            long s = edgeSrc.getLong(r);
            if (s == TOMBSTONE) continue;
            edgeSrc.set(w, s);
            edgeDst.set(w, edgeDst.getLong(r));
            slotsFor(edgeSlotsByReferrer, s).add(w);
            w++;
        }
        edgeSrc.size(w);
        edgeDst.size(w);
        edgeDead = 0;
        edgeDiskBlocks = writeAllBlocks(t, 0, edgeDiskBlocks, edgeSrc, edgeDst::getLong);
    }

    /// Kind-1 compaction: additionally prunes + renumbers the interning table to live keys only.
    private void compactUnresolved(LmdbTxn t) {
        List<byte[]> newTable = new ArrayList<>();
        unresKeyLookup.clear();
        unresSlotsByReferrer.clear();
        int w = 0;
        for (int r = 0; r < unresSrc.size(); r++) {
            long s = unresSrc.getLong(r);
            if (s == TOMBSTONE) continue;
            byte[] key = unresKeyTable.get(unresKeyId.getInt(r));
            BytesKey bk = new BytesKey(key);
            int nid = unresKeyLookup.getInt(bk);
            if (nid == -1) {
                nid = newTable.size();
                newTable.add(key);
                unresKeyLookup.put(bk, nid);
            }
            unresSrc.set(w, s);
            unresKeyId.set(w, nid);
            slotsFor(unresSlotsByReferrer, s).add(w);
            w++;
        }
        unresSrc.size(w);
        unresKeyId.size(w);
        unresDead = 0;
        unresKeyTable.clear();
        unresKeyTable.addAll(newTable);
        unresKeys.drop(t.tx, false);
        for (int id = 0; id < unresKeyTable.size(); id++) {
            unresKeys.put(t.tx, NulKeyCodec.u32le(id), unresKeyTable.get(id));
        }
        internOnDisk = unresKeyTable.size();
        unresDiskBlocks = writeAllBlocks(t, 1, unresDiskBlocks, unresSrc, i -> unresKeyId.getInt(i));
    }

    @Override
    protected void onAbort(LmdbTxn t) {
        if (dirty) loadEdgeState(); // roll the in-memory state back to the last committed blocks
    }

    // ---------------------------------------------------------------- edge/unresolved hooks

    @Override
    protected void putEdge(LmdbTxn t, long referrerFk, long targetFk) {
        IntArrayList slots = edgeSlotsByReferrer.get(referrerFk);
        if (slots != null) { // DUPSORT pair semantics: duplicate (referrer, target) collapses
            for (int i = 0; i < slots.size(); i++) {
                if (edgeDst.getLong(slots.getInt(i)) == targetFk) return;
            }
        }
        int slot = edgeSrc.size();
        reserve(edgeSrc); // cap the growth increment (see GROW_CHUNK)
        reserve(edgeDst);
        edgeSrc.add(referrerFk);
        edgeDst.add(targetFk);
        slotsFor(edgeSlotsByReferrer, referrerFk).add(slot);
        edgeDirty.add(slot / blockPairs);
        dirty = true;
        cachedCsr = null;
    }

    @Override
    protected void putUnresolved(LmdbTxn t, long referrerFk, byte[] encodedTarget) {
        BytesKey k = new BytesKey(encodedTarget);
        int id = unresKeyLookup.getInt(k);
        if (id == -1) {
            id = unresKeyTable.size();
            unresKeyTable.add(encodedTarget);
            unresKeyLookup.put(k, id);
        }
        IntArrayList slots = unresSlotsByReferrer.get(referrerFk);
        if (slots != null) { // duplicate (referrer, target) collapses
            for (int i = 0; i < slots.size(); i++) {
                if (unresKeyId.getInt(slots.getInt(i)) == id) return;
            }
        }
        int slot = unresSrc.size();
        reserve(unresSrc); // cap the growth increment (see GROW_CHUNK)
        reserve(unresKeyId);
        unresSrc.add(referrerFk);
        unresKeyId.add(id);
        slotsFor(unresSlotsByReferrer, referrerFk).add(slot);
        unresDirty.add(slot / blockPairs);
        dirty = true;
    }

    @Override
    protected void deleteUnresolved(LmdbTxn t, long referrerFk, byte[] encodedTarget) {
        int id = unresKeyLookup.getInt(new BytesKey(encodedTarget));
        if (id == -1) return;
        IntArrayList slots = unresSlotsByReferrer.get(referrerFk);
        if (slots == null) return;
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.getInt(i);
            if (unresKeyId.getInt(slot) == id) {
                slots.removeInt(i);
                if (slots.isEmpty()) unresSlotsByReferrer.remove(referrerFk);
                tombstoneUnres(slot);
                return;
            }
        }
    }

    private void tombstoneUnres(int slot) {
        unresSrc.set(slot, TOMBSTONE);
        unresDead++;
        unresDirty.add(slot / blockPairs);
        dirty = true;
    }

    private void tombstoneEdge(int slot) {
        edgeSrc.set(slot, TOMBSTONE);
        edgeDead++;
        edgeDirty.add(slot / blockPairs);
        dirty = true;
        cachedCsr = null;
    }

    @Override
    protected void clearRefRows(LmdbTxn t, long fullKey) {
        IntArrayList es = edgeSlotsByReferrer.remove(fullKey);
        if (es != null) {
            for (int i = 0; i < es.size(); i++) tombstoneEdge(es.getInt(i));
        }
        IntArrayList us = unresSlotsByReferrer.remove(fullKey);
        if (us != null) {
            for (int i = 0; i < us.size(); i++) tombstoneUnres(us.getInt(i));
        }
    }

    @Override
    protected void purgeReferrerRows(LmdbTxn t, int classIdx) {
        LongArrayList referrers = new LongArrayList();
        for (var it = edgeSlotsByReferrer.keySet().iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            if (NulKeyCodec.fullKeyClassIdx(k) == classIdx) referrers.add(k);
        }
        for (var it = unresSlotsByReferrer.keySet().iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            if (NulKeyCodec.fullKeyClassIdx(k) == classIdx) referrers.add(k);
        }
        for (int i = 0; i < referrers.size(); i++) clearRefRows(t, referrers.getLong(i));
    }

    @Override
    public Iterable<long[]> iterEdges(Txn txn) {
        ensureEdgeState();
        return () -> new Iterator<>() {
            int i = skipDead(0);

            private int skipDead(int j) {
                while (j < edgeSrc.size() && edgeSrc.getLong(j) == TOMBSTONE) j++;
                return j;
            }

            @Override
            public boolean hasNext() {
                return i < edgeSrc.size();
            }

            @Override
            public long[] next() {
                if (!hasNext()) throw new NoSuchElementException();
                long[] e = {edgeSrc.getLong(i), edgeDst.getLong(i)};
                i = skipDead(i + 1);
                return e;
            }
        };
    }

    @Override
    public Iterable<UnresolvedRow> iterUnresolved(Txn txn) {
        ensureEdgeState();
        return () -> new Iterator<>() {
            int i = skipDead(0);

            private int skipDead(int j) {
                while (j < unresSrc.size() && unresSrc.getLong(j) == TOMBSTONE) j++;
                return j;
            }

            @Override
            public boolean hasNext() {
                return i < unresSrc.size();
            }

            @Override
            public UnresolvedRow next() {
                if (!hasNext()) throw new NoSuchElementException();
                int kid = unresKeyId.getInt(i);
                UnresolvedRow r = new UnresolvedRow(unresSrc.getLong(i), unresKeyTable.get(kid), kid);
                i = skipDead(i + 1);
                return r;
            }
        };
    }

    // ------------------------------------------------------- streaming edge readers
    //
    // scanEdges/scanUnresolved yield EXACTLY what iterEdges/iterUnresolved yield -- same pairs,
    // same order, same tombstone exclusions -- while holding ONE BLOCK at a time instead of the
    // whole slot set.
    //
    // Blocks are keyed [kind][block# u32 LE] in a byte-ordered map, so cursor order stops being
    // numeric order at block 256: [k,00,01,00,00] sorts before [k,01,00,00,00]. Numeric order is
    // recovered in blockOrder, which emits in numeric order in both of its arms.

    /// {@inheritDoc}
    ///
    /// A READ-WRITE store must delegate to [#iterEdges]. The in-memory slot arrays are the
    /// authority there — they carry this transaction's appends and tombstones, which are not on
    /// disk until `beforeCommit` — so streaming the blocks would answer from the last commit and
    /// break the identity contract.
    ///
    /// A read-only store always streams, even once [#ensureEdgeState] has run for some other
    /// reason.
    @Override
    public Iterable<long[]> scanEdges(Txn txn) {
        if (!readonly) return iterEdges(txn);
        LmdbTxn t = cast(txn);
        return () -> blockPairs(t, 0);
    }

    /// {@inheritDoc}
    @Override
    public Iterable<UnresolvedRow> scanUnresolved(Txn txn) {
        if (!readonly) return iterUnresolved(txn);
        LmdbTxn t = cast(txn);
        return () -> {
            Iterator<long[]> pairs = blockPairs(t, 1);
            return new Iterator<UnresolvedRow>() {
                @Override
                public boolean hasNext() {
                    return pairs.hasNext();
                }

                @Override
                public UnresolvedRow next() {
                    long[] p = pairs.next();
                    // loadEdgeState holds the whole intern table; this resolves only the rows it
                    // emits, one point lookup each.
                    return new UnresolvedRow(p[0], unresKeys.get(t.tx, NulKeyCodec.u32le((int) p[1])));
                }
            };
        };
    }

    /// One kind's live (a, b) pairs, block by block in NUMERIC block order, one block resident.
    private Iterator<long[]> blockPairs(LmdbTxn t, int kind) {
        int[] order = blockOrder(t, kind);
        return new Iterator<>() {
            int bi;
            byte[] block;
            int off;
            long[] pending;

            {
                advance();
            }

            private void advance() {
                pending = null;
                while (true) {
                    if (block == null) {
                        if (bi >= order.length) return;
                        block = edgeBlocks.get(t.tx, blockKey(kind, order[bi++]));
                        off = 0;
                        continue;
                    }
                    if (off + 16 > block.length) {
                        block = null;
                        continue;
                    }
                    long a = leLong(block, off);
                    long b = leLong(block, off + 8);
                    off += 16;
                    if (a == TOMBSTONE) continue; // dead slot, kept in place; iterEdges skips it too
                    pending = new long[] {a, b};
                    return;
                }
            }

            @Override
            public boolean hasNext() {
                return pending != null;
            }

            @Override
            public long[] next() {
                if (pending == null) throw new NoSuchElementException();
                long[] r = pending;
                advance();
                return r;
            }
        };
    }

    /// The block numbers of one kind, ascending. ~2·log2(n) point reads per kind when the numbering
    /// is dense, as the format guarantees, checked against `MDB_stat`'s entry count so a file
    /// chunked some other way falls back to enumeration rather than being answered confidently.
    private int[] blockOrder(LmdbTxn t, int kind) {
        long total = edgeBlocks.stat(t.tx).entries;
        int n0 = denseCount(t, 0);
        int n1 = denseCount(t, 1);
        if (n0 + n1 == total) {
            int n = kind == 0 ? n0 : n1;
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = i;
            return a;
        }
        TreeSet<Integer> found = new TreeSet<>();
        try (CursorIterable<byte[]> ci = edgeBlocks.iterate(t.tx)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                byte[] k = kv.key();
                if (k.length != 5 || k[0] != (byte) kind) continue;
                found.add((k[1] & 0xFF) | ((k[2] & 0xFF) << 8) | ((k[3] & 0xFF) << 16) | ((k[4] & 0xFF) << 24));
            }
        }
        int[] a = new int[found.size()];
        int i = 0;
        for (int b : found) a[i++] = b;
        return a;
    }

    /// Smallest `n` with block `n` of this kind absent, by galloping binary search.
    private int denseCount(LmdbTxn t, int kind) {
        if (edgeBlocks.get(t.tx, blockKey(kind, 0)) == null) return 0;
        int lo = 0;   // present
        int hi = 1;
        while (edgeBlocks.get(t.tx, blockKey(kind, hi)) != null) {
            lo = hi;
            if (hi > Integer.MAX_VALUE / 2) return hi + 1;
            hi <<= 1;
        }
        while (hi - lo > 1) {
            int mid = lo + (hi - lo) / 2;
            if (edgeBlocks.get(t.tx, blockKey(kind, mid)) != null) lo = mid; else hi = mid;
        }
        return lo + 1;
    }

    @Override
    public Csr csr(Txn txn) {
        ensureEdgeState();
        if (cachedCsr == null) {
            int live = edgeSrc.size() - edgeDead;
            long[] src = new long[live];
            long[] dst = new long[live];
            int w = 0;
            for (int i = 0; i < edgeSrc.size(); i++) {
                long s = edgeSrc.getLong(i);
                if (s == TOMBSTONE) continue;
                src[w] = s;
                dst[w] = edgeDst.getLong(i);
                w++;
            }
            cachedCsr = Csr.build(src, dst);
        }
        return cachedCsr;
    }

    // ---------------------------------------------------------------- clone

    @Override
    public long cloneFrom(Store source, Txn sourceTxn, Txn targetTxn, LongPredicate skipFullKeys, long keyOffset) {
        checkWritable();
        if (!(source instanceof V2Store src)) {
            throw new UnsupportedOperationException(
                    "cloneFrom requires a same-format (v2) source; use copyMap for cross-format transfer");
        }
        LmdbTxn st = src.cast(sourceTxn);
        LmdbTxn tt = cast(targetTxn);
        requireSameClassTable(src);
        src.ensureEdgeState(); // this method reads src.edgeSrc/unresSrc directly, not via iterEdges
        // This copies stored fragments VERBATIM — load-side fixes never re-apply to them — so the
        // target is only as capable as this source: take the MINIMUM. Recorded BEFORE the copy, so
        // a half-finished clone is still marked.
        toolkit.prov.Provenance.mergeFrom(path, src.path());

        // Read before the copy, for the sweep marker below: a merge clones source after source into
        // one target, and each of them has to be able to retract what an earlier one earned.
        boolean targetEmpty;
        try (CursorIterable<byte[]> ci = idIdx.iterate(tt.tx)) {
            targetEmpty = !ci.iterator().hasNext();
        }
        boolean targetSwept = markerValue(tt, Marker.BLOB_REPAIR_DONE) != null;

        Set<Integer> validClassIdx = new HashSet<>();
        for (Class<?> c : src.dbNames(sourceTxn)) validClassIdx.add(src.classIdxInt(c));

        long maxLocal = 0;
        for (Class<?> c : src.dbNames(sourceTxn)) {
            int idx = src.classIdxInt(c);
            Dbi<byte[]> sd = src.classDbi(st, idx, false);
            if (sd == null) continue;
            Dbi<byte[]> td = classDbi(tt, idx, true);
            long copied = 0;
            try (CursorIterable<byte[]> ci = sd.iterate(st.tx)) {
                for (CursorIterable.KeyVal<byte[]> kv : ci) {
                    long local = NulKeyCodec.u32le(kv.key()) & 0xFFFFFFFFL;
                    long fk = NulKeyCodec.fullKey(idx, (int) local);
                    if (skipFullKeys != null && skipFullKeys.test(fk)) continue;
                    long newLocal = local + keyOffset;
                    // a key collision must fail loudly; an overwrite here would silently replace
                    // another feed's blob while its id-index row still points at it
                    if (!td.put(tt.tx, NulKeyCodec.u32le((int) newLocal), kv.val(),
                            org.lmdbjava.PutFlags.MDB_NOOVERWRITE)) {
                        throw new IllegalStateException("clone key collision: " + c.getSimpleName()
                                + " local " + newLocal + " already present (bad keyOffset or non-empty target)");
                    }
                    if (newLocal > maxLocal) maxLocal = newLocal;
                    copied++;
                }
            }
            countCloned(idx, copied);
        }
        try (CursorIterable<byte[]> ci = src.idIdx.iterate(st.tx)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                long fk = NulKeyCodec.u64le(kv.val());
                if (skipFullKeys != null && skipFullKeys.test(fk)) continue;
                if (!validClassIdx.contains(NulKeyCodec.fullKeyClassIdx(fk))) continue;
                idIdx.put(tt.tx, kv.key(), NulKeyCodec.u64le(fk + keyOffset));
            }
        }
        // An edge dropped while its REFERRER survives is the one loss no later pass can find: rows
        // are copied rather than re-derived, so the reference loses its edge without gaining an
        // unresolved row, and only the blob sweep still reaches it. Counting those is what decides
        // whether this target may inherit the source's sweep marker below.
        long orphanedRefs = 0;
        for (int i = 0; i < src.edgeSrc.size(); i++) {
            long k = src.edgeSrc.getLong(i);
            if (k == TOMBSTONE) continue;
            long v = src.edgeDst.getLong(i);
            if (skipFullKeys != null && skipFullKeys.test(k)) continue;
            if (!validClassIdx.contains(NulKeyCodec.fullKeyClassIdx(k))) continue;
            if ((skipFullKeys != null && skipFullKeys.test(v))
                    || !validClassIdx.contains(NulKeyCodec.fullKeyClassIdx(v))) {
                orphanedRefs++;
                continue;
            }
            putEdge(tt, k + keyOffset, v + keyOffset);
        }
        for (int i = 0; i < src.unresSrc.size(); i++) {
            long k = src.unresSrc.getLong(i);
            if (k == TOMBSTONE) continue;
            if (skipFullKeys != null && skipFullKeys.test(k)) continue;
            if (!validClassIdx.contains(NulKeyCodec.fullKeyClassIdx(k))) continue;
            putUnresolved(tt, k + keyOffset, src.unresKeyTable.get(src.unresKeyId.getInt(i)));
        }
        // Embedding index: key pass-through, container full_key shifted and class-filtered. The
        // source must be complete AND under the TARGET's scope — cloning rows built under a
        // different allowlist would hand this store a marker it cannot honour.
        String srcScope = src.embIdx == null ? null : src.markerValue(st, Marker.EMBEDDING_IDX_DONE);
        boolean sourceComplete = embeddingScopeTag().equals(srcScope);
        if (sourceComplete && embIdx != null) {
            try (CursorIterable<byte[]> ci = src.embIdx.iterate(st.tx)) {
                for (CursorIterable.KeyVal<byte[]> kv : ci) {
                    long fk = NulKeyCodec.u64le(kv.val());
                    if (skipFullKeys != null && skipFullKeys.test(fk)) continue;
                    if (!validClassIdx.contains(NulKeyCodec.fullKeyClassIdx(fk))) continue;
                    embIdx.put(tt.tx, kv.key(), NulKeyCodec.u64le(fk + keyOffset),
                            org.lmdbjava.PutFlags.MDB_NODUPDATA);
                }
            }
        } else {
            putMarker(tt, Marker.EMBEDDING_IDX_DONE, null);
        }
        // Blob-sweep marker, the same minimum-taking discipline as the embedding index above. The
        // sweep asks only whether a stored blob's declared class and version miss their exact
        // id-index key, and this clone reproduces both maps, so a target cloned whole from a swept
        // source is swept too. Three ways to lose it, and the AND is over all of them: an unswept
        // source; an orphaned reference, whose target this store no longer holds; and a target an
        // earlier clone already disqualified. A caller that rewires its own orphaned referrers
        // takes the marker back afterwards on its own argument -- MergeToDb does.
        boolean sourceSwept = src.markerValue(st, Marker.BLOB_REPAIR_DONE) != null;
        boolean inherit = sourceSwept && orphanedRefs == 0 && (targetEmpty || targetSwept);
        putMarker(tt, Marker.BLOB_REPAIR_DONE, inherit ? MARKER_SET : null);
        if (!inherit) {
            Log.info("[clone_db] target keeps the full blob sweep (%s)",
                    orphanedRefs > 0 ? orphanedRefs + " cloned references lost their target"
                            : !sourceSwept ? "the source carries no blob-repair marker"
                            : "an earlier clone into this target carried none");
        }
        long current = sequence(targetTxn, 0);
        if (maxLocal + 1 > current) sequence(targetTxn, (maxLocal + 1) - current);
        Log.info("[clone_db] cloned %s -> %s (max local %d)", src.path(), path, maxLocal);
        return maxLocal;
    }

    /// {@inheritDoc}
    ///
    /// The in-memory table, which is the authority on a writable store, and the same table
    /// [#iterUnresolved] hands intern ids out of.
    @Override
    protected int internedUnresolvedKeyCount() {
        ensureEdgeState();
        return unresKeyTable.size();
    }

    /// Test/debug snapshot of the block machinery:
    /// {edgeSlots, edgeDead, edgeDiskBlocks, unresSlots, unresDead, unresDiskBlocks, internTableSize}.
    public int[] debugStats() {
        ensureEdgeState();
        return new int[] {edgeSrc.size(), edgeDead, edgeDiskBlocks,
                unresSrc.size(), unresDead, unresDiskBlocks, unresKeyTable.size()};
    }
}
