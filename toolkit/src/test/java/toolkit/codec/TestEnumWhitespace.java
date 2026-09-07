package toolkit.codec;

// Regression test for toolkit.codec.EnumWhitespace, the xs:collapse the JAXB RI skips on
// enum-valued elements.
//
// Four properties: a padded lexical binds at any of the three whitespace positions; the stored
// fragment still carries the source bytes while the decoded bean and its re-marshal carry the
// collapsed value; non-enum text and undeclared lexicals are left alone; and the table covers every
// enum-valued element name rather than TransportMode alone.

import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.Line;
import noi.netex.model.Notice;
import noi.netex.model.ScheduledStopPoint;
import org.junit.jupiter.api.Test;
import toolkit.store.RawRow;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import noi.netex.text.Mls;

public class TestEnumWhitespace {

    /// The padded shape as a feed writes it, plus the two other whitespace positions and a control
    /// Line with no padding at all.
    static final String LINES = """
        <ServiceFrame id="svf" version="1">
          <lines>
            <Line version="any" id="epd:IT:ITI3:Line:CONTRAM_0002">
              <Name>CONTRAM_0002</Name>
              <TransportMode>bus </TransportMode>
              <OperatorRef ref="epd:IT:ITI3:Operator:CONTRAM"/>
            </Line>
            <Line version="any" id="lead">
              <Name>lead</Name><TransportMode> rail</TransportMode>
            </Line>
            <Line version="any" id="both">
              <Name>both</Name><TransportMode>
                tram
              </TransportMode>
            </Line>
            <Line version="any" id="clean">
              <Name>clean</Name><TransportMode>metro</TransportMode>
            </Line>
            <Line version="any" id="bogus">
              <Name>bogus</Name><TransportMode>notamode </TransportMode>
            </Line>
          </lines>
        </ServiceFrame>
        """;

    /// Non-enum text of three kinds, all of which must keep every space they were given.
    static final String TEXTS = """
        <ServiceFrame id="svf" version="1">
          <notices>
            <Notice id="n1" version="1"><Text>  spaced   out  </Text></Notice>
          </notices>
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>  Stazione  Centrale  </Name></ScheduledStopPoint>
          </scheduledStopPoints>
        </ServiceFrame>
        """;

    /// The stored fragment bytes of one object (not a bean re-marshal).
    private static String fragmentOf(TestStore db, Class<?> clazz, String id) {
        FragmentCodec codec = new FragmentCodec();
        try (Txn tx = db.store.roTxn()) {
            for (RawRow row : db.store.iterRawObjects(tx, clazz)) {
                String s = new String(codec.decompress(row.blob()), StandardCharsets.UTF_8);
                if (s.contains("id=\"" + id + "\"")) return s;
            }
        }
        throw new AssertionError("no stored " + clazz.getSimpleName() + " " + id);
    }

    // ---------------------------------------------------------------- 1. collapsed

    @Test
    void testPaddedEnumTextBinds(TestStore db) throws Exception {
        db.loadNetex(LINES);
        var byId = Gen.byId(db, Line.class);
        Check.equals(AllVehicleModesOfTransportEnumeration.BUS,
                byId.get("epd:IT:ITI3:Line:CONTRAM_0002").getTransportMode(),
                "trailing space: <TransportMode>bus </TransportMode> binds to BUS (the 460 Marche Lines)");
        Check.equals(AllVehicleModesOfTransportEnumeration.RAIL,
                byId.get("lead").getTransportMode(), "leading space binds");
        Check.equals(AllVehicleModesOfTransportEnumeration.TRAM,
                byId.get("both").getTransportMode(), "newlines and indentation on both sides bind");
        Check.equals(AllVehicleModesOfTransportEnumeration.METRO,
                byId.get("clean").getTransportMode(), "an unpadded value is unaffected");
    }

    // ---------------------------------------------------------------- 2. asymmetric

