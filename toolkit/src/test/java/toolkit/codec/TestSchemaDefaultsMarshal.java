package toolkit.codec;

// The defect is JAXB-specific: JAXB marshals whatever is in a field, where a schema-aware
// serializer skips a field equal to its declared default.
//
// Two arms, marshal-time omit-empty and marshal-time omit-default:
//
//   arm 1  an empty <DaysOfWeek></DaysOfWeek>, from an addAll of an empty list and from a
//          create-on-get read of a bean that is then re-inserted
//   arm 2  modification="new" status="active" publication="public" on projected objects
//
// The rule lives in toolkit/codec/SchemaDefaults, installed as a Marshaller.Listener by
// FragmentCodec — the chokepoint every mutated or synthesized object passes through. It must also
// strip values that arrived FROM THE SOURCE PARSE, which is the arm no getter-side or
// post-processing fix can reach.
//
// NEUTER PROOF: comment out `marshaller.setListener(SchemaDefaults.LISTENER)` in
// FragmentCodec.marshalFragment and all eight arm tests below fail;
// testNonDefaultValuesAndUndefaultedFieldsSurvive and testDerivedRuleSetTracksTheModel keep
// passing, being the guard against over-stripping.

import jakarta.xml.bind.JAXBElement;
import noi.netex.calls.Calls;
import noi.netex.model.Call;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.Calls_RelStructure;
import noi.netex.model.DayOfWeekEnumeration;
import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.LineRefStructure;
import noi.netex.model.ModificationEnumeration;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StatusEnumeration;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.UicOperatingPeriod;
import org.junit.jupiter.api.Test;
import toolkit.model.NetexUtils;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;
import toolkit.test.Xml;
import toolkit.transform.epip.EpipCalendar;
import toolkit.transform.epip.EpipJourneys;
import toolkit.transform.epip.GeneratorDefaults;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class TestSchemaDefaultsMarshal {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    // ------------------------------------------------------------------ helpers

    /// Round-trip a source fragment through the real codec: parse, then re-marshal.
    private static String reparse(String sourceXml, Class<?> clazz) {
        FragmentCodec codec = new FragmentCodec();
        byte[] blob = codec.encodeRaw(sourceXml.getBytes(StandardCharsets.UTF_8));
        Object bean = codec.decode(blob, clazz);
        return new String(codec.marshalFragment(bean), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------- arm 1: empty collections

    /// The route no getter change can reach: `<DaysOfWeek></DaysOfWeek>` in the SOURCE
    /// unmarshals to a non-null EMPTY `ArrayList`, and JAXB re-emits the element because
    /// the field is non-null. A schema-aware serializer writes nothing.
    @Test
    void testEmptyXmlListFromSourceParseIsOmitted() {
        String xml = reparse("""
            <DayType xmlns="http://www.netex.org.uk/netex" id="x:DayType:1" version="1">
              <properties><PropertyOfDay><DaysOfWeek></DaysOfWeek></PropertyOfDay></properties>
            </DayType>
            """, DayType.class);
        Check.that(!xml.contains("DaysOfWeek"),
                "an empty <DaysOfWeek/> that came from the SOURCE PARSE must not be re-emitted: " + xml);
        Check.that(xml.contains("<PropertyOfDay/>") || xml.contains("<PropertyOfDay></PropertyOfDay>"),
                "the PropertyOfDay itself survives: " + xml);
    }

    /// The dominant shape: `getDaysOfWeek().addAll(empty)`
    /// materialises the list on the bean, and the bean then marshals an empty element.
    @Test
    void testEmptyXmlListFromCreateOnGetAccessorIsOmitted() {
        UicOperatingPeriod uop = new UicOperatingPeriod();
        uop.setId("x:UicOperatingPeriod:1");
        uop.setVersion("1");
        uop.getDaysOfWeek().addAll(List.of());   // EpipJourneys.java:399, verbatim shape
        Check.that(uop.getDaysOfWeek().isEmpty(), "precondition: the materialised list is empty");
        String xml = Xml.toXml(uop);
        Check.that(!xml.contains("DaysOfWeek"),
                "an empty list materialised by a create-on-get accessor must not be emitted: " + xml);
    }

    // ------------------------------------------------- arm 2: scalars equal to their default

    /// The other route no getter change can reach: the source SPELLS OUT the schema defaults. JAXB
    /// parses them into real fields and re-emits them.
    @Test
    void testSchemaDefaultAttributesFromSourceParseAreOmitted() {
        String xml = reparse("""
            <DayType xmlns="http://www.netex.org.uk/netex" id="x:DayType:1" version="1"
                     modification="new" status="active" publication="public">
              <Name>Weekdays</Name>
            </DayType>
            """, DayType.class);
        Check.that(!xml.contains("modification="), "no modification attribute: " + xml);
        Check.that(!xml.contains("status="), "no status attribute: " + xml);
        Check.that(!xml.contains("publication="), "no publication attribute: " + xml);
        Check.that(xml.contains("id=\"x:DayType:1\"") && xml.contains("version=\"1\""),
                "id and version have no schema default and survive: " + xml);
        Check.that(xml.contains("<Name>Weekdays</Name>"), "content is untouched: " + xml);
    }

    /// [NetexUtils#project] copies JAXB's DEFAULTED GETTERS into real target FIELDS, so the
    /// projected bean marshals three attributes that must never be emitted.
    @Test
    void testSchemaDefaultAttributesFromGetterToFieldCopyAreOmitted() {
        Call call = new Call();
        call.setId("x:Call:1");
        call.setVersion("1");
        call.setOrder(BigInteger.ONE);
        StopPointInJourneyPattern pis = NetexUtils.project(call, StopPointInJourneyPattern.class);

        // project() really did copy the getter values into the fields; the fix is at marshal time.
        Check.equals(ModificationEnumeration.NEW, pis.getModification(),
                "precondition: project copied the defaulted getter into the field");

        String xml = Xml.toXml(pis);
        Check.that(!xml.contains("modification="), "no modification attribute: " + xml);
        Check.that(!xml.contains("status="), "no status attribute: " + xml);
        Check.that(!xml.contains("publication="), "no publication attribute: " + xml);
    }

    // ------------------------------------------------------------- reach: nested + JAXBElement

    /// The listener must reach every bean in the tree, not just the root — including beans that
    /// are only reachable through a wildcarded `List<JAXBElement<?>>` element-ref field
    /// (`ServiceJourney/calls/Call`), and `@XmlValue`-only leaf types, whose text
    /// must survive untouched.
    @Test
    void testNestedAndJaxbElementChildrenAreNormalised() {
        String xml = reparse("""
            <ServiceJourney xmlns="http://www.netex.org.uk/netex" id="x:ServiceJourney:1" version="1">
              <calls>
                <Call id="x:Call:1" version="1" order="1" modification="new" status="active">
                  <ScheduledStopPointRef ref="x:ScheduledStopPoint:1"/>
                </Call>
              </calls>
            </ServiceJourney>
            """, ServiceJourney.class);
        Check.that(!xml.contains("modification="),
                "a Call reached only through a JAXBElement list is normalised too: " + xml);
        Check.that(!xml.contains("status="), "same for status: " + xml);
        Check.that(xml.contains("order=\"1\"") && xml.contains("id=\"x:Call:1\""),
                "the Call's own attributes survive: " + xml);
        Check.that(xml.contains("ref=\"x:ScheduledStopPoint:1\""),
                "the ref structure survives: " + xml);

        String leaf = reparse("""
            <DayType xmlns="http://www.netex.org.uk/netex" id="x:DayType:1" version="1" modification="new">
              <Name lang="en">Weekdays</Name>
            </DayType>
            """, DayType.class);
        Check.that(leaf.contains("<Name lang=\"en\">Weekdays</Name>"),
                "the @XmlValue leaf keeps its text and attribute: " + leaf);
        Check.that(!leaf.contains("modification="), "and the root is still normalised: " + leaf);
    }

    // ------------------------------------------------------------------ the guard rails

    /// The rule strips ONLY the declared default. A non-default value of the same field, and a
    /// field that has no schema default at all, must both survive — including
    /// `<isAvailable>true</isAvailable>`, whose value looks like a default but is not one
    /// (the XSD declares none, so xjc generated a plain, non-defaulting getter).
    @Test
    void testNonDefaultValuesAndUndefaultedFieldsSurvive() {
        String xml = reparse("""
            <DayType xmlns="http://www.netex.org.uk/netex" id="x:DayType:1" version="1"
                     modification="revise" status="inactive">
              <properties><PropertyOfDay><DaysOfWeek>Monday Tuesday</DaysOfWeek></PropertyOfDay></properties>
            </DayType>
            """, DayType.class);
        Check.that(xml.contains("modification=\"revise\""), "a non-default modification survives: " + xml);
        Check.that(xml.contains("status=\"inactive\""), "a non-default status survives: " + xml);
        Check.that(xml.contains("<DaysOfWeek>Monday Tuesday</DaysOfWeek>"),
                "a NON-empty DaysOfWeek survives verbatim: " + xml);

        DayTypeAssignment dta = new DayTypeAssignment();
        dta.setId("x:DayTypeAssignment:1");
        dta.setVersion("1");
        dta.setIsAvailable(Boolean.TRUE);
        dta.setOrder(BigInteger.ONE);
        String dtaXml = Xml.toXml(dta);
        Check.that(dtaXml.contains("<isAvailable>true</isAvailable>"),
                "isAvailable has NO schema default and must never be stripped: " + dtaXml);
        Check.that(dtaXml.contains("order=\"1\""),
                "DayTypeAssignment/@order has no schema default either: " + dtaXml);
    }

    /// Does the generated model bind this accessor at all? The 2.0/1.15.2 discriminator.
    private static boolean binds(Class<?> c, String getter) {
        try {
            c.getMethod(getter);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /// The rule set is DERIVED from a virgin instance, never written down: a field is "defaulted"
    /// exactly when it is null on a virgin bean while its accessor returns non-null. This pins
    /// that derivation against the model as generated — if netex-model is regenerated from a
    /// newer schema the table follows automatically, and this test says what it currently finds.
    ///
    /// **`@publication` is the one row the 2.0 flip deleted.** NeTEx 2.0 drops `publication` from
    /// `BasicModificationDetailsGroup`, i.e. from `EntityInVersionStructure`, the base class of
    /// every versioned NeTEx entity. It is a REMOVED census row rather than a changed cell, and it
    /// is an ATTRIBUTE, so the element-QName census does not carry it either.
    ///
    /// The assertion is therefore VERSION-AWARE rather than deleted, and deliberately not weakened
    /// to "if it is there, fine". The model is asked a DIFFERENT question from the one
    /// [SchemaDefaults#rulesFor] asks — "does the generated bean bind `getPublication()` at all"
    /// against "is this field null on a virgin bean while its accessor answers non-null" — and the
    /// two must agree. A version that binds the field WITHOUT a schema default still fails here.
    ///
    /// CONSEQUENCE: at 1.15.2 `SchemaDefaults` strips `publication="public"` as a declared default
    /// and lets a non-default value through; at 2.0 there is no field, so a non-default
    /// `publication` on a RE-MARSHALLED object is lost silently.
    @Test
    void testDerivedRuleSetTracksTheModel() {
        Map<String, Object> dt = SchemaDefaults.rulesFor(DayType.class);
        Check.equals(ModificationEnumeration.NEW, dt.get("modification"),
                "modification's default is derived from the getter");
        Check.equals(StatusEnumeration.ACTIVE, dt.get("status_BasicModificationDetailsGroup"),
                "status' default is derived from the getter");

        // The discriminator itself: `binds` must be able to answer BOTH ways,
        // or "2.0 does not bind publication" would be indistinguishable from "binds() is broken".
        Check.that(binds(DayType.class, "getModification"),
                "binds() finds an accessor the model certainly has (guards the two arms below)");
        Check.that(!binds(DayType.class, "getNoSuchAccessorAnywhere"),
                "and does not find one it certainly has not");

        boolean bindsPublication = binds(DayType.class, "getPublication");
        Check.equals(bindsPublication, dt.get("publication") != null,
                "publication carries a derived default EXACTLY WHEN the generated model binds it "
                        + "— 1.15.2 binds it and 2.0 deletes it from EntityInVersionStructure, and "
                        + "a version that bound the field with no default would stop SchemaDefaults "
                        + "stripping it. Model binds it: " + bindsPublication
                        + "; rule set has it: " + (dt.get("publication") != null));
        Check.that(!dt.containsKey("version"), "version has no schema default: " + dt.keySet());
        Check.that(!dt.containsKey("id"), "id has no schema default: " + dt.keySet());

        Map<String, Object> pod = SchemaDefaults.rulesFor(noi.netex.model.PropertyOfDay.class);
        Check.equals("[]", pod.get("daysOfWeek"), "a repeated element defaults to the empty list");

        Map<String, Object> dta = SchemaDefaults.rulesFor(DayTypeAssignment.class);
        Check.that(!dta.containsKey("isAvailable"),
                "isAvailable must NOT be in the rule set: " + dta.keySet());
        Check.that(!dta.containsKey("order"),
                "DayTypeAssignment/@order must NOT be in the rule set: " + dta.keySet());
    }

    // --------------------------------------------------------- the two real call sites

    /// At its site: `EpipJourneys.serviceJourneyPatternFromCalls` projects every Call into a
    /// StopPointInJourneyPattern and suppresses no schema default on any projected point.
    @Test
    void testTask81ProjectedStopPointsCarryNoSchemaDefaults() {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("x:ServiceJourney:1");
        sj.setVersion("1");
        LineRefStructure line = new LineRefStructure();
        line.setRef("x:Line:1");
        line.setVersion("1");
        sj.setLineRef(FACTORY.createLineRef(line));

        Calls_RelStructure calls = new Calls_RelStructure();
        for (int i = 1; i <= 2; i++) {
            Call c = new Call();
            c.setId("x:Call:" + i);
            c.setVersion("1");
            c.setOrder(BigInteger.valueOf(i));
            ScheduledStopPointRefStructure s = new ScheduledStopPointRefStructure();
            s.setRef("x:ScheduledStopPoint:" + i);
            s.setVersion("1");
            c.setScheduledStopPointRef(FACTORY.createScheduledStopPointRef(s));
            Calls.add(calls, c);
        }
        sj.setCalls(calls);

        ServiceJourneyPattern sjp = EpipJourneys.serviceJourneyPatternFromCalls(sj, GeneratorDefaults.TEST);
        List<PointInLinkSequence_VersionedChildStructure> points = sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        Check.equals(2, points.size(), "two projected points");

        // Asserted on the WHOLE pattern: the points are nested inside it in the export, so the
        // fix has to hold through the container, not only on a marshalled leaf.
        String xml = Xml.toXml(sjp);
        Check.that(!xml.contains("modification="), "no modification anywhere in the pattern: " + xml);
        Check.that(!xml.contains("status="), "no status anywhere in the pattern: " + xml);
        Check.that(!xml.contains("publication="), "no publication anywhere in the pattern: " + xml);
        Check.that(xml.contains("order=\"1\"") && xml.contains("order=\"2\""),
                "the projected points keep their order: " + xml);
    }

    // A national-holiday DayType whose single PropertyOfDay has NO DaysOfWeek. EpipCalendar READS
    // getDaysOfWeek() on that bean to build the rrule byweekday set, which CREATES the empty list
    // on a bean it is about to re-insert.
    private static final String CALENDAR_FIXTURE = """
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

    /// At its second site: the DayType the service-calendar transform re-emits, which without the
    /// marshal rule writes a `<DaysOfWeek></DaysOfWeek>` that must never be written.
    @Test
    void testTask79EpipCalendarDayTypeCarriesNoEmptyDaysOfWeek(TestStore db)
            throws Exception {
        db.loadNetex(CALENDAR_FIXTURE);
        List<Object> result = Gen.run(db,
                (s, txn) -> EpipCalendar.epipServiceCalendar(s, txn, GeneratorDefaults.TEST));

        DayType dayType = null;
        for (Object o : result) {
            if (o instanceof DayType d) dayType = d;
        }
        Check.that(dayType != null, "the source DayType is re-emitted");

        // The create-on-get really happened — the field is a live empty list on the bean the
        // generator handed back. The fix is at marshal time; the in-memory state is unchanged.
        Check.that(dayType.getProperties().getPropertyOfDay().get(0).getDaysOfWeek().isEmpty(),
                "precondition: the accessor materialised an empty DaysOfWeek on the bean");

        Xml.assertXmlEqual(Xml.toXml(dayType), """
            <DayType xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="ch:1:DayType:Neujahr" version="any">
              <Name>Neujahr</Name>
              <properties><PropertyOfDay><HolidayTypes>NationalHoliday</HolidayTypes></PropertyOfDay></properties>
            </DayType>""",
                "the re-emitted DayType must carry no empty <DaysOfWeek/>");
    }

    /// Belt and braces: the JAXBElement wrapper itself must never be normalised or damaged.
    @Test
    void testJaxbElementWrapperIsUntouched() {
        Call c = new Call();
        c.setId("x:Call:1");
        c.setVersion("1");
        // Calls.element, not FACTORY.createCall: 2.0 dissolves the Call_ substitution group, so the
        // @XmlElementDecl that generated createCall(Call) is gone.
        JAXBElement<Call> el = Calls.element(c);
        SchemaDefaults.normalize(el);
        Check.equals(c, el.getValue(), "normalize() is a no-op on a JAXBElement");
        Check.equals(0, SchemaDefaults.rulesFor(JAXBElement.class).size(),
                "JAXBElement has no rules");
        Check.that(SchemaDefaults.rulesFor(DayOfWeekEnumeration.class).isEmpty(),
                "an enum class has no rules");
    }
}
