package toolkit.store;

import org.lmdbjava.Cursor;
import org.lmdbjava.Dbi;
import org.lmdbjava.GetOp;
import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.codec.RefPatcher;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.ModelRegistry;
import toolkit.model.RecursiveAttributes;
import toolkit.model.RefElementIndex;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/// The FIRST resolution pass: the three-tier id-index match that turns unresolved reference rows
/// into edges, the bind-time blob repair that follows a tier-2/3 match, and the one-shot
/// marker-gated sweep over already-resolved referrers.
///
/// The four measurement knobs ([#TIER_MEMO], [#TIER_NULL], [#BLOB_SWEEP], [#SWEEP_SCAN]) each have
/// to change nothing but the wall clock, and two of them (`-Dtoolkit.resolve.tierMemo.verify`,
/// `-Dtoolkit.blobSweep.verify`) run the fast and slow paths together and fail on the first
/// disagreement. Run those after touching anything here.
///
/// The transaction is NOT ours: `AbstractLmdbStore.resolve()` opens it, calls [#resolve] and
/// commits. Nothing here opens or commits a transaction.
///
/// [BlobRepair] arrives from the store because its patcher's counters are cumulative across both
/// passes.
final class ReferenceResolver {

    private final AbstractLmdbStore store;
    private final BlobRepair blobRepair;

    ReferenceResolver(AbstractLmdbStore store, BlobRepair blobRepair) {
        this.store = store;
        this.blobRepair = blobRepair;
    }

    /// The three-tier id-index match wiring edges, PLUS the bind-time blob class/version repair on
    /// tier-2/3 matches, PLUS the one-shot marker-gated blob sweep.
    ///
    /// Rows are processed in cursor order — (unsigned referrer full_key, byte-ordered
    /// target key) — grouped by referrer so each repaired blob is rewritten once.
    ///
    /// Fragments are stored source-verbatim, so the repair-matching key cannot be read off a
    /// materialised `nameOfRefClass`; the normalisation is applied LIVE instead — registered name,
    /// else the ref-class-derived default, else DataManagedObject.
    long resolve(AbstractLmdbStore.LmdbTxn t) {
        Log.info("[resolve] resolving references");
        List<UnresolvedRow> rows = store.sortedUnresolvedRows(t);
        TierMemo memo = TierMemo.forRows(store, rows);
        long tierT0 = System.nanoTime();
        long resolved = 0;
        long repairedRefs = 0;
        long repairedObjects = 0;
        int i = 0;
        while (i < rows.size()) {
            long referrer = rows.get(i).referrerFullKey();
            List<Object[]> fixes = null; // {targetFk, rowValue, versionChange, classChange}
            int j = i;
            for (; j < rows.size() && rows.get(j).referrerFullKey() == referrer; j++) {
                UnresolvedRow row = rows.get(j);
                byte[] value = row.encodedTarget();
                TierHit hit = memo.resolve(this, t, row, value);
                if (hit == null) continue;
                store.putEdge(t, referrer, hit.fk());
                store.deleteUnresolved(t, referrer, value);
                resolved++;
                if (hit.classChange() || hit.versionChange()) {
                    if (fixes == null) fixes = new ArrayList<>();
                    fixes.add(new Object[] {hit.fk(), value, hit.versionChange(), hit.classChange()});
                }
            }
            i = j;
            if (fixes != null) {
                repairedObjects++;
                repairedRefs += fixes.size();
                applyBindTimeRepairs(t, referrer, fixes);
            }
        }
        memo.report((System.nanoTime() - tierT0) / 1e9, rows.size());
        Log.info("[resolve] %d unresolved rows scanned, %d reference an id that exists",
                rows.size(), resolved);
        // A row that matched no tier keeps its _unresolved row and gets no edge.
        long neverResolved = rows.size() - resolved;
        toolkit.util.Strictness.require(
                toolkit.util.Strictness.UNRESOLVED_REFS, "unresolvedRefs", neverResolved,
                "resolve", "references matched no tier and stay unresolved");
        Log.info("[resolve] %d references resolved, of which %d needed a class/version repair in %d objects",
                resolved, repairedRefs, repairedObjects);
        // One-shot blob sweep, marker-gated.
        if (store.markerValue(t, AbstractLmdbStore.Marker.BLOB_REPAIR_DONE) == null) {
            if (BLOB_SWEEP) {
                long[] c = repairResolvedReferenceBlobs(t);
                Log.info("[resolve] %d already-resolved references had a mis-typed blob repaired in %d objects",
                        c[0], c[1]);
            } else {
                Log.info("[resolve] blob sweep SKIPPED (-Dtoolkit.resolve.blobSweep=false); "
                        + "the marker is set as if it had run — see ReferenceResolver#BLOB_SWEEP");
            }
            store.putMarker(t, AbstractLmdbStore.Marker.BLOB_REPAIR_DONE, AbstractLmdbStore.MARKER_SET);
        }
        blobRepair.logPatchStats("resolve");
        return resolved;
    }

