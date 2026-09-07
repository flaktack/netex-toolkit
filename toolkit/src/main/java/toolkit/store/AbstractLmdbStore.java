package toolkit.store;

import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.Cursor;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.lmdbjava.GetOp;
import org.lmdbjava.PutFlags;
import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.EntityStructure;
import toolkit.codec.FragmentCodec;
import toolkit.graph.Csr;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.ModelRegistry;
import toolkit.model.RecursiveAttributes;
import toolkit.util.Conf;
import toolkit.util.Log;
import toolkit.util.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/// Shared lmdbjava machinery of the store: env/txn lifecycle, the per-db class registry
/// (name/idx/Class maps restored from the stored table), the id index and the insert path with the
/// RecursiveAttributes reference extraction. Format-specific pieces (edge/unresolved storage, meta
/// layout, clone) are abstract.
///
/// Storage facts, shared with the v2 format:
///
/// - class maps are PLAIN byte-ordered dbs keyed by 4-byte LE local key, NOT INTEGERKEY;
/// - class-map NAMES are the raw 2-byte LE class idx. LMDB db names are C strings, so a trailing
///    0x00 is truncated — harmless — but an idx that is a MULTIPLE of 256 would truncate to the
///    empty root name; those, and the reserved 0xFFF0+ marker range, are skipped at registration;
/// - `_id_idx`: NulKeyCodec key -&gt; 8-byte LE full_key;
/// - `_meta`: the local-key sequence row, LMDB having no native sequences, plus the format marker
///    and class table.
public abstract class AbstractLmdbStore implements Store {

    protected static final byte[] SEQ_KEY = "seq".getBytes(StandardCharsets.UTF_8);

    protected final Path path;
    protected final boolean readonly;
    protected final Env<byte[]> env;
    protected final FragmentCodec codec = new FragmentCodec();

    protected final Map<Class<?>, Integer> classToIdx = new HashMap<>();
    protected final Map<Integer, Class<?>> idxToClass = new HashMap<>();
    protected final Map<String, Integer> nameToIdx = new HashMap<>();

    protected Dbi<byte[]> idIdx;
    protected Dbi<byte[]> meta;
    /// Persisted scoped embedding index: encoded embedded key -&gt; container full_key. Null on old
    /// dbs.
    protected Dbi<byte[]> embIdx;
    private final Map<Integer, Dbi<byte[]>> classDbis = new HashMap<>();
    private final Set<Integer> existingClassMaps = new HashSet<>();

    /// -Dtoolkit.embIndex=false: fresh stores set no completeness marker and inserts skip index rows.
    protected static final boolean EMB_INDEX_ENABLED = Conf.on(Conf.EMB_INDEX);

    /// Class-scoped duplicate-merge policies (see [Store#setMergePolicy]); session state, empty by default.
    private final Map<Class<?>, Store.MergeFn> mergePolicies = new HashMap<>();

    /// Which classes get an embedding-index row, injected at construction.
    ///
    /// `classNames` is curated, and [AbstractLmdbStore#inEmbeddingScope] matches it against the
    /// JAXB SIMPLE NAME of the object the parser built — not always the profile's element name,
    /// which is why a list may need entries that look redundant. The other half is the generative
    /// `TypeOf*`-but-not-`*Ref` rule, versioned by [#RULE].
    ///
    /// A scope is a value and [#tag()] is its identity on disk. The tag must change whenever the
    /// answer to "is this class indexed?" changes in either half; the completion marker guarantees
    /// nothing otherwise.
    public record EmbeddingScope(Set<String> classNames) {

        /// Version of the generative half. Bump it when the `TypeOf*` predicate changes shape;
        /// the curated half needs no bump, since [#tag()] carries the names themselves.
        private static final String RULE = "typeOfNonRef/1";

        public EmbeddingScope {
            classNames = Set.copyOf(classNames);
        }

        /// Does this scope cover a class of that JAXB simple name? Whether the store can SEE such a
        /// class at all is `inEmbeddingScope`'s question, not this one's.
        public boolean admits(String simpleName) {
            return classNames.contains(simpleName)
                    || (simpleName.startsWith("TypeOf") && !simpleName.endsWith("Ref"));
        }

        /// The identity of this scope, stored as the completion marker's value: an index built
        /// under a widened allowlist reads as incomplete and degrades to the corpus scan.
        public String tag() {
            List<String> names = new ArrayList<>(classNames);
            names.sort(Comparator.naturalOrder());
            return RULE + "|" + String.join(",", names);
        }
    }

    /// This store's scope. Never null; the factory supplies the profile's when a caller does not.
    protected final EmbeddingScope embScope;

    /// The scope this store indexes and marks under — which need not be the profile default.
    public final EmbeddingScope embeddingScope() {
        return embScope;
    }

    /// The default profile's curated names, i.e. `Stores.NETEX_EMBEDDING_SCOPE`'s. What a given
    /// store is indexed under is [#embeddingScope()]; the two differ exactly when a caller injected
    /// a scope.
    public static Set<String> embeddingIndexClassNames() {
        return Stores.NETEX_EMBEDDING_SCOPE.classNames();
    }

    protected final boolean inEmbeddingScope(Class<?> clazz) {
        String name = clazz.getSimpleName();
        if (!nameToIdx.containsKey(name)) return false;
        return embScope.admits(name);
    }

    /// This store's scope identity — what its completion marker must hold for the index to be used.
    public final String embeddingScopeTag() {
        return embScope.tag();
    }

    /// The two persisted one-shot markers: the 0xFFFE / 0xFFFD rows in DB_CLASS_IDX.
    protected enum Marker { BLOB_REPAIR_DONE, EMBEDDING_IDX_DONE }

    /// Marker value, or null when the marker is absent. Pre-scope stores return `"1"`.
    protected abstract String markerValue(LmdbTxn t, Marker m);

