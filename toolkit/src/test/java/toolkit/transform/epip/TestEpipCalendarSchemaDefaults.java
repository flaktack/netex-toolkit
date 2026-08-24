package toolkit.transform.epip;

// The defect is JAXB-specific: it is created by the model's defaulted getters.
//
// EPIP calendar, SYNTHESIZED branch: a DayType whose only assignment carries a bare <Date> has no
// UicOperatingPeriod, so the calendar pass synthesizes one and derives the assignment by
// projecting the DayType onto a DayTypeAssignment. NetexUtils.project copies the source's GETTER
// values into the target's FIELDS — and JAXB's EntityInVersionStructure getters DEFAULT:
// getModification() returns NEW,
// getStatus_BasicModificationDetailsGroup() ACTIVE and getPublication() PUBLIC while the
// underlying fields are null. The projected DayTypeAssignment therefore marshalled
//   modification="new" status="active" publication="public"
// which must NEVER be emitted: an attribute equal to its XSD default does not reach the wire.
//
// The rule is enforced at MARSHAL time, not by a call-site shim, because a call-site fix cannot
// reach the far larger population of the same defect — every projected object, and every default
// an input file spells out explicitly. This file asserts the observable; the dedicated test for
// the rule itself is TestSchemaDefaultsMarshal.
//
// NEUTER PROOF: comment out the `marshaller.setListener(SchemaDefaults.LISTENER)` line in
// toolkit/codec/FragmentCodec.marshalFragment, rebuild, and BOTH methods below fail with exactly
// those three attributes present on the DayTypeAssignment.

import noi.netex.model.DayTypeAssignment;
import noi.netex.model.UicOperatingPeriod;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;
import toolkit.test.Xml;

import java.util.ArrayList;
import java.util.List;

public class TestEpipCalendarSchemaDefaults {

    // The holiday shape, minimised: a national-holiday DayType (a PropertyOfDay WITHOUT DaysOfWeek,
    // so no rrule expansion) and one bare-<Date> assignment naming it.
    private static final String INPUT = """
        <ServiceCalendarFrame id="scf" version="1">
          <ServiceCalendar id="sc" version="any">
            <dayTypes>
              <DayType id="ch:1:DayType:Neujahr" version="any">
                <Name>Neujahr</Name>
                <properties><PropertyOfDay><HolidayTypes>NationalHoliday</HolidayTypes></PropertyOfDay></properties>
              </DayType>
            </dayTypes>
            <dayTypeAssignments>
              <DayTypeAssignment id="ch:1:DayTypeAssignment:3" version="any" order="3">
                <Date>2026-01-01</Date>
                <DayTypeRef ref="ch:1:DayType:Neujahr" version="any"/>
              </DayTypeAssignment>
            </dayTypeAssignments>
          </ServiceCalendar>
        </ServiceCalendarFrame>
        """;

    /// The synthesized DayTypeAssignment marshals with the correct attribute set —
    /// id/version/order only, no modification/status/publication.
    @Test
    void testSynthesizedAssignmentCarriesNoSchemaDefaultAttributes(TestStore db)
            throws Exception {
        db.loadNetex(INPUT);

        List<Object> result = Gen.run(db,
                (s, txn) -> EpipCalendar.epipServiceCalendar(s, txn, GeneratorDefaults.TEST));

        DayTypeAssignment resDta = null;
        for (Object o : result) {
            if (o instanceof DayTypeAssignment d
                    && "ch:1:DayTypeAssignment:Neujahr".equals(d.getId())) {
                resDta = d;
            }
        }
        Check.that(resDta != null, "the synthesized DayTypeAssignment is emitted");

        // The three EntityInVersionStructure fields must stay NULL. Their getters DEFAULT
        // (NEW/ACTIVE/PUBLIC) whether or not the field is set, so the marshalled form — which is
        // what reaches the export — is the observable.
        String xml = Xml.toXml(resDta);
        Check.that(!xml.contains("modification="), "no modification attribute: " + xml);
        Check.that(!xml.contains("status="), "no status attribute: " + xml);
        Check.that(!xml.contains("publication="), "no publication attribute: " + xml);
    }

    /// The synthesized PAIR (assignment + operating period), asserted as XML — the exact shape
    /// the wire carries for them (verified against the production
    /// data/netex-epip-merged.zip shared-05 entry for all 18 Swiss holidays).
    ///
    /// The group's third member — the re-emitted source DayType — is asserted in
    /// `TestSchemaDefaultsMarshal`, not here. It used to be unassertable: Java marshalled
    /// it with an EMPTY `<DaysOfWeek></DaysOfWeek>` that must never be emitted, because
    /// `EpipCalendar`'s `getDaysOfWeek()` read CREATES-ON-GET the JAXB list on the
    /// bean it then re-inserts (the export carries these where a schema-aware writer emits none —
    /// nearly all from the journey generator, a few in PropertyOfDay
    /// from `EpipCalendar`). It is closed at marshal time.</p>
    @Test
    void testSynthesizedPairMatchesExpectedWire(TestStore db) throws Exception {
        db.loadNetex(INPUT);

        List<Object> result = Gen.run(db,
                (s, txn) -> EpipCalendar.epipServiceCalendar(s, txn, GeneratorDefaults.TEST));

        List<Object> pair = new ArrayList<>();
        for (Object o : result) {
            if (o instanceof DayTypeAssignment || o instanceof UicOperatingPeriod) pair.add(o);
        }
        Xml.assertXmlEqual(Xml.toXmlAll(pair), EXPECTED);
    }

    private static final String EXPECTED = """
        <DayTypeAssignment xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="ch:1:DayTypeAssignment:Neujahr" version="any" order="1">
          <Name>Neujahr</Name>
          <OperatingPeriodRef nameOfRefClass="UicOperatingPeriod" version="any" ref="ch:1:UicOperatingPeriod:Neujahr"/>
          <DayTypeRef version="any" ref="ch:1:DayType:Neujahr"/>
          <isAvailable>true</isAvailable>
        </DayTypeAssignment>
        <UicOperatingPeriod xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="ch:1:UicOperatingPeriod:Neujahr" version="any">
          <FromDate>2026-01-01T00:00:00</FromDate>
          <ToDate>2026-01-01T00:00:00</ToDate>
          <ValidDayBits>1</ValidDayBits>
        </UicOperatingPeriod>""";
}