    private record TierHit(long fk, boolean classChange, boolean versionChange) {}

    /// The three-tier id-index match, reporting WHICH tier hit.
    private TierHit tierResolveDetailed(AbstractLmdbStore.LmdbTxn t, byte[] encTarget) {
        byte[] exact = store.idIdx.get(t.tx, encTarget);
        if (exact != null) return new TierHit(NulKeyCodec.u64le(exact), false, false);
        int n = encTarget.length;
        byte[] idVersion = Arrays.copyOf(encTarget, n - NulKeyCodec.CLASS_IDX_LEN);
        byte[] classPart = {encTarget[n - 2], encTarget[n - 1]};
        try (Cursor<byte[]> c = store.idIdx.openCursor(t.tx)) {
            // Alternative 1: id + version exist, class does not match
            if (c.get(idVersion, GetOp.MDB_SET_RANGE) && NulKeyCodec.startsWith(c.key(), idVersion)) {
                return new TierHit(NulKeyCodec.u64le(c.val()), true, false);
            }
            // Alternative 2: id exists (any version); class compared via the 2-byte trailer,
            // else the smallest key wins deterministically.
            byte[] idPrefix = Arrays.copyOf(encTarget, NulKeyCodec.idPrefixLen(encTarget));
            long first = -1;
            if (c.get(idPrefix, GetOp.MDB_SET_RANGE)) {
                do {
                    byte[] k = c.key();
                    if (!NulKeyCodec.startsWith(k, idPrefix)) break;
                    if (NulKeyCodec.endsWith(k, classPart)) {
                        return new TierHit(NulKeyCodec.u64le(c.val()), false, true);
                    }
                    if (first == -1) first = NulKeyCodec.u64le(c.val());
                } while (c.next());
            }
            return first == -1 ? null : new TierHit(first, true, true);
        }
    }

    // ------------------------------------------- the tier loop's id-index memo

    /// `-Dtoolkit.resolve.tierMemo=false` reverts [#resolve]'s tier loop to one
    /// [#tierResolveDetailed] call per unresolved ROW. **Default true.**
    ///
    /// Nothing inside `resolve()`'s write transaction writes `_id_idx` — the only writers are on
    /// the insert path — so the tier probe is a pure function of the encoded target key for the
    /// whole loop, and one answer serves every row sharing a key.
    ///
    /// Keyed by intern id, not key bytes. A producer that cannot supply one passes `-1` and falls
    /// through to a direct probe.
    ///
    /// It declines to allocate when it cannot pay: redundancy is counted from a bitset before the
    /// arrays exist, so a corpus below 2 rows per distinct key runs as before.
    private static final boolean TIER_MEMO = Conf.on(Conf.RESOLVE_TIER_MEMO);

    /// `-Dtoolkit.resolve.tierMemo.verify=true` re-probes every memoised row and fails loudly on
    /// the first disagreement. Strictly slower than either path alone, since it does both.
    private static final boolean TIER_MEMO_VERIFY = Conf.on(Conf.RESOLVE_TIER_MEMO_VERIFY);