    /// Write `value`, or delete the marker when `value` is null.
    protected abstract void putMarker(LmdbTxn t, Marker m, String value);

    /// The value a marker that carries no scope of its own is written with.
    protected static final String MARKER_SET = "1";

    @Override
    public boolean blobRepairMarkerPresent(Txn txn) {
        return markerValue(cast(txn), Marker.BLOB_REPAIR_DONE) != null;
    }

    @Override
    public void setBlobRepairMarker(Txn txn) {
        checkWritable();
        putMarker(cast(txn), Marker.BLOB_REPAIR_DONE, MARKER_SET);
    }

    protected AbstractLmdbStore(Path path, boolean readonly, long mapSize, int maxDbs,
                                EmbeddingScope embScope) {
        this.path = path;
        this.readonly = readonly;
        this.embScope = Objects.requireNonNull(embScope, "embeddingScope");
        boolean exists = Files.exists(path.resolve("data.mdb"));
        if (readonly && !exists) {
            throw new IllegalArgumentException("cannot open storage readonly: " + path + " does not exist");
        }
        try {
            if (!readonly) Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create " + path, e);
        }
        EnvFlags[] flags = readonly
                ? new EnvFlags[] {EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS}
                : new EnvFlags[] {EnvFlags.MDB_NOTLS};
        this.env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(mapSize)
                .setMaxDbs(maxDbs)
                .setMaxReaders(126)
                .open(path.toFile(), flags);
    }

    /// Called by subclasses at the end of their constructor.
    protected final void initTables(boolean freshDatabase) {
        if (freshDatabase) {
            if (readonly) throw new IllegalStateException("fresh database cannot be readonly");
            populateClassTable();
        }
        restoreClassTable();
        // AFTER restoreClassTable, so nothing is written to a database that turns out not to be
        // one of ours: the format check is the gate, and it throws.
        if (!freshDatabase && !readonly) retractForeignScopeMarker();
        openExistingClassMaps();
    }

    /// A writable open of a store whose index was built under a different scope drops that store's
    /// completeness marker, before any insert can run: a marker survives only while every row
    /// behind it was written under the scope it names, and inserts feed the index through THIS
    /// store's scope while touching no marker. The bare `"1"` of a pre-scope store is cleared too.
    private void retractForeignScopeMarker() {
        try (LmdbTxn t = (LmdbTxn) rwTxn()) {
            String stored = markerValue(t, Marker.EMBEDDING_IDX_DONE);
            String want = embeddingScopeTag();
            if (stored != null && !stored.equals(want)) {
                Log.warn("[store] %s: embedding index was built for another scope and this store is "
                        + "opened for writing; dropping its completeness marker (stored %s, want %s)",
                        path, stored, want);
                putMarker(t, Marker.EMBEDDING_IDX_DONE, null);
            }
            t.commit();
        }
    }

    /// Open every class map the database already holds in ONE committing transaction, before this
    /// constructor returns and therefore before any caller's transaction exists.
    ///
    /// `mdb_dbi_open` publishes a handle to the ENVIRONMENT only when the opening transaction
    /// commits, and this hits both consequences:
    ///
    /// - a handle opened inside a READ txn dies with it — lmdbjava's `Txn.close()` aborts, closing
    ///    the handle and recycling its slot — while the cache outlives the txn;
    /// - a handle published AFTER a transaction began is invisible to it: a txn snapshots the dbi
    ///    count at start.
    ///
    /// Opening once, up front, makes a class handle valid in every transaction of this store's
    /// lifetime, which is what a cached `Dbi` is assumed to be.
    ///
    /// Names come from `getDbiNames()` verbatim, not [#idxToMapName], so a map whose stored name
    /// does not round-trip through the idx codec is still opened.
    private void openExistingClassMaps() {
        List<byte[]> names = new ArrayList<>();
        for (byte[] name : env.getDbiNames()) {
            if (name.length <= 2) names.add(name);
        }
        if (names.isEmpty()) return;
        try (org.lmdbjava.Txn<byte[]> t = readonly ? env.txnRead() : env.txnWrite()) {
            for (byte[] name : names) {
                int idx = mapNameToIdx(name);
                existingClassMaps.add(idx);
                classDbis.put(idx, env.openDbi(t, name, null, false, new DbiFlags[] {}));
            }
            t.commit(); // MDB_END_UPDATE — this is what publishes the handles; do not drop it
        }
    }

    /// Write the class table (and format marker) of a fresh database.
    protected abstract void populateClassTable();

    /// Read the class table into classToIdx/idxToClass/nameToIdx; verify the format marker.
    protected abstract void restoreClassTable();

    // ---------------------------------------------------------------- registry helpers

    /// Deterministic idx assignment for a fresh database: registry order (sorted simple names),
    /// starting at 1, skipping multiples of 256 (empty C-string map name) and the reserved
    /// 0xFFF0+ marker range.
    protected static LinkedHashMap<String, Integer> assignIdx() {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        int idx = 1;
        for (Class<?> c : ModelRegistry.classes()) {
            while (idx % 256 == 0 || idx >= 0xFFF0) idx++;
            out.put(c.getSimpleName(), idx++);
        }
        return out;
    }

    protected final void register(int idx, String name) {
        Class<?> clazz = ModelRegistry.classForName(name);
        nameToIdx.put(name, idx);
        if (clazz != null) {
            idxToClass.put(idx, clazz);
            classToIdx.putIfAbsent(clazz, idx);
        }
    }

    @Override
    public byte[] classIdx(Class<?> clazz) {
        return NulKeyCodec.classIdxBytes(classIdxInt(clazz));
    }

