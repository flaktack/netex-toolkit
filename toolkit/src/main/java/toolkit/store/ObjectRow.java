package toolkit.store;

/// One class-map row: the 4-byte LE local key and the decoded model object.
public record ObjectRow(byte[] localKey, Object object) {}
