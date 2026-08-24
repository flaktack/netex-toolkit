package toolkit.transform.epip;

// No serializer deviation applies here: the point refs fall back to the meeting's own
// AtStopPointRef, whose runtime class equals the declared field type, so no xsi:type is emitted.
//
// Journey-meeting refs: a JourneyMeeting couples two journeys via From/ToJourneyRef. The EPIP
// ServiceJourneyInterchange must carry those journey refs; without them a consumer reads
// from=null/to=null.

import org.junit.jupiter.api.Test;
import toolkit.test.Gen;
import toolkit.test.TestStore;
import toolkit.test.Xml;

import java.util.List;

public class TestEpipJourneyMeetingRefs {

    static final String JM = """
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="sj_a" version="1"><DepartureTime>10:00:00</DepartureTime></ServiceJourney>
    <ServiceJourney id="sj_b" version="1"><DepartureTime>10:30:00</DepartureTime></ServiceJourney>
  </vehicleJourneys>
  <journeyInterchanges>
    <JourneyMeeting id="jm1" version="1">
      <AtStopPointRef ref="ssp1" versionRef="1"/>
      <FromJourneyRef ref="sj_a" version="1"/>
      <ToJourneyRef ref="sj_b" version="1"/>
    </JourneyMeeting>
  </journeyInterchanges>
</TimetableFrame>
""";

    // The interchange carries both journey refs (From/ToJourneyRef -> sj_a/sj_b) and is marked
    // StaySeated/Planned/Advertised (SKI 10.12.1 DURCHBI), but NOT Guaranteed -- note the absence
    // of a <Guaranteed> element, which DURCHBI does not express.
    static final String EXPECTED = """
<ServiceJourneyInterchange xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="jm1" version="1">
  <StaySeated>true</StaySeated>
  <Planned>true</Planned>
  <Advertised>true</Advertised>
  <FromPointRef ref="ssp1" versionRef="1"/>
  <ToPointRef ref="ssp1" versionRef="1"/>
  <FromJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_a"/>
  <ToJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_b"/>
</ServiceJourneyInterchange>""";

    @Test
    void testInterchangeKeepsFromAndToJourneyRefs(TestStore db) throws Exception {
        db.loadNetex(JM);

        List<Object> result = Gen.run(db, (store, txn) ->
                EpipInterchange.epipServiceJourneyInterchange(store, txn, GeneratorDefaults.TEST));

        Xml.assertXmlEqual(Xml.toXmlAll(result), EXPECTED);
    }
}
