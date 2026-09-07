package toolkit.harness;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import toolkit.keycodec.NulKeyCodec;
import toolkit.store.BytesKey;
import toolkit.store.IdRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/// Shared clone-side helpers of the multi-source and overlay drivers — the clone-plus-patch idiom.
public final class CloneSupport {

    private CloneSupport() {}

    /// The source full_keys of every object whose (id, version, class) key already exists in the
    /// target's id index (raw probe, no deserialisation).
    public static LongOpenHashSet duplicateIdFullKeys(Store source, Txn sourceTxn, Store target, Txn targetTxn) {
        LongOpenHashSet dups = new LongOpenHashSet();
        for (IdRow row : source.iterIdIndex(sourceTxn)) {
            if (target.lookupFullKeyRaw(targetTxn, row.encodedKey()) != -1) dups.add(row.fullKey());
        }
        return dups;
    }

    /// Referrers of a skipped row, outside the skip and re-inserted sets — the resolved half only.
    public static LongOpenHashSet referrerFullKeys(Store source, Txn sourceTxn,
                                                   LongOpenHashSet skip, LongOpenHashSet reinserted) {
        return referrerFullKeys(source, sourceTxn, skip, reinserted, Set.of());
    }

    /// Both halves of the referrer scan — the full_keys of every object that must be re-inserted
    /// rather than left as raw-cloned bytes:
    ///
    /// - resolved referrers of `skip` (the rows being skipped by the clone): the clone drops an
    ///    index row whose referenced side was skipped, so the edge has to be re-extracted onto the
    ///    new full_key — one pass over the outward edge index;
    /// - unresolved referrers of `ids` (the ids being qualified): their stored ref string still
    ///    names the old id; left alone, the final resolve() would silently bind them to an earlier
    ///    source's object. One [Store#iterUnresolved] pass; a row matches when its encoded target's
    ///    id — the value bytes before the first NUL of the `id NUL version NUL class2` layout; the
    ///    version is ignored, it being why the ref did not resolve — equals a member of `ids` as
    ///    raw UTF-8 bytes.
    ///
    /// The union minus `skip` (and, when non-null, `reinserted`) is returned.
    public static LongOpenHashSet referrerFullKeys(Store source, Txn sourceTxn,
                                                   LongOpenHashSet skip, LongOpenHashSet reinserted,
                                                   Set<String> ids) {
        LongOpenHashSet patch = new LongOpenHashSet();
        if (!skip.isEmpty()) {
            for (long[] e : source.iterEdges(sourceTxn)) {
                if (skip.contains(e[1])) patch.add(e[0]);
            }
        }
        if (!ids.isEmpty()) {
            Set<BytesKey> idBytes = new HashSet<>();
            for (String id : ids) idBytes.add(new BytesKey(id.getBytes(StandardCharsets.UTF_8)));
            for (UnresolvedRow row : source.iterUnresolved(sourceTxn)) {
                byte[] enc = row.encodedTarget();
                // bytes before the first NUL (whole value if none).
                int n = 0;
                while (n < enc.length && enc[n] != 0) n++;
                if (idBytes.contains(new BytesKey(Arrays.copyOf(enc, n)))) patch.add(row.referrerFullKey());
            }
        }
        patch.removeAll(skip);
        if (reinserted != null) patch.removeAll(reinserted);
        return patch;
    }

    /// The patch set's objects in sorted order (byte-lexicographic over the stored little-endian
    /// keys); missing rows are skipped. Lazy: the keys are sorted up front but each object is
    /// decoded only when the iterator advances. The source txn must stay open while it is consumed.
    public static Iterator<Object> loadPatchObjects(Store source, Txn sourceTxn, LongOpenHashSet patch) {
        LongArrayList sorted = new LongArrayList(patch);
        sorted.sort((a, b) -> NulKeyCodec.compareFullKeysLe(a, b));
        return new Iterator<>() {
            private int i = 0;
            private Object next;

            @Override
            public boolean hasNext() {
                while (next == null && i < sorted.size()) {
                    next = source.loadObjectByFullKey(sourceTxn, sorted.getLong(i++));
                }
                return next != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object out = next;
                next = null;
                return out;
            }
        };
    }
}
