package toolkit.store;

// Store.fetchAllReferencesByClass must surface the DIRECT reference of the seed class
// (ServiceJourneyPattern -> Route) AND the TRANSITIVE one (Route -> Line), but not the seed
// object itself. skipExisting stays false — sjp1 stays out because it is never a collected TARGET
// of any edge, not because of the skip filter; the fixpoint additionally never expands INTO
// in-set classes.
//
// The Line/Route/SJP fixture is built bean-for-bean; referenced objects are
// inserted FIRST so the references resolve at insert time.
// The test takes TestStore, so Runner drives it against BOTH store formats.

import noi.netex.model.EntityStructure;
import noi.netex.model.Line;
import noi.netex.model.LineRefStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.Route;
import noi.netex.model.RouteRefStructure;
import noi.netex.model.ServiceJourneyPattern;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import noi.netex.text.Mls;

public class TestFetchAllReferencesByClass {

    @Test
    void testFetchAllReferencesByClassYieldsReferencedObjects(TestStore db) throws Exception {
        // make_line_route_sjp: the small outward-reference chain SJP -> Route -> Line.
        ObjectFactory factory = new ObjectFactory();
        Line line = new Line();
        line.setId("l1");
        line.setVersion("1");
        line.setName(Mls.of("Line l1"));
        Route route = new Route();
        route.setId("r1");
        route.setVersion("1");
        LineRefStructure lineRef = new LineRefStructure();
        lineRef.setRef("l1");
        lineRef.setVersion("1");
        route.setLineRef(factory.createLineRef(lineRef));
        ServiceJourneyPattern sjp = new ServiceJourneyPattern();
        sjp.setId("sjp1");
        sjp.setVersion("1");
        RouteRefStructure routeRef = new RouteRefStructure();
        routeRef.setRef("r1");
        routeRef.setVersion("1");
        sjp.setRouteRef(routeRef);

        try (Txn txnWrite = db.store.rwTxn()) {
            // Referenced objects first, so references resolve at insert time.
            db.store.insertAnyObjects(txnWrite, List.<Object>of(line, route, sjp));
            txnWrite.commit();
        }

        try (Txn txnRead = db.store.roTxn()) {
            Iterator<Object> fetched = db.store.fetchAllReferencesByClass(
                    txnRead, Set.of(ServiceJourneyPattern.class), false);
            Set<List<Object>> fetchedIds = new HashSet<>();
            while (fetched.hasNext()) {
                Object obj = fetched.next();
                fetchedIds.add(List.of(obj.getClass(), ((EntityStructure) obj).getId()));
            }

            // The direct reference (Route) and its transitive reference (Line) must both surface.
            Check.that(fetchedIds.contains(List.of(Route.class, "r1")),
                    "direct reference Route r1 surfaces, got " + fetchedIds);
            Check.that(fetchedIds.contains(List.of(Line.class, "l1")),
                    "transitive reference Line l1 surfaces, got " + fetchedIds);
            Check.that(!fetchedIds.contains(List.of(ServiceJourneyPattern.class, "sjp1")),
                    "the seed-class object itself does not surface");
        }
    }

    /// `skipExisting` used to be decided from the DECODED bean's class, so every collected target
    /// was unmarshalled and then possibly thrown away. It is now decided from the row's class
    /// INDEX, which is what `loadObjectByFullKey` would have unmarshalled with anyway.
    ///
    /// This pins the behaviour that makes that legal: with `Route` in the seed set, Route
    /// IS a collected target (SJP references it) and must still be filtered out, while its own
    /// transitive reference Line must still surface. If the key-based filter over- or under-shot,
    /// one of these two assertions fails.
    @Test
    void testSkipExistingFiltersTheSameObjectsFromTheKey(TestStore db) throws Exception {
        seed(db);
        try (Txn txnRead = db.store.roTxn()) {
            Set<List<Object>> skipped = drain(db, txnRead, Set.of(ServiceJourneyPattern.class,
                    Route.class), true);
            Check.that(!skipped.contains(List.of(Route.class, "r1")),
                    "Route is a collected target but its class is in the set: skipped, got " + skipped);
            Check.that(skipped.contains(List.of(Line.class, "l1")),
                    "Line is not in the set and still surfaces, got " + skipped);
        }
        try (Txn txnRead = db.store.roTxn()) {
            Set<List<Object>> kept = drain(db, txnRead, Set.of(ServiceJourneyPattern.class,
                    Route.class), false);
            Check.that(kept.contains(List.of(Route.class, "r1")),
                    "and without skipExisting the same Route is emitted, got " + kept);
        }
    }