    /// `-Dtoolkit.resolve.tierNull=true` makes every tier probe return "no match" without
    /// touching `_id_idx` at all.
    ///
    /// THIS IS A DIAGNOSTIC AND IT PRODUCES A WRONG STORE: references that should have become
    /// edges are left unresolved. It measures the tier loop's own overhead with the probes removed.
    /// Never ship a store built with it.
    private static final boolean TIER_NULL = Conf.on(Conf.RESOLVE_TIER_NULL);

    /// [#tierResolveDetailed] memoised by [UnresolvedRow#internId].
    private static final class TierMemo {
        private static final byte UNKNOWN = 0;
        private static final byte MISS = 1;
        private static final byte HIT = 2; // +1 classChange, +2 versionChange

        private final byte[] state; // null => memo disabled, every row probes
        private final long[] fk;
        private final long distinctKeys;
        private final int internSize;
        private long probes;
        private long memoHits;
        private long verifyMismatches;

        private TierMemo(int n, int internSize, long distinctKeys) {
            this.state = n > 0 ? new byte[n] : null;
            this.fk = n > 0 ? new long[n] : null;
            this.internSize = internSize;
            this.distinctKeys = distinctKeys;
        }

        /// Sizes the memo and counts the loop's DISTINCT keys exactly. Both happen here, before
        /// the timed loop and in EVERY arm, so memo-on and memo-off differ only inside the loop.
        static TierMemo forRows(AbstractLmdbStore store, List<UnresolvedRow> rows) {
            int n = store.internedUnresolvedKeyCount();
            long distinct = -1;
            if (n > 0) {
                long[] seen = new long[(n >> 6) + 1];
                long d = 0;
                for (int i = 0, sz = rows.size(); i < sz; i++) {
                    int id = rows.get(i).internId();
                    if (id < 0 || id >= n) {
                        d = -1;
                        break;
                    }
                    if ((seen[id >> 6] & (1L << (id & 63))) == 0) {
                        seen[id >> 6] |= 1L << (id & 63);
                        d++;
                    }
                }
                distinct = d;
            }
            // Allocate only when the memo can pay for itself: the arrays cost 9 bytes per intern
            // id, and below 2 rows per distinct key the probes saved cannot repay them.
            boolean worth = distinct > 0 && rows.size() >= 2L * distinct;
            return new TierMemo(TIER_MEMO && worth ? n : 0, n, distinct);
        }

        TierHit resolve(ReferenceResolver owner, AbstractLmdbStore.LmdbTxn t,
                        UnresolvedRow row, byte[] value) {
            if (TIER_NULL) {
                probes++;
                return null;
            }
            int id = state == null ? -1 : row.internId();
            if (id < 0) {
                probes++;
                return owner.tierResolveDetailed(t, value);
            }
            byte s = state[id];
            if (s == UNKNOWN) {
                probes++;
                TierHit h = owner.tierResolveDetailed(t, value);
                if (h == null) {
                    state[id] = MISS;
                } else {
                    state[id] = (byte) (HIT + (h.classChange() ? 1 : 0) + (h.versionChange() ? 2 : 0));
                    fk[id] = h.fk();
                }
                return h;
            }
            memoHits++;
            TierHit h = s == MISS ? null
                    : new TierHit(fk[id], ((s - HIT) & 1) != 0, ((s - HIT) & 2) != 0);
            if (TIER_MEMO_VERIFY) {
                TierHit fresh = owner.tierResolveDetailed(t, value);
                if (!java.util.Objects.equals(h, fresh)) {
                    verifyMismatches++;
                    throw new IllegalStateException("[tier] MEMO MISMATCH on intern id " + id
                            + ": memo " + h + " vs live " + fresh
                            + " — _id_idx moved inside resolve()'s txn, which "
                            + "ReferenceResolver#TIER_MEMO argues it cannot");
                }
            }
            return h;
        }

