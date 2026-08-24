package noi.netex.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import noi.netex.Corpus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class XmlDateTimeTest {

    @Test
    @DisplayName("every corpus value round-trips byte-exactly, both arms")
    void roundTripIsByteExact() {
        for (String lexical : Corpus.DATE_TIMES) {
            XmlDateTime d = XmlDateTime.parse(lexical);
            assertFalse(d.isUnparsed(), lexical + " must parse");
            assertEquals(lexical, d.toXmlFormat(), lexical + " must re-marshal verbatim");
            assertEquals(lexical, d.toString());
            assertFalse(d.isDateOnly());
        }
        for (String lexical : Corpus.DATES) {
            XmlDateTime d = XmlDateTime.parseDate(lexical);
            assertFalse(d.isUnparsed(), lexical + " must parse");
            assertEquals(lexical, d.toXmlFormat(), lexical + " must re-marshal verbatim");
            assertTrue(d.isDateOnly());
        }
    }

    @Test
    @DisplayName("toString()'s first 10 chars are the date — Crossborder.expandBits depends on it")
    void toStringSliceIsTheDate() {
        assertEquals("2024-12-13",
                String.valueOf(XmlDateTime.parse("2024-12-13T00:37:41.000+01:00")).substring(0, 10));
        assertEquals("2025-12-14",
                String.valueOf(XmlDateTime.parseDate("2025-12-14")).substring(0, 10));
        // ...including for a synthesized value, which has no lexical to return.
        assertEquals("2030-01-02",
                String.valueOf(XmlDateTime.from(LocalDateTime.of(2030, 1, 2, 3, 4, 5)))
                        .substring(0, 10));
    }

    @Test
    @DisplayName("the offset is preserved and discarded by normalisation, as the old binding did")
    void offsetIsPreservedButNotNormalised() {
        XmlDateTime d = XmlDateTime.parse("2024-12-13T00:37:41.000+01:00");
        assertEquals(ZoneOffset.ofHours(1), d.getOffset());
        assertEquals(LocalDateTime.of(2024, 12, 13, 0, 37, 41), d.toLocalDateTime());

        XmlDateTime z = XmlDateTime.parse("2026-02-10T09:29:40.961807061Z");
        assertEquals(ZoneOffset.UTC, z.getOffset());
        assertEquals(LocalDateTime.of(2026, 2, 10, 9, 29, 40, 961_807_061), z.toLocalDateTime());

        assertNull(XmlDateTime.parse("2025-12-18T23:59:59").getOffset());
        assertNull(XmlDateTime.parseDate("2025-12-14").getOffset());
        assertEquals(ZoneOffset.ofHours(2), XmlDateTime.parseDate("2025-12-14+02:00").getOffset());
    }

    @Test
    @DisplayName("the date arm normalises to start of day")
    void dateArmIsStartOfDay() {
        assertEquals(LocalDateTime.of(2025, 12, 14, 0, 0), XmlDateTime.parseDate("2025-12-14")
                .toLocalDateTime());
    }

    @Test
    @DisplayName("from(LocalDateTime) canonicalises — the lexical is dropped")
    void synthesizedValuesCanonicalise() {
        XmlDateTime d = XmlDateTime.from(LocalDateTime.of(2030, 1, 2, 3, 4, 5));
        assertNull(d.getLexical());
        assertNull(d.getOffset());
        assertFalse(d.isDateOnly());
        assertEquals("2030-01-02T03:04:05", d.toXmlFormat());

        // seconds are always written, unlike LocalDateTime.toString(), which drops :00
        assertEquals("2030-01-02T03:04:00",
                XmlDateTime.from(LocalDateTime.of(2030, 1, 2, 3, 4)).toXmlFormat());
        assertEquals("2030-01-02T03:04:05.250",
                XmlDateTime.from(LocalDateTime.of(2030, 1, 2, 3, 4, 5, 250_000_000)).toXmlFormat());

        // a parsed value re-emits its source form; a synthesized one cannot and must not.
        assertEquals("2026-02-10T00:00:00",
                XmlDateTime.parse("2026-02-10T00:00:00").toXmlFormat());
    }

    @Test
    @DisplayName("unparseable input is retained verbatim, never thrown")
    void unparseableInputIsRetained() {
        for (String junk : new String[] {"not-a-date", "2025-13-01T00:00:00", "2025-02-30T00:00:00"}) {
            XmlDateTime d = XmlDateTime.parse(junk);
            assertTrue(d.isUnparsed(), junk + " must be an unparsed carrier");
            assertNull(d.toLocalDateTime(), junk + " must not normalise");
            assertEquals(junk, d.toXmlFormat(), junk + " must re-marshal verbatim");
        }
        assertNull(XmlDateTime.parse(null));
        assertNull(XmlDateTime.parse("   "));
        assertNull(XmlDateTime.parseDate(null));

        // The arms are strict, exactly as the old adapters were: a date-only value in
        // an xs:dateTime field is retained raw rather than silently promoted, and vice versa.
        assertTrue(XmlDateTime.parse("2025-12-14").isUnparsed());
        assertTrue(XmlDateTime.parseDate("2025-12-14T00:00:00").isUnparsed());
    }

    @Test
    @DisplayName("the xs:dateTime end-of-day form rolls over, as the old adapter's resolver did")
    void endOfDayRollsOver() {
        XmlDateTime d = XmlDateTime.parse("2025-12-14T24:00:00");
        assertFalse(d.isUnparsed());
        assertEquals(LocalDateTime.of(2025, 12, 15, 0, 0), d.toLocalDateTime());
        assertEquals("2025-12-14T24:00:00", d.toXmlFormat());
        // ...but a genuinely out-of-range hour is not invented away.
        assertTrue(XmlDateTime.parse("2025-12-14T25:15:00").isUnparsed());
    }

    @Test
    @DisplayName("a carrier must never be hashed")
    void hashingIsRefused() {
        assertThrows(UnsupportedOperationException.class,
                XmlDateTime.parse("2026-02-10T00:00:00")::hashCode);
    }
}
