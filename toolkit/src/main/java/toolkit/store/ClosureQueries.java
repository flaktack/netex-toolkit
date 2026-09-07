package toolkit.store;

import toolkit.graph.Csr;
import toolkit.keycodec.NulKeyCodec;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Set;

/// The two [RefGraph] reads that walk the edge index rather than serve one row: the CSR build
/// and the reference-graph closure behind the export payload. Both are a pure function of the edge
/// set and `clazzes` — no cursor is held and nothing is written.
final class ClosureQueries {

    private final AbstractLmdbStore store;

    ClosureQueries(AbstractLmdbStore store) {
        this.store = store;
    }

    /// [RefGraph#csr]: forward+inverse CSR over the current edge set.
    Csr csr(Txn txn) {
        it.unimi.dsi.fastutil.longs.LongArrayList src = new it.unimi.dsi.fastutil.longs.LongArrayList();
        it.unimi.dsi.fastutil.longs.LongArrayList dst = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (long[] e : store.iterEdges(txn)) {
            src.add(e[0]);
            dst.add(e[1]);
        }
        return Csr.build(src.toLongArray(), dst.toLongArray());
    }

    /// [Store#fetchAllReferencesByClass]. The seed pass walks all edges grouped by referrer and the
    /// fixpoint uses [Csr#out]; sets are insertion-ordered, which is what makes the emission
    /// deterministic.
    Iterator<Object> fetchAllReferencesByClass(Txn txn, Set<Class<?>> clazzes, boolean skipExisting) {
        AbstractLmdbStore.LmdbTxn t = store.cast(txn);
        Csr csr = csr(t);
        LinkedHashSet<Long> yielded = new LinkedHashSet<>();
        LinkedHashSet<Long> partial = new LinkedHashSet<>();
        // Seed: every target referenced by an object of the given classes. No class filter on the
        // target here — skipExisting handles those at emission.
        for (int i = 0; i < csr.n; i++) {
            Class<?> referrer = store.idxToClass.get(NulKeyCodec.fullKeyClassIdx(csr.nodes[i]));
            if (referrer == null || !clazzes.contains(referrer)) continue;
            for (long o = csr.fwdOff[i]; o < csr.fwdOff[i + 1]; o++) {
                long target = csr.fwdKeys[(int) o];
                if (yielded.add(target)) partial.add(target);
            }
        }
        // Fixpoint: collected targets may reference further objects; targets whose class is in
        // clazzes are skipped (they are already being exported by the caller).
        while (!partial.isEmpty()) {
            LinkedHashSet<Long> partialNew = new LinkedHashSet<>();
            for (long key : partial) {
                for (long target : csr.out(key)) {
                    Class<?> targetClass = store.idxToClass.get(NulKeyCodec.fullKeyClassIdx(target));
                    if (targetClass != null && clazzes.contains(targetClass)) continue;
                    if (!yielded.contains(target)) partialNew.add(target);
                }
            }
            if (partialNew.isEmpty()) break;
            yielded.addAll(partialNew);
            partial = partialNew;
        }
        Iterator<Long> keys = yielded.iterator();
        return new Iterator<>() {
            private Object next;
            private boolean ready;

            @Override
            public boolean hasNext() {
                while (!ready && keys.hasNext()) {
                    long fk = keys.next();
                    if (skipExisting && skippableWithoutDecoding(t, fk, clazzes)) continue;
                    Object o = store.loadObjectByFullKey(t, fk);
                    if (o == null) continue; // resolved edges point at existing rows; guard anyway
                    if (skipExisting && clazzes.contains(o.getClass())) continue;
                    next = o;
                    ready = true;
                }
                return ready;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                ready = false;
                Object o = next;
                next = null;
                return o;
            }
        };
    }

    /// `skipExisting`'s question — "is this row's class one of `clazzes`?" — answered without a
    /// JAXB unmarshal, or `false` meaning "cannot answer, decode it".
    ///
    /// A row's class is its full key's class index, and `loadObjectByFullKey` unmarshals with
    /// exactly that class as the declared type — so the two agree, except that JAXB honours an
    /// `xsi:type` on the fragment root and may return a different class, which `Set.contains`
    /// (exact class, not assignability) judges differently. So this reads the stored blob and
    /// refuses to answer whenever the root start tag carries an `xsi:type`.
    private boolean skippableWithoutDecoding(AbstractLmdbStore.LmdbTxn t, long fullKey,
                                             Set<Class<?>> clazzes) {
        Class<?> clazz = store.idxToClass.get(NulKeyCodec.fullKeyClassIdx(fullKey));
        if (clazz == null || !clazzes.contains(clazz)) return false; // decode: it may be emitted
        byte[] blob = store.loadRawObject(t, clazz, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fullKey)));
        if (blob == null) return false; // absent row: let the load path report it as it always did
        return !toolkit.codec.FragmentPeek.rootHasTypeAttribute(store.codec.decompress(blob));
    }
}
