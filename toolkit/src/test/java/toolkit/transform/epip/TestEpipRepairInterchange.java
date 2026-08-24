package toolkit.transform.epip;

// The rw-apply style: Gen.apply runs the repair generator through insertAnyObjects on ONE rw txn,
// then Gen.byId re-reads the interchanges for the field asserts.
//
// Interchange point repair: a From/ToPointRef that is not on the referenced journey's pattern is
// rewritten to the pattern's stop at the SAME StopPlace; a cross-border interchange whose point
// sits off-pattern because the two feeds code the border station differently is re-anchored at a
// StopPlace both patterns serve; refs that cannot be remapped stay untouched.

import noi.netex.model.ServiceJourneyInterchange;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.util.Map;

public class TestEpipRepairInterchange {

    static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <journeyPatterns>
    <ServiceJourneyPattern id="P1" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="P1-1" version="1" order="1"><ScheduledStopPointRef ref="sspA" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="P1-2" version="1" order="2"><ScheduledStopPointRef ref="sspE" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="P3" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="P3-1" version="1" order="1"><ScheduledStopPointRef ref="sspA" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="P2" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="P2-1" version="1" order="1"><ScheduledStopPointRef ref="sspB" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="P2-2" version="1" order="2"><ScheduledStopPointRef ref="sspD" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
  <stopAssignments>
    <PassengerStopAssignment id="paA" version="1" order="1"><ScheduledStopPointRef ref="sspA"/><StopPlaceRef ref="sp1"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paB" version="1" order="2"><ScheduledStopPointRef ref="sspB"/><StopPlaceRef ref="sp1"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paC" version="1" order="3"><ScheduledStopPointRef ref="sspC"/><StopPlaceRef ref="sp2"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paD" version="1" order="4"><ScheduledStopPointRef ref="sspD"/><StopPlaceRef ref="sp3"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paE" version="1" order="5"><ScheduledStopPointRef ref="sspE"/><StopPlaceRef ref="sp3"/></PassengerStopAssignment>
  </stopAssignments>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="J1" version="1"><ServiceJourneyPatternRef ref="P1" version="1"/></ServiceJourney>
    <ServiceJourney id="J2" version="1"><ServiceJourneyPatternRef ref="P2" version="1"/></ServiceJourney>
    <ServiceJourney id="J3" version="1"><ServiceJourneyPatternRef ref="P3" version="1"/></ServiceJourney>
  </vehicleJourneys>
  <journeyInterchanges>
    <ServiceJourneyInterchange id="x-repairable" version="1">
      <FromPointRef ref="sspA" version="1"/><ToPointRef ref="sspA" version="1"/>
      <FromJourneyRef ref="J1"/><ToJourneyRef ref="J2"/>
    </ServiceJourneyInterchange>
    <ServiceJourneyInterchange id="x-different-station" version="1">
      <FromPointRef ref="sspA" version="1"/><ToPointRef ref="sspC" version="1"/>
      <FromJourneyRef ref="J1"/><ToJourneyRef ref="J2"/>
    </ServiceJourneyInterchange>
    <ServiceJourneyInterchange id="x-crossborder" version="1">
      <CrossBorder>true</CrossBorder>
      <FromPointRef ref="sspC" version="1"/><ToPointRef ref="sspD" version="1"/>
      <FromJourneyRef ref="J1"/><ToJourneyRef ref="J2"/>
    </ServiceJourneyInterchange>
    <ServiceJourneyInterchange id="x-crossborder-shared" version="1">
      <CrossBorder>true</CrossBorder>
      <FromPointRef ref="sspC" version="1"/><ToPointRef ref="sspD" version="1"/>
      <FromJourneyRef ref="J3"/><ToJourneyRef ref="J2"/>
    </ServiceJourneyInterchange>
  </journeyInterchanges>
</TimetableFrame>
""";

    @Test
    void testRewritesToTheSameStationSspOnThePattern(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        Gen.apply(db, (store, txn) ->
                EpipInterchange.epipRepairInterchangePoints(store, txn, GeneratorDefaults.TEST));

        Map<String, ServiceJourneyInterchange> sjis = Gen.byId(db, ServiceJourneyInterchange.class);

        // J2's pattern serves sp1 via sspB (unique same-station stop) -> ToPointRef rewritten;
        // the from side (sspA on J1's pattern) is already valid and stays.
        ServiceJourneyInterchange repaired = sjis.get("x-repairable");
        Check.equals("sspA", repaired.getFromPointRef().getRef(), "x-repairable from stays");
        Check.equals("sspB", repaired.getToPointRef().getRef(), "x-repairable to rewritten");
        // sspC belongs to a station J2 never serves -> not cross-border, untouched.
        ServiceJourneyInterchange untouched = sjis.get("x-different-station");
        Check.equals("sspA", untouched.getFromPointRef().getRef(), "x-different-station from untouched");
        Check.equals("sspC", untouched.getToPointRef().getRef(), "x-different-station to untouched");
        // cross-border, one side off: ToPointRef sspD is valid on J2's pattern and must be KEPT;
        // only the off from side is re-anchored, at the valid endpoint's station sp3, which J1's
        // pattern serves via sspE.
        ServiceJourneyInterchange xb = sjis.get("x-crossborder");
        Check.equals("sspE", xb.getFromPointRef().getRef(), "x-crossborder from re-anchored");
        Check.equals("sspD", xb.getToPointRef().getRef(), "x-crossborder to kept");
        // cross-border, valid station unreachable: J3's pattern (sspA@sp1 only) cannot serve the
        // valid endpoint's station sp3 -> both endpoints re-anchored at the shared station sp1,
        // each in its own coding (J3->sspA, J2->sspB).
        ServiceJourneyInterchange xbs = sjis.get("x-crossborder-shared");
        Check.equals("sspA", xbs.getFromPointRef().getRef(), "x-crossborder-shared from");
        Check.equals("sspB", xbs.getToPointRef().getRef(), "x-crossborder-shared to");
    }
}
