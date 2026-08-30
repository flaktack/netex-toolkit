package toolkit.transform.feedfix;

// A ServiceLink geometry published lon,lat where NeTEx wants lat,lon, normalised against the stop
// the link starts at.
//
// Neither srsName nor the codespace decides the order. Over the 1,469,211 ServiceLinks carrying
// geometry on the national corpus srsName is present on 1.5% (21,547), always the literal
// `urn:ogc:def:crs:EPSG::4326` -- formally lat,lon -- yet 8,454 of the 21,467 classifiable links
// declaring it are lon,lat. Every at:* codespace is uniformly lon,lat and most IT codespaces are
// uniform too, but IT:ITC1 is mixed and consistent only per sub-agency (the 4th :-token): `:1:`,
// `:8:`, `:36:` lon,lat against `:16:`, `:51:`, `:52:`, `:73:`, `:14:` lat,lon.
//
// It runs in phase C of the EPIP conversion, ahead of Projection's reprojection op on the same
// class. The oracle needs every ScheduledStopPoint to have a Location, which EpipLocations
// (generator #5 of phase B) backfills from a Quay or StopPlace centroid; run per feed, every SSP
// that relied on that backfill would yield no anchor and its links would be skipped silently. A
// link that does take a real transform must be normalised into the authority's own convention
// before being reprojected: Projection's Transform is axis-aware by CRS declaration and swaps
// symmetrically in and out, so on a 4326->4326 identity it never questions whether the declared
// order matches the data.

import noi.netex.gml.DirectPositionType;
import noi.netex.gml.LineStringType;
import noi.netex.model.LocationStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceLink;
import toolkit.harness.Ensemble;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class AxisOrder {

    /// The same tolerance OTP applies between a stop and the end of a shape
    /// (`maxStopToShapeSnapDistance`, whose build-config default is 150 m), so a geometry this pass
    /// accepts is one the consumer will also accept.
    private static final double SNAP_METRES = 150.0;

    /// A degree of latitude is ~111.3 km everywhere; a degree of longitude is that times cos(lat).
    /// The two questions here are "within 150 m" and "3,000 km away", so a geodesic buys nothing.
    private static final double METRES_PER_DEGREE = 111_320.0;

    /// Aggregates over one pass, reported and reset by [#reportSwapped]. Static for the same
    /// reason Projection's are: the operation is a lambda body and owns no pass, so only the driver
    /// knows when the pass is finished.
    private static final AtomicLong SWAPPED = new AtomicLong();
    private static final AtomicLong UNCLASSIFIABLE = new AtomicLong();

    private AxisOrder() {}

    /// Report what one pass normalised, and reset. Idempotent: a second call reports zeroes. Called
    /// by the driver.
    public static void reportSwapped(String tag) {
        long swapped = SWAPPED.getAndSet(0);
        long unclassifiable = UNCLASSIFIABLE.getAndSet(0);
        if (swapped > 0 || unclassifiable > 0) {
            Log.info("[%s] axis-order: %d ServiceLink geometries normalised to lat,lon; %d left as "
                    + "published (far from their own FromPointRef under either reading)",
                    tag, swapped, unclassifiable);
        }
    }

    /// Query phase: index every ScheduledStopPoint's position once, and return the per-object
    /// operation (ServiceLink -> changed?). Everything the operation needs is ready before the first
    /// object is processed, so it can run in any stream/ensemble/worker.
    ///
    /// The test is per-link: read the first coordinate pair both ways, measure each against the
    /// Location of the ScheduledStopPoint the link's FromPointRef names, and keep whichever is
    /// closer. Swapping an Italian or Austrian coordinate moves it some 3,000 km, so the two
    /// readings are never close.
    ///
    /// A link that is far from its start stop under both readings is left exactly as published and
    /// counted -- ~76,855 links, mostly IT:ITH5 and IT:ITH20, mis-anchored for reasons this pass
    /// cannot diagnose. One whose FromPointRef resolves to no indexed stop is left unchanged.
    public static Ensemble.Operation axisOrderOp(Store dbRead, Txn txn) {
        Map<String, double[]> stopAt = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
            ScheduledStopPoint ssp = (ScheduledStopPoint) o;
            if (ssp.getId() == null) continue;
            double[] at = locate(ssp);
            if (at != null) stopAt.put(ssp.getId(), at);
        }
        Log.info("axis-order: %d ScheduledStopPoints indexed as the axis oracle", stopAt.size());

        return obj -> {
            ServiceLink sl = (ServiceLink) obj;
            LineStringType ls = sl.getLineString();
            List<Double> pos = ls == null ? null : posList(ls);
            if (pos == null || pos.size() < 4 || pos.size() % 2 != 0) {
                return false;
            }
            ScheduledStopPointRefStructure from = sl.getFromPointRef();
            double[] anchor = from == null ? null : stopAt.get(from.getRef());
            if (anchor == null) {
                return false;
            }
            double lon = anchor[0];
            double lat = anchor[1];
            // pos[0], pos[1] read as (lat, lon) -- the NeTEx-conformant order -- against (lon, lat).
            double asLatLon = metres(pos.get(1), pos.get(0), lon, lat);
            double asLonLat = metres(pos.get(0), pos.get(1), lon, lat);
            if (Math.min(asLatLon, asLonLat) > SNAP_METRES) {
                UNCLASSIFIABLE.incrementAndGet();
                return false;
            }
            if (asLatLon <= asLonLat) {
                return false;
            }
            for (int i = 0; i < pos.size(); i += 2) {
                double swap = pos.get(i);
                pos.set(i, pos.get(i + 1));
                pos.set(i + 1, swap);
            }
            SWAPPED.incrementAndGet();
            return true;
        };
    }

    /// Either gml encoding as a flat lat,lon,lat,lon list. A posList is returned LIVE so the caller
    /// can rewrite it in place; a <pos> sequence is copied, so a swap on one of those would be lost
    /// -- which is correct, because Projection collapses every geometry it touches into a posList
    /// and an untouched <pos> geometry is one this pass must not claim to have fixed.
    private static List<Double> posList(LineStringType ls) {
        if (ls.getPosList() != null) {
            return ls.getPosList().getValue();
        }
        return null;
    }

    private static double[] locate(ScheduledStopPoint ssp) {
        LocationStructure loc = ssp.getLocation();
        if (loc == null) {
            return null;
        }
        if (loc.getLongitude() != null && loc.getLatitude() != null) {
            return new double[] { loc.getLongitude().doubleValue(), loc.getLatitude().doubleValue() };
        }
        DirectPositionType pos = loc.getPos();
        if (pos != null && pos.getValue() != null && pos.getValue().size() == 2) {
            return new double[] { pos.getValue().get(1), pos.getValue().get(0) };
        }
        return null;
    }

    /// Flat-earth metres between two lon/lat pairs. See METRES_PER_DEGREE.
    private static double metres(double lonA, double latA, double lonB, double latB) {
        double dLat = (latA - latB) * METRES_PER_DEGREE;
        double dLon = (lonA - lonB) * METRES_PER_DEGREE * Math.cos(Math.toRadians((latA + latB) / 2));
        return Math.hypot(dLat, dLon);
    }
}
