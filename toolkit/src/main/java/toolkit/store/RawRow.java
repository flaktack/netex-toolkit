package toolkit.store;

/// One class-map row as stored: 4-byte LE local key plus the raw value blob
/// (`[4B LE origLen][LZ4 block]`), no decode.
public record RawRow(byte[] localKey, byte[] blob) {}
