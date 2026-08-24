package toolkit.keycodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// The NUL-separated key codec.
///
/// Key layout: `id_utf8 . NUL . version_utf8 . NUL [. class_idx (2 bytes, fixed width LE)]`.
/// Ids/versions come from XML attribute values and XML 1.0 forbids NUL, so the separator can never
/// occur inside a fragment. `version in (null, "any")` is encoded as the empty string (the
/// two NULs are adjacent). The class idx is a FIXED-WIDTH 2-byte trailer, never separator-parsed
/// (its bytes are an arbitrary little-endian counter that may itself contain NUL).
///
/// full_key: `class_idx << 32 | local_key`, stored 8-byte LE.
public final class NulKeyCodec {
    public static final byte SEPARATOR = 0;
    public static final int CLASS_IDX_LEN = 2;

    private NulKeyCodec() {}

    /// Encode a full key; `classIdx` is the 2-byte LE class index, or null to omit the trailer.
    public static byte[] encodeKey(String id, String version, byte[] classIdx, boolean includeClazz) {
        byte[] idB = id.getBytes(StandardCharsets.UTF_8);
        byte[] verB = (version != null && !version.equals("any"))
                ? version.getBytes(StandardCharsets.UTF_8) : EMPTY;
        int clsLen = (includeClazz && classIdx != null) ? classIdx.length : 0;
        byte[] out = new byte[idB.length + 1 + verB.length + 1 + clsLen];
        System.arraycopy(idB, 0, out, 0, idB.length);
        out[idB.length] = SEPARATOR;
        System.arraycopy(verB, 0, out, idB.length + 1, verB.length);
        out[idB.length + 1 + verB.length] = SEPARATOR;
        if (clsLen > 0) System.arraycopy(classIdx, 0, out, idB.length + 2 + verB.length, clsLen);
        return out;
    }

    /// Encode a scan prefix. With a null/"any" version the prefix is
    /// just `id NUL` (matches every stored version of exactly this id).
    public static byte[] encodePrefix(String id, String version, byte[] classIdx, boolean includeClazz) {
        byte[] idB = id.getBytes(StandardCharsets.UTF_8);
        if (version == null || version.equals("any")) {
            byte[] out = Arrays.copyOf(idB, idB.length + 1);
            out[idB.length] = SEPARATOR;
            return out;
        }
        byte[] verB = version.getBytes(StandardCharsets.UTF_8);
        int clsLen = (includeClazz && classIdx != null) ? classIdx.length : 0;
        byte[] out = new byte[idB.length + 1 + verB.length + 1 + clsLen];
        System.arraycopy(idB, 0, out, 0, idB.length);
        out[idB.length] = SEPARATOR;
        System.arraycopy(verB, 0, out, idB.length + 1, verB.length);
        out[idB.length + 1 + verB.length] = SEPARATOR;
        if (clsLen > 0) System.arraycopy(classIdx, 0, out, idB.length + 2 + verB.length, clsLen);
        return out;
    }

    private static final byte[] EMPTY = new byte[0];

    // -- fixed-width class-idx trailer helpers ---------------------------------------------------

    /// The `id.NUL.version.NUL` prefix length of a key that carries a class trailer.
    public static int stripClazzLen(byte[] key) {
        return key.length - CLASS_IDX_LEN;
    }

    /// The 2-byte class trailer of such a key, as a little-endian int value.
    public static int classTrailer(byte[] key) {
        return (key[key.length - 2] & 0xFF) | ((key[key.length - 1] & 0xFF) << 8);
    }

    /// 'id.NUL' prefix length of an encoded key (index of first NUL, inclusive of the NUL).
    public static int idPrefixLen(byte[] key) {
        for (int i = 0; i < key.length; i++) {
            if (key[i] == 0) return i + 1;
        }
        throw new IllegalArgumentException("no NUL separator in key");
    }

    /// The id part of an `id.NUL.version.NUL` prefix.
    public static String idPart(byte[] idVersion) {
        int n = idPrefixLen(idVersion) - 1;
        return new String(idVersion, 0, n, StandardCharsets.UTF_8);
    }

    /// The version part of an `id.NUL.version.NUL` prefix ("" when absent).
    public static String versionPart(byte[] idVersion) {
        int start = idPrefixLen(idVersion);
        int end = idVersion.length;
        if (end > start && idVersion[end - 1] == 0) end--;
        return new String(idVersion, start, end - start, StandardCharsets.UTF_8);
    }

    // -- full_key helpers ------------------------------------------------------------------------

    /// full_key (u64) -> class idx (high 32 bits; 2 bytes used).
    public static int fullKeyClassIdx(long fullKey) {
        return (int) (fullKey >>> 32);
    }

    /// full_key (u64) -> local key (low 32 bits).
    public static int fullKeyLocal(long fullKey) {
        return (int) (fullKey & 0xFFFFFFFFL);
    }

    /// A full_key from a class idx and a local key.
    /// Byte-lexicographic order of two full keys AS STORED (little-endian), for deterministic
    /// re-insert orders.
    public static int compareFullKeysLe(long a, long b) {
        return Long.compareUnsigned(Long.reverseBytes(a), Long.reverseBytes(b));
    }

    public static long fullKey(int classIdx, int local) {
        return ((long) classIdx << 32) | (local & 0xFFFFFFFFL);
    }

    /// 2-byte LE class idx bytes for an int idx.
    public static byte[] classIdxBytes(int idx) {
        return new byte[] {(byte) (idx & 0xFF), (byte) ((idx >>> 8) & 0xFF)};
    }

    public static int classIdxInt(byte[] idx2) {
        return (idx2[0] & 0xFF) | ((idx2[1] & 0xFF) << 8);
    }

    public static byte[] u32le(int v) {
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    public static int u32le(byte[] b) {
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    public static byte[] u64le(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    public static long u64le(byte[] b) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[i] & 0xFFL) << (8 * i);
        return v;
    }

    public static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        return Arrays.mismatch(key, 0, prefix.length, prefix, 0, prefix.length) == -1;
    }

    public static boolean endsWith(byte[] key, byte[] suffix) {
        int off = key.length - suffix.length;
        if (off < 0) return false;
        return Arrays.mismatch(key, off, key.length, suffix, 0, suffix.length) == -1;
    }
}
