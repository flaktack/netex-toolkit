package toolkit.transform.epip;

// The getPassingTimesFromTimeDemandType slice needed by the EPIP journey generator, plus the time
// helpers in its closure: getDepartureTime, getDuration, getArrivalTime, getDepartureTimeOffset,
// getWaitTimesFromPointInJourneyPattern.
//
// Time-arithmetic contract: the running clock is an int of SECONDS SINCE THE JOURNEY'S
// OPERATING-DAY MIDNIGHT, with day offsets folded in as day*86400. Day offset = floorDiv(seconds,
// 86400), time-of-day = floorMod, so negatives behave. Passing times are tz-less local times
// throughout, so every helper drops the offset.
//
// The model hands this file noi.netex.time.XmlTime / XmlDuration carriers; every one is normalised
// at the point of use via toLocalTime() / toDuration(), and every value the clock ever sees is a
// plain int of seconds.
//
// QUIRK KEPT: the emitted TimetabledPassingTime's DEPARTURE time is computed from `arrival`, not
// `departure` — departure_time == arrival_time on every row. The wait time still advances the clock
// for subsequent stops.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.JourneyPatternWaitTimes_RelStructure;
import noi.netex.model.JourneyRunTime;
import noi.netex.model.JourneyWaitTime;
import noi.netex.model.JourneyWaitTime_VersionedChildStructure;
import noi.netex.model.LinkInLinkSequence_VersionedChildStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PointInJourneyPatternRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.time.XmlDuration;
import noi.netex.time.XmlTime;
import noi.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.StopPointInJourneyPatternRefStructure;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.TimeDemandType;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.TimetabledPassingTimes_RelStructure;
import noi.netex.model.TimingLinkInJourneyPattern_VersionedChildStructure;
import noi.netex.model.TimingPointInJourneyPatternRefStructure;
import noi.netex.model.TimingPointInJourneyPattern_VersionedChildStructure;
import toolkit.model.NetexUtils;

