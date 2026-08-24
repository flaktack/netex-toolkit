package toolkit.keycodec;

// THE ON-DISK KEY SHAPE, AS BYTES.
//
// NulKeyCodec is the only thing that decides what a stored key IS: `id NUL version NUL [classIdx]`,
// classIdx a u16 LE trailer, and the u64 LE full key that splits into (classIdx, local). Every
// other test in this suite reaches those bytes through a store, so all of them would keep passing
// after a change that altered the encoding CONSISTENTLY on both sides — write and read stay in
// agreement and every assertion about lookups, edges and prefixes still holds. What such a change
// breaks is not this process but the next one: an existing database, and every other
// reader, which does not go through this class at all.
//
// So the vectors below are written out by hand from the key-codec specification rather than
// derived from the code. They are the only assertions in the suite that a self-consistent
// re-encoding cannot satisfy.
//
// The version-less arm is not cosmetic: `version = null` and `version = "any"` must both encode to
// EMPTY between the two NULs, because that is the id-only key an unversioned *Ref resolves against
// (the tier-3 fallback) and the prefix an id-only lookup scans.

import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import java.util.Arrays;

public class TestKeyCodecGoldens {

    private static void bytes(byte[] expected, byte[] actual, String what) {
        Check.that(Arrays.equals(expected, actual),
                what + ": expected " + hex(expected) + " but was " + hex(actual));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < b.length; i++) sb.append(i == 0 ? "" : " ").append(String.format("%02X", b[i]));
        return sb.append(']').toString();
    }

    /// classIdx is a u16 LITTLE-endian trailer: 1332 = 0x0534 -> 34 05, not 05 34.
    @Test
    void testClassIdxIsTwoBytesLittleEndian() {
        bytes(new byte[] {0x34, 0x05}, NulKeyCodec.classIdxBytes(1332), "classIdxBytes(1332)");
        Check.equals(1332, NulKeyCodec.classIdxInt(new byte[] {0x34, 0x05}), "classIdxInt round-trips");
    }

    /// encode_key('ABC:1', '2', ServiceJourney(idx 1332), include_clazz=True).
    @Test
    void testEncodeKeyIsIdNulVersionNulClass() {
        bytes(new byte[] {0x41, 0x42, 0x43, 0x3A, 0x31, 0x00, 0x32, 0x00, 0x34, 0x05},
                NulKeyCodec.encodeKey("ABC:1", "2", NulKeyCodec.classIdxBytes(1332), true),
                "encodeKey id+version+class");
    }

    /// No version is an EMPTY field, i.e. two adjacent NULs — not an omitted one. A key that simply
    /// left the separator out would sort into a different place and would not be found by an
    /// id-only prefix scan.
    @Test
    void testAbsentVersionIsAnEmptyFieldAndAnyMeansAbsent() {
        byte[] clazz = {0x01, 0x00};
        bytes(new byte[] {0x41, 0x00, 0x00, 0x01, 0x00},
                NulKeyCodec.encodeKey("A", null, clazz, true), "encodeKey version=null");
        bytes(NulKeyCodec.encodeKey("A", null, clazz, true),
                NulKeyCodec.encodeKey("A", "any", clazz, true),
                "the literal version 'any' encodes exactly as no version");
    }

    /// A prefix carries only the parts it was given: 'A' NUL and nothing trailing.
    @Test
    void testPrefixStopsWhereItsArgumentsStop() {
        bytes(new byte[] {0x41, 0x00}, NulKeyCodec.encodePrefix("A", null, null, false),
                "encodePrefix id only");
        bytes(new byte[] {0x41, 0x00, 0x32, 0x00, 0x01, 0x00},
                NulKeyCodec.encodePrefix("A", "2", new byte[] {0x01, 0x00}, true),
                "encodePrefix with version+class is the full key shape");
    }

    /// Ids are UTF-8 bytes, not a charset the platform happens to have: 'ä' is C3 A4.
    @Test
    void testIdsAreUtf8() {
        bytes(new byte[] {(byte) 0xC3, (byte) 0xA4, 0x00, 0x00},
                NulKeyCodec.encodeKey("ä", null, null, false), "encodeKey utf8");
    }

    /// The full key is (classIdx, local) packed into one long, and u64le is its own inverse.
    @Test
    void testFullKeySplitsBackIntoClassAndLocal() {
        long fk = NulKeyCodec.fullKey(1332, 7);
        Check.equals(1332, NulKeyCodec.fullKeyClassIdx(fk), "fullKey keeps the class index");
        Check.equals(7, NulKeyCodec.fullKeyLocal(fk), "fullKey keeps the local key");
        Check.equals(fk, NulKeyCodec.u64le(NulKeyCodec.u64le(fk)), "u64le round-trips");
    }
}
