package toolkit.transform.common;

// The mode a merged station takes, and the wiring that gives it to planStopplaceMerge's survivor.
//
// The fixtures are shaped after the collisions that drove the rule: a publisher that surveyed the
// platforms and typed the station after its own branch service, a pier and the kerb outside it
// folding at one name, and a station minted by the synthesis, which carries no mode at all.

import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.LocationStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.RailSubmodeEnumeration;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.TramSubmodeEnumeration;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import java.math.BigDecimal;
import java.util.List;

public class TestTransportModes {

    /// The fullest record is not always the one that knows what the station is. The surveying
    /// publisher types the station `tram`, because a tram is what it runs there; the mainline copy
    /// it beats says `rail`. The submode has to travel with the mode -- OTP reads the submodes
    /// before the mode, so a TramSubmode left beside a rail TransportMode still maps the whole
    /// station to tram.
    @Test
    void testTheSurvivorTakesTheHeaviestModeAndItsSubmodeGoesWithIt() {
        StopPlace surveyed = stop("SP:surveyed", AllVehicleModesOfTransportEnumeration.TRAM);
        surveyed.setTramSubmode(TramSubmodeEnumeration.LOCAL_TRAM);
        StopPlace mainline = stop("SP:mainline", AllVehicleModesOfTransportEnumeration.RAIL);

        Check.that(TransportModes.adoptHeaviestMode(surveyed, List.of(surveyed, mainline)),
                "the survivor took a member's mode block");
        Check.equals(AllVehicleModesOfTransportEnumeration.RAIL, surveyed.getTransportMode(),
                "the mainline copy's rail beats the surveying publisher's tram");
        Check.isNull(surveyed.getTramSubmode(),
                "and its TramSubmode went with it, or OTP would read tram anyway");
        Check.equals(List.of(AllVehicleModesOfTransportEnumeration.TRAM),
                surveyed.getOtherTransportModes(),
                "the mode that lost is still recorded as something that calls here");
    }

    /// A pier and the kerb outside it, at one name. The berth is what the station is, so the order
    /// has to put water above road.
    @Test
    void testAPierBeatsTheKerbOutsideIt() {
        StopPlace kerb = stop("SP:kerb", AllVehicleModesOfTransportEnumeration.BUS);
        StopPlace pier = stop("SP:pier", AllVehicleModesOfTransportEnumeration.FERRY);

        TransportModes.adoptHeaviestMode(kerb, List.of(kerb, pier));
        Check.equals(AllVehicleModesOfTransportEnumeration.FERRY, kerb.getTransportMode(),
                "the pier types the station");
    }

    /// The survivor already holding the heaviest mode changes nothing, and reports nothing to a
    /// caller's counter. Its own mode does not reach OtherTransportModes.
    @Test
    void testTheSurvivorAlreadyHoldingTheHeaviestModeIsUntouched() {
        StopPlace mainline = stop("SP:mainline", AllVehicleModesOfTransportEnumeration.RAIL);
        StopPlace branch = stop("SP:branch", AllVehicleModesOfTransportEnumeration.TRAM);

        Check.that(!TransportModes.adoptHeaviestMode(mainline, List.of(mainline, branch)),
                "no promotion: the survivor was already the heaviest");
        Check.equals(AllVehicleModesOfTransportEnumeration.RAIL, mainline.getTransportMode(),
                "and it kept its own mode");
        Check.equals(List.of(AllVehicleModesOfTransportEnumeration.TRAM),
                mainline.getOtherTransportModes(),
                "the mode that lost is recorded; the survivor's own is not");
    }

    /// A StopPlace carrying no mode ranks below every mode the list holds, so it never wins -- which
    /// is what keeps a station minted by the synthesis from typing the one it merges into.
    @Test
    void testAModelessCopyNeverWins() {
        StopPlace minted = stop("SP:aminted", null);
        StopPlace kerb = stop("SP:kerb", AllVehicleModesOfTransportEnumeration.BUS);

        Check.that(TransportModes.adoptHeaviestMode(minted, List.of(minted, kerb)),
                "the modeless survivor took the kerb's mode, though its id sorts first");
        Check.equals(AllVehicleModesOfTransportEnumeration.BUS, minted.getTransportMode(),
                "bus beats no mode at all");
        Check.equals(List.of(), minted.getOtherTransportModes(),
                "and an absent mode is not a mode that calls here");
    }

    /// Two members declaring one mode: the smallest id decides, so the result does not depend on
    /// the order the cluster arrives in.
    @Test
    void testATieFallsToTheSmallestId() {
        StopPlace survivor = stop("SP:survivor", AllVehicleModesOfTransportEnumeration.BUS);
        StopPlace a = stop("SP:a", AllVehicleModesOfTransportEnumeration.RAIL);
        a.setRailSubmode(RailSubmodeEnumeration.LOCAL);
        StopPlace b = stop("SP:b", AllVehicleModesOfTransportEnumeration.RAIL);
        b.setRailSubmode(RailSubmodeEnumeration.LONG_DISTANCE);

        TransportModes.adoptHeaviestMode(survivor, List.of(survivor, b, a));
        Check.equals(RailSubmodeEnumeration.LOCAL, survivor.getRailSubmode(),
                "the smallest id's block was taken, though it arrived last");
    }

    /// The same rule through the merge that runs it: two copies of one station at one name, and the
    /// election picks the one with the quay while the mode comes from the other.
    @Test
    void testPlanStopplaceMergeTypesItsSurvivorByTheHeaviestMode() {
        StopPlace surveyed = located("SP:surveyed", "Bahnhof", "46.496637", "11.358386",
                AllVehicleModesOfTransportEnumeration.TRAM);
        surveyed.setTramSubmode(TramSubmodeEnumeration.LOCAL_TRAM);
        StopPlace mainline = located("SP:mainline", "Bahnhof", "46.496640", "11.358390",
                AllVehicleModesOfTransportEnumeration.RAIL);

        ScheduledStopPoints.MergePlan plan = ScheduledStopPoints.planStopplaceMerge(
                List.of(surveyed, mainline), List.<PassengerStopAssignment>of());

        Check.equals(1, plan.kept().size(), "the two copies merge to one station");
        StopPlace survivor = plan.kept().values().iterator().next();
        Check.equals(AllVehicleModesOfTransportEnumeration.RAIL, survivor.getTransportMode(),
                "and it is typed rail, whichever copy the quay-count election kept");
        Check.isNull(survivor.getTramSubmode(), "with no submode of the mode it replaced left on it");
    }

    static StopPlace stop(String id, AllVehicleModesOfTransportEnumeration mode) {
        StopPlace sp = new StopPlace();
        sp.setId(id);
        sp.setVersion("1");
        sp.setTransportMode(mode);
        return sp;
    }

    static StopPlace located(String id, String name, String lat, String lon,
            AllVehicleModesOfTransportEnumeration mode) {
        StopPlace sp = stop(id, mode);
        sp.setName(Mls.of(name));
        LocationStructure loc = new LocationStructure();
        loc.setLatitude(new BigDecimal(lat));
        loc.setLongitude(new BigDecimal(lon));
        sp.setCentroid(new SimplePoint_VersionStructure().withLocation(loc));
        return sp;
    }
}
