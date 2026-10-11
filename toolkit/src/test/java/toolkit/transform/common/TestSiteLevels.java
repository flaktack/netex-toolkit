package toolkit.transform.common;

// Covers SiteLevels: the match key that decides two embedded Levels are one floor, the union two
// copies of one Site take, the fold that keeps one of each group, and the LevelRef re-point.
//
// Nothing downstream reads a Level, so no export comparison and no OTP gate can see a wrong fold.
// The key's strictness is what bounds the damage: it folds only Levels agreeing on every field the
// schema gives them, so what it collapses carried no information the survivor lacks.

import noi.netex.model.Level;
import noi.netex.model.LevelRefStructure;
import noi.netex.model.StopPlace;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestSiteLevels {

    // ---------------------------------------------------------------- fixtures

    /// Two publishers describing one national stop id, which is what the loader's StopPlace merge
    /// leaves behind: one ground floor each, identical in every field, each publisher's quay
    /// pointing at its own.
    private static final String TWO_PUBLISHERS = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:41:3145" version="1"><Name>Baumgarten-Schattendorf Bahnhof</Name>
            <levels>
              <Level id="at:obb:Level:380-0:" version="1"><Name lang="de">Erdgeschoss</Name><ShortName lang="de">G</ShortName></Level>
              <Level id="at:vor:Level:3145-0:" version="1"><Name lang="de">Erdgeschoss</Name><ShortName lang="de">G</ShortName></Level>
            </levels>
            <quays>
              <Quay id="at:41:3145:1" version="1"><Name>1</Name><LevelRef ref="at:obb:Level:380-0:" version="1"/></Quay>
              <Quay id="at:41:3145:2" version="1"><Name>Bahn</Name><LevelRef ref="at:vor:Level:3145-0:" version="1"/></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// Every carrier the model declares, each pointing at the Level that loses the fold. The
    /// `<levels>` member is containment-by-reference at a floor its own container defines, so it
    /// goes with the definition rather than moving.
    private static final String EVERY_CARRIER = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:41:3200" version="1"><Name>Every carrier</Name>
            <levels>
              <Level id="L:a" version="1"><Name>Erdgeschoss</Name></Level>
              <Level id="L:b" version="1"><Name>Erdgeschoss</Name></Level>
              <LevelRef ref="L:b" version="1"/>
            </levels>
            <entrances>
              <Entrance id="at:41:3200:90" version="1"><Name>Eingang</Name><LevelRef ref="L:b" version="1"/></Entrance>
            </entrances>
            <accessSpaces>
              <AccessSpace id="at:41:3200:A" version="1"><Name>Halle</Name><LevelRef ref="L:b" version="1"/></AccessSpace>
            </accessSpaces>
            <pathLinks>
              <SitePathLink id="at:41:3200:PL1" version="1">
                <From><PlaceRef ref="at:41:3200:1" version="1"/><LevelRef ref="L:b" version="1"/></From>
                <To><PlaceRef ref="at:41:3200:2" version="1"/><LevelRef ref="L:b" version="1"/></To>
                <LevelRef ref="L:b" version="1"/>
              </SitePathLink>
            </pathLinks>
            <siteStructures>
              <SiteStructure id="at:41:3200:S1" version="1"><Name>Haupthalle</Name>
                <levelsInStructure>
                  <LevelInStructure id="at:41:3200:S1:0" version="1"><LevelRef ref="L:b" version="1"/></LevelInStructure>
                </levelsInStructure>
              </SiteStructure>
            </siteStructures>
            <quays>
              <Quay id="at:41:3200:1" version="1"><Name>1</Name><LevelRef ref="L:b" version="1"/></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// One field apart: the basement and the ground floor of one station.
    private static final String TWO_FLOORS = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:41:4177" version="1"><Name>Two floors</Name>
            <levels>
              <Level id="L:a" version="1"><Name lang="de">Erdgeschoss</Name><ShortName lang="de">G</ShortName></Level>
              <Level id="L:b" version="1"><Name lang="de">Erdgeschoss</Name><ShortName lang="de">B1</ShortName></Level>
            </levels></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// One spelling apart, which is how two Italian publishers write one ground floor.
    private static final String TRAILING_SPACE = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="it:22095:fvo3" version="1"><Name>Trailing space</Name>
            <levels>
              <Level id="L:a" version="1"><Name>Ground</Name><PublicCode>G</PublicCode></Level>
              <Level id="L:b" version="1"><Name>Ground </Name><PublicCode>G</PublicCode></Level>
            </levels></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// Identical in every `LevelGroup` field, and both carrying an `AccessibilityAssessment` the
    /// key does not read.
    private static final String ASSESSED = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:41:3147" version="1"><Name>Assessed</Name>
            <levels>
              <Level id="L:a" version="1"><Name>Erdgeschoss</Name><AccessibilityAssessment id="AA:a" version="1"/></Level>
              <Level id="L:b" version="1"><Name>Erdgeschoss</Name><AccessibilityAssessment id="AA:b" version="1"/></Level>
            </levels></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    /// Two languages, one spelling.
    private static final String TWO_LANGS = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:41:3146" version="1"><Name>Two langs</Name>
            <levels>
              <Level id="L:a" version="1"><Name lang="de">Erdgeschoss</Name></Level>
              <Level id="L:b" version="1"><Name lang="it">Erdgeschoss</Name></Level>
            </levels></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    // ---------------------------------------------------------------- the fold

    @Test
    void testTwoPublishersOneFloorFoldToTheSmallerId(TestStore db) throws Exception {
        StopPlace sp = load(db, TWO_PUBLISHERS, "at:41:3145");
        List<Object> levels = levels(sp);
        Map<String, String> remap = new LinkedHashMap<>();

        Check.equals(1, SiteLevels.fold(levels, remap), "one of the two definitions goes");
        Check.equals(List.of("at:obb:Level:380-0:"), ids(levels),
                "the smaller id survives, so the outcome does not depend on which copy loaded last");
        Check.equals(Map.of("at:vor:Level:3145-0:", "at:obb:Level:380-0:"), remap,
                "the loser is recorded against its survivor");

        SiteLevels.install(sp, levels);
        Check.equals(1, SiteLevels.repoint(sp, remap), "the VOR quay's ref moves");
        Check.equals(List.of("at:obb:Level:380-0:", "at:obb:Level:380-0:"), refs(sp),
                "both publishers' quays now name the surviving floor");
    }

    /// The election reads the whole group, so shuffling the container cannot change it.
    @Test
    void testTheSurvivorDoesNotDependOnMemberOrder(TestStore db) throws Exception {
        StopPlace sp = load(db, TWO_PUBLISHERS, "at:41:3145");
        List<Object> forwards = levels(sp);
        List<Object> backwards = levels(sp);
        Collections.reverse(backwards);

        Map<String, String> first = new LinkedHashMap<>();
        Map<String, String> second = new LinkedHashMap<>();
        SiteLevels.fold(forwards, first);
        SiteLevels.fold(backwards, second);

        Check.equals(ids(forwards), ids(backwards), "the same floor survives either way");
        Check.equals(first, second, "and the same remap is recorded");
    }

    @Test
    void testEveryCarrierIsRePointed(TestStore db) throws Exception {
        StopPlace sp = load(db, EVERY_CARRIER, "at:41:3200");
        List<Object> levels = levels(sp);
        Map<String, String> remap = new LinkedHashMap<>();

        Check.equals(2, SiteLevels.fold(levels, remap),
                "the losing <Level> and the <LevelRef> member naming it both go");
        Check.equals(List.of("L:a"), ids(levels), "one definition left");
        SiteLevels.install(sp, levels);

        Check.equals(7, SiteLevels.repoint(sp, remap),
                "Quay, Entrance, AccessSpace, LevelInStructure, the SitePathLink's own ref and its "
                        + "two ends");
        Check.equals(7, refs(sp).size(), "every one of them is still there");
        Check.equals(Set.of("L:a"), new LinkedHashSet<>(refs(sp)),
                "and none still names the folded id");
    }

    /// A Level the key separates from its neighbour stays, whichever field does the separating.
    @Test
    void testLevelsTheKeySeparatesBothSurvive(TestStore db) throws Exception {
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("at:41:4177", TWO_FLOORS);
        fixtures.put("it:22095:fvo3", TRAILING_SPACE);
        fixtures.put("at:41:3147", ASSESSED);
        fixtures.put("at:41:3146", TWO_LANGS);
        for (Map.Entry<String, String> e : fixtures.entrySet()) {
            List<Object> levels = levels(load(db, e.getValue(), e.getKey()));
            Map<String, String> remap = new LinkedHashMap<>();
            Check.equals(0, SiteLevels.fold(levels, remap), "nothing folds in " + e.getKey());
            Check.equals(List.of("L:a", "L:b"), ids(levels), "both kept in " + e.getKey());
            Check.that(remap.isEmpty(), "nothing recorded for " + e.getKey());
        }
    }

    /// An `AccessibilityAssessment` or a `SiteRef` puts a Level outside the key's reach, so it
    /// matches nothing — including a Level identical to it.
    @Test
    void testAnAssessedLevelHasNoKey(TestStore db) throws Exception {
        for (Object member : levels(load(db, ASSESSED, "at:41:3147"))) {
            Check.isNull(SiteLevels.key((Level) member),
                    "a Level the key cannot compare in full answers no key at all");
        }
        List<Object> plain = levels(load(db, TWO_PUBLISHERS, "at:41:3145"));
        Check.equals(SiteLevels.key((Level) plain.get(0)), SiteLevels.key((Level) plain.get(1)),
                "two Levels agreeing on every field answer one key");
    }

    // ---------------------------------------------------------------- the union

    /// One id space across both arms: a `<LevelRef>` naming a floor the other copy defines occupies
    /// the same slot, so the union keeps whichever arrived first.
    @Test
    void testTheUnionKeysDefinitionsAndReferencesTogether() {
        StopPlace incoming = sp(level("L:a", "Erdgeschoss"), ref("L:b"));
        StopPlace stored = sp(level("L:b", "Untergeschoss"), ref("L:a"), level("L:c", "Dach"));

        List<Object> into = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        SiteLevels.union(into, seen, incoming);
        SiteLevels.union(into, seen, stored);

        Check.equals(List.of("L:a", "L:b", "L:c"), ids(into),
                "the incoming members first, then the ones only the stored copy carries");
        Check.that(into.get(1) instanceof LevelRefStructure,
                "the incoming <LevelRef ref=\"L:b\"> held the slot, so the stored <Level id=\"L:b\"> "
                        + "never arrived");
    }

    /// A member carrying neither an id nor a ref is appended rather than silently dropped.
    @Test
    void testAnIdlessMemberIsPassedThrough() {
        List<Object> into = new ArrayList<>();
        SiteLevels.union(into, new LinkedHashSet<>(), sp(level(null, "Erdgeschoss")));
        Check.equals(1, into.size(), "nothing the union cannot key is lost");
    }

    @Test
    void testAnEmptiedContainerIsDropped() {
        StopPlace sp = sp(level("L:a", "Erdgeschoss"));
        SiteLevels.install(sp, new ArrayList<>());
        Check.isNull(sp.getLevels(), "<levels> is minOccurs=0, so an emptied container goes");
    }

    // ---------------------------------------------------------------- helpers

    private static StopPlace load(TestStore db, String framesXml, String id) throws Exception {
        db.loadNetex(framesXml);
        try (Txn txn = db.store.roTxn()) {
            return (StopPlace) db.store.loadObjectByFullKey(txn,
                    db.store.lookupFullKey(txn, id, "1", StopPlace.class));
        }
    }

    /// The Site's `<levels>` as a list the fold can mutate without touching the bean.
    private static List<Object> levels(StopPlace sp) {
        return new ArrayList<>(sp.getLevels().getLevelRefOrLevel());
    }

    private static List<String> ids(List<Object> members) {
        List<String> out = new ArrayList<>();
        for (Object m : members) out.add(SiteLevels.id(m));
        return out;
    }

    /// Every `LevelRef` under the Site, in walk order.
    private static List<String> refs(StopPlace sp) {
        List<String> out = new ArrayList<>();
        SiteLevels.visit(sp, r -> out.add(r.getRef()));
        return out;
    }

    private static Level level(String id, String name) {
        Level l = new Level();
        l.setId(id);
        l.setVersion("1");
        l.setName(Mls.of(name));
        return l;
    }

    private static LevelRefStructure ref(String target) {
        LevelRefStructure r = new LevelRefStructure();
        r.setRef(target);
        r.setVersion("1");
        return r;
    }

    private static StopPlace sp(Object... members) {
        StopPlace sp = new StopPlace();
        sp.setId("SP:1");
        sp.setVersion("1");
        SiteLevels.install(sp, Arrays.asList(members));
        return sp;
    }
}
