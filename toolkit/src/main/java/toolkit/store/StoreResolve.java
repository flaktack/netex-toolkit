package toolkit.store;

/// Role: reference-index maintenance. The two resolution passes that turn unresolved reference
/// rows into edges, plus the one-shot blob-repair marker that records whether a database's sweep
/// has already run.
///
/// A mutator that takes no write txn: [#resolve()] and [#resolveEmbeddings()] open and commit their
/// own, so calling either while a [StoreTxns#rwTxn()] is open deadlocks on LMDB's single-writer
/// lock. Run them between transactions.
public interface StoreResolve {

    /// Wire every unresolved reference whose target id now exists in the id index into the outward
    /// reference index (exact key, then id+version-any-class, then id-any-version-preferring-class
    /// — the three tiers), repairing the referring blob's declared class/version on tier-2/3
    /// matches, plus the marker-gated one-shot blob-repair sweep. Opens and commits its own write
    /// transaction. Returns the number of references resolved.
    long resolve();

    /// Resolve unresolved references whose target is an embedded
    /// object (no own row) by walking container objects and wiring the reference to the embedding
    /// container — exact tier plus the order-sensitive almost-match queue with phase-B blob repair.
    /// Container discovery uses the persisted scoped embedding index when this database carries a
    /// complete one, else the candidate-class corpus scan. Returns resolutions + repairs.
    long resolveEmbeddings();

    /// Has this database's one-shot blob sweep already run?
    boolean blobRepairMarkerPresent(Txn txn);

    /// Record the sweep as satisfied — this is what a merge propagates to its target.
    void setBlobRepairMarker(Txn txn);
}
