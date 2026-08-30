package toolkit.transform.feedfix;

// The axis-order oracle, over real Carinthian coordinates so the metre arithmetic runs at the
// latitude it will see in production; a swap there lands the geometry some 3,000 km away.
//
// [AxisOrder#axisOrderOp] is the operation and the driver-side wiring holds no logic of its own, so
// these drive the operation directly over a store built by the real loader.

import noi.netex.model.ServiceLink;
import org.junit.jupiter.api.Test;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.List;

public class TestAxisOrder {

    // ssp1 -> ssp2 runs south-west down a Carinthian valley.
    static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <scheduledStopPoints>
    <ScheduledStopPoint id="ssp1" version="1"><Location><Longitude>14.855584</Longitude><Latitude>46.721527</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp2" version="1"><Location><Longitude>14.795055</Longitude><Latitude>46.620892</Latitude></Location></ScheduledStopPoint>
  </scheduledStopPoints>
  <serviceLinks>
    <ServiceLink id="slLonLat" version="1"><Distance>12000</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="lsA"><posList srsDimension="2">14.855584 46.721527 14.820000 46.670000 14.795055 46.620892</posList></LineString>
      <FromPointRef ref="ssp1" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
    <ServiceLink id="slLatLon" version="1"><Distance>12000</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="lsB"><posList srsDimension="2">46.721527 14.855584 46.670000 14.820000 46.620892 14.795055</posList></LineString>
      <FromPointRef ref="ssp1" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
    <ServiceLink id="slFar" version="1"><Distance>500</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="lsC"><posList srsDimension="2">2.100000 41.400000 2.110000 41.410000</posList></LineString>
      <FromPointRef ref="ssp1" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
    <ServiceLink id="slNoAnchor" version="1"><Distance>500</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="lsD"><posList srsDimension="2">14.855584 46.721527 14.795055 46.620892</posList></LineString>
      <FromPointRef ref="sspUnknown" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
    <ServiceLink id="slBare" version="1"><Distance>800</Distance>
      <FromPointRef ref="ssp1" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
  </serviceLinks>
</ServiceFrame>
""";

    /// The Austrian and Italian majority: published lon,lat, which OTP reads as lat,lon and lands in
    /// the Indian Ocean.
    @Test
    public void testLonLatIsNormalised(TestStore db) throws Exception {
        List<Double> pos = applyTo(db, "slLonLat");

        Check.equals(46.721527, pos.get(0), "first value is now the latitude");
        Check.equals(14.855584, pos.get(1), "second is the longitude");
        Check.equals(46.620892, pos.get(pos.size() - 2), "the last pair swapped too, not just the first");
        Check.equals(14.795055, pos.get(pos.size() - 1), "last longitude");
    }

    /// A link already in the conformant order must come out untouched.
    @Test
    public void testLatLonIsLeftAlone(TestStore db) throws Exception {
        List<Double> pos = applyTo(db, "slLatLon");

        Check.equals(46.721527, pos.get(0), "already lat,lon and stayed");
        Check.equals(14.855584, pos.get(1), "unswapped longitude");
    }

    /// Far from its own FromPointRef under BOTH readings -- the IT:ITH5 / IT:ITH20 tail, ~76,855
    /// mis-anchored links in the corpus. Picking the nearer of two wrong answers would turn a
    /// mis-anchored geometry into a corrupt one.
    @Test
    public void testUnclassifiableIsLeftAlone(TestStore db) throws Exception {
        List<Double> pos = applyTo(db, "slFar");

        Check.equals(2.100000, pos.get(0), "left exactly as published");
        Check.equals(41.400000, pos.get(1), "second value unchanged");
    }

    /// No anchor, no opinion. An unresolvable FromPointRef is a Map.get miss, not a crash.
    @Test
    public void testLinkWithoutAnAnchorIsLeftAlone(TestStore db) throws Exception {
        List<Double> pos = applyTo(db, "slNoAnchor");

        Check.equals(14.855584, pos.get(0), "no anchor, so nothing to judge against");
    }

    /// A link with no geometry at all is not a geometry defect.
    @Test
    public void testLinkWithoutGeometryIsSkipped(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        try (Txn txn = db.store.roTxn()) {
            var op = AxisOrder.axisOrderOp(db.store, txn);
            Check.that(!op.apply(link(db, txn, "slBare")), "slBare reports no change");
        }
    }

    /// Run the operation over one link and hand back its (live) coordinate list.
    private static List<Double> applyTo(TestStore db, String id) throws Exception {
        db.loadNetex(NETEX);
        try (Txn txn = db.store.roTxn()) {
            var op = AxisOrder.axisOrderOp(db.store, txn);
            ServiceLink sl = link(db, txn, id);
            op.apply(sl);
            return sl.getLineString().getPosList().getValue();
        }
    }

    private static ServiceLink link(TestStore db, Txn txn, String id) {
        for (Object o : db.store.iterOnlyObjects(txn, ServiceLink.class)) {
            if (o instanceof ServiceLink sl && id.equals(sl.getId())) {
                return sl;
            }
        }
        throw new IllegalStateException("no ServiceLink " + id + " in the fixture");
    }
}
