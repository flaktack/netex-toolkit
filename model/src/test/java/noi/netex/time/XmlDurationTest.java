package noi.netex.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import noi.netex.Corpus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class XmlDurationTest {

    @Test
    @DisplayName("every corpus value round-trips byte-exactly")
    void roundTripIsByteExact() {
        for (String lexical : Corpus.DURATIONS) {
            XmlDuration d = XmlDuration.parse(lexical);
            assertFalse(d.isUnparsed(), lexical + " must parse");
            assertEquals(lexical, d.toXmlFormat(), lexical + " must re-marshal verbatim");
            assertEquals(lexical, d.toString());
        }
    }

    @Test
    @DisplayName("the forms java.time.Duration.parse rejects are the ones we must hold")
    void theRejectedFormsParse() {
        for (String lexical : new String[] {"P0Y0M0DT0H36M0.000S", "-P0Y0M0DT0H10M0.000S"}) {
            assertThrows(RuntimeException.class, () -> Duration.parse(lexical),
                    lexical + " is expected to defeat java.time.Duration.parse");
            assertFalse(XmlDuration.parse(lexical).isUnparsed(), lexical + " must parse here");
        }

        XmlDuration positive = XmlDuration.parse("P0Y0M0DT0H36M0.000S");
        assertEquals(1, positive.getSign());
        assertEquals(Duration.ofMinutes(36), positive.toDuration());

        XmlDuration negative = XmlDuration.parse("-P0Y0M0DT0H10M0.000S");
        assertEquals(-1, negative.getSign());
        assertEquals(Duration.ofMinutes(-10), negative.toDuration());
    }

    @Test
    @DisplayName("fields are exposed the way the source exposes them, so to_seconds ports exactly")
    void fieldsMirrorSource() {
        XmlDuration d = XmlDuration.parse("P1Y2M3DT4H5M6.500S");
        assertEquals(1, d.getYears());
        assertEquals(2, d.getMonths());
        assertEquals(3, d.getDays());
        assertEquals(4, d.getHours());
        assertEquals(5, d.getMinutes());
        assertEquals(0, new BigDecimal("6.500").compareTo(d.getSeconds()));
        assertEquals(1, d.getSign());

        // absent fields read as zero, matching the `(x or 0)`
        XmlDuration m = XmlDuration.parse("PT16M");
        assertEquals(0, m.getYears());
        assertEquals(0, m.getMonths());
        assertEquals(0, m.getDays());
        assertEquals(0, m.getHours());
        assertEquals(16, m.getMinutes());
        assertEquals(0, BigDecimal.ZERO.compareTo(m.getSeconds()));

        // the magnitude fields are unsigned; the sign is separate
        XmlDuration n = XmlDuration.parse("-P0Y0M0DT0H10M0.000S");
        assertEquals(10, n.getMinutes());
        assertEquals(-1, n.getSign());
    }

    @Test
    @DisplayName("toDuration() sums days/hours/minutes/fractional seconds, ignoring years+months")
    void normalisation() {
        assertEquals(Duration.ofMinutes(16), XmlDuration.parse("PT16M").toDuration());
        assertEquals(Duration.ofMinutes(53), XmlDuration.parse("PT53M").toDuration());
        assertEquals(Duration.ZERO, XmlDuration.parse("PT0S").toDuration());
        assertEquals(Duration.ofSeconds(72), XmlDuration.parse("PT1M12S").toDuration());
        assertEquals(Duration.ofMinutes(11 * 60 + 22), XmlDuration.parse("PT11H22M").toDuration());
        assertEquals(Duration.ofSeconds(90).plusNanos(500_000_000),
                XmlDuration.parse("PT1M30.5S").toDuration());
        assertEquals(Duration.ofDays(2).plusHours(3), XmlDuration.parse("P2DT3H").toDuration());
        // years and months are dropped
        assertEquals(Duration.ofDays(3), XmlDuration.parse("P1Y2M3D").toDuration());
        assertEquals(2, XmlDuration.parse("P1Y2M3D").getMonths(), "…but they remain readable");
    }

    @Test
    @DisplayName("unparseable input is retained verbatim, never thrown")
    void unparseableInputIsRetained() {
        for (String junk : new String[] {"not-a-duration", "P", "16M"}) {
            XmlDuration d = XmlDuration.parse(junk);
            assertTrue(d.isUnparsed(), junk + " must be an unparsed carrier");
            assertNull(d.toDuration(), junk + " must not normalise");
            assertEquals(junk, d.toXmlFormat(), junk + " must re-marshal verbatim");
            assertEquals(0, d.getSign());
        }
        assertNull(XmlDuration.parse(null));
        assertNull(XmlDuration.parse("  "));
    }

    @Test
    @DisplayName("a carrier must never be hashed")
    void hashingIsRefused() {
        assertThrows(UnsupportedOperationException.class, XmlDuration.parse("PT16M")::hashCode);
    }
}
