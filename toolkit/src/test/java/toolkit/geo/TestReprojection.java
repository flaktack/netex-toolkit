package toolkit.geo;

// Reprojection, over UTM 32N easting/northing 680000/5140000 — roughly Bolzano.
//
// [Projection#reprojectionChanged] is the operation; the driver-side wrappers around it hold no
// logic of their own, so:
//   * testReprojectionConvertsLocationToWgs84 drives reprojectionChanged directly;
//   * testReprojectionUpdateConvertsStoredLocationsToWgs84 replays the store-wide update loop
//     (dbNames ∩ the geo classes, re-inserting only the CHANGED objects) over the public Store
//     API, then reloads by (id, version). It takes TestStore, so TestStoreExtension hands it a
//     fresh store per method.
//
// Expected coordinates are computed at runtime via Projection.resolveTransform — the same
// transform the operation is built on — to a delta of 1e-5, so this asserts the plumbing rather
// than the projection library's arithmetic. The absolute numbers are pinned separately by the
// ensemble's reference pairs.

import noi.netex.gml.DirectPositionType;
import noi.netex.model.LocationStructure;
import noi.netex.model.Operator;
import noi.netex.model.RoutePoint;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPlace;
import org.junit.jupiter.api.Test;
import toolkit.model.GeoClasses;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

public class TestReprojection {

    static final String WGS84 = "urn:ogc:def:crs:EPSG::4326";
    static final String UTM32N = "EPSG:32632";

    static final double EASTING = 680000.0, NORTHING = 5140000.0;  // roughly Bolzano, UTM zone 32N

    private static ScheduledStopPoint stopPointInUtm32n() {
        ScheduledStopPoint ssp = new ScheduledStopPoint();
        ssp.setId("ssp1");
        ssp.setVersion("1");
        ssp.setLocation(new LocationStructure().withSrsName(UTM32N)
                .withPos(new DirectPositionType().withValue(EASTING, NORTHING)
                        .withSrsName(UTM32N).withSrsDimension(BigInteger.TWO)));
        return ssp;
    }

    private static void assertLocationIsWgs84(LocationStructure location) {
        Check.equals(WGS84, location.getSrsName(), "location srs_name is WGS84");
        Check.that(location.getPos() != null, "location pos present");

        // UTM32N -> WGS84 at runtime.
        double[] expected = Projection.resolveTransform(UTM32N, WGS84).apply(EASTING, NORTHING);
        List<Double> stored = location.getPos().getValue();
        Check.equals(2, stored.size(), "two stored coordinates");
        for (int i = 0; i < stored.size(); i++) {
            Check.that(Math.abs(stored.get(i) - expected[i]) <= 1e-5,
                    "coordinate " + i + ": got " + stored.get(i) + ", want ~" + expected[i]);
        }
        // Coordinates must now be valid latitude/longitude (authority order: lat, lon).
        Check.that(-90 <= stored.get(0) && stored.get(0) <= 90, "valid latitude");
        Check.that(-180 <= stored.get(1) && stored.get(1) <= 180, "valid longitude");
    }

    @Test
    void testReprojectionConvertsLocationToWgs84() {
        ScheduledStopPoint ssp = stopPointInUtm32n();
        Projection.reprojectionChanged(ssp, WGS84);   // reprojection(ssp, WGS84) minus the return

        Check.that(ssp.getLocation() != null, "location present");
        assertLocationIsWgs84(ssp.getLocation());
    }

    /// GeoClasses.all() is the reprojection pass's whole subject: it is intersected with dbNames
    /// and everything outside it is never opened. A "non-empty, contains one class I know about"
    /// assertion cannot fail for a set that grew to every model class — which is the failure that
    /// costs a national run a full decode of the timetable — nor for one that shrank to a handful,
    /// which loses geometry silently. So the NEGATIVES and the size are asserted too, from the
    /// retired toolkit.smoke.Smoke: the set is derived from Zone/Point geometry membership, so a
    /// journey and an operator must be outside it while a stop place and a route point are in.
    @Test
    void testGetAllGeoElementsIsNotEmpty() {
        Set<Class<?>> geoClasses = GeoClasses.all();

        Check.that(geoClasses.size() > 0, "geo classes non-empty");
        Check.that(geoClasses.contains(ScheduledStopPoint.class), "ScheduledStopPoint is a geo class");
        Check.that(geoClasses.contains(StopPlace.class), "StopPlace is a geo class (Zone centroid/polygon)");
        Check.that(geoClasses.contains(RoutePoint.class), "RoutePoint is a geo class (Point location)");
        Check.that(!geoClasses.contains(ServiceJourney.class), "a journey carries no geometry");
        Check.that(!geoClasses.contains(Operator.class), "neither does an operator");
        Check.that(geoClasses.size() > 100,
                "the set is the model's geometry-bearing classes (139 at the pinned schema), got "
                        + geoClasses.size());
    }

    @Test
    void testReprojectionUpdateConvertsStoredLocationsToWgs84(TestStore db) throws Exception {
        try (Txn txnWrite = db.store.rwTxn()) {
            db.store.insertAnyObjects(txnWrite, List.<Object>of(stopPointInUtm32n()));
            txnWrite.commit();
        }

        try (Txn txnWrite = db.store.rwTxn()) {
            db.store.insertAnyObjects(txnWrite, reprojectionUpdate(db.store, txnWrite, WGS84));
            txnWrite.commit();
        }

        try (Txn txnRead = db.store.roTxn()) {
            long fullKey = db.store.lookupFullKey(txnRead, "ssp1", "1", ScheduledStopPoint.class);
            Check.that(fullKey != -1, "ssp1 (version 1) found in the id index");
            Object result = db.store.loadObjectByFullKey(txnRead, fullKey);
            Check.that(result instanceof ScheduledStopPoint, "reloaded as a ScheduledStopPoint");
            ScheduledStopPoint reprojected = (ScheduledStopPoint) result;
            Check.that(reprojected.getLocation() != null, "reloaded location present");
            assertLocationIsWgs84(reprojected.getLocation());
        }
    }

    /// The reprojection update, over the public Store API:
    /// every class present in the db ∩ get_all_geo_elements, re-emitting ONLY the objects a
    /// projection actually modified.
    private static Iterator<Object> reprojectionUpdate(Store db, Txn txn, String crsTo) {
        List<Class<?>> classes = new ArrayList<>(db.dbNames(txn));
        classes.retainAll(GeoClasses.all());
        return classes.stream()
                .flatMap(clazz -> StreamSupport.stream(
                        db.iterOnlyObjects(txn, clazz).spliterator(), false))
                .filter(obj -> Projection.reprojectionChanged(obj, crsTo))
                .iterator();
    }
}
