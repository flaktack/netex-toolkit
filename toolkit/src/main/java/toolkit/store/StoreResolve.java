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

    /// What [#duplicateEmbeddedIds] counted: how many embedded ids more than one live container
    /// claims, and up to the requested number of them for a message.
    record EmbeddedDuplicates(long ids, java.util.List<String> examples) {
        public EmbeddedDuplicates {
            examples = java.util.List.copyOf(examples);
        }
    }

    /// Every embedded id that more than one live container claims — the ambiguity
    /// [#resolveEmbeddings] has to pick a winner for, counted instead of resolved.
    ///
    /// Keyed as that pass keys it, by id alone: one id at two versions or under two classes is one
    /// id to the resolve, which selects containers by id prefix. Keys, not objects — naming the
    /// containers is [ObjectReader#loadObjectByFullKey]'s job.
    ///
    /// Scoped: an embedded class outside the embedding scope has no row here and is not counted.
    ///
    /// Zero where the database carries no index, which is also what the default answers. Zero is
    /// not "none" unless the index is also complete — ask
    /// `AbstractLmdbStore.embeddingIndexComplete` before reporting one.
    default EmbeddedDuplicates duplicateEmbeddedIds(Txn txn, int examples) {
        return new EmbeddedDuplicates(0, java.util.List.of());
    }

    /// Has this database's one-shot blob sweep already run?
    boolean blobRepairMarkerPresent(Txn txn);

    /// Record the sweep as satisfied — this is what a merge propagates to its target.
    void setBlobRepairMarker(Txn txn);
}
