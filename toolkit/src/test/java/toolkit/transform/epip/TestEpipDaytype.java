package toolkit.transform.epip;

// DayType: a ServiceCalendar shaped DayTypeAssignment -> plain OperatingPeriodRef ->
// UicOperatingPeriod must survive the EPIP calendar transform. A NameOfClass comparison against the
// expected type instead drops the assignment and the calendar comes out empty.

import org.junit.jupiter.api.Test;
import toolkit.test.Gen;
import toolkit.test.TestStore;
import toolkit.test.Xml;

import java.util.List;

public class TestEpipDaytype {

    private static final String INPUT = """
        <ServiceCalendarFrame id="scf" version="1">
          <ServiceCalendar id="sc" version="1">
            <dayTypes>
              <DayType id="dt1" version="1">
                <properties><PropertyOfDay><DaysOfWeek>Monday</DaysOfWeek></PropertyOfDay></properties>
              </DayType>
            </dayTypes>
            <operatingPeriods>
              <UicOperatingPeriod id="op1" version="1">
                <FromDate>2026-01-05T00:00:00</FromDate>
                <ToDate>2026-01-26T00:00:00</ToDate>
                <ValidDayBits>1000000100000010000001</ValidDayBits>
              </UicOperatingPeriod>
            </operatingPeriods>
            <dayTypeAssignments>
              <DayTypeAssignment id="dta1" version="1" order="1">
                <!-- like the Trenitalia feed: a plain OperatingPeriodRef with no nameOfRefClass;
                     resolve() fills it in as a NameOfClass enum that the pre-fix code mis-compared. -->
                <OperatingPeriodRef ref="op1" version="1"/>
                <DayTypeRef ref="dt1" version="1"/>
              </DayTypeAssignment>
            </dayTypeAssignments>
          </ServiceCalendar>
        </ServiceCalendarFrame>
        """;

    // The DayTypeAssignment is emitted (its OperatingPeriodRef now typed as UicOperatingPeriod),
    // and the referenced UicOperatingPeriod + DayType are carried through.
    private static final String EXPECTED = """
        <DayTypeAssignment xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="dta1" version="1" order="1">
          <OperatingPeriodRef nameOfRefClass="UicOperatingPeriod" version="1" ref="op1"/>
          <DayTypeRef version="1" ref="dt1"/>
        </DayTypeAssignment>
        <UicOperatingPeriod xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="op1" version="1">
          <FromDate>2026-01-05T00:00:00</FromDate>
          <ToDate>2026-01-26T00:00:00</ToDate>
          <ValidDayBits>1000000100000010000001</ValidDayBits>
        </UicOperatingPeriod>
        <DayType xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="dt1" version="1">
          <properties>
            <PropertyOfDay>
              <DaysOfWeek>Monday</DaysOfWeek>
            </PropertyOfDay>
          </properties>
        </DayType>""";

    /// The DayTypeAssignment is emitted.
    @Test
    void testDaytypeAssignmentIsEmitted(TestStore db) throws Exception {
        db.loadNetex(INPUT);

        List<Object> result = Gen.run(db,
                (s, txn) -> EpipCalendar.epipServiceCalendar(s, txn, GeneratorDefaults.TEST));

        Xml.assertXmlEqual(Xml.toXmlAll(result), EXPECTED);
    }
}
