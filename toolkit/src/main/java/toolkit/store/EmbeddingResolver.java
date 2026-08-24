package toolkit.store;

import org.lmdbjava.Cursor;
import org.lmdbjava.Dbi;
import org.lmdbjava.GetOp;
import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.EntityStructure;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.ContainmentIndex;
import toolkit.model.RecursiveAttributes;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The SECOND resolution pass: references whose target is an EMBEDDED object — one that carries an
/// id but owns no row of its own, only its container does — wired to the container that holds it.
///
/// Not part of the engine it sits on: everything below is a statement about the NeTEx object model
/// — which classes can contain which, what counts as an embedded entity, how close a near-match
/// must be before a reference is rewritten to reach it.
///
/// The transaction is NOT ours: `AbstractLmdbStore.resolveEmbeddings()` opens it and commits, so
/// [StoreResolve]'s "never call this under an open rwTxn" rule has exactly one subject.
///
/// [BlobRepair] arrives from the store rather than being constructed here, because its patcher's
/// counters are cumulative across BOTH passes.
final class EmbeddingResolver {

    /// -Dtoolkit.embIndexTrust=false: ignore a complete index and take the corpus scan. The
    /// insert-side twin stays on the store, which writes the marker.
    private static final boolean EMB_INDEX_TRUST =
            Conf.on(Conf.EMB_INDEX_TRUST);

    private final AbstractLmdbStore store;
    private final BlobRepair blobRepair;

    EmbeddingResolver(AbstractLmdbStore store, BlobRepair blobRepair) {
        this.store = store;
        this.blobRepair = blobRepair;
    }

    private record Almost(BytesKey candidate, String versionChange, String classChange, long parentFk) {}

    private record Variant(BytesKey matchedRow, String versionChange, String classChange) {}

    /// Mutable state of one resolveEmbeddings pass (shared with the per-class scan).
    private static final class EmbState {
        final LinkedHashMap<BytesKey, LinkedHashSet<Long>> unresolvedPairs = new LinkedHashMap<>();
        final LinkedHashMap<BytesKey, List<BytesKey>> unresolvedById = new LinkedHashMap<>();
        final Map<Class<?>, Integer> missingClassCount = new HashMap<>();
        final Set<Class<?>> stillMissing = new HashSet<>();
        final LinkedHashMap<BytesKey, Almost> almostQueue = new LinkedHashMap<>();
        final List<Object[]> scannedClasses = new ArrayList<>();
        Map<Integer, List<byte[]>> containersByClass; // null = corpus-scan fallback
        long exactResolved;
    }

    /// Exact container binding, the ORDER-SENSITIVE near-match queue, and the phase-B reference
    /// repair with blob rewrite.
    ///
    /// Order matters. Unresolved rows are taken in cursor order, so the bucket insertion
    /// order that decides which variant wins a near-match is fixed; class maps are visited
    /// size-ascending with ties broken by class idx, and containers within a map in ascending key
    /// order, index-driven discovery included. The almost-queue is last-write-wins.
    long resolveEmbeddings(AbstractLmdbStore.LmdbTxn t) {
        EmbState s = new EmbState();
        Set<Class<?>> missingClasses = new LinkedHashSet<>();
        for (UnresolvedRow r : store.sortedUnresolvedRows(t)) {
            byte[] v = r.encodedTarget();
            BytesKey vk = new BytesKey(v);
            Class<?> cls = store.idxToClass.get(NulKeyCodec.classTrailer(v));
            LinkedHashSet<Long> referrers = s.unresolvedPairs.get(vk);
            if (referrers == null) {
                referrers = new LinkedHashSet<>();
                s.unresolvedPairs.put(vk, referrers);
                byte[] idPrefix = Arrays.copyOf(v, NulKeyCodec.idPrefixLen(v));
                s.unresolvedById.computeIfAbsent(new BytesKey(idPrefix), k -> new ArrayList<>()).add(vk);
                if (cls != null) s.missingClassCount.merge(cls, 1, Integer::sum);
            }
            referrers.add(r.referrerFullKey());
            if (cls != null) missingClasses.add(cls);
        }
        if (s.unresolvedPairs.isEmpty()) {
            Log.info("[resolve_embeddings] nothing unresolved, skipping");
            return 0;
        }

        List<Class<?>> usedClasses = store.dbNames(t);
        Map<Class<?>, Set<Class<?>>> index = ContainmentIndex.collect(usedClasses, missingClasses);
        Set<Class<?>> clazzes = new LinkedHashSet<>();
        for (Set<Class<?>> parents : index.values()) clazzes.addAll(parents);

        // Container discovery: with a COMPLETE persisted embedding index, one id-prefix
        // range-scan per unresolved id selects every relevant container; otherwise fall back
        // to the candidate-class corpus scan. Only which rows are deserialised changes.
        if (embeddingIndexReady(t)) {
            s.containersByClass = new HashMap<>();
            Map<Integer, Set<BytesKey>> byClass = new HashMap<>();
            long lookups = 0;
            long hits = 0;
            try (Cursor<byte[]> c = store.embIdx.openCursor(t.tx)) {
                for (BytesKey prefixKey : s.unresolvedById.keySet()) {
                    byte[] prefix = prefixKey.bytes;
                    lookups++;
                    if (!c.get(prefix, GetOp.MDB_SET_RANGE)) continue;
                    do {
                        if (!NulKeyCodec.startsWith(c.key(), prefix)) break;
                        hits++;
                        long containerFk = NulKeyCodec.u64le(c.val());
                        byClass.computeIfAbsent(NulKeyCodec.fullKeyClassIdx(containerFk), k -> new HashSet<>())
                                .add(new BytesKey(NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(containerFk))));
                    } while (c.next());
                }
            }
            long selected = 0;
            for (Map.Entry<Integer, Set<BytesKey>> e : byClass.entrySet()) {
                List<byte[]> locals = new ArrayList<>(e.getValue().size());
                for (BytesKey bk : e.getValue()) locals.add(bk.bytes);
                locals.sort(Arrays::compareUnsigned); // = ascending class-map cursor order
                s.containersByClass.put(e.getKey(), locals);
                selected += locals.size();
            }
            Log.info("[resolve_embeddings] embedding index: %d id lookups, %d candidate rows, %d containers selected",
                    lookups, hits, selected);
        } else {
            Log.info("[resolve_embeddings] no complete embedding index, falling back to the candidate-class corpus scan");
        }

