package toolkit.store;

import java.nio.file.Path;

/// The transformer-facing storage SPI. One implementation, `toolkit.store.v2.V2Store`;
/// transformers code ONLY against this interface.
///
/// Store is the UNION of seven roles and declares almost nothing itself:
///
/// - [StoreTxns] — `roTxn`, `rwTxn`
/// - [ClassRegistry] — the class ↔ idx mapping and key arithmetic
/// - [ObjectReader] — object and raw-blob reads by a key you already hold
/// - [IdIndex] — NeTEx addresses to full_keys
/// - [ObjectWriter] — inserts, merge policy, sequence, clone
/// - [RefGraph] — edges, unresolved rows, CSR, closure
/// - [StoreResolve] — the resolution passes and the blob-repair marker
///
/// No call site is narrowed to a role: a "reader + registry" consumer would need
/// `<S extends ObjectReader & ClassRegistry>` intersection generics threaded through every caller.
/// What the split buys is that the roles are NAMED, and that a narrow test double is writable.
///
/// What is left here is what belongs to the handle rather than to any role: where the database
/// is, what format it is, whether it is writable, and closing it.
public interface Store extends StoreTxns, ClassRegistry, ObjectReader, IdIndex,
                               ObjectWriter, RefGraph, StoreResolve, AutoCloseable {

    Path path();

    /// The db's on-disk format marker ("java-v2:1"). NOT valid as a [Stores#create] format
    /// argument — use [#formatId()] for that.
    String formatMarker();

    /// This store's format as a [Stores] id — always [Stores#V2].
    String formatId();

    boolean isReadonly();

    /// Re-insert every object of a class into the target store (object-level,
    /// re-extracts refs). FIDELITY NOTE: this decodes to beans and re-inserts, so the target's
    /// blobs are JAXB re-marshals — source-verbatim captured fragments are NOT preserved (use the
    /// raw-fragment path if exported bytes must survive a transfer).
    ///
    /// The one member that stayed on `Store`: it reads through `this` as an [ObjectReader] and
    /// writes through `target` as an [ObjectWriter], so it belongs to neither role.
    default void copyMap(Txn txn, Store target, Txn targetTxn, Class<?> clazz) {
        target.insertAnyObjects(targetTxn, iterOnlyObjects(txn, clazz).iterator());
    }

    @Override
    void close();
}
