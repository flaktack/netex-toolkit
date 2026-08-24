/// **API.** The storage engine: keys, blobs, transactions, the class table, the reference graph.
///
/// [toolkit.store.Store] is the surface transformers code against, and it is the UNION of
/// seven narrower roles rather than a list of methods:
/// [toolkit.store.StoreTxns], [toolkit.store.ClassRegistry],
/// [toolkit.store.ObjectReader], [toolkit.store.IdIndex],
/// [toolkit.store.ObjectWriter], [toolkit.store.RefGraph] and
/// [toolkit.store.StoreResolve]. [toolkit.store.Stores] is the factory;
/// [toolkit.store.AbstractLmdbStore] the engine; `toolkit.store.v2` the one format.
///
/// THE ROLES ARE FLAT and none extends another. `ObjectReader` not extending
/// [toolkit.store.StoreTxns] is what lets a test double implement it in thirty lines: the double
/// never has to produce a [toolkit.store.Txn], which is the part that cannot be faked, being
/// LMDB-bound. `TestStoreRoles` pins this.
///
/// `StoreTxns` and `StoreResolve` are documentation rather than structure; nothing will implement
/// them separately. `StoreResolve` gives the "opens and commits its own write transaction, so never
/// call it under an open `rwTxn`" rule a role to live in.
///
/// `copyMap` stays on `Store`: its default body reads through `this` and writes through `target`,
/// so it belongs to the union and nowhere narrower.
///
/// Four package-private classes are the engine's other seam:
/// [toolkit.store.ReferenceResolver], [toolkit.store.EmbeddingResolver],
/// [toolkit.store.BlobRepair] and [toolkit.store.ClosureQueries] — below them the code is an LMDB
/// key-value engine, above them domain logic over the NeTEx object model.
///
/// What this package does NOT own: which NeTEx classes get an embedding-index row is profile policy
/// and arrives as a constructor input, so the engine names no NeTEx class.
package toolkit.store;