        void report(double wall, int rows) {
            Log.info("[tier] %d rows in %.2f s | %s distinct keys%s | intern table %d (memo %s) "
                            + "| memo %s | %d probes, %d memo hits%s",
                    rows, wall,
                    distinctKeys < 0 ? "?" : Long.toString(distinctKeys),
                    distinctKeys > 0 ? String.format(" (%.2fx redundancy)", rows / (double) distinctKeys) : "",
                    internSize, state == null ? "0 B" : ((9L * internSize) >> 20) + " MB",
                    TIER_NULL ? "n/a — PROBES DISABLED (-Dtoolkit.resolve.tierNull=true), THIS STORE IS WRONG"
                            : state == null ? "OFF" : TIER_MEMO_VERIFY ? "ON+VERIFY" : "ON",
                    probes, memoHits,
                    TIER_MEMO_VERIFY ? ", " + verifyMismatches + " mismatches" : "");
        }
    }

    /// resolve() pass-1 repair: fix the matching reference(s) in the blob and rewrite it once.
    private void applyBindTimeRepairs(AbstractLmdbStore.LmdbTxn t, long referrerFk, List<Object[]> fixes) {
        Object obj = store.loadObjectByFullKey(t, referrerFk);
        if (obj == null) return;
        List<RecursiveAttributes.Ref> refs = RecursiveAttributes.referenceObjects(obj);
        String[][] before = BlobRepair.snapshotRefs(refs);
        for (Object[] fix : fixes) {
            long targetFk = (Long) fix[0];
            byte[] value = (byte[]) fix[1];
            boolean versionChange = (Boolean) fix[2];
            boolean classChange = (Boolean) fix[3];
            boolean matched = false;
            for (RecursiveAttributes.Ref ref : refs) {
                // live re-derivation: an earlier fix may already have rewritten this reference
                byte[] cmp = blobRepair.encodeLiveRef(ref.refObject());
                if (Arrays.equals(cmp, value)) {
                    matched = true;
                    applyRefFix(t, ref.refObject(), targetFk, versionChange, classChange);
                }
            }
            if (!matched) {
                // Bind by the row's bare id (the reconstructed key can miss the row that produced
                // the fix — resolve() does exactly this).
                String rowId = NulKeyCodec.idPart(value);
                for (RecursiveAttributes.Ref ref : refs) {
                    if (rowId.equals(ref.refObject().getRef())) {
                        applyRefFix(t, ref.refObject(), targetFk, versionChange, classChange);
                    }
                }
            }
        }
        blobRepair.writeRepairedBlob(t, referrerFk, obj, refs, before);
    }

    private void applyRefFix(AbstractLmdbStore.LmdbTxn t, VersionOfObjectRefStructure ref, long targetFk,
                             boolean versionChange, boolean classChange) {
        if (classChange) {
            Class<?> cls = store.idxToClass.get(NulKeyCodec.fullKeyClassIdx(targetFk));
            if (cls != null) ref.setNameOfRefClass(cls.getSimpleName());
        }
        if (versionChange) {
            Object target = store.loadObjectByFullKey(t, targetFk);
            // Guarded like the classChange arm above: a null TARGET means it could not be read —
            // tombstoned row, unknown class idx, a full_key from another store — not that it has
            // no version. A target that loads and is simply unversioned still clears the attribute.
            if (target != null) {
                ref.setVersion((target instanceof EntityInVersionStructure eiv) ? eiv.getVersion() : null);
            }
        }
    }

