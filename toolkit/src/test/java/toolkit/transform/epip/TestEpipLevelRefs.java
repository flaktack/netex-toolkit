package toolkit.transform.epip;

// Covers EpipSanitize.stripOrphanLevelRefs. Clearing the dangling LevelRefs is a DECISION: EPIP
// requires an internal LevelRef to resolve in the same document, while its absence is conformant,
// so a ref to a Level that is not there is removed rather than emitted.
//
// THE GUARD IS THE SUBJECT OF MOST OF THIS FILE, because nothing downstream can see this change:
// no consumer reads LevelRef, so the export A/B and the OTP gate are both blind to a wrong strip.
// The only instrument that can catch one is the G1-G6 guard inside the transform. Hence the two
// sabotage tests, one per direction:
//
//   clear-all  must fail on `intact`  — and the <Level>-count invariant stays GREEN under it,
//                                       which is why `intact` has to be asserted;
//   clear-none must fail on `cleared`.
//
// Both shapes are covered: a Quay's SiteComponent `levelRef`, and a <LevelRef> sitting directly in
// a Site's <levels> (containment by reference), which is where a Parking carries it. A
// StopPlace-only strip leaves the export's shared-06 entry non-conformant.

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;
import noi.netex.model.Level;
import noi.netex.model.LevelRefStructure;
import noi.netex.model.Parking;
import noi.netex.model.Quay;
import noi.netex.model.SitePathLink_VersionStructure;
import noi.netex.model.Site_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.sites.PathLinkEnds;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.model.RecursiveAttributes;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestEpipLevelRefs {

    // ---------------------------------------------------------------- fixtures

    /// Both shapes at once, with a control on each: the StopPlace defines one Level, one quay
    /// points at it (must survive) and one points at a Level nobody defines (must go); its
    /// `<levels>` carries a containment-by-reference `<LevelRef>` at a Level nobody
    /// defines (must go); and a bare `<QuayRef>` member of `<quays>` carries no
    /// levelRef at all and must be counted as nothing. The Parking is the shape a StopPlace-only
    /// strip would miss, in a document of its own.
    private static final String BOTH_SHAPES = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>Wien Testplatz</Name>
            <levels>
              <Level id="at:obb:Level:here:" version="1"><Name>Erdgeschoss</Name></Level>
              <LevelRef ref="at:obb:Level:gone:" version="1"/>
            </levels>
            <quays>
              <Quay id="at:49:975:1" version="1"><Name>Steig 1</Name><LevelRef ref="at:obb:Level:here:" version="1"/></Quay>
              <Quay id="at:49:975:2" version="1"><Name>Steig 2</Name><LevelRef ref="at:obb:Level:gone:" version="1"/></Quay>
              <QuayRef ref="at:49:975:9" version="1"/>
            </quays></StopPlace>
        </stopPlaces>
        <parkings>
          <Parking id="at:45:50290:3" version="1"><Name>P+R</Name>
            <levels><LevelRef ref="at:obb:Level:185-0:" version="1"/></levels>
          </Parking>
        </parkings></SiteFrame>
        """;

    /// The Parking shape ALONE — the entry a StopPlace-only strip leaves non-conformant.
    private static final String PARKING_ONLY = """
        <SiteFrame id="site" version="1"><parkings>
          <Parking id="at:45:50290:3" version="1"><Name>P+R</Name>
            <levels><LevelRef ref="at:obb:Level:185-0:" version="1"/></levels>
          </Parking>
        </parkings></SiteFrame>
        """;

    /// Two StopPlaces, one defining a Level and the other's quay referencing it — the shape G6
    /// exists to refuse. Per Site the second one's ref is an orphan; per DOCUMENT, which is what
    /// `Level_KeyRef`'s selector actually says, it resolves.
    private static final String SHARED_LEVEL_ID = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>A</Name>
            <levels><Level id="at:obb:Level:shared:" version="1"><Name>Erdgeschoss</Name></Level></levels>
            <quays><Quay id="at:49:975:1" version="1"><Name>1</Name><LevelRef ref="at:obb:Level:shared:" version="1"/></Quay></quays></StopPlace>
          <StopPlace id="at:49:976" version="1"><Name>B</Name>
            <quays><Quay id="at:49:976:1" version="1"><Name>1</Name><LevelRef ref="at:obb:Level:shared:" version="1"/></Quay></quays></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// `<Entrance>` and `<AccessSpace>` carry the SAME
    /// `SiteComponent_VersionStructure.levelRef` member a `<Quay>` does, so a Quay-only traversal
    /// reaches none of them.
    ///
    /// Against a Quay-only traversal this fixture reports `refs=1 cleared=0 intact=1` and passes
    /// G1-G6 clean, which is why the counts below are asserted.
    private static final String SITE_COMPONENTS_BEYOND_QUAYS = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>Wien Testplatz</Name>
            <levels>
              <Level id="at:obb:Level:here:" version="1"><Name>Erdgeschoss</Name></Level>
            </levels>
            <entrances>
              <Entrance id="at:49:975:90" version="1"><Name>Eingang Mitte</Name><LevelRef ref="at:obb:Level:here:" version="1"/></Entrance>
              <Entrance id="at:49:975:91" version="1"><Name>Eingang West</Name><LevelRef ref="at:obb:Level:gone:" version="1"/></Entrance>
            </entrances>
            <quays>
              <Quay id="at:49:975:1" version="1"><Name>Steig 1</Name><LevelRef ref="at:obb:Level:here:" version="1"/></Quay>
            </quays>
            <accessSpaces>
              <AccessSpace id="at:49:975:A" version="1"><Name>Halle</Name><LevelRef ref="at:obb:Level:gone:" version="1"/></AccessSpace>
            </accessSpaces></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// The `SitePathLink`'s own `levelRef` and its `From`/`To` ends, which the reflective carrier
    /// test only pins as DECLARED by the model, not as HANDLED by the pass — and which clear
    /// nothing on the corpus, so a defect in either arm is invisible to every output comparison.
    /// They are also the arms whose class 2.0 splits, so this fixture drives both with a control.
    ///
    /// The `<From>` end points at the Level this StopPlace defines and must survive; the
    /// `<To>` end and the link's own `<LevelRef>` point at one nobody defines and must
    /// go. The `<PlaceRef>`s are required by the schema and carry no levelRef, so they also
    /// serve as the "counted as nothing" control the `<QuayRef>` plays in [#BOTH_SHAPES].
    private static final String SITE_PATH_LINK_ENDS = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>Wien Testplatz</Name>
            <levels>
              <Level id="at:obb:Level:here:" version="1"><Name>Erdgeschoss</Name></Level>
            </levels>
            <pathLinks>
              <SitePathLink id="at:49:975:PL1" version="1">
                <From><PlaceRef ref="at:49:975:1" version="1"/><LevelRef ref="at:obb:Level:here:" version="1"/></From>
                <To><PlaceRef ref="at:49:975:2" version="1"/><LevelRef ref="at:obb:Level:gone:" version="1"/></To>
                <LevelRef ref="at:obb:Level:gone:" version="1"/>
              </SitePathLink>
            </pathLinks>
            <quays>
              <Quay id="at:49:975:1" version="1"><Name>Steig 1</Name></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// THE FIFTH CARRIER, WHICH ONLY EXISTS AT 2.0. `<LevelInStructure>` is a `+v2.0` element whose
    /// content group carries a **mandatory** `<LevelRef>`. It is TWO hops from a Site, not one —
    /// `Site.siteStructures` &rarr; `SiteStructure` &rarr; `levelsInStructure` &rarr;
    /// `LevelInStructure` — and `SiteStructure_VersionStructure` extends
    /// `DataManagedObjectStructure`, so it is not itself a Site and the pass reaches it only because
    /// the walker descends generically. This fixture would fail if navigation were ever narrowed
    /// back to named containers.
    ///
    /// THE CLEAR IS A REMOVAL, hence its own fixture. For the other four carriers the repair is
    /// `setLevelRef(null)`; here the field is `required = true`, so nulling it would trade a
    /// `Level_KeyRef` violation for a content-model violation. The whole `<LevelInStructure>` goes,
    /// and an emptied `<levelsInStructure>` goes with it — `maxOccurs="unbounded"` at the default
    /// `minOccurs=1` makes an empty container invalid too.
    ///
    /// A 1.15.2-authored feed cannot contain a 2.0-only element and be schema-valid, so this arm
    /// clears nothing today and exists for the same reason [#SITE_PATH_LINK_ENDS] does.
    private static final String LEVEL_IN_STRUCTURE = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>Wien Testplatz</Name>
            <levels>
              <Level id="at:obb:Level:here:" version="1"><Name>Erdgeschoss</Name></Level>
            </levels>
            <siteStructures>
              <SiteStructure id="at:49:975:S1" version="1"><Name>Haupthalle</Name>
                <levelsInStructure>
                  <LevelInStructure id="at:49:975:S1:0" version="1"><LevelRef ref="at:obb:Level:here:" version="1"/></LevelInStructure>
                  <LevelInStructure id="at:49:975:S1:1" version="1"><LevelRef ref="at:obb:Level:gone:" version="1"/></LevelInStructure>
                </levelsInStructure>
              </SiteStructure>
            </siteStructures>
            <quays>
              <Quay id="at:49:975:1" version="1"><Name>Steig 1</Name><LevelRef ref="at:obb:Level:here:" version="1"/></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// Every `<LevelInStructure>` of the one structure is an orphan: the container goes too.
    private static final String LEVEL_IN_STRUCTURE_ALL_ORPHANED = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>Wien Testplatz</Name>
            <levels>
              <Level id="at:obb:Level:here:" version="1"><Name>Erdgeschoss</Name></Level>
            </levels>
            <siteStructures>
              <SiteStructure id="at:49:975:S1" version="1"><Name>Haupthalle</Name>
                <levelsInStructure>
                  <LevelInStructure id="at:49:975:S1:1" version="1"><LevelRef ref="at:obb:Level:gone:" version="1"/></LevelInStructure>
                </levelsInStructure>
              </SiteStructure>
            </siteStructures>
            <quays>
              <Quay id="at:49:975:1" version="1"><Name>Steig 1</Name><LevelRef ref="at:obb:Level:here:" version="1"/></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// The Parking case G6 is deliberately NOT scoped to: its target IS a Level, in a StopPlace.
    private static final String PARKING_POINTS_INTO_A_STOPPLACE = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>A</Name>
            <levels><Level id="at:obb:Level:here:" version="1"><Name>Erdgeschoss</Name></Level></levels>
            <quays><Quay id="at:49:975:1" version="1"><Name>1</Name><LevelRef ref="at:obb:Level:here:" version="1"/></Quay></quays></StopPlace>
        </stopPlaces>
        <parkings>
          <Parking id="at:45:50290:3" version="1"><Name>P+R</Name>
            <levels><LevelRef ref="at:obb:Level:here:" version="1"/></levels>
          </Parking>
        </parkings></SiteFrame>
        """;

    // ---------------------------------------------------------------- driver

    /// One rw txn for reads and writes, then commit — the in-place fix idiom the phase uses.
    private static EpipSanitize.LevelRefResult strip(TestStore db) {
        try (Txn txn = db.store.rwTxn()) {
            EpipSanitize.LevelRefResult r = EpipSanitize.stripOrphanLevelRefs(db.store, txn);
            txn.commit();
            return r;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    private static Map<String, Quay> quaysOf(StopPlace sp) {
        Map<String, Quay> out = new LinkedHashMap<>();
        if (sp.getQuays() == null) return out;
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            if (el.getValue() instanceof Quay q) out.put(q.getId(), q);
        }
        return out;
    }

    private static List<String> levelRefsOf(Site_VersionStructure site) {
        List<String> out = new ArrayList<>();
        if (site.getLevels() == null) return out;
        for (Object o : site.getLevels().getLevelRefOrLevel()) {
            if (o instanceof LevelRefStructure r) out.add(r.getRef());
        }
        return out;
    }

    private static int levelsOf(Site_VersionStructure site) {
        int n = 0;
        if (site.getLevels() == null) return 0;
        for (Object o : site.getLevels().getLevelRefOrLevel()) if (o instanceof Level) n++;
        return n;
    }

    // ---------------------------------------------------------------- the pass

    /// The positive test, over both shapes and both controls. The counts come out of the guard's
    /// own Result, so this also pins the formula: refs = quay levelRefs ++ levels LevelRefs,
    /// cleared = those whose target the Site does not define, intact = the rest.
    @Test
    void testClearsBothShapesAndKeepsTheResolvableRef(TestStore db) throws Exception {
        db.loadNetex(BOTH_SHAPES);
        EpipSanitize.LevelRefResult r = strip(db);

        Check.equals(2L, r.sites(), "both Sites examined — the StopPlace AND the Parking");
        Check.equals(2L, r.sitesTouched(), "both lost a ref");
        Check.equals(4L, r.refs(), "4 refs: 2 quay levelRefs, 2 in <levels>; the QuayRef is none");
        Check.equals(3L, r.cleared(), "3 orphans cleared");
        Check.equals(1L, r.intact(), "the one ref the StopPlace can resolve stays");
        Check.equals(1L, r.levelsBefore(), "one <Level> definition before");
        Check.equals(1L, r.levelsAfter(), "and the same one after — this pass removes no Level");
        Check.equals(0L, r.orphansAfter(), "nothing unresolvable survives");
        Check.equals(1L, r.refsAfter(), "and what survives is exactly the intact ref");
        Check.equals(0L, r.g6Collisions(), "no cleared StopPlace target is a Level anywhere");

        StopPlace sp = one(db, StopPlace.class);
        Map<String, Quay> quays = quaysOf(sp);
        Check.equals("at:obb:Level:here:", quays.get("at:49:975:1").getLevelRef().getRef(),
                "the quay whose Level the StopPlace defines keeps its levelRef");
        Check.isNull(quays.get("at:49:975:2").getLevelRef(),
                "the quay pointing at a Level nobody defines lost it");
        Check.equals(List.of(), levelRefsOf(sp),
                "the containment-by-reference orphan is gone from <levels>");
        Check.equals(1, levelsOf(sp), "and the <Level> beside it is untouched");

        Parking p = one(db, Parking.class);
        Check.isNull(p.getLevels(),
                "the Parking's <levels> held nothing but the orphan, so the container goes too");
    }

    /// The `SitePathLink` carriers, driven both ways.
    ///
    /// The executable half of the seam `noi.netex.sites.PathLinkEnds`: the two `From`/`To` arms go
    /// through it because 2.0 moves `levelRef` off `PathLinkEndStructure` onto
    /// `SitePathLinkEndStructure`. It also covers `SitePathLink_VersionStructure.levelRef`, which
    /// the seam does NOT touch — that member keeps its class at 2.0.
    ///
    /// With `PathLinkEnds.isEnd` forced to `false` this is the only test in the file that goes red,
    /// and it goes red through `G7 reach`, so the seam is bound by a guard whose denominator comes
    /// from outside it and not only by the numbers in this method.
    @Test
    void testSitePathLinkEndsAndTheLinksOwnLevelRef(TestStore db) throws Exception {
        db.loadNetex(SITE_PATH_LINK_ENDS);
        EpipSanitize.LevelRefResult r = strip(db);

        Check.equals(1L, r.sites(), "one Site: the StopPlace");
        Check.equals(3L, r.refs(),
                "3 refs on the path link — the From end, the To end and the link's own levelRef; "
                        + "the two <PlaceRef>s are not LevelRefs and are counted as nothing");
        Check.equals(2L, r.cleared(), "the To end and the link's own ref are orphans");
        Check.equals(1L, r.intact(), "the From end resolves against the Level this Site defines");
        Check.equals(1L, r.levelsBefore(), "one <Level> definition before");
        Check.equals(1L, r.levelsAfter(), "and after — this pass removes no Level");
        Check.equals(0L, r.orphansAfter(), "nothing unresolvable survives");
        Check.equals(1L, r.refsAfter(), "and what survives is exactly the intact ref");

        StopPlace sp = one(db, StopPlace.class);
        Check.that(sp.getPathLinks() != null, "the <pathLinks> container survives");
        // Reached by WALK, not by the container's accessor: `sitePathLinks_RelStructure`'s member
        // list is not the same at both schema versions, so the navigation here goes through the
        // same walker the pass uses.
        List<SitePathLink_VersionStructure> links = new ArrayList<>();
        RecursiveAttributes.walk(sp, new RecursiveAttributes.Visitor() {
            @Override public void reference(noi.netex.model.VersionOfObjectRefStructure ref) {}
            @Override public void node(Object o) {
                if (o instanceof SitePathLink_VersionStructure p) links.add(p);
            }
        });
        Check.equals(1, links.size(), "and it still holds exactly the one SitePathLink");
        SitePathLink_VersionStructure pl = links.get(0);
        Check.equals("at:obb:Level:here:", PathLinkEnds.levelRef(pl.getFrom()).getRef(),
                "the From end's resolvable levelRef is kept, through the seam that reads it");
        Check.isNull(PathLinkEnds.levelRef(pl.getTo()),
                "the To end's orphan levelRef is cleared");
        Check.isNull(pl.getLevelRef(), "and the link's own orphan levelRef is cleared too");
    }

    /// THE SCOPE CORRECTION, on its own. A StopPlace-only strip reports a clean run over this
    /// store and leaves the export entry that holds it non-conformant.
    @Test
    void testTheParkingShapeIsCoveredOnItsOwn(TestStore db) throws Exception {
        db.loadNetex(PARKING_ONLY);
        EpipSanitize.LevelRefResult r = strip(db);
        Check.equals(1L, r.sites(), "the Parking is a Site and is examined");
        Check.equals(1L, r.refs(), "its <levels><LevelRef/> is a ref");
        Check.equals(1L, r.cleared(), "and it is cleared — a StopPlace-only strip would miss it");
        Check.equals(0L, r.levelsBefore(), "this document defines no <Level> at all");
        Check.isNull(one(db, Parking.class).getLevels(),
                "the emptied container is dropped rather than left as a bare <levels/>");
    }

    /// The asymmetry G6 is scoped for. This Parking's target IS a Level — inside a StopPlace — so
    /// the store-wide intersection is NOT empty, and the pass must still clear it and must NOT
    /// fail. What licenses that is document scope: the exporter puts Parking in the closure entry
    /// and StopPlaces in the sites entry, so the ref is orphaned in its own document whatever the
    /// store holds. Assert the collision is SEEN and reported, not asserted on.
    @Test
    void testParkingIsClearedEvenWhenItsTargetIsALevelElsewhere(TestStore db) throws Exception {
        db.loadNetex(PARKING_POINTS_INTO_A_STOPPLACE);
        EpipSanitize.LevelRefResult r = strip(db);
        Check.equals(1L, r.cleared(), "the Parking's ref is cleared");
        Check.equals(1L, r.intact(), "the StopPlace's own quay ref resolves and stays");
        Check.equals(1L, r.otherSiteCollisions(),
                "and the pass REPORTS that its target is a Level held elsewhere in the store");
        Check.equals(0L, r.g6Collisions(),
                "G6 is StopPlace-scoped, so the Parking collision does not fire it");
    }

    /// G6: the moment a feed shares a Level id across two StopPlaces, the pass must refuse.
    @Test
    void testG6RefusesWhenTwoStopPlacesShareALevelId(TestStore db) throws Exception {
        db.loadNetex(SHARED_LEVEL_ID);
        String message = null;
        try {
            strip(db);
        } catch (Throwable t) {
            message = rootMessage(t);
        }
        Check.that(message != null,
                "the run ABORTED — per Site this ref is an orphan, per document it resolves");
        Check.that(message.contains("G6"), "and it was G6 that saw it: " + message);
        Check.that(message.contains("at:obb:Level:shared:"),
                "naming the target it refused to strip: " + message);
    }

    // ---------------------------------------------------------------- the guard, negatively

    /// NEGATIVE TEST 1 — clear-all. The seam makes the strip pass clear EVERY LevelRef, including
    /// the one the StopPlace can resolve. What stays green: the `<Level>` count is unchanged, no
    /// orphan survives, and a second pass would clear nothing — every invariant except the ones
    /// that count what was LEFT, which is why `intact` is asserted.
    @Test
    void testGuardFiresOnClearAll(TestStore db) throws Exception {
        db.loadNetex(BOTH_SHAPES);
        String message = null;
        EpipSanitize.setOrphanTest((own, ref) -> true);
        try {
            strip(db);
        } catch (Throwable t) {
            message = rootMessage(t);
        } finally {
            EpipSanitize.setOrphanTest(null);
        }
        Check.that(message != null, "the run ABORTED — a guard that never fires is not a guard");
        Check.that(message.contains("GUARD"), "and it was the guard that stopped it: " + message);
        Check.that(message.contains("G4 intact"),
                "on the INTACT arm, which is the only one an over-strip moves: " + message);
        Check.that(!message.contains("G2 ") && !message.contains("G3 ") && !message.contains("G5 "),
                "while the <Level>-count, soundness and second-pass invariants stayed GREEN — "
                        + "the reason `intact` cannot be dropped: " + message);
        EpipSanitize.LevelRefResult r = EpipSanitize.lastLevelRefResult();
        Check.equals(r.levelsBefore(), r.levelsAfter(),
                "measured, not argued: over-stripping does not move the <Level> count");
        Check.equals(4L, r.cleared(), "the sabotage really did clear all four refs");
    }

    /// NEGATIVE TEST 2 — clear-none. Nothing is removed, and `cleared` is what catches it.
    @Test
    void testGuardFiresOnClearNone(TestStore db) throws Exception {
        db.loadNetex(BOTH_SHAPES);
        String message = null;
        EpipSanitize.setOrphanTest((own, ref) -> false);
        try {
            strip(db);
        } catch (Throwable t) {
            message = rootMessage(t);
        } finally {
            EpipSanitize.setOrphanTest(null);
        }
        Check.that(message != null, "the run ABORTED");
        Check.that(message.contains("G4 cleared"),
                "on the CLEARED arm, against the independent pre-pass: " + message);
        EpipSanitize.LevelRefResult r = EpipSanitize.lastLevelRefResult();
        Check.equals(0L, r.cleared(), "the sabotage really did clear nothing");
        Check.equals(r.levelsBefore(), r.levelsAfter(),
                "and under-stripping does not move the <Level> count either");
    }

    /// A green from counters that are all zero has checked nothing; pin that the
    /// fixture exercises every arm the guard compares.
    @Test
    void testTheCountersAreNotVacuouslyZero(TestStore db) throws Exception {
        db.loadNetex(BOTH_SHAPES);
        EpipSanitize.LevelRefResult r = strip(db);
        Check.that(r.refs() > 0, "the pass measured refs");
        Check.that(r.cleared() > 0, "found orphans to clear");
        Check.that(r.intact() > 0, "AND left something intact — otherwise `intact` proves nothing");
        Check.that(r.levelsBefore() > 0, "over a store that actually defines a Level");
        Check.that(r.targetsStopPlace() > 0 && r.targetsOtherSite() > 0,
                "on both scopes, so G6's StopPlace restriction is exercised rather than empty");
    }

    // -------------------------------------------------- reach, and the guard on reach

    /// Every `SiteComponent` carries the same `levelRef`, so the pass must reach an
    /// `<Entrance>`'s and an `<AccessSpace>`'s and not only a `<Quay>`'s. The store's OWN byte
    /// census is asserted beside the traversal's count, which is what makes this a completeness
    /// claim.
    @Test
    void testReachesEverySiteComponentNotOnlyQuays(TestStore db) throws Exception {
        db.loadNetex(SITE_COMPONENTS_BEYOND_QUAYS);
        EpipSanitize.LevelRefResult r = strip(db);

        Check.equals(4L, r.refs(),
                "2 <Entrance> + 1 <Quay> + 1 <AccessSpace>; a Quay-only traversal sees 1 of these");
        Check.equals(2L, r.cleared(), "the two pointing at a Level nobody defines go");
        Check.equals(2L, r.intact(), "the two that resolve inside this StopPlace stay");
        Check.equals(1L, r.levelsBefore(), "one <Level>");
        Check.equals(1L, r.levelsAfter(), "still one after");
        Check.equals(0L, r.orphansAfter(), "nothing unresolvable survives");

        Check.equals(4L, r.storeRefsBefore(),
                "and the STORE's own fragment bytes say 4 too — G7's denominator, which no bean "
                        + "traversal produced");
        Check.equals(2L, r.storeRefsAfter(), "2 in the bytes afterwards, matching intact");
        Check.equals(0L, r.storeRefsOutside(), "none of them outside a Site class map");
        Check.equals(0L, r.nestedSites(), "and no Site nested inside a Site, so own(s) is defined");
    }

    /// NEGATIVE TEST 3 — the one G1-G6 CANNOT do, and the reason G7 exists. The reach seam hides
    /// every `<Entrance>` from BOTH the pre-pass and the strip, so the two bean traversals still
    /// agree with each other and every G1-G6 arm is green over the part of the store they can see.
    /// Only the store's own byte count disagrees.
    @Test
    void testG7FiresWhenTheTraversalIsNarrowerThanTheStore(TestStore db)
            throws Exception {
        db.loadNetex(SITE_COMPONENTS_BEYOND_QUAYS);
        String message = null;
        EpipSanitize.setReachBlindSpot(o -> o.getClass().getSimpleName().contains("Entrance"));
        try {
            strip(db);
        } catch (Throwable t) {
            message = rootMessage(t);
        } finally {
            EpipSanitize.setReachBlindSpot(null);
        }
        Check.that(message != null, "the run ABORTED — a traversal narrower than the store");
        Check.that(message.contains("G7 reach"),
                "and it was G7's reach arm that saw it: " + message);
        // Only the failure list, not the Result the message trails: that prints "G6 collisions 0"
        // and would make a naive contains() read a green counter as a red guard.
        String fired = message.substring(0, message.indexOf("Refusing to write"));
        Check.that(!fired.contains("G1 ") && !fired.contains("G2 ") && !fired.contains("G3 ")
                        && !fired.contains("G4 ") && !fired.contains("G5 ")
                        && !fired.contains("G6 "),
                "while conservation, the <Level> count, soundness, completeness and idempotence "
                        + "ALL stayed green — they are scoped to the refs the traversal reached, "
                        + "which is precisely how 2,339 references went unguarded: " + fired);
        EpipSanitize.LevelRefResult r = EpipSanitize.lastLevelRefResult();
        Check.equals(2L, r.refs(), "the blinded traversal reached 2 of the 4");
        Check.equals(4L, r.storeRefsBefore(), "while the store's bytes still held 4");
    }

    /// THE PIN UNDER THE TRAVERSAL. The pass handles the carriers by TYPE rather than by container
    /// name, which is only complete while those types are all the model declares. A model upgrade
    /// that adds one fails here instead of silently halving the pass's reach.
    ///
    /// ONE CARRIER IS NAMED THROUGH THE SEAM, NOT AS A LITERAL. 2.0 splits `PathLinkEndStructure`
    /// and moves `levelRef` to `SitePathLinkEndStructure`, so a literal here would be true at one
    /// schema version and quietly false at the other. `PathLinkEnds.CARRIER` is this tree's own
    /// answer to "which class carries it here", so the expectation and the reach cannot disagree.
    ///
    /// The 2.0-only fifth carrier is `LevelInStructure_VersionStructure.levelRef`, so this pin is
    /// VERSION-AWARE without becoming a tautology: the sweep asks "which fields have type
    /// `LevelRefStructure`", the expectation asks whether the class
    /// `LevelInStructure_VersionStructure` exists at all, and the two must agree. A model that grew
    /// a sixth carrier, or that kept the class and dropped its `levelRef`, fails.
    @Test
    void testModelDeclaresExactlyFiveLevelRefCarriers(TestStore db) throws Exception {
        Set<String> carriers = new TreeSet<>();
        for (Class<?> c : ModelRegistry.allModelClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                boolean hit = f.getType() == LevelRefStructure.class;
                XmlElements xes = f.getAnnotation(XmlElements.class);
                if (xes != null) {
                    for (XmlElement e : xes.value()) hit |= e.type() == LevelRefStructure.class;
                }
                if (hit) carriers.add(c.getSimpleName() + "." + f.getName());
            }
        }
        Check.that(!carriers.isEmpty(), "the reflective sweep found something (operative-first)");

        // The four every version declares...
        Set<String> expected = new TreeSet<>(Set.of(
                "Levels_RelStructure.levelRefOrLevel",
                PathLinkEnds.CARRIER.getSimpleName() + ".levelRef",
                "SiteComponent_VersionStructure.levelRef",
                "SitePathLink_VersionStructure.levelRef"));
        // ...plus the +v2.0 one, expected EXACTLY WHEN the model declares the class that owns it.
        // The discriminator is class EXISTENCE, a different question from the sweep's "which fields
        // are LevelRefStructure", so this is agreement between two readings of the model.
        boolean v20Carrier = false;
        for (Class<?> c : ModelRegistry.allModelClasses()) {
            if (c.getSimpleName().equals("LevelInStructure_VersionStructure")) v20Carrier = true;
        }
        if (v20Carrier) expected.add("LevelInStructure_VersionStructure.levelRef");

        Check.equals(expected, carriers,
                "the model declares a LevelRefStructure member in exactly these "
                        + expected.size() + " places, and EpipSanitize handles exactly these "
                        + expected.size() + ". Found: " + carriers);
        Check.equals(v20Carrier, expected.size() == 5,
                "the fifth carrier is present exactly when LevelInStructure_VersionStructure is "
                        + "(4 at 1.15.2, 5 at 2.0) — a count asserted absolutely would be true at "
                        + "one schema version and quietly false at the other");
    }

    // -------------------------------------------------- the fifth carrier

    /// The `+v2.0` carrier, driven end to end. Three things at once:
    ///
    /// - the walker REACHES it, two hops down through a class that is not a Site;
    /// - the orphan is removed as a WHOLE `<LevelInStructure>` — the mandatory
    ///    `<LevelRef>` makes clearing the field the wrong repair;
    /// - G7 reconciles, so the fifth carrier is inside the denominator and not beside it. This
    ///    is the arm that would have fired on the unextended pass: with the carrier unhandled
    ///    the bean traversal reaches 1 and the store's bytes hold 3.
    @Test
    void testReachesTheV20LevelInStructureCarrier(TestStore db) throws Exception {
        db.loadNetex(LEVEL_IN_STRUCTURE);
        EpipSanitize.LevelRefResult r = strip(db);

        Check.equals(3L, r.refs(),
                "1 <Quay> + 2 <LevelInStructure>; a Quay-only traversal sees 1 of these");
        Check.equals(1L, r.cleared(), "the one pointing at a Level nobody defines goes");
        Check.equals(2L, r.intact(), "the quay's and the resolvable LevelInStructure's stay");
        Check.equals(1L, r.levelsBefore(), "one <Level>");
        Check.equals(1L, r.levelsAfter(), "still one after — a <LevelInStructure> is not a <Level>");
        Check.equals(0L, r.orphansAfter(), "nothing unresolvable survives");
        Check.equals(3L, r.storeRefsBefore(),
                "and the STORE's own fragment bytes say 3 too — G7's denominator");
        Check.equals(2L, r.storeRefsAfter(), "2 in the bytes afterwards, matching intact");
        Check.equals(0L, r.storeRefsOutside(), "none of them outside a Site class map");

        StopPlace sp = one(db, StopPlace.class);
        var structures = sp.getSiteStructures().getSiteStructure();
        Check.equals(1, structures.size(), "the SiteStructure itself is untouched");
        var kept = structures.get(0).getLevelsInStructure().getLevelInStructure();
        Check.equals(1, kept.size(), "one <LevelInStructure> REMOVED, not emptied");
        Check.equals("at:obb:Level:here:", kept.get(0).getLevelRef().getRef(),
                "and the one that survives is the one whose Level the Site defines");
        Check.that(kept.get(0).getLevelRef() != null,
                "no survivor was left with the mandatory <LevelRef> nulled");
    }

    /// An emptied `<levelsInStructure>` is itself invalid, so it goes with its last child.
    @Test
    void testEmptiedLevelsInStructureContainerIsDropped(TestStore db) throws Exception {
        db.loadNetex(LEVEL_IN_STRUCTURE_ALL_ORPHANED);
        EpipSanitize.LevelRefResult r = strip(db);
        Check.equals(2L, r.refs(), "1 <Quay> + 1 <LevelInStructure>");
        Check.equals(1L, r.cleared(), "the LevelInStructure's orphan goes");
        Check.equals(1L, r.storeRefsAfter(), "the quay's survives in the store's own bytes");
        StopPlace sp = one(db, StopPlace.class);
        var ss = sp.getSiteStructures().getSiteStructure().get(0);
        Check.that(ss.getLevelsInStructure() == null,
                "the emptied <levelsInStructure> container is dropped, not left as <levelsInStructure/> "
                        + "— its content is maxOccurs=\"unbounded\" at minOccurs=1, so an empty one "
                        + "is as non-conformant as the orphan was");
    }

    /// G7's counter is a byte scan, so its one failure mode is matching the wrong bytes. Name-exact
    /// both ways: the element, and nothing whose name merely starts with it.
    @Test
    void testTheStoreByteCensusIsNameExact(TestStore db) throws Exception {
        Check.equals(3L, EpipSanitize.countLevelRefElements(
                        "<a><LevelRef ref=\"x\"/><LevelRef/><LevelRef\n ref='y'></LevelRef></a>"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "self-closing, bare and newline-separated start tags all count once");
        Check.equals(0L, EpipSanitize.countLevelRefElements(
                        "<LevelRefView/><LevelRefs/><xLevelRef/> LevelRef "
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "a longer name, a plural, a suffix match and a bare word count for nothing");
    }

    // ---------------------------------------------------------------- helper

    /// The single object of a class in the store.
    private static <T> T one(TestStore db, Class<T> clazz) {
        Map<String, T> all = Gen.byId(db, clazz);
        Check.equals(1, all.size(), clazz.getSimpleName() + ": exactly one in the store");
        return all.values().iterator().next();
    }
}