    @Override
    public int classIdxInt(Class<?> clazz) {
        Integer idx = classToIdx.get(clazz);
        if (idx == null) {
            Integer byName = nameToIdx.get(clazz.getSimpleName());
            if (byName != null) return byName;
            throw new IllegalArgumentException("class not registered: " + clazz.getName());
        }
        return idx;
    }

    protected final int idxForName(String name) {
        Integer idx = nameToIdx.get(name);
        if (idx != null) return idx;
        Class<?> c = ModelRegistry.classForName(name); // resolves the DataManagedObject alias
        if (c != null) {
            idx = nameToIdx.get(c.getSimpleName());
            if (idx != null) return idx;
        }
        throw new IllegalArgumentException("class name not registered: " + name);
    }

    @Override
    public Class<?> classForIdx(int idx) {
        return idxToClass.get(idx);
    }

    @Override
    public Class<?> classForName(String name) {
        Integer idx = nameToIdx.get(name);
        return idx == null ? null : idxToClass.get(idx);
    }

    // ---------------------------------------------------------------- txn handling

    public final class LmdbTxn implements Txn {
        public final org.lmdbjava.Txn<byte[]> tx;
        final boolean ro;
        boolean committed;
        final List<Integer> createdClassDbis = new ArrayList<>();

        LmdbTxn(org.lmdbjava.Txn<byte[]> tx, boolean ro) {
            this.tx = tx;
            this.ro = ro;
        }

        @Override
        public void commit() {
            beforeCommit(this);
            tx.commit();
            committed = true;
            afterCommit(this);
        }

        @Override
        public boolean isReadOnly() {
            return ro;
        }

        AbstractLmdbStore owner() {
            return AbstractLmdbStore.this;
        }

        @Override
        public void close() {
            if (!committed && !ro) {
                // aborted write txn: evict dbi handles created inside it and let the subclass
                // restore any in-memory state it staged for this txn
                tx.close();
                for (Integer idx : createdClassDbis) {
                    classDbis.remove(idx);
                    existingClassMaps.remove(idx);
                }
                onAbort(this);
            } else {
                tx.close();
            }
        }
    }

    @Override
    public Txn roTxn() {
        return new LmdbTxn(env.txnRead(), true);
    }

    @Override
    public Txn rwTxn() {
        checkWritable();
        return new LmdbTxn(env.txnWrite(), false);
    }

    protected final LmdbTxn cast(Txn txn) {
        LmdbTxn t = (LmdbTxn) txn;
        if (t.owner() != this) {
            // A txn is only valid against the store that created it; a foreign txn's dbi handle
            // numbers would silently address the wrong maps in this env.
            throw new IllegalArgumentException(
                    "txn belongs to another store: " + t.owner().path() + " (this store: " + path + ")");
        }
        return t;
    }

    protected final void checkWritable() {
        if (readonly) throw new IllegalStateException("storage is opened readonly");
    }

    /// Clone precondition: raw-copied keys and full_keys embed class idxs, so the two stores'
    /// class tables must match exactly (they diverge only across toolkit versions).
    protected final void requireSameClassTable(AbstractLmdbStore src) {
        if (!nameToIdx.equals(src.nameToIdx)) {
            throw new IllegalStateException("class-idx tables differ between clone source ("
                    + src.path + ") and target (" + path + ")");
        }
    }

    /// Format hook: v2 flushes in-memory edge state to blocks here.
    protected void beforeCommit(LmdbTxn txn) {}

    /// Format hook, called only after the LMDB commit SUCCEEDED: v2 clears its dirty flags here,
    /// so a failed commit (ENOSPC, MAP_FULL) still routes through [#onAbort] and reloads.
    protected void afterCommit(LmdbTxn txn) {}

    /// Format hook: v2 reloads in-memory edge state after an aborted write txn.
    protected void onAbort(LmdbTxn txn) {}

    // ---------------------------------------------------------------- class map dbis

    protected static byte[] idxToMapName(int idx) {
        return NulKeyCodec.classIdxBytes(idx);
    }

    /// Reverse of the C-string-truncated map name (1 byte when idx &lt; 256, else 2 bytes LE).
    protected static int mapNameToIdx(byte[] name) {
        if (name.length == 1) return name[0] & 0xFF;
        return NulKeyCodec.classIdxInt(name);
    }

    /// The class map dbi; null when absent and !create. Created maps register with the txn for
    /// abort-eviction.
    ///
    /// An EXISTING map can only take the first branch — [#initTables] opened it already. Only a map
    /// this session CREATES is opened here, and it must be opened on `t`, so it disappears with `t`
    /// if that write txn aborts.
    protected final Dbi<byte[]> classDbi(LmdbTxn t, int idx, boolean create) {
        Dbi<byte[]> d = classDbis.get(idx);
        if (d != null) return d;
        boolean present = existingClassMaps.contains(idx);
        if (!present && !create) return null;
        DbiFlags[] flags = (!present && create)
                ? new DbiFlags[] {DbiFlags.MDB_CREATE}
                : new DbiFlags[] {};
        d = env.openDbi(t.tx, idxToMapName(idx), null, false, flags);
        classDbis.put(idx, d);
        if (!present) {
            existingClassMaps.add(idx);
            t.createdClassDbis.add(idx);
        }
        return d;
    }

    @Override
    public List<Class<?>> dbNames(Txn txn) {
        List<Class<?>> out = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>(existingClassMaps);
        idxs.sort(Comparator.naturalOrder());
        for (int idx : idxs) {
            Class<?> c = idxToClass.get(idx);
            if (c != null) out.add(c);
        }
        return out;
    }

    // ---------------------------------------------------------------- reads

