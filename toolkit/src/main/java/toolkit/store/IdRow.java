package toolkit.store;

/// One `_id_idx` row: NulKeyCodec-encoded (id, version, class) key -&gt; 8-byte full_key.
public record IdRow(byte[] encodedKey, long fullKey) {}