    /// Blob-only repair over referrers that already own a resolved outward edge. A reference whose
    /// declared class/version does not make the exact id-index key hit adopts the class+version of
    /// the first id-prefix match. Edges and the unresolved map are untouched.
    private long[] repairResolvedReferenceBlobs(AbstractLmdbStore.LmdbTxn t) {
        TreeMap<Long, Boolean> referrers = new TreeMap<>(Long::compareUnsigned);
        for (long[] e : store.iterEdges(t)) referrers.put(e[0], Boolean.TRUE);
        long repairedRefs = 0;
        long repairedObjs = 0;
        long cleared = 0;   // referrers the fragment scan proved clean (no unmarshal)
        long decoded = 0;   // referrers that still had to be unmarshalled
        try (Cursor<byte[]> icur = store.idIdx.openCursor(t.tx)) {
            for (long referrerFk : referrers.keySet()) {
                boolean scanClean = SWEEP_SCAN && !mayNeedBlobRepair(t, icur, referrerFk);
                if (scanClean) {
                    cleared++;
                    // VERIFY mode falls through to the unmarshal anyway, to cross-check the two.
                    if (!SWEEP_VERIFY) continue;
                } else {
                    decoded++;
                }
                Object obj = store.loadObjectByFullKey(t, referrerFk);
                if (obj == null) continue;
                boolean dirty = false;
                List<RecursiveAttributes.Ref> refs = RecursiveAttributes.referenceObjects(obj);
                String[][] before = BlobRepair.snapshotRefs(refs);
                for (RecursiveAttributes.Ref ref : refs) {
                    VersionOfObjectRefStructure r = ref.refObject();
                    // Same exact-key attempt loadObjectByReference makes: a hit means the blob is
                    // already correct for this reference. "Declared" is the normalised chain the
                    // insert used — fragments do not materialise it.
                    byte[] exactKey = NulKeyCodec.encodeKey(ref.ref(), r.getVersion(),
                            NulKeyCodec.classIdxBytes(store.idxForName(ref.targetClassName())), true);
                    if (store.idIdx.get(t.tx, exactKey) != null) continue;
                    // Exact key missed: adopt the first id-prefix target's class+version.
                    byte[] prefix = NulKeyCodec.encodePrefix(ref.ref(), null, null, false);
                    if (!icur.get(prefix, GetOp.MDB_SET_RANGE)) continue;
                    byte[] targetKey = icur.key();
                    if (!NulKeyCodec.startsWith(targetKey, prefix)) continue;
                    long targetFk = NulKeyCodec.u64le(icur.val());
                    Class<?> targetClass = store.idxToClass.get(NulKeyCodec.fullKeyClassIdx(targetFk));
                    if (targetClass == null) continue;
                    String newName = targetClass.getSimpleName();
                    byte[] idVersion = Arrays.copyOf(targetKey, NulKeyCodec.stripClazzLen(targetKey));
                    String targetVersion = NulKeyCodec.versionPart(idVersion);
                    String newVersion = targetVersion.isEmpty() ? null : targetVersion;
                    boolean changed = false;
                    if (!newName.equals(r.getNameOfRefClass())) {
                        r.setNameOfRefClass(newName);
                        changed = true;
                    }
                    if (!Objects.equals(r.getVersion(), newVersion)) {
                        r.setVersion(newVersion);
                        changed = true;
                    }
                    if (changed) {
                        dirty = true;
                        repairedRefs++;
                    }
                }
                if (dirty && scanClean) {
                    // The one direction that matters: the scan may over-report ("maybe dirty" on a
                    // clean object costs a decode), never under-report.
                    throw new IllegalStateException("blob-sweep scan/unmarshal disagree on full_key "
                            + Long.toUnsignedString(referrerFk) + " (" + obj.getClass().getSimpleName()
                            + "): the fragment scan cleared an object the unmarshal repaired");
                }
                if (dirty) {
                    repairedObjs++;
                    blobRepair.writeRepairedBlob(t, referrerFk, obj, refs, before);
                }
            }
        }
        Log.info("[resolve] blob sweep: %d referrers, %d cleared by fragment scan, %d unmarshalled"
                + " (%d fragment scans refused)%s", referrers.size(), cleared, decoded,
                sweepScanRefused, SWEEP_VERIFY ? " [VERIFY: every cleared referrer re-checked]" : "");
        if (SWEEP_DEBUG) logSweepDebug();
        return new long[] {repairedRefs, repairedObjs};
    }

    // ------------------------------------------- blob sweep: the decode-free "is it clean?" read