    @Test
    void testFragmentKeepsSourceBytesAndRemarshalCollapses(TestStore db) throws Exception {
        db.loadNetex(LINES);

        // The stored fragment is a byte copy of the source events and must not move.
        String stored = fragmentOf(db, Line.class, "epd:IT:ITI3:Line:CONTRAM_0002");
        Check.that(stored.contains("<TransportMode>bus </TransportMode>"),
                "the stored fragment still carries the source's trailing space");

        // The re-marshal is the export path (EpipLines rewrites every Line) and must emit the
        // collapsed lexical.
        FragmentCodec codec = new FragmentCodec();
        Line line = Gen.byId(db, Line.class).get("epd:IT:ITI3:Line:CONTRAM_0002");
        String remarshalled = new String(codec.marshalFragment(line), StandardCharsets.UTF_8);
        Check.that(remarshalled.contains("<TransportMode>bus</TransportMode>"),
                "the re-marshalled Line carries <TransportMode>bus</TransportMode> (was: nothing)");
    }

    // ---------------------------------------------------------------- 3. scoped

    @Test
    void testNonEnumTextIsUntouched(TestStore db) throws Exception {
        db.loadNetex(TEXTS);
        Check.equals("  spaced   out  ",
                Mls.text(Gen.byId(db, Notice.class).get("n1").getText()),
                "MultilingualString text keeps its whitespace verbatim");
        Check.equals("  Stazione  Centrale  ",
                Mls.text(Gen.byId(db, ScheduledStopPoint.class).get("ssp1").getName()),
                "a Name is not an enum and is not collapsed");
    }

    @Test
    void testUnknownPaddedValueIsLeftAlone(TestStore db) throws Exception {
        db.loadNetex(LINES);
        Check.isNull(Gen.byId(db, Line.class).get("bogus").getTransportMode(),
                "a padded value that is not a declared lexical still fails to bind — the "
                        + "substitution is gated on membership, which is what makes the "
                        + "name-keyed table safe for the 8 names that also bind to String/Boolean");
    }

    // ---------------------------------------------------------------- 4. general

    @Test
    void testCollapseIsTheXsdFacet() {
        Check.equals("bus", EnumWhitespace.collapse("bus "), "trailing");
        Check.equals("bus", EnumWhitespace.collapse(" bus"), "leading");
        Check.equals("bus", EnumWhitespace.collapse("\n  bus\t\r\n "), "all four whitespace chars");
        Check.equals("a b", EnumWhitespace.collapse("  a \n\t b  "), "internal runs squeeze to one space");
        Check.equals("", EnumWhitespace.collapse("   "), "whitespace-only collapses to empty");
        Check.equals("bus", EnumWhitespace.collapse("bus"), "an already-collapsed value is identical");
    }

    @Test
    void testTableIsDerivedFromTheWholeModel() {
        var table = EnumWhitespace.table();
        // Built by reflection, so it follows a netex.schema.version bump instead of rotting across
        // it.
        Check.that(table.size() > 300,
                "the reflective table covers every enum-valued element name (got " + table.size() + ")");
        Set<String> modes = table.get("TransportMode");
        Check.that(modes != null && modes.contains("bus") && modes.contains("rail")
                        && modes.contains("water"),
                "TransportMode carries the AllVehicleModesOfTransport / AllModes lexicals");
        Check.that(table.get("VehicleMode") != null && table.get("Weighting") != null
                        && table.get("WheelchairAccess") != null,
                "other enum-valued element names are covered too");
        // The lexical form is the @XmlEnumValue text, not the Java constant name.
        Check.that(modes.contains("trolleyBus") && !modes.contains("TROLLEY_BUS"),
                "lexicals come from @XmlEnumValue, not Enum.name()");
    }

    /// The premise of the reader's hot-path pre-filter (EnumWhitespace.maybeCollapse): it looks
    /// only at the first and last character of a text node, which is correct while no enum lexical
    /// contains a space. Every NeTEx enumeration restricts xs:NMTOKEN, so this holds — but a
    /// netex.schema.version bump could in principle introduce one.
    @Test
    void testEveryEnumLexicalIsAnNmtoken() {
        int bad = 0;
        StringBuilder examples = new StringBuilder();
        for (var e : EnumWhitespace.table().entrySet()) {
            for (String v : e.getValue()) {
                if (v.isEmpty() || v.indexOf(' ') >= 0 || !v.equals(EnumWhitespace.collapse(v))) {
                    bad++;
                    if (bad <= 5) examples.append(' ').append(e.getKey()).append("=[").append(v).append(']');
                }
            }
        }
        Check.equals(0, bad, "no enum lexical in the model contains whitespace" + examples);
    }
}
