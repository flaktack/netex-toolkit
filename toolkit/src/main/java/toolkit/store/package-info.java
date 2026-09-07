/// **API.** The storage engine: keys, blobs, transactions, the class table, the reference graph.
///
/// [toolkit.store.Store] is the surface transformers code against, and it is the union of
/// seven narrower roles: [toolkit.store.StoreTxns], [toolkit.store.ClassRegistry],
/// [toolkit.store.ObjectReader], [toolkit.store.IdIndex], [toolkit.store.ObjectWriter],
/// [toolkit.store.RefGraph] and [toolkit.store.StoreResolve]. [toolkit.store.Stores] is the
/// factory; [toolkit.store.AbstractLmdbStore] the engine; `toolkit.store.v2` the one format.
///
/// The roles are flat and none extends another. `ObjectReader` in particular must not come to
/// require a [toolkit.store.Txn], which is LMDB-bound and cannot be faked by a test double.
/// `TestStoreRoles` pins this.
///
/// `StoreResolve` carries the rule that its methods open and commit their own write transaction:
/// never call one under an open `rwTxn`.
///
/// `copyMap` stays on `Store`: its default body reads through `this` and writes through `target`,
/// so it fits no single role.
///
/// Four package-private classes are the engine's other seam:
/// [toolkit.store.ReferenceResolver], [toolkit.store.EmbeddingResolver],
/// [toolkit.store.BlobRepair] and [toolkit.store.ClosureQueries] — below them the code is an LMDB
/// key-value engine, above them domain logic over the NeTEx object model.
///
/// What this package does not own: which NeTEx classes get an embedding-index row is profile policy
/// and arrives as a constructor input, so the engine names no NeTEx class.
package toolkit.store;