    /// The refusal that keeps the key-based filter honest: JAXB honours an `xsi:type` on the
    /// fragment root and can then return a class other than the declared one, which is exactly what
    /// the old bean-side `Set.contains` would have judged differently. Any `*:type`
    /// attribute in the root start tag therefore forces the decode.
    @Test
    void testRootTypeAttributeForcesTheDecode() {
        String ns = "http://www.netex.org.uk/netex";
        record Case(boolean expected, String xml) {}
        for (Case c : List.of(
                new Case(false, "<Line xmlns=\"" + ns + "\" id=\"l\"><Name>n</Name></Line>"),
                new Case(false, "<Line xmlns=\"" + ns + "\" id=\"l\"/>"),
                new Case(false, "<?xml version=\"1.0\"?><Line xmlns=\"" + ns + "\" id=\"l\"><Name/></Line>"),
                // a child carrying xsi:type is NOT the root and must not force a decode
                new Case(false, "<Line xmlns=\"" + ns + "\" id=\"l\"><Name xsi:type=\"x\">n</Name></Line>"),
                // an attribute VALUE that merely contains ":type" is inside quotes and ignored
                new Case(false, "<Line xmlns=\"" + ns + "\" id=\"a:type=b\"><Name/></Line>"),
                new Case(true, "<Line xmlns=\"" + ns + "\" xsi:type=\"FlexibleLine\" id=\"l\"/>"),
                new Case(true, "<Line xmlns=\"" + ns + "\" q:type = \"X\" id=\"l\"/>"),
                new Case(true, ""),
                new Case(true, "<Line xmlns=\"" + ns + "\" id=\"l\""))) {
            Check.equals(c.expected(),
                    toolkit.codec.FragmentPeek.rootHasTypeAttribute(
                            c.xml().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "rootHasTypeAttribute: " + c.xml());
        }
    }

    /// THE ORDER, WHICH THE ASSERTIONS ABOVE THROW AWAY. Both drains collect into a HashSet, so a
    /// build that emitted the fixpoint's discoveries before the seed's would satisfy every one of
    /// them. The SPI records this method's emission as deterministic and TestScanEdges pins
    /// the determinism of the block scan underneath it; between the two sits the fixpoint, which is
    /// where the order is actually decided. Seed-discovered targets come out before the ones a later
    /// round found: Route, then Line. Ported from the retired toolkit.smoke.Smoke.
    @Test
    void testSeedDiscoveriesAreEmittedBeforeFixpointOnes(TestStore db) throws Exception {
        seed(db);
        try (Txn txnRead = db.store.roTxn()) {
            List<Object> fetched = ordered(db, txnRead, Set.of(ServiceJourneyPattern.class), false);
            Check.equals(2, fetched.size(), "the closure is Route and Line");
            Check.that(fetched.get(0) instanceof Route,
                    "the seed's direct target comes first, got " + fetched.get(0).getClass().getSimpleName());
            Check.that(fetched.get(1) instanceof Line,
                    "and the transitive one after it, got " + fetched.get(1).getClass().getSimpleName());
        }
    }

    /// The two ends of the walk that the SJP seed cannot distinguish. From a LEAF class there is
    /// nothing to collect at all — an implementation that seeded the output with the sources
    /// themselves would return one object here instead of none — and from an interior class only
    /// its own targets surface, never the class it was seeded with.
    @Test
    void testALeafSeedYieldsNothingAndASourceIsNeverItsOwnResult(TestStore db)
            throws Exception {
        seed(db);
        try (Txn txnRead = db.store.roTxn()) {
            Check.equals(List.of(), ordered(db, txnRead, Set.of(Line.class), false).stream()
                            .map(o -> o.getClass().getSimpleName()).toList(),
                    "Line references nothing, so its closure is empty");
            Check.equals(List.of("Line"), ordered(db, txnRead, Set.of(Route.class), false).stream()
                            .map(o -> o.getClass().getSimpleName()).toList(),
                    "from Route only its target surfaces — the Route itself never does");
        }
    }

    /// SJP -> Route -> Line, referenced objects first.
    private static void seed(TestStore db) throws Exception {
        ObjectFactory factory = new ObjectFactory();
        Line line = new Line();
        line.setId("l1");
        line.setVersion("1");
        line.setName(Mls.of("Line l1"));
        Route route = new Route();
        route.setId("r1");
        route.setVersion("1");
        LineRefStructure lineRef = new LineRefStructure();
        lineRef.setRef("l1");
        lineRef.setVersion("1");
        route.setLineRef(factory.createLineRef(lineRef));
        ServiceJourneyPattern sjp = new ServiceJourneyPattern();
        sjp.setId("sjp1");
        sjp.setVersion("1");
        RouteRefStructure routeRef = new RouteRefStructure();
        routeRef.setRef("r1");
        routeRef.setVersion("1");
        sjp.setRouteRef(routeRef);
        try (Txn txnWrite = db.store.rwTxn()) {
            db.store.insertAnyObjects(txnWrite, List.<Object>of(line, route, sjp));
            txnWrite.commit();
        }
    }

    /// As [#drain], keeping the emission order the set discards.
    private static List<Object> ordered(TestStore db, Txn txn, Set<Class<?>> classes,
                                        boolean skipExisting) {
        List<Object> out = new ArrayList<>();
        Iterator<Object> it = db.store.fetchAllReferencesByClass(txn, classes, skipExisting);
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    private static Set<List<Object>> drain(TestStore db, Txn txn, Set<Class<?>> classes,
                                           boolean skipExisting) {
        Set<List<Object>> out = new HashSet<>();
        Iterator<Object> it = db.store.fetchAllReferencesByClass(txn, classes, skipExisting);
        while (it.hasNext()) {
            Object o = it.next();
            out.add(List.of(o.getClass(), ((EntityStructure) o).getId()));
        }
        return out;
    }
}
