package toolkit.transform.common;

// What planStopplaceMerge does with the levels of the copies it folds into one station.
//
// Feeds in this corpus mint a Level per StopPlace and give every one of them the same floor label,
// so a cluster of same-name copies arrives carrying one ground floor each. The survivor has to end
// up with one, named by every copy's quays.

import noi.netex.model.Level;
import noi.netex.model.LevelRefStructure;
import noi.netex.model.LocationStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TestStopplaceMergeLevels {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// Three copies of one station, a ground floor each under its own publisher's id, a quay each
    /// naming its own. The merge used to union the quays and drop every level but the survivor's.
    @Test
    void testEveryCopysFloorArrivesAndTheIdenticalOnesFold() {
        List<StopPlace> members = new ArrayList<>();
        members.add(copy("it:1:a", "L:obb", "46.496637"));
        members.add(copy("it:1:b", "L:oov", "46.496640"));
        members.add(copy("it:1:c", "L:vor", "46.496643"));

        StopPlace survivor = merge(members);
        Check.equals(List.of("L:obb"), levelIds(survivor),
                "one floor, under the smallest of the three ids");
        Check.equals(List.of("L:obb", "L:obb", "L:obb"), refs(survivor),
                "and each copy's quay names it");
        resolvesInSite(survivor);
    }

    /// A floor the key separates survives the move on its own terms.
    @Test
    void testAFloorTheKeySeparatesMovesWithoutFolding() {
        StopPlace ground = copy("it:1:a", "L:ground", "46.496637");
        StopPlace basement = copy("it:1:b", "L:basement", "46.496640");
        level(basement, "L:basement").setShortName(Mls.of("B1"));

        StopPlace survivor = merge(List.of(ground, basement));
        Check.equals(List.of("L:basement", "L:ground"), sorted(levelIds(survivor)),
                "both floors are on the survivor");
        resolvesInSite(survivor);
    }

    /// The quay fold runs first and removes quays. A reference the level fold then rewrites has to
    /// be one of the quays that remain.
    @Test
    void testAQuayFoldedAwayTakesItsLevelRefWithIt() {
        StopPlace a = copy("it:1:a", "L:a", "46.496637");
        StopPlace b = copy("it:1:b", "L:b", "46.4966371");
        // One name and one position, so the quay fold collapses the two into the smaller id.
        quay(a).setName(Mls.of("Binario 1"));
        quay(b).setName(Mls.of("Binario 1"));
        quay(b).getCentroid().getLocation().setLatitude(new BigDecimal("46.496637"));

        StopPlace survivor = merge(List.of(a, b));
        Check.equals(1, survivor.getQuays().getQuayRefOrQuay().size(), "one quay left");
        Check.equals(List.of("L:a"), levelIds(survivor), "one floor left");
        Check.equals(List.of("L:a"), refs(survivor),
                "the surviving quay names it, and the removed quay's reference went with the quay");
        resolvesInSite(survivor);
    }

    // ---------------------------------------------------------------- helpers

    /// The invariant the EPIP strip enforces later: every `LevelRef` under the Site names a `Level`
    /// the Site itself defines.
    private static void resolvesInSite(StopPlace sp) {
        Set<String> own = new LinkedHashSet<>(levelIds(sp));
        for (String ref : refs(sp)) {
            Check.that(own.contains(ref), ref + " names a floor this Site defines; it has " + own);
        }
    }

    private static StopPlace merge(List<StopPlace> members) {
        ScheduledStopPoints.MergePlan plan = ScheduledStopPoints.planStopplaceMerge(
                members, List.<PassengerStopAssignment>of());
        Check.equals(1, plan.kept().size(), "the copies merge to one station");
        return plan.kept().values().iterator().next();
    }

    /// One copy of a station: one quay naming one ground floor, both ids its own.
    private static StopPlace copy(String id, String levelId, String lat) {
        StopPlace sp = new StopPlace();
        sp.setId(id);
        sp.setVersion("1");
        sp.setName(Mls.of("Aosta"));
        sp.setCentroid(point(lat));

        Level l = new Level();
        l.setId(levelId);
        l.setVersion("1");
        l.setName(Mls.of("Ground "));
        SiteLevels.install(sp, Arrays.asList(l));

        Quay q = new Quay();
        q.setId(id + ":1");
        q.setVersion("1");
        // A name of its own, so the coincident-quay fold leaves one quay per copy for the level
        // fold to re-point.
        q.setName(Mls.of("Binario " + id));
        q.setCentroid(point(lat));
        LevelRefStructure ref = new LevelRefStructure();
        ref.setRef(levelId);
        ref.setVersion("1");
        q.setLevelRef(ref);
        Quays_RelStructure quays = new Quays_RelStructure();
        quays.getQuayRefOrQuay().add(FACTORY.createQuay(q));
        sp.setQuays(quays);
        return sp;
    }

    private static SimplePoint_VersionStructure point(String lat) {
        LocationStructure loc = new LocationStructure();
        loc.setLatitude(new BigDecimal(lat));
        loc.setLongitude(new BigDecimal("7.320"));
        return new SimplePoint_VersionStructure().withLocation(loc);
    }

    private static Quay quay(StopPlace sp) {
        return (Quay) sp.getQuays().getQuayRefOrQuay().get(0).getValue();
    }

    private static Level level(StopPlace sp, String id) {
        for (Object member : sp.getLevels().getLevelRefOrLevel()) {
            if (member instanceof Level l && id.equals(l.getId())) return l;
        }
        throw new IllegalArgumentException(id);
    }

    private static List<String> levelIds(StopPlace sp) {
        List<String> ids = new ArrayList<>();
        if (sp.getLevels() == null) return ids;
        for (Object member : sp.getLevels().getLevelRefOrLevel()) ids.add(SiteLevels.id(member));
        return ids;
    }

    private static List<String> refs(StopPlace sp) {
        List<String> out = new ArrayList<>();
        SiteLevels.visit(sp, r -> out.add(r.getRef()));
        return out;
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        java.util.Collections.sort(out);
        return out;
    }
}
