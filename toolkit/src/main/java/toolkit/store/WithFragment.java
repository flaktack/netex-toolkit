package toolkit.store;

/// Insert-path wrapper pairing a bound model object with its already-captured XML fragment, so the
/// value encode is LZ4-only. A plain object is marshalled by the codec instead; an insert iterator
/// may mix both.
public record WithFragment(Object object, byte[] fragmentXml) {}
