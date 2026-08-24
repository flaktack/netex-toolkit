package toolkit.transform.epip;

// Drives its own rw txn through Gen.apply and reads back under a ro txn via Gen.byId. The
// generator never reads generatorDefaults, so GeneratorDefaults.TEST stands in.
//
// Union-read notes (the split of the choice groups, per EpipSanitize's header):
//  - sp.quays.taxi_stand_ref_or_quay_ref_or_quay -> getQuays().getQuayRefOrQuay()
//    (JAXBElement entries, all embedded Quays here);
//  - an absent quay union -> BOTH halves null
//    (getQuayRef() and getQuay());
//  - psa.taxi_stand_ref_or_quay_ref_or_quay.ref -> getQuayRef().getValue().getRef();
//  - psa.taxi_rank_ref_or_stop_place_ref_or_stop_place.ref -> getStopPlaceRef().getValue().getRef().
// The latitude renders as "48.20010": the decimal string equals BigDecimal.toString(), scale
// preserved.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.StopPlace;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class TestEpipSanitize {

    static final String NETEX = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>Wien Testplatz</Name>
            <Centroid><Location><Latitude>48.20000</Latitude><Longitude>16.37000</Longitude></Location></Centroid>
            <quays>
              <Quay id="at:49:975:1" version="1"><Centroid><Location><Latitude>48.20010</Latitude><Longitude>16.37010</Longitude></Location></Centroid><ParentQuayRef ref="at:vor:Quay:at:49:975:HoB:" version="1"/></Quay>
              <Quay id="at:49:975:2" version="1"/>
              <Quay id="at:vor:Quay:at:49:975:HoB:" version="1"><Name>Hst. ohne Bereich</Name><PublicCode>HoB</PublicCode></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        <ServiceFrame id="sf" version="1"><stopAssignments>
          <PassengerStopAssignment id="pa-ok" version="1" order="1"><ScheduledStopPointRef ref="s1"/><StopPlaceRef ref="at:49:975"/><QuayRef ref="at:49:975:1"/></PassengerStopAssignment>
          <PassengerStopAssignment id="pa-dangling" version="1" order="1"><ScheduledStopPointRef ref="s2"/><StopPlaceRef ref="at:49:975"/><QuayRef ref="at:49:975:0:7"/></PassengerStopAssignment>
          <PassengerStopAssignment id="pa-hob" version="1" order="1"><ScheduledStopPointRef ref="s3"/><StopPlaceRef ref="at:49:975"/><QuayRef ref="at:vor:Quay:at:49:975:HoB:"/></PassengerStopAssignment>
        </stopAssignments></ServiceFrame>
        """;

    @Test
    void testFillsQuayCentroidsAndClearsDanglingRefs(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        Gen.apply(db, (store, txn) ->
                EpipSanitize.epipSanitizeStopAssignments(store, txn, GeneratorDefaults.TEST));

        StopPlace sp = Gen.byId(db, StopPlace.class).values().iterator().next();
        Map<String, PassengerStopAssignment> psas = Gen.byId(db, PassengerStopAssignment.class);

        Map<String, Quay> quays = new LinkedHashMap<>();
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            Quay q = (Quay) el.getValue();
            quays.put(q.getId(), q);
        }
        // every coordinate-less quay is stripped (the HoB placeholder AND the anonymous :2);
        // only the located platform survives, untouched
        Check.equals(Set.of("at:49:975:1"), quays.keySet(), "only the located platform survives");
        Check.equals("48.20010",
                quays.get("at:49:975:1").getCentroid().getLocation().getLatitude().toString(),
                "surviving quay's centroid untouched");
        // a surviving quay whose parent was a stripped placeholder is reparented to the stop
        Check.isNull(quays.get("at:49:975:1").getParentQuayRef(),
                "ParentQuayRef to the stripped placeholder cleared");
        // dangling, stripped-placeholder and HoB quay refs are all cleared (station-level
        // assignments); the valid one survives
        Check.isNull(psas.get("pa-dangling").getQuayRef(), "pa-dangling quay ref cleared");
        Check.isNull(psas.get("pa-dangling").getQuay(), "pa-dangling embedded quay half null");
        Check.isNull(psas.get("pa-hob").getQuayRef(), "pa-hob quay ref cleared");
        Check.isNull(psas.get("pa-hob").getQuay(), "pa-hob embedded quay half null");
        Check.equals("at:49:975", psas.get("pa-hob").getStopPlaceRef().getValue().getRef(),
                "pa-hob keeps its StopPlaceRef (station-level assignment)");
        Check.equals("at:49:975:1", psas.get("pa-ok").getQuayRef().getValue().getRef(),
                "pa-ok keeps its valid quay ref");
        Check.equals("at:49:975", psas.get("pa-dangling").getStopPlaceRef().getValue().getRef(),
                "pa-dangling keeps its StopPlaceRef (station-level assignment)");
    }
}
