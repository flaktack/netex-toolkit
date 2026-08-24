package noi.netex.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.time.ZoneOffset;
import noi.netex.Corpus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class XmlTimeTest {

    @Test
    @DisplayName("every corpus value round-trips byte-exactly")
    void roundTripIsByteExact() {
        for (String lexical : Corpus.TIMES) {
            XmlTime t = XmlTime.parse(lexical);
            assertEquals(lexical, t.toXmlFormat(), lexical + " must re-marshal verbatim");
            assertEquals(lexical, t.toString());
            assertEquals(lexical, t.getLexical());
            assertFalse(t.isUnparsed(), lexical + " must parse");
        }
    }

    @Test
    @DisplayName("the hour is raw: 24:09:00 is hour 24, day offset 1")
    void hourIsRaw() {
        XmlTime t = XmlTime.parse("24:09:00");
        assertEquals(24, t.getHour());
        assertEquals(1, t.getDayOffset());
        assertEquals(9, t.getMinute());
        assertEquals(LocalTime.of(0, 9), t.toLocalTime());

        assertEquals(25, XmlTime.parse("25:15:00").getHour());
        assertEquals(1, XmlTime.parse("25:15:00").getDayOffset());
        assertEquals(30, XmlTime.parse("30:05:00").getHour());
        assertEquals(1, XmlTime.parse("30:05:00").getDayOffset());

        assertEquals(7, XmlTime.parse("07:30:00").getHour());
        assertEquals(0, XmlTime.parse("07:30:00").getDayOffset());
    }

    @Test
    @DisplayName("24:00:00 does not become 00:00:00 on the wire")
    void midnightEndOfDaySurvives() {
        XmlTime t = XmlTime.parse("24:00:00");
        assertEquals("24:00:00", t.toXmlFormat());
        assertEquals(24, t.getHour());
        assertEquals(1, t.getDayOffset());
        // ...while the normalisation still reproduces the old binding exactly.
        assertEquals(LocalTime.of(0, 0), t.toLocalTime());
    }

    @Test
    @DisplayName("the offset is preserved, and null when the source carried none")
    void offsetIsPreserved() {
        assertEquals(ZoneOffset.ofHours(1), XmlTime.parse("18:51:00+01:00").getOffset());
        assertEquals(ZoneOffset.UTC, XmlTime.parse("18:51:00Z").getOffset());
        assertEquals(ZoneOffset.ofHoursMinutes(-3, -30), XmlTime.parse("18:51:00-03:30").getOffset());
        assertNull(XmlTime.parse("18:51:00").getOffset());
        // ...and the normalisation ignores it, exactly as the old binding did.
        assertEquals(LocalTime.of(18, 51), XmlTime.parse("18:51:00+01:00").toLocalTime());
    }

    @Test
    @DisplayName("fractional seconds keep full nanosecond precision")
    void fractionalSeconds() {
        assertEquals(500_000_000, XmlTime.parse("10:00:00.5").getNano());
        assertEquals(123_000_000, XmlTime.parse("10:00:00.123").getNano());
        assertEquals(123_456_789, XmlTime.parse("10:00:00.123456789").getNano());
        assertEquals("10:00:00.123456789", XmlTime.parse("10:00:00.123456789").toXmlFormat());
        assertEquals(LocalTime.of(10, 0, 0, 123_456_789),
                XmlTime.parse("10:00:00.123456789").toLocalTime());
    }

    @Test
    @DisplayName("synthesized values carry no lexical and re-emit canonically")
    void synthesizedValuesCanonicalise() {
        XmlTime t = XmlTime.of(24, 9, 0);
        assertNull(t.getLexical());
        assertEquals("24:09:00", t.toXmlFormat());
        assertEquals(24, t.getHour());

        XmlTime f = XmlTime.from(LocalTime.of(7, 5, 3, 400_000_000));
        assertNull(f.getLexical());
        assertEquals("07:05:03.400", f.toXmlFormat());
        assertNull(f.getOffset());
    }

    @Test
    @DisplayName("unparseable input is retained verbatim, never thrown")
    void unparseableInputIsRetained() {
        for (String junk : new String[] {"not-a-time", "12:60:00", "12:00", "", "  "}) {
            XmlTime t = XmlTime.parse(junk);
            if (junk.isBlank()) {
                assertNull(t, "blank input is null");
                continue;
            }
            assertTrue(t.isUnparsed(), junk + " must be an unparsed carrier");
            assertNull(t.toLocalTime(), junk + " must not normalise");
            assertEquals(junk, t.getLexical());
            assertEquals(junk, t.toXmlFormat(), junk + " must re-marshal verbatim");
        }
        assertNull(XmlTime.parse(null));
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed")
    void whitespaceIsTrimmed() {
        XmlTime t = XmlTime.parse("  24:09:00\n");
        assertFalse(t.isUnparsed());
        assertEquals("24:09:00", t.toXmlFormat());
    }

    /// The boundary [XmlTime#isXsdValid()] draws is sharp: hour 24 iff minute, second and
    /// fraction are all zero. Consumers key their reading of an after-midnight value off it.
    @Test
    @DisplayName("isXsdValid is the source boundary: hour 24 iff m=s=nano=0")
    void xsdValidityBoundary() {
        // Accepted (an offset and a zero fraction are fine).
        for (String ok : new String[] {
                "00:00:00", "09:30:00", "23:59:59", "23:59:59.999999999",
                "24:00:00", "24:00:00.000", "24:00:00Z", "24:00:00+01:00", "24:00:00-05:30"}) {
            assertTrue(XmlTime.parse(ok).isXsdValid(), ok + " is legal xs:time");
        }
        // Rejected -> the raw string is kept, which has no .hour at all.
        for (String bad : new String[] {
                "24:00:01", "24:00:00.001", "24:01:00", "24:03:00", "24:09:00",
                "25:00:00", "25:15:00", "27:15:00", "30:05:00", "47:59:59"}) {
            assertFalse(XmlTime.parse(bad).isXsdValid(), bad + " is NOT legal xs:time");
            // ...while the value itself is still fully retained, which is the carrier's job.
            assertEquals(bad, XmlTime.parse(bad).toXmlFormat());
        }
        // An unparsed carrier is not a value either.
        assertFalse(XmlTime.parse("not-a-time").isXsdValid());
        // And a synthesized out-of-range hour follows the same rule as a parsed one.
        assertTrue(XmlTime.of(24, 0, 0).isXsdValid());
        assertFalse(XmlTime.of(24, 0, 1).isXsdValid());
        assertFalse(XmlTime.of(25, 0, 0).isXsdValid());
    }

    @Test
    @DisplayName("a carrier must never be hashed")
    void hashingIsRefused() {
        XmlTime a = XmlTime.parse("24:09:00");
        assertThrows(UnsupportedOperationException.class, a::hashCode);
        // equals stays identity: two carriers of the same value are not interchangeable.
        assertNotEquals(a, XmlTime.parse("24:09:00"));
        assertEquals(a, a);
    }
}
