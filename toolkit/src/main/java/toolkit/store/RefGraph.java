package toolkit.store;

import toolkit.graph.Csr;

import java.util.Iterator;

/// **Role: the reference graph.** Resolved edges, unresolved rows, the CSR adjacency built over
/// them, and the closure walk that turns a set of classes into the objects they reach.
///
/// It READS the edge index and does not maintain it: wiring an unresolved row into an edge is
/// [StoreResolve]'s job, inserting is [ObjectWriter]'s. Edge state is the expensive thing in the
/// store, and the streaming variants below exist so a read-only consumer can avoid materialising it.
///
/// [#fetchAllReferencesByClass] is the one method that hands back objects rather than
/// keys, because its whole purpose is the export payload; it decodes only at emission.
public interface RefGraph {

    /// Every resolved reference edge as {referrer full_key, target full_key}.
    Iterable<long[]> iterEdges(Txn txn);

    /// Every unresolved reference row.
    Iterable<UnresolvedRow> iterUnresolved(Txn txn);

    /// [#iterEdges] again — **the same rows, in the same order** — but for a consumer that
    /// intends to FILTER rather than to keep.
    ///
    /// The contract is IDENTITY: it must yield exactly the sequence `iterEdges` yields on the same
    /// `txn` — same pairs, same order, same tombstone exclusions — and nothing may branch on which
    /// was called. Hence a `default` of literally `return iterEdges(txn)`; an override can buy only
    /// a different MEMORY PROFILE.
    ///
    /// It exists because `iterEdges` is a view over materialised state — v2 concatenates every block
    /// into slot arrays before the first pair comes out.
    ///
    /// An override may hold one block at a time and resolve side tables by point lookup. It may NOT
    /// reorder: v2's block numbers are u32 LE in a byte-ordered map, so cursor order stops being
    /// numeric order past block 255, and [#fetchAllReferencesByClass]'s determinism is defined in
    /// terms of edge order.
    ///
    /// The returned `Iterable` is single-use and bound to `txn` and its thread.
    default Iterable<long[]> scanEdges(Txn txn) {
        return iterEdges(txn);
    }

    /// [#iterUnresolved] streamed, under the identity contract of [#scanEdges].
    ///
    /// It trades memory for time, and unlike [#scanEdges] the trade is STEEP: an unresolved row's
    /// target key is interned, so a streaming reader that does not hold the intern table pays one
    /// point lookup per emitted row.
    ///
    /// So this is for a consumer that filters, stops early, or runs against a small unresolved set;
    /// full enumeration of a large one is what [#iterUnresolved] is for.
    default Iterable<UnresolvedRow> scanUnresolved(Txn txn) {
        return iterUnresolved(txn);
    }

    /// Forward+inverse CSR over the current edge set (A4), cached and invalidated by any edge mutation.
    Csr csr(Txn txn);

    /// The reference-graph closure over the outward-edge index. Seeded from the OUTGOING edges of
    /// objects whose class is in `clazzes`, so a source object is emitted only when it is itself a
    /// collected target and `skipExisting` is false. The fixpoint then adds the collected targets'
    /// own targets, skipping classes in `clazzes` — those are already exported — until no new key
    /// appears. Emission decodes each collected full_key; `skipExisting` drops in-set classes
    /// there, having collected them regardless during the seed pass.
    ///
    /// Ordering is DETERMINISTIC — discovery order: the seed scan over the edge set in ascending
    /// unsigned full_key order, then per fixpoint round. Unresolved references contribute nothing;
    /// the closure walks the resolved edge index only. Single-use, bound to `txn`.
    Iterator<Object> fetchAllReferencesByClass(Txn txn, java.util.Set<Class<?>> clazzes, boolean skipExisting);
}