        Map<Class<?>, Long> classCount = new HashMap<>();
        for (Class<?> clazz : clazzes) {
            Dbi<byte[]> d = store.classDbi(t, store.classIdxInt(clazz), false);
            classCount.put(clazz, d == null ? 0L : d.stat(t.tx).entries);
        }
        Map<Class<?>, Set<Class<?>>> parentTargets = new HashMap<>();
        for (Map.Entry<Class<?>, Set<Class<?>>> e : index.entrySet()) {
            for (Class<?> parent : e.getValue()) {
                parentTargets.computeIfAbsent(parent, k -> new HashSet<>()).add(e.getKey());
            }
        }
        for (Map.Entry<Class<?>, Integer> e : s.missingClassCount.entrySet()) {
            if (e.getValue() > 0) s.stillMissing.add(e.getKey());
        }

        Log.info("[resolve_embeddings] %d unresolved refs, scanning %d class maps for %d missing classes",
                s.unresolvedPairs.size(), classCount.size(), missingClasses.size());

        List<Map.Entry<Class<?>, Long>> order = new ArrayList<>(classCount.entrySet());
        order.sort(Comparator.comparingLong((Map.Entry<Class<?>, Long> e) -> e.getValue())
                .thenComparingInt(e -> store.classIdxInt(e.getKey())));
        List<Object[]> skippedScoped = new ArrayList<>();
        List<Object[]> skippedEmpty = new ArrayList<>();
        for (Map.Entry<Class<?>, Long> e : order) {
            if (s.unresolvedPairs.isEmpty()) {
                skippedEmpty.add(new Object[] {e.getKey(), e.getValue()});
                continue;
            }
            Set<Class<?>> targets = parentTargets.get(e.getKey());
            boolean canResolve = false;
            if (targets != null) {
                for (Class<?> m : targets) {
                    if (s.stillMissing.contains(m)) {
                        canResolve = true;
                        break;
                    }
                }
            }
            if (!canResolve) {
                skippedScoped.add(new Object[] {e.getKey(), e.getValue()});
                continue;
            }
            scanEmbeddingClass(t, e.getKey(), e.getValue(), s);
        }
        if (!s.unresolvedPairs.isEmpty() && !skippedScoped.isEmpty()) {
            // The scoped prefilter is exact only for EXACT matches; rescan the skipped parents so
            // id-based near-match repairs are not lost.
            Log.info("[resolve_embeddings] %d rows remain; rescanning %d scoped-skipped class maps for near-match repairs",
                    s.unresolvedPairs.size(), skippedScoped.size());
            List<Object[]> rescan = skippedScoped;
            skippedScoped = new ArrayList<>();
            for (Object[] e : rescan) {
                if (s.unresolvedPairs.isEmpty()) {
                    skippedEmpty.add(e);
                    continue;
                }
                scanEmbeddingClass(t, (Class<?>) e[0], (Long) e[1], s);
            }
        }
        List<Object[]> skipped = new ArrayList<>(skippedScoped);
        skipped.addAll(skippedEmpty);
        skipped.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
        StringBuilder largest = new StringBuilder();
        for (int k = 0; k < Math.min(3, skipped.size()); k++) {
            if (k > 0) largest.append(", ");
            largest.append(((Class<?>) skipped.get(k)[0]).getSimpleName()).append('(').append(skipped.get(k)[1]).append(')');
        }
        Log.info("[resolve_embeddings] scanned %d class maps, skipped %d (targets resolved) + %d (nothing left); largest skipped: %s",
                s.scannedClasses.size(), skippedScoped.size(), skippedEmpty.size(),
                largest.length() == 0 ? "none" : largest.toString());

