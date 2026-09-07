package toolkit.transform.epip;

// The calls-to-passing-times slice needed by the EPIP journey generator:
// getTimetabledPassingtimesFromCalls, mapCallToTimetabledPassingTime, getProperty and
// getDestination.

import noi.netex.calls.Calls;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.JourneyPattern_VersionStructure;
import noi.netex.model.PointInJourneyPatternRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.TimingPointInJourneyPattern_VersionedChildStructure;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import toolkit.model.NetexUtils;
import toolkit.util.Log;
import toolkit.util.Strictness;

public final class TimetabledPassingTimesProfile {

    private TimetabledPassingTimesProfile() {}

    /// Calls that produced NO TimetabledPassingTime because the pattern point at the call's order
    /// named a different stop than the call did. The journey is still emitted — with a timetable
    /// SHORT by this many stops, which no consumer can distinguish from a journey that really
    /// calls at fewer stops than its pattern lists.
    ///
    /// Both mismatch arms are KNOWN-UNFINISHED — they log and skip rather than resolving which of
    /// the two refs is right. Reported and reset by [#reportDropped].
    private static final AtomicLong ORDER_MISMATCH = new AtomicLong();

    /// Report the aggregate under the `droppedOutput` concern and reset it, returning what was
    /// reported. This class has no pass of its own, so the caller that DOES own one reports for it:
    /// [EpipJourneys#epipServiceJourneyGenerator].
    public static long reportDropped(String tag) {
        long n = ORDER_MISMATCH.getAndSet(0);
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", n, tag,
                "calls were dropped from a journey's timetable because the pattern point at the "
                + "call's order named a different stop (the journey is emitted SHORT)");
        return n;
    }

    /// One Call → one TimetabledPassingTime. `pattern` is the order → point-in-sequence map built by
    /// [#getTimetabledPassingtimesFromCalls]. The id swaps `:Call:` for `:TimetabledPassingTime:`;
    /// version and the derivedFrom pair come from the call; arrival/departure time + day offset
    /// copy through only when the respective structure exists.
    public static TimetabledPassingTime mapCallToTimetabledPassingTime(
            Call_VersionedChildStructure call,
            Map<BigInteger, PointInLinkSequence_VersionedChildStructure> pattern) {
        TimetabledPassingTime tpt = new TimetabledPassingTime();
        tpt.setId(call.getId().replace(":Call:", ":TimetabledPassingTime:"));
        tpt.setVersion(call.getVersion());
        tpt.setDerivedFromObjectRef(call.getId());
        tpt.setDerivedFromVersionRef_BasicModificationDetailsGroup(call.getVersion());
        // The JAXBElement wrap is this model's union representation.
        tpt.setPointInJourneyPatternRef(CallsProfile.wrapPointInJourneyPatternRef(
                (PointInJourneyPatternRefStructure) NetexUtils.getRef(pattern.get(call.getOrder()))));
        // PURE COPIES: XmlTime carriers assigned straight across, so an after-midnight 24:09:00
        // stays 24:09:00 and an offset-bearing 18:51:00+01:00 keeps its offset. Do NOT normalise
        // here — toLocalTime() would fold hour 24 to 00 and drop the offset.
        if (call.getArrival() != null) {
            tpt.setArrivalTime(call.getArrival().getTime());
            tpt.setArrivalDayOffset(call.getArrival().getDayOffset());
        }
        if (call.getDeparture() != null) {
            tpt.setDepartureTime(call.getDeparture().getTime());
            tpt.setDepartureDayOffset(call.getDeparture().getDayOffset());
        }
        return tpt;
    }

    /// The display string source of a destination union value: a ref-bearing object yields its
    /// `ref`, anything else its `name` (a MultilingualString, hence the Object return); null →
    /// `""`. An object with neither attribute throws.
    public static Object getDestination(Object destinationName) {
        if (destinationName != null) {
            Method ref = method(destinationName.getClass(), "getRef");   // a ref wins over a name
            if (ref != null) return invoke(ref, destinationName);
            Method name = method(destinationName.getClass(), "getName");
            if (name == null) {
                throw new IllegalArgumentException(
                        destinationName.getClass().getSimpleName() + " declares neither ref nor name");
            }
            return invoke(name, destinationName);
        }
        return "";
    }

    /// The stringified value of a bean property: a null object or a missing accessor is `""`, and a
    /// present-but-null value is the literal `"None"`. Both spellings reach LOG TEXT only.
    public static String getProperty(Object obj, String attr) {
        if (obj == null) return "";
        String cap = Character.toUpperCase(attr.charAt(0)) + attr.substring(1);
        Method m = method(obj.getClass(), "get" + cap);
        if (m == null) m = method(obj.getClass(), "is" + cap);
        if (m == null) return "";                    // no accessor -> empty, never a throw
        Object v = invoke(m, obj);
        return v == null ? "None" : v.toString();
    }

    /// Map every Call of `sj` whose stop ref agrees with the pattern point of the same order; a
    /// disagreement logs, COUNTS (see [#reportDropped]) and skips the call. Takes the JourneyPattern
    /// base, so the caller may pass `JourneyPattern` or `ServiceJourneyPattern`.
    public static List<TimetabledPassingTime> getTimetabledPassingtimesFromCalls(
            ServiceJourney sj, JourneyPattern_VersionStructure journeyPattern) {
        Map<BigInteger, PointInLinkSequence_VersionedChildStructure> pattern = new HashMap<>();
        for (PointInLinkSequence_VersionedChildStructure x : journeyPattern.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
            pattern.put(x.getOrder(), x);   // duplicate orders last-win
        }

        List<TimetabledPassingTime> ttpt = new ArrayList<>();
        for (Call_VersionedChildStructure call : Calls.of(sj.getCalls())) {
            // 2.0 leaves calls_RelStructure holding plain Calls, so there is nothing to dispatch on.
            PointInLinkSequence_VersionedChildStructure pis = pattern.get(call.getOrder());
            String callRef = call.getScheduledStopPointRef().getValue().getRef();
            if (pis instanceof TimingPointInJourneyPattern_VersionedChildStructure tp) {
                String pisRef = tp.getTimingPointRef().getValue().getRef();
                if (!Objects.equals(pisRef, callRef)) {   // KNOWN GAP
                    ORDER_MISMATCH.incrementAndGet();
                    Log.warn("%s order does not match %s order (%s vs %s)",
                            journeyPattern.getId(), sj.getId(),
                            refRepr(call.getScheduledStopPointRef().getValue()),
                            refRepr(tp.getTimingPointRef().getValue()));
                } else {
                    ttpt.add(mapCallToTimetabledPassingTime(call, pattern));
                }
            } else {
                // A missing order makes this cast/deref of the null lookup throw.
                StopPointInJourneyPattern_VersionedChildStructure spp =
                        (StopPointInJourneyPattern_VersionedChildStructure) pis;
                String pisRef = spp.getScheduledStopPointRef().getValue().getRef();
                if (!Objects.equals(pisRef, callRef)) {   // KNOWN GAP
                    ORDER_MISMATCH.incrementAndGet();
                    Log.warn("%s order does not match %s order (%s vs %s)",
                            journeyPattern.getId(), sj.getId(),
                            refRepr(call.getScheduledStopPointRef().getValue()),
                            refRepr(spp.getScheduledStopPointRef().getValue()));
                } else {
                    ttpt.add(mapCallToTimetabledPassingTime(call, pattern));
                }
            }
        }

        return ttpt;
    }

    /// A readable stand-in for a ref: JAXB beans have no useful toString.
    private static String refRepr(Object refStructure) {
        return refStructure.getClass().getSimpleName()
                + "(ref=" + getProperty(refStructure, "ref") + ")";
    }

    /// Public zero-arg method, or null — the shared memoised lookup in [NetexUtils#method].
    private static Method method(Class<?> clazz, String name) {
        return NetexUtils.method(clazz, name);
    }

    private static Object invoke(Method m, Object obj) {
        try {
            return m.invoke(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(m + " failed", e);
        }
    }
}
