package toolkit.transform.epip;

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

    // A turnback: PT and PT2 each call station sp4 twice, on different platforms, and the meeting
    // names a third stop there (sspH) that neither pattern calls at. The source JourneyMeeting
    // carries one AtStopPointRef for both legs, so the side decides which call is meant. PT also
    // serves sp6 (sspW), which is what the cross-border case re-anchors on.
    static final String TURNBACK = """
<ServiceFrame id="sf" version="1">
  <journeyPatterns>
    <ServiceJourneyPattern id="PT" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="PT-1" version="1" order="1"><ScheduledStopPointRef ref="sspF" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="PT-2" version="1" order="2"><ScheduledStopPointRef ref="sspM" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="PT-3" version="1" order="3"><ScheduledStopPointRef ref="sspW" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="PT-4" version="1" order="4"><ScheduledStopPointRef ref="sspG" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="PT2" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="PT2-1" version="1" order="1"><ScheduledStopPointRef ref="sspP" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="PT2-2" version="1" order="2"><ScheduledStopPointRef ref="sspQ" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="PT2-3" version="1" order="3"><ScheduledStopPointRef ref="sspR" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="PV" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="PV-1" version="1" order="1"><ScheduledStopPointRef ref="sspV" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
  <stopAssignments>
    <PassengerStopAssignment id="paF" version="1" order="1"><ScheduledStopPointRef ref="sspF"/><StopPlaceRef ref="sp4"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paG" version="1" order="2"><ScheduledStopPointRef ref="sspG"/><StopPlaceRef ref="sp4"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paH" version="1" order="3"><ScheduledStopPointRef ref="sspH"/><StopPlaceRef ref="sp4"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paP" version="1" order="4"><ScheduledStopPointRef ref="sspP"/><StopPlaceRef ref="sp4"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paR" version="1" order="5"><ScheduledStopPointRef ref="sspR"/><StopPlaceRef ref="sp4"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paM" version="1" order="6"><ScheduledStopPointRef ref="sspM"/><StopPlaceRef ref="sp5"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paQ" version="1" order="7"><ScheduledStopPointRef ref="sspQ"/><StopPlaceRef ref="sp7"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paW" version="1" order="8"><ScheduledStopPointRef ref="sspW"/><StopPlaceRef ref="sp6"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paV" version="1" order="9"><ScheduledStopPointRef ref="sspV"/><StopPlaceRef ref="sp6"/></PassengerStopAssignment>
    <PassengerStopAssignment id="paZ" version="1" order="10"><ScheduledStopPointRef ref="sspZ"/><StopPlaceRef ref="sp9"/></PassengerStopAssignment>
  </stopAssignments>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="JT" version="1"><ServiceJourneyPatternRef ref="PT" version="1"/></ServiceJourney>
    <ServiceJourney id="JT2" version="1"><ServiceJourneyPatternRef ref="PT2" version="1"/></ServiceJourney>
    <ServiceJourney id="JV" version="1"><ServiceJourneyPatternRef ref="PV" version="1"/></ServiceJourney>
  </vehicleJourneys>
  <journeyInterchanges>
    <ServiceJourneyInterchange id="t-to" version="1">
      <FromPointRef ref="sspV" version="1"/><ToPointRef ref="sspH" version="1"/>
      <FromJourneyRef ref="JV"/><ToJourneyRef ref="JT"/>
    </ServiceJourneyInterchange>
    <ServiceJourneyInterchange id="t-from" version="1">
      <FromPointRef ref="sspH" version="1"/><ToPointRef ref="sspV" version="1"/>
      <FromJourneyRef ref="JT"/><ToJourneyRef ref="JV"/>
    </ServiceJourneyInterchange>
    <ServiceJourneyInterchange id="t-both" version="1">
      <FromPointRef ref="sspH" version="1"/><ToPointRef ref="sspH" version="1"/>
      <FromJourneyRef ref="JT"/><ToJourneyRef ref="JT2"/>
    </ServiceJourneyInterchange>
    <ServiceJourneyInterchange id="t-nostation" version="1">
      <FromPointRef ref="sspV" version="1"/><ToPointRef ref="sspZ" version="1"/>
      <FromJourneyRef ref="JV"/><ToJourneyRef ref="JT"/>
    </ServiceJourneyInterchange>
    <ServiceJourneyInterchange id="t-crossborder" version="1">
      <CrossBorder>true</CrossBorder>
      <FromPointRef ref="sspH" version="1"/><ToPointRef ref="sspV" version="1"/>
      <FromJourneyRef ref="JT"/><ToJourneyRef ref="JV"/>
    </ServiceJourneyInterchange>
  </journeyInterchanges>
</TimetableFrame>
""";

    @Test
    void testResolvesARepeatedStationCallPerSide(TestStore db) throws Exception {
        db.loadNetex(TURNBACK);
        Gen.apply(db, (store, txn) ->
                EpipInterchange.epipRepairInterchangePoints(store, txn, GeneratorDefaults.TEST));

        Map<String, ServiceJourneyInterchange> sjis = Gen.byId(db, ServiceJourneyInterchange.class);

        // The distributor leg begins at sp4, so its departure is the first of PT's two calls there.
        ServiceJourneyInterchange to = sjis.get("t-to");
        Check.equals("sspV", to.getFromPointRef().getRef(), "t-to from already on PV, stays");
        Check.equals("sspF", to.getToPointRef().getRef(), "t-to resolved to PT's first call at sp4");
        // The feeder leg ends at sp4, so its arrival is the last of PT's two calls there.
        ServiceJourneyInterchange from = sjis.get("t-from");
        Check.equals("sspG", from.getFromPointRef().getRef(), "t-from resolved to PT's last call at sp4");
        Check.equals("sspV", from.getToPointRef().getRef(), "t-from to already on PV, stays");
        // Both sides off and both turnbacks: each takes its own end, in its own pattern.
        ServiceJourneyInterchange both = sjis.get("t-both");
        Check.equals("sspG", both.getFromPointRef().getRef(), "t-both from takes PT's last call");
        Check.equals("sspP", both.getToPointRef().getRef(), "t-both to takes PT2's first call");
        // sspZ's station sp9 is not on PT at all -> no candidate, left as-is.
        ServiceJourneyInterchange none = sjis.get("t-nostation");
        Check.equals("sspV", none.getFromPointRef().getRef(), "t-nostation from untouched");
        Check.equals("sspZ", none.getToPointRef().getRef(), "t-nostation to untouched");
        // Cross-border still re-anchors at the valid endpoint's station sp6 (sspW), which keeps both
        // ends of the handover in one place; the turnback rule must not pre-empt it and return sspG.
        ServiceJourneyInterchange xb = sjis.get("t-crossborder");
        Check.equals("sspW", xb.getFromPointRef().getRef(), "t-crossborder re-anchored at sp6, not sp4");
        Check.equals("sspV", xb.getToPointRef().getRef(), "t-crossborder to kept");
    }
}
