package noi.netex.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.xml.bind.annotation.adapters.NormalizedStringAdapter;
import noi.netex.Corpus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdaptersTest {

    @Test
    @DisplayName("every adapter is null-safe and round-trips the corpus byte-exactly")
    void adaptersRoundTrip() throws Exception {
        XmlTimeAdapter time = new XmlTimeAdapter();
        for (String lexical : Corpus.TIMES) {
            assertEquals(lexical, time.marshal(time.unmarshal(lexical)));
        }
        XmlDateTimeAdapter dateTime = new XmlDateTimeAdapter();
        for (String lexical : Corpus.DATE_TIMES) {
            assertEquals(lexical, dateTime.marshal(dateTime.unmarshal(lexical)));
        }
        XmlDateAdapter date = new XmlDateAdapter();
        for (String lexical : Corpus.DATES) {
            assertEquals(lexical, date.marshal(date.unmarshal(lexical)));
        }
        XmlDurationAdapter duration = new XmlDurationAdapter();
        for (String lexical : Corpus.DURATIONS) {
            assertEquals(lexical, duration.marshal(duration.unmarshal(lexical)));
        }

        for (String empty : new String[] {null, "", "   "}) {
            assertNull(time.unmarshal(empty));
            assertNull(dateTime.unmarshal(empty));
            assertNull(date.unmarshal(empty));
            assertNull(duration.unmarshal(empty));
        }
        assertNull(time.marshal(null));
        assertNull(dateTime.marshal(null));
        assertNull(date.marshal(null));
        assertNull(duration.marshal(null));
    }

    @Test
    @DisplayName("xs:date and xs:dateTime land in the same carrier type — EpipCalendar needs it")
    void bothDateArmsShareOneType() {
        var fromDate = new XmlDateTimeAdapter().unmarshal("2026-02-10T00:00:00");
        var date = new XmlDateAdapter().unmarshal("2026-02-10");
        assertEquals(fromDate.toLocalDateTime(), date.toLocalDateTime());
        assertSame(fromDate.getClass(), date.getClass());
    }

    @Test
    @DisplayName("VerbatimStringAdapter keeps line breaks that NormalizedStringAdapter destroys")
    void verbatimStringKeepsLineBreaks() {
        String address = "Einöd 138\nSteiermark";
        VerbatimStringAdapter verbatim = new VerbatimStringAdapter();
        assertEquals(address, verbatim.unmarshal(address));
        assertEquals(address, verbatim.marshal(address));
        assertSame(address, verbatim.unmarshal(address));
        assertNull(verbatim.unmarshal(null));

        // the defect this replaces: the xs:normalizedString whitespace facet eats CR/LF/TAB
        String normalized = new NormalizedStringAdapter().unmarshal(address);
        assertNotEquals(address, normalized);
        assertTrue(normalized.contains("Einöd 138 Steiermark"));
    }

    /// [VersionIdAdapter] is the one adapter here that is not part of the date/time set: it exists
    /// so that `VersionIdType` carries an adapter of its own rather than inheriting the
    /// `xs:normalizedString` one, and dropping the binding that names it would move
    /// `@XmlJavaTypeAdapter` off every `version` attribute in the model.
    ///
    /// It must not change a value; the one observable subtlety is the canonicalisation of `"any"`,
    /// asserted here because `assertSame` is the only way to see it.
    @Test
    @DisplayName("VersionIdAdapter is the identity function, plus canonicalisation of \"any\"")
    void versionAdapterIsIdentity() {
        VersionIdAdapter version = new VersionIdAdapter();
        for (String v : new String[] {
                "1", "396", "any", "20260718234018", "ntx:1.1", "", "  ", "ANY", "Any"}) {
            assertEquals(v, version.unmarshal(v), "unmarshal must not change " + v);
            assertEquals(v, version.marshal(v), "marshal must not change " + v);
        }
        assertNull(version.unmarshal(null));
        assertNull(version.marshal(null));

        // A freshly built "any" (never a compile-time constant, so never already interned) comes
        // back as the shared instance; everything else comes back as itself.
        String freshAny = new StringBuilder("an").append('y').toString();
        assertEquals("any", freshAny);
        assertSame(version.unmarshal(freshAny), version.unmarshal(new String("any")));
        String other = new StringBuilder("39").append('6').toString();
        assertSame(other, version.unmarshal(other));
    }
}
