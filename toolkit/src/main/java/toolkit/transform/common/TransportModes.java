package toolkit.transform.common;

// Which mode a station takes when a merge folds several publishers' copies of it into one.
//
// A cluster is one physical station, and the copy a merge elects is the fullest record rather than
// the best-informed one: a publisher that runs a branch service into a mainline station types the
// station after the service it runs there. So the mode is decided separately from the election,
// from the whole cluster, by [TransportModes#PRECEDENCE].

import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.StopPlace;

import java.util.ArrayList;
import java.util.List;

public final class TransportModes {

    private TransportModes() {}

    /// The transport modes a merged station can take, heaviest first.
    ///
    /// Heaviest is the mode whose infrastructure defines the place: a fixed guideway, then a berth,
    /// then a kerb. A ferry pier and the bus stop outside it fold together at a station radius, and
    /// the pier is what the station is.
    ///
    /// A mode absent from the list ranks below every mode in it, which is where `other`, `unknown`
    /// and an absent mode all land -- so a StopPlace carrying no mode never wins, and one minted by
    /// [ScheduledStopPoints#synthesizeStopPlaces], which sets none, cannot.
    public static final List<AllVehicleModesOfTransportEnumeration> PRECEDENCE = List.of(
            AllVehicleModesOfTransportEnumeration.RAIL,
            AllVehicleModesOfTransportEnumeration.METRO,
            AllVehicleModesOfTransportEnumeration.TRAM,
            AllVehicleModesOfTransportEnumeration.FUNICULAR,
            AllVehicleModesOfTransportEnumeration.CABLEWAY,
            AllVehicleModesOfTransportEnumeration.FERRY,
            AllVehicleModesOfTransportEnumeration.WATER,
            AllVehicleModesOfTransportEnumeration.COACH,
            AllVehicleModesOfTransportEnumeration.BUS,
            AllVehicleModesOfTransportEnumeration.TROLLEY_BUS,
            AllVehicleModesOfTransportEnumeration.AIR);

    /// `sp`'s position in [#PRECEDENCE], or one past its end for a mode the list does not rank.
    public static int rank(StopPlace sp) {
        int i = sp.getTransportMode() == null ? -1 : PRECEDENCE.indexOf(sp.getTransportMode());
        return i < 0 ? PRECEDENCE.size() : i;
    }

    /// Give `survivor` the heaviest mode any member of its cluster declares, and record the modes
    /// that lost in `OtherTransportModes`. Returns whether the mode block came from a member.
    ///
    /// The block moves as one -- `TransportMode` and every submode taken from the same member --
    /// because a submode left behind outranks the mode: OTP's StopPlaceTypeMapper reads the
    /// submodes first, so a `TramSubmode` beside a `rail` TransportMode still maps to tram. OTP
    /// then reads a StopPlace's mode onto every Quay under it, which is what makes one mistyped
    /// survivor a mainline station and all its platforms arriving in the graph as tram stops.
    ///
    /// `OtherTransportModes` is where NeTEx holds the rest of what calls at a station. Nothing in
    /// OTP reads it.
    ///
    /// Ties fall to the smallest id, so the result does not depend on the order `members` arrives
    /// in. `survivor` must be one of `members`.
    public static boolean adoptHeaviestMode(StopPlace survivor, List<StopPlace> members) {
        StopPlace heaviest = survivor;
        for (StopPlace sp : members) {
            int c = Integer.compare(rank(sp), rank(heaviest));
            if (c < 0 || (c == 0 && sp.getId().compareTo(heaviest.getId()) < 0)) heaviest = sp;
        }
        List<AllVehicleModesOfTransportEnumeration> others = new ArrayList<>();
        for (StopPlace sp : members) {
            AllVehicleModesOfTransportEnumeration m = sp.getTransportMode();
            if (m != null && m != heaviest.getTransportMode() && !others.contains(m)) others.add(m);
        }
        boolean promoted = heaviest != survivor;
        if (promoted) adoptModeBlock(survivor, heaviest);
        for (AllVehicleModesOfTransportEnumeration m : others) {
            if (!survivor.getOtherTransportModes().contains(m)) survivor.getOtherTransportModes().add(m);
        }
        return promoted;
    }

    /// Copy `from`'s mode and every submode onto `to`, nulls included, so no submode of the mode
    /// being replaced survives the swap.
    private static void adoptModeBlock(StopPlace to, StopPlace from) {
        to.setTransportMode(from.getTransportMode());
        to.setAirSubmode(from.getAirSubmode());
        to.setBusSubmode(from.getBusSubmode());
        to.setCoachSubmode(from.getCoachSubmode());
        to.setFunicularSubmode(from.getFunicularSubmode());
        to.setMetroSubmode(from.getMetroSubmode());
        to.setRailSubmode(from.getRailSubmode());
        to.setSnowAndIceSubmode(from.getSnowAndIceSubmode());
        to.setTelecabinSubmode(from.getTelecabinSubmode());
        to.setTramSubmode(from.getTramSubmode());
        to.setWaterSubmode(from.getWaterSubmode());
    }
}
