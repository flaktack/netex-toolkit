package toolkit.store;

/// **Role: object reads.** Every way to get an object (or its undecoded blob) out of a class
/// map — whole-map iteration, key-only scans, point loads by local key, by full_key and by
/// reference.
///
/// Excludes the id index and the edge index: everything here is addressed by a key the caller
/// ALREADY holds. Turning an `id` into a key is [IdIndex]'s question, following an edge is
/// [RefGraph]'s. [#loadObjectByReference] is the exception because its ANSWER is an object — it
/// does the id-index hop internally and never shows the caller a full_key.
///
/// A holder cannot write, open a txn or name a class: it is handed a [Txn] and a [Class] and can
/// only read.
public interface ObjectReader {

    /// All (localKey, object) rows of a class map, decode included; empty when the map is absent.
    Iterable<ObjectRow> iterObjects(Txn txn, Class<?> clazz);

    /// Objects only.
    Iterable<Object> iterOnlyObjects(Txn txn, Class<?> clazz);

    /// Local keys only (no decode) — the cheap existence/iteration scan.
    Iterable<byte[]> scanObjects(Txn txn, Class<?> clazz);

    /// All (localKey, raw value blob) rows — no decode (the fragment-emission export path).
    Iterable<RawRow> iterRawObjects(Txn txn, Class<?> clazz);

    /// Raw value blob by class + 4-byte LE local key; null when absent (no decode).
    byte[] loadRawObject(Txn txn, Class<?> clazz, byte[] localKey);

    /// Decode one object by class + 4-byte LE local key; null when absent.
    Object loadObject(Txn txn, Class<?> clazz, byte[] localKey);

    /// Decode one object by 8-byte full_key; null when absent.
    Object loadObjectByFullKey(Txn txn, long fullKey);

    /// Exact (id, version, declared class) id-index hit when the ref carries a registered
    /// `nameOfRefClass`, else a bare-id prefix scan preferring the row whose class trailer matches
    /// the declared class, first row winning otherwise. With `missingOk` a dangling ref returns
    /// null; without it, it throws.
    Object loadObjectByReference(Txn txn, noi.netex.model.VersionOfObjectRefStructure ref,
                                 boolean missingOk);

    default Object loadObjectByReference(Txn txn, noi.netex.model.VersionOfObjectRefStructure ref) {
        return loadObjectByReference(txn, ref, false);
    }
}