    /// `-Dtoolkit.resolve.blobSweep=false` — skip the one-shot sweep entirely and set its
    /// marker as if it had run. **Default true.**
    ///
    /// After a pure-INSERT load the sweep finds nothing: it repairs a reference only when (a) the
    /// exact id-index key built from its declared class+version misses AND (b) an id-prefix match
    /// exists, and on the insert path (a) implies an unresolved row that the tier loop has already
    /// processed in the SAME txn.
    ///
    /// NOT SOUND AFTER A SKIPPING CLONE: [ObjectWriter#cloneFrom] copies edge and unresolved rows
    /// rather than re-deriving them, so a clone that SKIPS a target drops that target's edge
    /// without leaving an unresolved row, leaving a reference only the sweep can repair.
    ///
    /// The marker is persisted, so a skip is never retried by a later stage.
    private static final boolean BLOB_SWEEP =
            Conf.on(Conf.RESOLVE_BLOB_SWEEP);

    /// -Dtoolkit.blobSweep.scan=false reverts [#repairResolvedReferenceBlobs] to unmarshalling
    /// every referrer: with it off the sweep must produce byte-identical results, only slower.
    private static final boolean SWEEP_SCAN = Conf.on(Conf.BLOB_SWEEP_SCAN);

    /// -Dtoolkit.blobSweep.verify=true runs the scan AND the unmarshal for every referrer and fails
    /// loudly on the first disagreement. Strictly slower than either path alone, so off by default.
    private static final boolean SWEEP_VERIFY = Conf.on(Conf.BLOB_SWEEP_VERIFY);

    /// `nameOfRefClass` is the one adapted attribute of the three (see the model).
    private static final jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter NAME_ADAPTER =
            new jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter();

    private long sweepScanRefused;