    @Override
    public Iterable<ObjectRow> iterObjects(Txn txn, Class<?> clazz) {
        LmdbTxn t = cast(txn);
        int idx = classIdxInt(clazz);
        Dbi<byte[]> d = classDbi(t, idx, false);
        if (d == null) return List.of();
        return () -> new RowIterator<>(d.iterate(t.tx), kv -> new ObjectRow(kv.key(), codec.decode(kv.val(), clazz)));
    }

    @Override
    public Iterable<Object> iterOnlyObjects(Txn txn, Class<?> clazz) {
        LmdbTxn t = cast(txn);
        int idx = classIdxInt(clazz);
        Dbi<byte[]> d = classDbi(t, idx, false);
        if (d == null) return List.of();
        return () -> new RowIterator<>(d.iterate(t.tx), kv -> codec.decode(kv.val(), clazz));
    }

    @Override
    public Iterable<byte[]> scanObjects(Txn txn, Class<?> clazz) {
        LmdbTxn t = cast(txn);
        int idx = classIdxInt(clazz);
        Dbi<byte[]> d = classDbi(t, idx, false);
        if (d == null) return List.of();
        return () -> new RowIterator<>(d.iterate(t.tx), CursorIterable.KeyVal::key);
    }

    @Override
    public Iterable<RawRow> iterRawObjects(Txn txn, Class<?> clazz) {
        LmdbTxn t = cast(txn);
        Dbi<byte[]> d = classDbi(t, classIdxInt(clazz), false);
        if (d == null) return List.of();
        return () -> new RowIterator<>(d.iterate(t.tx), kv -> new RawRow(kv.key(), kv.val()));
    }

    @Override
    public byte[] loadRawObject(Txn txn, Class<?> clazz, byte[] localKey) {
        LmdbTxn t = cast(txn);
        Dbi<byte[]> d = classDbi(t, classIdxInt(clazz), false);
        return d == null ? null : d.get(t.tx, localKey);
    }

    /// Lazy cursor-backed iterator; closes the cursor at exhaustion. A consumer that stops EARLY
    /// (break, exception, existence probe) must call [#close()] — an abandoned read-txn
    /// cursor is never freed by LMDB. Consuming to exhaustion closes automatically.
    public static final class RowIterator<R> implements Iterator<R>, AutoCloseable {
        public interface Fn<R> {
            R apply(CursorIterable.KeyVal<byte[]> kv);
        }

        private final CursorIterable<byte[]> ci;
        private final Iterator<CursorIterable.KeyVal<byte[]>> inner;
        private final Fn<R> fn;
        private boolean closed;

        public RowIterator(CursorIterable<byte[]> ci, Fn<R> fn) {
            this.ci = ci;
            this.inner = ci.iterator();
            this.fn = fn;
        }

        @Override
        public boolean hasNext() {
            if (closed) return false;
            boolean h = inner.hasNext();
            if (!h) close();
            return h;
        }

        @Override
        public R next() {
            if (!hasNext()) throw new NoSuchElementException();
            return fn.apply(inner.next());
        }

        @Override
        public void close() {
            if (!closed) {
                ci.close();
                closed = true;
            }
        }
    }

    @Override
    public Object loadObject(Txn txn, Class<?> clazz, byte[] localKey) {
        LmdbTxn t = cast(txn);
        Dbi<byte[]> d = classDbi(t, classIdxInt(clazz), false);
        if (d == null) return null;
        byte[] value = d.get(t.tx, localKey);
        return value == null ? null : codec.decode(value, clazz);
    }