import java.math.BigInteger;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class CallsProfile {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private CallsProfile() {}

    /// The (time, dayOffset) pair getArrivalTime/getDepartureTimeOffset return.
    public record TimeWithDayOffset(LocalTime time, int dayOffset) {}

    /// getArrivalTime: seconds-since-operating-day-midnight → (time-of-day, day offset).
    public static TimeWithDayOffset getArrivalTime(int arrival) {
        int dayOffset = Math.floorDiv(arrival, 86400);
        int hour = Math.floorMod(arrival, 86400) / 3600;
        int minute = Math.floorMod(arrival, 3600) / 60;
        int second = Math.floorMod(arrival, 60);
        return new TimeWithDayOffset(LocalTime.of(hour, minute, second), dayOffset);
    }

    /// getDepartureTimeOffset: the same arithmetic on the departure clock.
    public static TimeWithDayOffset getDepartureTimeOffset(int departure) {
        int dayOffset = Math.floorDiv(departure, 86400);
        int hour = Math.floorMod(departure, 86400) / 3600;
        int minute = Math.floorMod(departure, 3600) / 60;
        int second = Math.floorMod(departure, 60);
        return new TimeWithDayOffset(LocalTime.of(hour, minute, second), dayOffset);
    }

    /// getDuration: total seconds of a duration. A null duration, and one whose lexical form could
    /// not be parsed at all, are both 0. Sums days*86400 + hours*3600 + minutes*60 + int(seconds)
    /// and IGNORES months/years — [java.time.Duration] cannot carry months, and `toSeconds()` drops
    /// the fractional second.
    public static int getDuration(XmlDuration duration) {
        if (duration == null) return 0;
        // Normalisation boundary.
        java.time.Duration value = duration.toDuration();
        return value == null ? 0 : (int) value.toSeconds();
    }

    /// getDepartureTime: the journey's departure as seconds since its operating-day midnight —
    /// `(departure day offset, or 0) * 86400 + h*3600 + m*60 + s`. The departure time must be
    /// present.
    ///
    /// The RAW hour is read, not the wrapped wall clock, so `24:00:00` — the one hour-24 form that
    /// parses — contributes a whole extra day.
    ///
    /// KNOWN DEFECT: for a value the parser rejects, the field holds no time at all and this
    /// unguarded access throws.
    public static int getDepartureTime(ServiceJourney serviceJourney) {
        XmlTime departure = serviceJourney.getDepartureTime();
        if (departure == null) throw new IllegalStateException("serviceJourney.departureTime is absent");
        if (!departure.isXsdValid()) {
            throw new IllegalStateException("serviceJourney.departureTime is the unconvertible raw"
                    + " string '" + departure.toXmlFormat() + "', which has no readable hour");
        }
        BigInteger off = serviceJourney.getDepartureDayOffset();
        return (off == null ? 0 : off.intValue()) * 86400
                + departure.getHour() * 3600 + departure.getMinute() * 60 + departure.getSecond();
    }

    /// getWaitTimesFromPointInJourneyPattern, over the `waitTime`/`waitTimes` union; the choice
    /// binds as the point's `getWaitTime()` (the XmlDuration arm) and `getWaitTimes()` (the
    /// JourneyPatternWaitTimesRelStructure arm) — pass both. Resolution: a plain duration wins;
    /// else, with a TimeDemandType given, the first JourneyPatternWaitTime whose
    /// time-demand-type/timeband ref matches its id, falling back to the FIRST entry's wait time;
    /// else null. (List entries are JourneyPatternWaitTime or JourneyPatternWaitTimeRef — a ref
    /// entry has no waitTime and raises a ClassCastException.)
    public static XmlDuration getWaitTimesFromPointInJourneyPattern(
            XmlDuration waitTime, JourneyPatternWaitTimes_RelStructure waitTimes, TimeDemandType timeDemandType) {
        if (waitTime != null) return waitTime;

        if (waitTimes != null) {
            List<Object> entries = waitTimes.getJourneyPatternWaitTimeRefOrJourneyPatternWaitTime();
            // KNOWN LIMIT: the FIRST matching wait time wins. Several can match when Timebands are
            // in play, and nothing here disambiguates them.
            if (timeDemandType != null) {
                for (Object o : entries) {
                    JourneyWaitTime_VersionedChildStructure x = (JourneyWaitTime_VersionedChildStructure) o;
                    // The union splits into the two JourneyTiming accessors; both absent → NPE.
                    String ref = x.getTimeDemandTypeRef() != null
                            ? x.getTimeDemandTypeRef().getRef() : x.getTimebandRef().getRef();
                    if (Objects.equals(ref, timeDemandType.getId())) return x.getWaitTime();
                }
                return ((JourneyWaitTime_VersionedChildStructure) entries.get(0)).getWaitTime();
            }
            return ((JourneyWaitTime_VersionedChildStructure) entries.get(0)).getWaitTime();
        }
        return null;
    }

    /// getPassingTimesFromTimeDemandType: expand a (ServiceJourneyPattern, TimeDemandType) pair
    /// into the journey's TimetabledPassingTimes, IN PLACE on `serviceJourney`. No-op when passing
    /// times already exist or either provided object does not match the journey's refs.
    public static void getPassingTimesFromTimeDemandType(ServiceJourney serviceJourney,
            ServiceJourneyPattern serviceJourneyPattern, TimeDemandType timeDemandType) {
        if (serviceJourney.getPassingTimes() != null) return;

        if (!Objects.equals(serviceJourney.getJourneyPatternRef().getValue().getRef(),
                serviceJourneyPattern.getId())) {
            return;
        }

        if (!Objects.equals(serviceJourney.getTimeDemandTypeRef().getRef(), timeDemandType.getId())) {
            return;
        }

        // Index the journey run times by their TimingLink ref — insertion-ordered.
        // KNOWN LIMIT: duplicate keys last-win, with no disambiguation when Timebands or same-named
        // points make several entries match.
        Map<String, JourneyRunTime> tdtTl = new LinkedHashMap<>();
        if (timeDemandType.getRunTimes() != null) {
            for (JourneyRunTime jrt : timeDemandType.getRunTimes().getJourneyRunTime()) {
                tdtTl.put(jrt.getTimingLinkRef().getRef(), jrt);
            }
        }

        // Index the journey wait times by their timing-point ref — the union is getTimingPointRef()
        // JAXBElement (ScheduledStopPointRef substitutes into it).
        Map<String, JourneyWaitTime> tdtPoint = new LinkedHashMap<>();
        if (timeDemandType.getWaitTimes() != null) {
            for (JourneyWaitTime jwt : timeDemandType.getWaitTimes().getJourneyWaitTime()) {
                tdtPoint.put(jwt.getTimingPointRef().getValue().getRef(), jwt);
            }
        }

        List<PointInLinkSequence_VersionedChildStructure> points = serviceJourneyPattern
                .getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();

        if (serviceJourneyPattern.getLinksInSequence() != null) {
            int i = 0;
            // A link's own runTimes is an OVERRIDE, so a missing one is skipped — but `i` must
            // still advance with the link.
            for (LinkInLinkSequence_VersionedChildStructure lis : serviceJourneyPattern
                    .getLinksInSequence().getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern()) {
                if (lis instanceof ServiceLinkInJourneyPattern_VersionedChildStructure sl
                        && sl.getRunTimes() != null) {
                    for (JourneyRunTime jrt : sl.getRunTimes().getJourneyRunTime()) {
                        tdtTl.put(sl.getServiceLinkRef().getRef(), jrt);
                        // KNOWN GAP: the link's begin point is not checked against the point it is
                        // being assigned to.
                        setOnwardServiceLinkRef(points.get(i), sl);
                    }
                } else if (lis instanceof TimingLinkInJourneyPattern_VersionedChildStructure tl
                        && tl.getRunTimes() != null) {
                    for (JourneyRunTime jrt : tl.getRunTimes().getJourneyRunTime()) {
                        tdtTl.put(tl.getTimingLinkRef().getRef(), jrt);
                        // KNOWN GAP: the link's begin point is not checked against the point it is
                        // being assigned to.
                        setOnwardTimingLinkRef(points.get(i), tl);
                    }
                }
                i++;
            }
        }

        int departure = getDepartureTime(serviceJourney);
        int arrival = departure;

        TimetabledPassingTimes_RelStructure tpts = new TimetabledPassingTimes_RelStructure();

        int order = 0;
        for (PointInLinkSequence_VersionedChildStructure pis : points) {
            order++;
            // The carrier travels UNCONVERTED through the selection logic; getDuration() is where
            // the single normalisation happens.
            XmlDuration waitTime = null;
            JourneyRunTime runTime = null;
            StopPointInJourneyPattern_VersionedChildStructure spijp = null;
            // KNOWN GAP: the VDV462 shape is not handled here.
            if (pis instanceof StopPointInJourneyPattern_VersionedChildStructure spp) {
                spijp = spp;

                waitTime = getWaitTimesFromPointInJourneyPattern(
                        spp.getWaitTime(), spp.getWaitTimes(), timeDemandType);

                if (waitTime == null) {
                    JourneyWaitTime w = tdtPoint.get(spp.getScheduledStopPointRef().getValue().getRef());
                    if (w != null) waitTime = w.getWaitTime();
                }

                if (spp.getOnwardTimingLinkRef() != null) {
                    runTime = requireRunTime(tdtTl, spp.getOnwardTimingLinkRef().getRef());
                } else if (spp.getOnwardServiceLinkRef() != null) {
                    runTime = requireRunTime(tdtTl, spp.getOnwardServiceLinkRef().getRef());
                }

            } else if (pis instanceof TimingPointInJourneyPattern_VersionedChildStructure tp) {
                // The JAXBElement's value type is what separates a ScheduledStopPointRef from a
                // plain TimingPointRef here.
                if (tp.getTimingPointRef() != null
                        && tp.getTimingPointRef().getValue() instanceof ScheduledStopPointRefStructure) {
                    spijp = NetexUtils.project(tp, StopPointInJourneyPattern.class);
                    spijp.setForAlighting(true);
                    spijp.setForBoarding(true);
                }

                JourneyWaitTime w = tdtPoint.get(tp.getTimingPointRef().getValue().getRef());
                if (w != null) waitTime = w.getWaitTime();

                if (tp.getOnwardTimingLinkRef() != null) {
                    runTime = requireRunTime(tdtTl, tp.getOnwardTimingLinkRef().getRef());
                }
            }

            departure += getDuration(waitTime);
            if (spijp != null) {
                TimeWithDayOffset arrivalT = getArrivalTime(arrival);
                // QUIRK KEPT: computed from `arrival`, NOT `departure`.
                TimeWithDayOffset departureT = getDepartureTimeOffset(arrival);
                TimetabledPassingTime tpt = new TimetabledPassingTime();
                tpt.setId(serviceJourney.getId()
                        .replace(":ServiceJourney:", ":TimetabledPassingTime:") + "-" + order);
                tpt.setVersion(serviceJourney.getVersion());
                tpt.setPointInJourneyPatternRef(wrapPointInJourneyPatternRef(
                        (PointInJourneyPatternRefStructure) NetexUtils.getRef(spijp)));
                // Synthesis sites. These times are COMPUTED from the running seconds clock, not
                // copied from a source element, so they are built with XmlTime.from(): no retained
                // lexical, no offset, canonical "HH:mm:ss" on the wire.
                tpt.setArrivalTime(XmlTime.from(arrivalT.time()));
                tpt.setArrivalDayOffset(BigInteger.valueOf(arrivalT.dayOffset()));
                tpt.setDepartureTime(XmlTime.from(departureT.time()));
                tpt.setDepartureDayOffset(BigInteger.valueOf(departureT.dayOffset()));
                tpts.getTimetabledPassingTime().add(tpt);
            }

            if (runTime != null) {
                arrival = departure + getDuration(runTime.getRunTime());
                departure = arrival;
            }
        }

        serviceJourney.setPassingTimes(tpts);
    }

    // ---------------------------------------------------------------- plumbing

    /// A missing key throws rather than silently yielding no run time.
    private static JourneyRunTime requireRunTime(Map<String, JourneyRunTime> tdtTl, String key) {
        JourneyRunTime rt = tdtTl.get(key);
        if (rt == null) throw new NoSuchElementException("no run time for '" + key + "'");
        return rt;
    }

    /// Set the point's onward ServiceLink ref. ONLY StopPointInJourneyPattern declares one; any
    /// other point type throws.
    private static void setOnwardServiceLinkRef(PointInLinkSequence_VersionedChildStructure point,
            ServiceLinkInJourneyPattern_VersionedChildStructure lis) {
        if (point instanceof StopPointInJourneyPattern_VersionedChildStructure spp) {
            spp.setOnwardServiceLinkRef(lis.getServiceLinkRef());
        } else {
            throw new IllegalStateException(point.getClass().getSimpleName()
                    + " declares no onwardServiceLinkRef");
        }
    }

    /// Set the point's onward TimingLink ref. Declared by BOTH StopPointInJourneyPattern and
    /// TimingPointInJourneyPattern; any other point type throws.
    private static void setOnwardTimingLinkRef(PointInLinkSequence_VersionedChildStructure point,
            TimingLinkInJourneyPattern_VersionedChildStructure lis) {
        if (point instanceof StopPointInJourneyPattern_VersionedChildStructure spp) {
            spp.setOnwardTimingLinkRef(lis.getTimingLinkRef());
        } else if (point instanceof TimingPointInJourneyPattern_VersionedChildStructure tp) {
            tp.setOnwardTimingLinkRef(lis.getTimingLinkRef());
        } else {
            throw new IllegalStateException(point.getClass().getSimpleName()
                    + " declares no onwardTimingLinkRef");
        }
    }

    /// TimetabledPassingTime's point-in-journey-pattern ref is a JAXBElement union in the model —
    /// wrap the ref in the element matching its runtime type so it substitutes into
    /// PointInJourneyPatternRef correctly. Shared with [TimetabledPassingTimesProfile].
    static JAXBElement<? extends PointInJourneyPatternRefStructure> wrapPointInJourneyPatternRef(
            PointInJourneyPatternRefStructure ref) {
        if (ref instanceof StopPointInJourneyPatternRefStructure s) {
            return FACTORY.createStopPointInJourneyPatternRef(s);
        }
        if (ref instanceof TimingPointInJourneyPatternRefStructure t) {
            return FACTORY.createTimingPointInJourneyPatternRef(t);
        }
        return FACTORY.createPointInJourneyPatternRef(ref);
    }
}