    /// Can this referrer's stored fragment possibly be changed by the sweep? Answered from the
    /// fragment's reference attributes ([RefPatcher#scan]) and the id index alone — no
    /// unmarshal, no reflection walk.
    ///
    /// Exact, because the sweep changes a reference only when BOTH (a) the exact id-index key
    /// built from its declared class+version misses and (b) the first id-prefix match's
    /// class/version differ from the declared ones. Both inputs are the reference's own
    /// `ref`/`version`/`nameOfRefClass`, which the fragment carries verbatim.
    ///
    /// What the fragment does not spell out is the declared class of a reference whose
    /// `nameOfRefClass` is absent or unregistered, since it is then derived from the reference's
    /// JAVA class. [RefElementIndex] supplies the classes the ELEMENT can bind to, and a verdict is
    /// taken only when all of them agree. Anything else — no such element, candidates disagree, a
    /// fragment that will not parse — returns "maybe" and is decided by the unmarshal.
    ///
    /// Conservatism runs ONE WAY: [RefPatcher#scan] reports a superset of the references JAXB
    /// binds and every open question answers "maybe", so a wrong answer costs a decode and a missed
    /// repair is unreachable.
    private boolean mayNeedBlobRepair(AbstractLmdbStore.LmdbTxn t, Cursor<byte[]> icur, long referrerFk) {
        int classIdx = NulKeyCodec.fullKeyClassIdx(referrerFk);
        Class<?> referrerClass = store.idxToClass.get(classIdx);
        if (referrerClass == null) return true; // loadObjectByFullKey would return null
        Dbi<byte[]> d = store.classDbi(t, classIdx, false);
        if (d == null) return true;
        byte[] stored = d.get(t.tx, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(referrerFk)));
        if (stored == null) return true;
        List<RefPatcher.Occurrence> refs = blobRepair.patcher().scan(store.codec.decompress(stored));
        if (refs == null) {
            sweepScanRefused++;
            return true; // not well-formed: let the unmarshal have it, as before
        }
        for (RefPatcher.Occurrence r : refs) {
            if (mayNeedRefRepair(t, icur, referrerClass, r)) return true;
        }
        return false;
    }

    /// One reference of [#mayNeedBlobRepair]; `r` = {ref, version, nameOfRefClass,
    /// elementLocalName}.
    ///
    /// The id-prefix test runs FIRST: it needs no target class, so a reference whose stored
    /// class+version already agree with its target is settled without ever naming the declared
    /// class. Only when the values differ does the exact-key guard decide.
    private boolean mayNeedRefRepair(AbstractLmdbStore.LmdbTxn t, Cursor<byte[]> icur, Class<?> referrerClass,
                                     RefPatcher.Occurrence r) {
        String id = r.ref();
        if (id == null) return false; // the reference walk skips a ref-less reference
        String version = r.version();
        String name = r.nameOfRefClass() == null ? null : NAME_ADAPTER.unmarshal(r.nameOfRefClass());

        byte[] prefix = NulKeyCodec.encodePrefix(id, null, null, false);
        // No stored object for the id: dangling, and the sweep leaves dangling references alone —
        // whatever the declared class is, and whether or not its exact key would have hit.
        if (!icur.get(prefix, GetOp.MDB_SET_RANGE)) return false;
        byte[] targetKey = icur.key();
        if (!NulKeyCodec.startsWith(targetKey, prefix)) return false;
        Class<?> targetClass = store.idxToClass.get(NulKeyCodec.fullKeyClassIdx(NulKeyCodec.u64le(icur.val())));
        if (targetClass == null) return false;
        String targetVersion = NulKeyCodec.versionPart(
                Arrays.copyOf(targetKey, NulKeyCodec.stripClazzLen(targetKey)));
        if (targetClass.getSimpleName().equals(name)
                && Objects.equals(version, targetVersion.isEmpty() ? null : targetVersion)) {
            return false; // the adoption would be a no-op: `changed` stays false either way
        }

        // The values differ, so the sweep repairs this reference UNLESS its exact key hits. A
        // registered nameOfRefClass IS the target class name; otherwise the fragment gives only an
        // element name — hence a candidate set, and a verdict only when every candidate agrees.
        String[] targets;
        if (name != null && ModelRegistry.isRegistered(name)) {
            targets = new String[] {name};
        } else {
            // A DIRECT child of the fragment root has a known owner class, which pins the type
            // even for the element names NeTEx reuses; anything deeper takes the global set.
            targets = r.depth() == 1 ? RefElementIndex.candidatesUnder(referrerClass, r.element()) : null;
            if (targets == null) targets = RefElementIndex.candidates(r.element());
        }
        if (targets == null) {
            if (SWEEP_DEBUG) sweepDebug("<unknown element " + r.element() + ">", id, version);
            return true;
        }
        for (String target : targets) {
            int idx;
            try {
                idx = store.idxForName(target);
            } catch (RuntimeException e) {
                return true; // not registered in THIS store: let the original path do whatever it does
            }
            byte[] exactKey = NulKeyCodec.encodeKey(id, version, NulKeyCodec.classIdxBytes(idx), true);
            if (store.idIdx.get(t.tx, exactKey) == null) {
                if (SWEEP_DEBUG) {
                    sweepDebug(referrerClass.getSimpleName() + "@" + r.depth() + " " + r.element()
                            + " " + name + "/" + target + " -> " + targetClass.getSimpleName(),
                            id, version + "->" + targetVersion);
                }
                return true; // this candidate would repair; ambiguity resolves the same way
            }
        }
        return false;
    }

    private static final boolean SWEEP_DEBUG = Conf.on(Conf.BLOB_SWEEP_DEBUG);
    private final Map<String, long[]> sweepDebugCounts = new HashMap<>();

    private void sweepDebug(String bucket, String id, String version) {
        long[] c = sweepDebugCounts.computeIfAbsent(bucket, k -> new long[1]);
        if (c[0]++ == 0) Log.info("[blobSweep.debug] first %s : ref=%s version=%s", bucket, id, version);
    }

    private void logSweepDebug() {
        sweepDebugCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(40)
                .forEach(e -> Log.info("[blobSweep.debug] %10d %s", e.getValue()[0], e.getKey()));
        sweepDebugCounts.clear();
    }
}
