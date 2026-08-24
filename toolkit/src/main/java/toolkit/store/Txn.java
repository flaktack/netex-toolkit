package toolkit.store;

/// Thin transaction handle so scripts read naturally:
///
/// ```
/// try (var stx = src.roTxn(); var dtx = dst.rwTxn()) {
///     ...
///     dtx.commit();
/// }
/// ```
///
/// Closing without [#commit()] aborts (write txns) / releases (read txns). A store's Txn is
/// only valid against the store that created it.
public interface Txn extends AutoCloseable {
    void commit();

    boolean isReadOnly();

    @Override
    void close();
}
