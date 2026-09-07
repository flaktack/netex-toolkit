package toolkit.store;

import org.lmdbjava.Dbi;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.codec.RefPatcher;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.ModelRegistry;
import toolkit.model.RecursiveAttributes;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/// Persisting an object whose only change is `version`/`nameOfRefClass` on some of its
/// references — the shared write end of both resolve passes.
///
/// One instance per store: [#patchStats] is a per-stage tally reset by [#logPatchStats], while the
/// [RefPatcher]'s refusal and odd-name diagnostics belong to the patcher instance and are
/// cumulative over the store.
final class BlobRepair {

    /// -Dtoolkit.refPatch=false reverts resolve() to the whole-blob re-marshal; the two paths must
    /// produce the same graph dump.
    private static final boolean REF_PATCH = Conf.on(Conf.REF_PATCH);

    /// -Dtoolkit.inventNameOfRefClass=false stops resolve() from adding a `nameOfRefClass`
    /// attribute to references whose source never carried one. It changes only whether an absent
    /// attribute is invented; one that exists is corrected either way.
    private static final boolean INVENT_NAME_OF_REF_CLASS =
            Conf.on(Conf.INVENT_NAME_OF_REF_CLASS);

    private final AbstractLmdbStore store;

    private final RefPatcher refPatcher = new RefPatcher();
    /// {patched, unchanged (no write needed), fallback re-marshals, conflicts, blob-missing}.
    private final long[] patchStats = new long[5];

    BlobRepair(AbstractLmdbStore store) {
        this.store = store;
    }

    /// The store's one patcher, for the blob sweep's decode-free read
    /// (`ReferenceResolver.mayNeedBlobRepair`). The refusal counters it accumulates must be the
    /// same ones the write path fills.
    RefPatcher patcher() {
        return refPatcher;
    }

    /// {@code {ref, version, nameOfRefClass}} of every reference, before resolve() touches it.
    static String[][] snapshotRefs(List<RecursiveAttributes.Ref> refs) {
        String[][] out = new String[refs.size()][];
        for (int i = 0; i < refs.size(); i++) {
            VersionOfObjectRefStructure r = refs.get(i).refObject();
            out[i] = new String[] {r.getRef(), r.getVersion(), r.getNameOfRefClass()};
        }
        return out;
    }

    /// The stored-row key of a reference as the insert path would encode it right now.
    byte[] encodeLiveRef(VersionOfObjectRefStructure r) {
        String name = r.getNameOfRefClass();
        if (name == null || !ModelRegistry.isRegistered(name)) {
            name = RecursiveAttributes.deriveTargetName(r.getClass());
            if (!ModelRegistry.isRegistered(name)) name = "DataManagedObject";
        }
        return NulKeyCodec.encodeKey(r.getRef(), r.getVersion(),
                NulKeyCodec.classIdxBytes(store.idxForName(name)), true);
    }

    /// Re-marshal a repaired object over its stored blob — the fallback path, used whenever the
    /// byte-level patch cannot be proven safe. Lossy: it replaces the source-captured bytes with a
    /// marshalled fragment.
    private void writeBlob(AbstractLmdbStore.LmdbTxn t, long fullKey, Object obj) {
        int idx = NulKeyCodec.fullKeyClassIdx(fullKey);
        store.classDbi(t, idx, true).put(t.tx,
                NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fullKey)), store.codec.encode(obj));
    }

    /// Persist an object whose only change is `version`/`nameOfRefClass` on some of its
    /// references — by rewriting those start-tag attributes in the stored bytes rather than
    /// re-marshalling the whole fragment.
    void writeRepairedBlob(AbstractLmdbStore.LmdbTxn t, long fullKey, Object obj,
                           List<RecursiveAttributes.Ref> refs, String[][] before) {
        if (!REF_PATCH) {
            writeBlob(t, fullKey, obj);
            patchStats[2]++;
            return;
        }
        Map<RefPatcher.Key, RefPatcher.Val> patches =
                RefPatcher.patchSet(before, snapshotRefs(refs), INVENT_NAME_OF_REF_CLASS);
        if (patches == null) {
            patchStats[3]++;
            patchStats[2]++;
            writeBlob(t, fullKey, obj);
            return;
        }
        if (patches.isEmpty()) {
            // No reference attribute moved: the stored fragment already says what the bean says, so
            // writing here would be a lossy re-marshal for nothing.
            patchStats[1]++;
            return;
        }
        int idx = NulKeyCodec.fullKeyClassIdx(fullKey);
        Dbi<byte[]> d = store.classDbi(t, idx, true);
        byte[] stored = d.get(t.tx, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fullKey)));
        byte[] patched = stored == null ? null : refPatcher.patch(store.codec.decompress(stored), patches);
        if (patched == null) {
            if (stored == null) patchStats[4]++;
            patchStats[2]++;
            writeBlob(t, fullKey, obj);
            return;
        }
        d.put(t.tx, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fullKey)), store.codec.encodeRaw(patched));
        patchStats[0]++;
    }

    /// One line per resolve stage so the fallback rate is visible in every load log. The per-stage
    /// outcome counters are reset here; the [RefPatcher]'s diagnostics are not.
    void logPatchStats(String stage) {
        Log.info("[%s] blob repair: %d patched in place, %d needed no write, %d re-marshal "
                        + "fallbacks (%d attribute conflicts, %d missing blobs); %d odd-named ref elements",
                stage, patchStats[0], patchStats[1], patchStats[2], patchStats[3], patchStats[4],
                refPatcher.oddlyNamedMatches());
        Log.info("[%s] blob repair refusals {cr, malformed, offsets, unlanded, tagscan} = %s"
                        + " (these two counters are cumulative over the store, not per stage)",
                stage, Arrays.toString(refPatcher.refusals()));
        Arrays.fill(patchStats, 0);
    }
}
