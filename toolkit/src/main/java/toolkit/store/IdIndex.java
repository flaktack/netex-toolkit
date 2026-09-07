package toolkit.store;

import java.util.List;

/// Role: the `_id_idx` address book. NeTEx addresses — (id, version, class) — in, full_keys out,
/// plus the whole-index pass. This is the only place in the SPI that knows what an `id` is.
///
/// It returns keys, never objects: decoding is [ObjectReader#loadObjectByFullKey]'s job.
public interface IdIndex {

    /// id-index lookup: exact (id, version, class) key when version != null, otherwise the first
    /// row at the `id NUL` prefix. Returns the full_key or -1.
    ///
    /// `clazz` must not be null, and on the `version == null` arm it is a precondition rather than
    /// a filter. That arm answers "the first row at the `id NUL` prefix" — the class trailer is not
    /// part of the prefix, so the row returned may belong to any class, and the only thing the
    /// argument does is fail fast for an unregistered class ([ClassRegistry#classIdx] throws). Do
    /// not read a class-scoped answer out of it. An address that genuinely carries no class is
    /// [#lookupById]'s question, not this one's.
    long lookupFullKey(Txn txn, String id, String version, Class<?> clazz);

    /// The class-free address lookup: every `_id_idx` row at the `id NUL` prefix (or
    /// `id NUL version NUL` when `version != null`), in key order — i.e. every stored version and
    /// every class of exactly this id. Empty when the id has no row.
    ///
    /// The contract is identity with a filtered [#iterIdIndex]: same rows, same order, same encoded
    /// keys and full_keys. An override buys only a cost profile, since `_id_idx` is byte-ordered
    /// and an id's rows are a contiguous run. Same rule as [RefGraph#scanEdges].
    ///
    /// The result is materialised: it is bounded by the rows at the prefix, so a `List` spares the
    /// caller the cursor-lifetime rule. Rows come back in index order and choosing among them is
    /// the caller's decision.
    default List<IdRow> lookupById(Txn txn, String id, String version) {
        byte[] prefix = toolkit.keycodec.NulKeyCodec.encodePrefix(id, version, null, false);
        List<IdRow> out = new java.util.ArrayList<>();
        for (IdRow row : iterIdIndex(txn)) {
            if (toolkit.keycodec.NulKeyCodec.startsWith(row.encodedKey(), prefix)) {
                out.add(row);
            } else if (!out.isEmpty()) {
                break;   // byte-ordered index: the run at a prefix is contiguous
            }
        }
        return out;
    }

    /// Every `_id_idx` row (encoded key + full_key), in key order. The raw counterpart of
    /// [#lookupFullKey] for whole-index passes.
    Iterable<IdRow> iterIdIndex(Txn txn);

    /// Exact id-index probe by an already-encoded (id, version, class) key; -1 when absent.
    long lookupFullKeyRaw(Txn txn, byte[] encodedIdKey);
}
