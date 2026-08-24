package toolkit.store;

/// **Role: transaction lifecycle.** The two txn factories, and nothing else — this interface
/// cannot read a row, name a class or write anything, so a consumer that only has to hand a txn
/// around (a driver opening a snapshot for someone else to read under) can say so in its type.
///
/// The roles are NAMED, not narrowed: no call site is retyped to one, which would need
/// `<S extends A & B>` intersections everywhere. What they buy is a writable narrow test double.
public interface StoreTxns {

    /// Read-only snapshot txn. A txn is valid ONLY against the store that created it (a foreign
    /// txn throws from every operation), on the thread that created it; the Iterables the read
    /// methods return are single-use cursor views bound to it.
    Txn roTxn();

    /// The single write txn (LMDB single-writer). COMMIT IS EXPLICIT: closing without
    /// [Txn#commit()] aborts and discards; there is no flush-on-close anywhere. Do not call
    /// [StoreResolve#resolve()] / [StoreResolve#resolveEmbeddings()] while a write txn
    /// is open — they open their own and the single-writer lock deadlocks.
    Txn rwTxn();
}
