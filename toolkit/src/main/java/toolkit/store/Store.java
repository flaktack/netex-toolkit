package toolkit.store;

import java.nio.file.Path;

/// The transformer-facing storage SPI. One implementation, `toolkit.store.v2.V2Store`;
/// transformers code only against this interface.
///
/// Store is the union of the seven role interfaces it extends and declares almost nothing itself:
/// what is left here belongs to the handle rather than to any role. No call site is narrowed to a
/// single role.
public interface Store extends StoreTxns, ClassRegistry, ObjectReader, IdIndex,
                               ObjectWriter, RefGraph, StoreResolve, AutoCloseable {

    Path path();

    /// The db's on-disk format marker ("java-v2:1"). Not valid as a [Stores#create] format
    /// argument — use [#formatId()] for that.
    String formatMarker();

    /// This store's format as a [Stores] id — always [Stores#V2].
    String formatId();

    boolean isReadonly();

    /// Re-insert every object of a class into the target store (object-level, re-extracts refs).
    /// This decodes to beans and re-inserts, so the target's blobs are JAXB re-marshals —
    /// source-verbatim captured fragments are not preserved (use the raw-fragment path if exported
    /// bytes must survive a transfer).
    default void copyMap(Txn txn, Store target, Txn targetTxn, Class<?> clazz) {
        target.insertAnyObjects(targetTxn, iterOnlyObjects(txn, clazz).iterator());
    }

    @Override
    void close();
}
