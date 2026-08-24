package toolkit.store;

/// One unresolved reference: referrer full_key -> encoded target key (id.NUL.version.NUL.class2).
///
/// `internId` is the row's id in the store's `_unres_keys` table, or `-1` when the producer cannot
/// supply one. It is NOT part of the row's identity: rows with the same `encodedTarget` share an
/// intern id, which is what makes it usable as a memo index. A producer that does not intern passes
/// `-1`, and consumers must then fall back to the key bytes.
public record UnresolvedRow(long referrerFullKey, byte[] encodedTarget, int internId) {
    public UnresolvedRow(long referrerFullKey, byte[] encodedTarget) {
        this(referrerFullKey, encodedTarget, -1);
    }
}