    @Override
    public Object loadObjectByFullKey(Txn txn, long fullKey) {
        Class<?> clazz = idxToClass.get(NulKeyCodec.fullKeyClassIdx(fullKey));
        if (clazz == null) return null;
        return loadObject(txn, clazz, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fullKey)));
    }

    @Override
    public long lookupFullKey(Txn txn, String id, String version, Class<?> clazz) {
        LmdbTxn t = cast(txn);
        byte[] idxB = classIdx(clazz);
        if (version != null) {
            byte[] key = NulKeyCodec.encodeKey(id, version, idxB, true);
            byte[] fk = idIdx.get(t.tx, key);
            return fk == null ? -1 : NulKeyCodec.u64le(fk);
        }
        byte[] prefix = NulKeyCodec.encodePrefix(id, null, null, false);
        try (Cursor<byte[]> c = idIdx.openCursor(t.tx)) {
            if (c.get(prefix, GetOp.MDB_SET_RANGE) && NulKeyCodec.startsWith(c.key(), prefix)) {
                return NulKeyCodec.u64le(c.val());
            }
        }
        return -1;
    }

    /// [Store#lookupById] on a prefix cursor rather than the interface default's whole-index
    /// filter. Same rows, same order, same bytes: a byte-ordered `_id_idx` keeps an id's rows
    /// contiguous.
    @Override
    public List<IdRow> lookupById(Txn txn, String id, String version) {
        LmdbTxn t = cast(txn);
        byte[] prefix = NulKeyCodec.encodePrefix(id, version, null, false);
        List<IdRow> out = new ArrayList<>();
        try (Cursor<byte[]> c = idIdx.openCursor(t.tx)) {
            if (c.get(prefix, GetOp.MDB_SET_RANGE)) {
                do {
                    byte[] k = c.key();
                    if (!NulKeyCodec.startsWith(k, prefix)) break;
                    out.add(new IdRow(k.clone(), NulKeyCodec.u64le(c.val())));
                } while (c.next());
            }
        }
        return out;
    }

    @Override
    public Object loadObjectByReference(Txn txn, noi.netex.model.VersionOfObjectRefStructure ref,
                                        boolean missingOk) {
        LmdbTxn t = cast(txn);
        byte[] classTrailer = null;
        // Optimal path: a declared nameOfRefClass gives the exact (id, version, class) key. The
        // JAXB model leaves the attribute null whenever the XML omitted it, so derive it through
        // the SAME chain the insert path uses.
        String declared = ref.getNameOfRefClass();
        if (declared == null) {
            // isRegistered guard: an unregistered derived name (VersionOfObject, from the bare base
            // class) leaves `declared` null, i.e. behaves as if nameOfRefClass were absent.
            String derived = RecursiveAttributes.deriveTargetName(ref.getClass());
            if (ModelRegistry.isRegistered(derived)) declared = derived;
        }
        if (declared != null) {
            Integer idx = nameToIdx.get(declared);
            if (idx == null) {
                Class<?> c = ModelRegistry.classForName(declared);
                if (c != null) idx = nameToIdx.get(c.getSimpleName());
            }
            if (idx == null) {
                Log.warn("[load_object_by_reference] unknown NameOfClass %s on ref %s", declared, ref.getRef());
            } else {
                classTrailer = NulKeyCodec.classIdxBytes(idx);
                byte[] key = NulKeyCodec.encodeKey(ref.getRef(), ref.getVersion(), classTrailer, true);
                byte[] fk = idIdx.get(t.tx, key);
                if (fk != null) return loadObjectByFullKey(txn, NulKeyCodec.u64le(fk));
            }
        }
        // Fallback prefix scan: prefer the row whose class trailer matches the declared class,
        // else the first (smallest) row for the bare id wins, deterministically.
        //
        // Deterministic is not correct: with a null classTrailer the loop returns the smallest
        // id-index row for the bare id, ignoring the requested class and version, so a reference
        // can be answered with the wrong type or version. The choice must not change — byte
        // identity of every store built through this path depends on it.
        long seen = REF_FALLBACKS.sum();
        REF_FALLBACKS.increment();
        if (seen < REF_FALLBACK_LOG_CAP) {
            Log.warn("[load_object_by_reference] fallback prefix-scan for ref %s", ref.getRef());
        } else if (seen == REF_FALLBACK_LOG_CAP) {
            Log.warn("[load_object_by_reference] …%d fallback prefix-scans logged; further ones are "
                    + "counted only (-Dtoolkit.store.refFallbackLogCap=N)", REF_FALLBACK_LOG_CAP);
        }
        byte[] prefix = NulKeyCodec.encodePrefix(ref.getRef(), null, null, false);
        long resolved = -1;
        try (Cursor<byte[]> c = idIdx.openCursor(t.tx)) {
            if (c.get(prefix, GetOp.MDB_SET_RANGE)) {
                do {
                    if (!NulKeyCodec.startsWith(c.key(), prefix)) break;
                    if (classTrailer != null && NulKeyCodec.endsWith(c.key(), classTrailer)) {
                        resolved = NulKeyCodec.u64le(c.val());
                        break;
                    }
                    if (resolved == -1) resolved = NulKeyCodec.u64le(c.val());
                } while (c.next());
            }
        }
        if (resolved != -1) return loadObjectByFullKey(txn, resolved);
        if (missingOk) return null;
        throw new IllegalStateException("Can't load element from ref " + ref.getRef());
    }

    // ------------------------------------------------- the reference-fallback aggregate

    /// How many references [#loadObjectByReference] could not answer from an exact
    /// (id, version, class) key and had to resolve by prefix scan, in this JVM. Static: the
    /// question is about the RUN, not one store handle.
    private static final java.util.concurrent.atomic.LongAdder REF_FALLBACKS =
            new java.util.concurrent.atomic.LongAdder();

    /// Per-reference warnings emitted before the log falls silent and only the total is kept.
    private static final int REF_FALLBACK_LOG_CAP =
            Conf.intOf(Conf.STORE_REF_FALLBACK_LOG_CAP);

    /// Every fallback prefix scan counted in this JVM.
    public static long refFallbacks() {
        return REF_FALLBACKS.sum();
    }

    /// Drop the counter. For tests, and for a tool measuring one sweep at a time.
    public static void resetRefFallbacks() {
        REF_FALLBACKS.reset();
    }

    /// The per-run line; a clean run still says so in one INFO line. Distinct from
    /// `UNRESOLVED_REFS`, which counts references that matched nothing: these matched something,
    /// possibly of the wrong class and version, and returned it.
    public static void reportRefFallbacks(String tag) {
        long n = REF_FALLBACKS.sum();
        if (n == 0) {
            Log.info("[%s] reference fallback scans: 0 (every reference resolved on its exact "
                     + "(id, version, class) key)", tag);
            return;
        }
        Strictness.require(Strictness.REF_FALLBACK, "refFallback", n, tag,
                "references did not resolve on their exact (id, version, class) key and were "
                + "answered by a prefix scan that ignores the requested class and version");
    }

    @Override
    public Iterable<IdRow> iterIdIndex(Txn txn) {
        LmdbTxn t = cast(txn);
        return () -> new RowIterator<>(idIdx.iterate(t.tx),
                kv -> new IdRow(kv.key(), NulKeyCodec.u64le(kv.val())));
    }

    @Override
    public long lookupFullKeyRaw(Txn txn, byte[] encodedIdKey) {
        LmdbTxn t = cast(txn);
        byte[] fk = idIdx.get(t.tx, encodedIdKey);
        return fk == null ? -1 : NulKeyCodec.u64le(fk);
    }

    // ---------------------------------------------------------------- format-specific hooks

    /// Add one resolved reference edge (referrer -&gt; target); duplicate pairs collapse.
    protected abstract void putEdge(LmdbTxn t, long referrerFk, long targetFk);

    /// Add one unresolved reference (referrer -&gt; encoded target key).
    protected abstract void putUnresolved(LmdbTxn t, long referrerFk, byte[] encodedTarget);

    /// Delete one unresolved pair; missing rows are ignored.
    protected abstract void deleteUnresolved(LmdbTxn t, long referrerFk, byte[] encodedTarget);

    /// Drop every outward-edge and unresolved row keyed by this referrer (overwrite semantics).
    protected abstract void clearRefRows(LmdbTxn t, long fullKey);

    /// Drop every outward/unresolved row whose REFERRER belongs to this class (insertObjects empty=true).
    protected abstract void purgeReferrerRows(LmdbTxn t, int classIdx);

    // ---------------------------------------------------------------- insert path

    @Override
    public void insertAnyObjects(Txn txn, Iterator<?> objects) {
        LmdbTxn t = cast(txn);
        checkWritable();
        if (!LOAD_PROFILE) {
            while (objects.hasNext()) {
                Object item = objects.next();
                byte[] fragment = null;
                Object obj = item;
                if (item instanceof WithFragment wf) {
                    obj = wf.object();
                    fragment = wf.fragmentXml();
                }
                insertOne(t, obj, fragment);
            }
            return;
        }
        while (true) {
            long t0 = System.nanoTime();
            if (!objects.hasNext()) {
                profPullNanos += System.nanoTime() - t0;
                break;
            }
            Object item = objects.next();
            long t1 = System.nanoTime();
            byte[] fragment = null;
            Object obj = item;
            if (item instanceof WithFragment wf) {
                obj = wf.object();
                fragment = wf.fragmentXml();
            }
            insertOne(t, obj, fragment);
            long t2 = System.nanoTime();
            profPullNanos += t1 - t0;
            profInsertNanos += t2 - t1;
            if (++profObjects % LOAD_PROFILE_EVERY == 0) logLoadProfile("running");
        }
    }

    @Override
    public void insertObjects(Txn txn, Class<?> clazz, Iterator<?> objects, boolean empty) {
        LmdbTxn t = cast(txn);
        checkWritable();
        int idx = classIdxInt(clazz);
        if (empty) {
            Dbi<byte[]> d = classDbi(t, idx, true);
            d.drop(t.tx, false);
            purgeIdRows(t, idx);
            purgeReferrerRows(t, idx);
        }
        insertAnyObjects(txn, objects);
    }

    /// Delete id-index rows pointing into this class.
    private void purgeIdRows(LmdbTxn t, int classIdx) {
        List<byte[]> stale = new ArrayList<>();
        try (CursorIterable<byte[]> ci = idIdx.iterate(t.tx)) {
            for (CursorIterable.KeyVal<byte[]> kv : ci) {
                if (NulKeyCodec.fullKeyClassIdx(NulKeyCodec.u64le(kv.val())) == classIdx) {
                    stale.add(kv.key());
                }
            }
        }
        for (byte[] k : stale) idIdx.delete(t.tx, k);
    }

    // ---------------------------------------------------------------- what this run wrote

    /// Objects inserted this run, indexed by class idx; grown on demand, because the class table is
    /// not fully populated until the first insert of each class.
    ///
    /// Per store, and not reset by a commit: it answers "what did this stage write", and a stage
    /// may commit many times. Unsynchronised, so safe only while the single-writer discipline holds
    /// — one rw txn at a time, this store owning LMDB's writer lock.
    private long[] insertedByIdx = new long[0];

    /// Rows copied VERBATIM by `cloneFrom`, indexed by class idx. Counted apart from inserts
    /// because `cloneFrom` writes straight to the class dbi and never reaches `insertOne`.
    private long[] clonedByIdx = new long[0];

    private void countInserted(int idx) {
        if (idx >= insertedByIdx.length) {
            insertedByIdx = java.util.Arrays.copyOf(insertedByIdx, Math.max(idx + 1, 64));
        }
        insertedByIdx[idx]++;
    }

    /// Called by the format's clone path, which does not go through [#insertOne].
    protected final void countCloned(int idx, long n) {
        if (idx >= clonedByIdx.length) {
            clonedByIdx = java.util.Arrays.copyOf(clonedByIdx, Math.max(idx + 1, 64));
        }
        clonedByIdx[idx] += n;
    }

    /// Objects inserted this run, by class name, highest first, zero-count classes omitted. For an
    /// in-place fix this is the only measure of what ran: it re-emits objects under their existing
    /// (id, version), so a before/after row count is flat however much was rewritten.
    public java.util.LinkedHashMap<String, Long> insertedByClass() {
        return byClass(insertedByIdx);
    }

    /// Rows copied verbatim this run, by class name, highest first. See [#clonedByIdx].
    public java.util.LinkedHashMap<String, Long> clonedByClass() {
        return byClass(clonedByIdx);
    }

    /// Rows copied verbatim this run, across every class.
    public long clonedTotal() {
        long n = 0;
        for (long v : clonedByIdx) n += v;
        return n;
    }

    private java.util.LinkedHashMap<String, Long> byClass(long[] tally) {
        java.util.List<java.util.Map.Entry<String, Long>> rows = new ArrayList<>();
        for (int i = 0; i < tally.length; i++) {
            if (tally[i] == 0) continue;
            Class<?> c = classForIdx(i);
            rows.add(java.util.Map.entry(c == null ? "idx:" + i : c.getSimpleName(), tally[i]));
        }
        rows.sort(java.util.Comparator.<java.util.Map.Entry<String, Long>>comparingLong(
                java.util.Map.Entry::getValue).reversed()
                .thenComparing(java.util.Map.Entry::getKey));
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>();
        for (var e : rows) out.put(e.getKey(), e.getValue());
        return out;
    }

    /// Total objects inserted this run, across every class.
    public long insertedTotal() {
        long n = 0;
        for (long v : insertedByIdx) n += v;
        return n;
    }

    private void insertOne(LmdbTxn t, Object obj, byte[] fragment) {
        if (!(obj instanceof EntityStructure entity) || entity.getId() == null) {
            throw new IllegalArgumentException("insert requires a model object with an id, got "
                    + (obj == null ? "null" : obj.getClass().getName()));
        }
        Class<?> clazz = obj.getClass();
        int idx = classIdxInt(clazz);
        countInserted(idx);
        String version = (obj instanceof EntityInVersionStructure eiv) ? eiv.getVersion() : null;
        byte[] myId = NulKeyCodec.encodeKey(entity.getId(), version, NulKeyCodec.classIdxBytes(idx), true);

        byte[] existing = idIdx.get(t.tx, myId);
        long fullKey;
        int local;
        if (existing != null) {
            fullKey = NulKeyCodec.u64le(existing);
            local = NulKeyCodec.fullKeyLocal(fullKey);
            // The replacing object's references are re-extracted below: clear BOTH index sides
            // (a stale unresolved row would be re-wired onto this full_key by the next resolve()).
            clearRefRows(t, fullKey);
        } else {
            local = (int) sequence(t, 1);
            fullKey = NulKeyCodec.fullKey(idx, local);
        }

        // Class-scoped duplicate-merge policy: decode the stored copy and let the policy combine
        // the two. A non-null result replaces the incoming copy for everything below, and its
        // source fragment no longer describes the object; a null result leaves the incoming copy
        // standing, fragment kept.
        //
        // MUST run BEFORE the extraction below. clearRefRows above has tombstoned the stored copy's
        // edge/unresolved rows, so walking the PRE-merge copy would leave the edge index describing
        // the incoming copy while the blob describes the union.
        boolean mergedBlob = false;
        Store.MergeFn mergeFn = mergePolicies.get(clazz);
        if (mergeFn != null && existing != null) {
            byte[] stored = classDbi(t, idx, true).get(t.tx, NulKeyCodec.u32le(local));
            if (stored != null) {
                Object merged = mergeFn.merge(codec.decode(stored, clazz), obj);
                if (merged != null) {
                    obj = merged;
                    mergedBlob = true;
                }
            }
        }

        // One fused walk for references and scoped embedded ids.
        boolean feedIndex = EMB_INDEX_ENABLED && embIdx != null;
        long walkT0 = LOAD_PROFILE ? System.nanoTime() : 0L;
        RecursiveAttributes.Extraction ex = RecursiveAttributes.refsAndEmbeddings(
                obj, feedIndex ? this::inEmbeddingScope : null);
        if (LOAD_PROFILE) {
            profWalkNanos += System.nanoTime() - walkT0;
            profRefs += ex.references().size();
            profEmbs += ex.embedded().size();
        }
        // A reference whose target is an EMBEDDED child misses here and lands in the unresolved map:
        // the id index holds first-class rows, and an embedded object has none. Wiring it to its
        // container is EmbeddingResolver's job and must not be attempted here — this path sees the
        // store as of THIS insert, so it cannot tell "no row" from "no row yet".
        for (RecursiveAttributes.Ref ref : ex.references()) {
            int targetIdx = idxForName(ref.targetClassName());
            byte[] unresolvedValue = NulKeyCodec.encodeKey(
                    ref.ref(), ref.version(), NulKeyCodec.classIdxBytes(targetIdx), true);
            byte[] resolved = idIdx.get(t.tx, unresolvedValue);
            if (resolved != null) {
                putEdge(t, fullKey, NulKeyCodec.u64le(resolved));
            } else {
                putUnresolved(t, fullKey, unresolvedValue);
            }
        }
        if (feedIndex) {
            for (EntityStructure emb : ex.embedded()) {
                String embVersion = (emb instanceof EntityInVersionStructure eiv) ? eiv.getVersion() : null;
                byte[] embKey = NulKeyCodec.encodeKey(emb.getId(), embVersion,
                        NulKeyCodec.classIdxBytes(nameToIdx.get(emb.getClass().getSimpleName())), true);
                // DUPSORT pair set: an identical (key, container) pair collapses.
                embIdx.put(t.tx, embKey, NulKeyCodec.u64le(fullKey), PutFlags.MDB_NODUPDATA);
            }
        }

        long encT0 = LOAD_PROFILE ? System.nanoTime() : 0L;
        byte[] value = (fragment != null && !mergedBlob) ? codec.encodeRaw(fragment) : codec.encode(obj);
        if (LOAD_PROFILE) profEncodeNanos += System.nanoTime() - encT0;
        classDbi(t, idx, true).put(t.tx, NulKeyCodec.u32le(local), value);
        idIdx.put(t.tx, myId, NulKeyCodec.u64le(fullKey));
    }

    // ------------------------------------------------------------ load profile

    /// `-Dtoolkit.load.profile=true` — split the insert path's wall four ways. Folded away when off.
    ///
    /// - **pull** — inflate, StAX parse, capture, tee, unmarshal;
    /// - **walk** — reference and embedding extraction, a pure function of the bean;
    /// - **encode** — LZ4 of the captured fragment, also pure;
    /// - **lmdb** — the id-index lookups, the edge and unresolved rows, the two puts. The one leg
    ///    that cannot move off the writer: store state under one write txn, and LMDB has one writer.
    protected static final boolean LOAD_PROFILE = Conf.on(Conf.LOAD_PROFILE);
    /// Objects between running profile lines — the store-growth trend, not just the aggregate.
    private static final long LOAD_PROFILE_EVERY =
            Conf.longOf(Conf.LOAD_PROFILE_EVERY);

    private long profPullNanos, profInsertNanos, profWalkNanos, profEncodeNanos;
    private long profObjects, profRefs, profEmbs;

    /// Emit one cumulative profile line (no-op unless `-Dtoolkit.load.profile=true`).
    public void logLoadProfile(String tag) {
        if (!LOAD_PROFILE || profObjects == 0) return;
        double pull = profPullNanos / 1e9;
        double ins = profInsertNanos / 1e9;
        double walk = profWalkNanos / 1e9;
        double enc = profEncodeNanos / 1e9;
        double lmdb = ins - walk - enc;
        double tot = pull + ins;
        Log.info("[loadprof] %s objects=%d refs=%d embs=%d | pull %.1f s (%.1f%%) "
                        + "insert %.1f s (%.1f%%) = walk %.1f s (%.1f%%) + encode %.1f s (%.1f%%) "
                        + "+ lmdb %.1f s (%.1f%%) | total %.1f s",
                tag, profObjects, profRefs, profEmbs,
                pull, 100 * pull / tot, ins, 100 * ins / tot,
                walk, 100 * walk / tot, enc, 100 * enc / tot, lmdb, 100 * lmdb / tot, tot);
    }

    @Override
    public void setMergePolicy(Class<?> clazz, Store.MergeFn fn) {
        if (fn == null) {
            mergePolicies.remove(clazz);
        } else {
            mergePolicies.put(clazz, fn);
        }
    }

    // ---------------------------------------------------------------- sequence

    @Override
    public long sequence(Txn txn, long advance) {
        LmdbTxn t = cast(txn);
        byte[] cur = meta.get(t.tx, SEQ_KEY);
        long value = cur == null ? 0 : NulKeyCodec.u64le(cur);
        if (advance != 0) {
            checkWritable();
            meta.put(t.tx, SEQ_KEY, NulKeyCodec.u64le(value + advance));
        }
        return value;
    }

    // ------------------------------------------------- reference repair without re-marshalling

    /// The blob-repair write path of both resolve passes — the byte-level attribute patch, its
    /// re-marshal fallback, and the per-stage line that reports which of the two ran.
    ///
    /// One instance per store: the patcher's refusal and odd-name counters are cumulative over the
    /// store while the outcome tally resets per stage.
    ///
    /// Must stay declared ahead of the two resolvers: both take this instance in their own field
    /// initialisers, and Java runs those in textual order.
    private final BlobRepair blobRepair = new BlobRepair(this);

    // ---------------------------------------------------------------- resolve

    /// The tier loop, the bind-time repair and the one-shot sweep. See [ReferenceResolver].
    private final ReferenceResolver references = new ReferenceResolver(this, blobRepair);

    /// resolve(): the three-tier id-index match wiring edges, PLUS the bind-time blob class/version
    /// repair on tier-2/3 matches, PLUS the one-shot repair_resolved_reference_blobs sweep
    /// (marker-gated, once per database). The pass is [ReferenceResolver#resolve].
    ///
    /// The WRITE TRANSACTION stays here: this store owns LMDB's single-writer lock, so
    /// [StoreResolve]'s "never call it under an open [#rwTxn()]" rule has exactly one subject.
    @Override
    public long resolve() {
        checkWritable();
        try (LmdbTxn t = (LmdbTxn) rwTxn()) {
            long n = references.resolve(t);
            t.commit();
            return n;
        }
    }

    /// The number of rows in the store's unresolved-key interning table, or `-1` if it does
    /// not intern. Sizes [ReferenceResolver]'s tier memo; it is not otherwise observable.
    protected int internedUnresolvedKeyCount() {
        return -1;
    }

    /// Unresolved rows in cursor order: unsigned referrer, then byte-ordered target key. Stays on
    /// the store, package-private, because BOTH passes consume it.
    List<UnresolvedRow> sortedUnresolvedRows(LmdbTxn t) {
        long t0 = System.nanoTime();
        List<UnresolvedRow> rows = new ArrayList<>();
        for (UnresolvedRow r : iterUnresolved(t)) rows.add(r);
        long t1 = System.nanoTime();
        rows.sort((a, b) -> {
            int c = Long.compareUnsigned(a.referrerFullKey(), b.referrerFullKey());
            return c != 0 ? c : Arrays.compareUnsigned(a.encodedTarget(), b.encodedTarget());
        });
        Log.info("[unres_rows] %d rows materialised in %.2f s, sorted in %.2f s",
                rows.size(), (t1 - t0) / 1e9, (System.nanoTime() - t1) / 1e9);
        return rows;
    }

    // ---------------------------------------------------------------- resolveEmbeddings

    /// The second pass, and the model knowledge behind it. See [EmbeddingResolver].
    private final EmbeddingResolver embeddings = new EmbeddingResolver(this, blobRepair);

    /// Resolve references whose target is an EMBEDDED object by wiring the referrer to the
    /// container that holds it. The pass itself is [EmbeddingResolver]; what stays here is the
    /// WRITE TRANSACTION, so [StoreResolve]'s deadlock rule has one subject. Every arm commits
    /// once before returning, the "nothing unresolved" early exit included.
    @Override
    public long resolveEmbeddings() {
        checkWritable();
        try (LmdbTxn t = (LmdbTxn) rwTxn()) {
            long n = embeddings.resolveEmbeddings(t);
            t.commit();
            return n;
        }
    }

    // ---------------------------------------------------------------- closure reads

    /// The two edge-walking [RefGraph] reads. [ClosureQueries] carries no state of its own.
    private final ClosureQueries closure = new ClosureQueries(this);

    @Override
    public Csr csr(Txn txn) {
        return closure.csr(txn);
    }

    @Override
    public Iterator<Object> fetchAllReferencesByClass(Txn txn, Set<Class<?>> clazzes, boolean skipExisting) {
        return closure.fetchAllReferencesByClass(txn, clazzes, skipExisting);
    }

    // ---------------------------------------------------------------- misc

    @Override
    public Path path() {
        return path;
    }

    @Override
    public boolean isReadonly() {
        return readonly;
    }

    @Override
    public void close() {
        logLoadProfile("final");
        env.close();
    }
}