        // Phase B: repair the references behind the near-matches, grouped per referring object.
        long phasebRepairs = 0;
        if (!s.almostQueue.isEmpty()) {
            LinkedHashMap<Long, LinkedHashSet<BytesKey>> inverted = new LinkedHashMap<>();
            for (Map.Entry<BytesKey, LinkedHashSet<Long>> e : s.unresolvedPairs.entrySet()) {
                for (long r : e.getValue()) {
                    inverted.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(e.getKey());
                }
            }
            for (long referrer : inverted.keySet()) {
                Object obj = store.loadObjectByFullKey(t, referrer);
                if (obj == null) continue;
                boolean write = false;
                List<RecursiveAttributes.Ref> refs = RecursiveAttributes.referenceObjects(obj);
                String[][] before = BlobRepair.snapshotRefs(refs);
                for (RecursiveAttributes.Ref ref : refs) {
                    byte[] cmp = blobRepair.encodeLiveRef(ref.refObject());
                    Almost a = s.almostQueue.get(new BytesKey(cmp));
                    if (a == null) continue;
                    if (a.classChange() != null) ref.refObject().setNameOfRefClass(a.classChange());
                    if (a.versionChange() != null) ref.refObject().setVersion(a.versionChange());
                    store.putEdge(t, referrer, a.parentFk());
                    store.deleteUnresolved(t, referrer, cmp);
                    write = true;
                }
                if (write) {
                    phasebRepairs++;
                    blobRepair.writeRepairedBlob(t, referrer, obj, refs, before);
                }
            }
        }
        Log.info("[resolve_embeddings] %d exact embedding resolutions, %d almost-match repairs (%d queued)",
                s.exactResolved, phasebRepairs, s.almostQueue.size());
        blobRepair.logPatchStats("resolve_embeddings");
        return s.exactResolved + phasebRepairs;
    }

    private boolean embeddingIndexReady(AbstractLmdbStore.LmdbTxn t) {
        if (!EMB_INDEX_TRUST || store.embIdx == null) return false;
        String stored = store.markerValue(t, AbstractLmdbStore.Marker.EMBEDDING_IDX_DONE);
        if (stored == null) return false;
        String want = store.embeddingScopeTag();
        if (!stored.equals(want)) {
            // Complete for a DIFFERENT scope than this store was opened with, so it has no rows for
            // the classes that scope added. Fall back: the scan is correct, and re-running the load
            // rewrites the marker.
            Log.warn("[resolve_embeddings] embedding index was built for a different scope, "
                    + "falling back to the corpus scan (stored %s, want %s)", stored, want);
            return false;
        }
        return true;
    }

    /// Visit one candidate class map — the whole map, or the index-selected rows.
    private void scanEmbeddingClass(AbstractLmdbStore.LmdbTxn t, Class<?> clazz, long count,
                                    EmbState s) {
        s.scannedClasses.add(new Object[] {clazz, count});
        int cIdx = store.classIdxInt(clazz);
        if (s.containersByClass == null) {
            Log.info("[resolve_embeddings] scanning %s (%d objects)", clazz.getSimpleName(), count);
        } else {
            List<byte[]> sel = s.containersByClass.get(cIdx);
            Log.info("[resolve_embeddings] scanning %s (%d of %d objects via embedding index)",
                    clazz.getSimpleName(), sel == null ? 0 : sel.size(), count);
        }
        Dbi<byte[]> d = store.classDbi(t, cIdx, false);
        if (d == null) return;
        if (s.containersByClass == null) {
            // The break on an emptied unresolved set is the NORMAL exit — close the cursor.
            try (AbstractLmdbStore.RowIterator<ObjectRow> rows =
                         (AbstractLmdbStore.RowIterator<ObjectRow>) store.iterObjects(t, clazz).iterator()) {
                while (rows.hasNext()) {
                    if (s.unresolvedPairs.isEmpty()) break;
                    ObjectRow row = rows.next();
                    matchContainer(t, cIdx, row.localKey(), row.object(), s);
                }
            }
        } else {
            for (byte[] local : s.containersByClass.getOrDefault(cIdx, List.of())) {
                if (s.unresolvedPairs.isEmpty()) break;
                byte[] value = d.get(t.tx, local);
                if (value == null) continue; // stale index row of a replaced/purged object
                matchContainer(t, cIdx, local, store.codec.decode(value, clazz), s);
            }
        }
    }

    /// Match every embedded id of one container against the unresolved set (exact or near-match).
    private void matchContainer(AbstractLmdbStore.LmdbTxn t, int cIdx, byte[] localKey,
                                Object containerObj, EmbState s) {
        long containerFk = NulKeyCodec.fullKey(cIdx, NulKeyCodec.u32le(localKey));
        for (EntityStructure emb : RecursiveAttributes.embeddedEntities(
                containerObj, c -> store.nameToIdx.containsKey(c.getSimpleName()))) {
            String embVersion = (emb instanceof EntityInVersionStructure eiv) ? eiv.getVersion() : null;
            byte[] cand = NulKeyCodec.encodeKey(emb.getId(), embVersion,
                    NulKeyCodec.classIdxBytes(store.nameToIdx.get(emb.getClass().getSimpleName())), true);
            Variant v = variantOfCandidate(cand, emb, embVersion, s);
            if (v == null) continue;
            if (v.versionChange() == null && v.classChange() == null) {
                // Exact: wire every referrer of the MATCHED row onto the container and drain it.
                LinkedHashSet<Long> referrers = s.unresolvedPairs.remove(v.matchedRow());
                if (referrers == null) continue;
                for (long r : referrers) {
                    store.putEdge(t, r, containerFk);
                    store.deleteUnresolved(t, r, v.matchedRow().bytes);
                }
                s.almostQueue.remove(v.matchedRow());
                s.exactResolved++;
                Class<?> resolvedCls = store.idxToClass.get(NulKeyCodec.classTrailer(v.matchedRow().bytes));
                if (resolvedCls != null) {
                    int remaining = s.missingClassCount.merge(resolvedCls, -1, Integer::sum);
                    if (remaining <= 0) s.stillMissing.remove(resolvedCls);
                }
            } else {
                // The queue is keyed by the matched ROW while the guard checks the CANDIDATE key,
                // so the guard is always true: last write wins.
                if (!s.almostQueue.containsKey(new BytesKey(cand))) {
                    s.almostQueue.put(v.matchedRow(),
                            new Almost(new BytesKey(cand), v.versionChange(), v.classChange(), containerFk));
                }
            }
        }
    }

    /// Rank the still-unresolved rows of the candidate's id bucket: version match beats class
    /// match beats id-only, smallest key breaking ties within a rank.
    private Variant variantOfCandidate(byte[] candidate, EntityStructure emb, String embVersion,
                                       EmbState s) {
        BytesKey candKey = new BytesKey(candidate);
        if (s.unresolvedPairs.containsKey(candKey)) return new Variant(candKey, null, null);
        byte[] prefixIdVersion = Arrays.copyOf(candidate, NulKeyCodec.stripClazzLen(candidate));
        byte[] idPrefix = Arrays.copyOf(candidate, NulKeyCodec.idPrefixLen(candidate));
        byte[] classPart = {candidate[candidate.length - 2], candidate[candidate.length - 1]};
        List<BytesKey> bucket = s.unresolvedById.get(new BytesKey(idPrefix));
        if (bucket == null) return null;
        int bestRank = 4;
        byte[] result = null;
        String versionChange = null;
        String classChange = null;
        for (BytesKey ck : bucket) {
            if (!s.unresolvedPairs.containsKey(ck)) continue;
            byte[] k = ck.bytes;
            int rank;
            if (NulKeyCodec.startsWith(k, prefixIdVersion)) rank = 1;
            else if (NulKeyCodec.startsWith(k, idPrefix) && NulKeyCodec.endsWith(k, classPart)) rank = 2;
            else if (NulKeyCodec.startsWith(k, idPrefix)) rank = 3;
            else continue;
            if (rank > bestRank || (rank == bestRank && result != null && Arrays.compareUnsigned(k, result) >= 0)) {
                continue;
            }
            bestRank = rank;
            result = k;
            if (rank == 1) {
                classChange = emb.getClass().getSimpleName();
                versionChange = null;
            } else if (rank == 2) {
                classChange = null;
                versionChange = embVersion;
            } else {
                classChange = emb.getClass().getSimpleName();
                versionChange = embVersion;
            }
        }
        return result == null ? null : new Variant(new BytesKey(result), versionChange, classChange);
    }
}
