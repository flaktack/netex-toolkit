package toolkit.transform.epip;

// The EPIP journey-generator cluster.
//
// The generator is SERIAL: not sharded, with the index build inlined as its own query phase.
//
// WHAT IS PARALLEL HERE, AND WHAT IS NOT (-Dtoolkit.epip.parallelJourneys=N, default off). The
// flag moves TWO PURE FUNCTIONS off the transaction thread: the DECODE of each stored journey and
// the MARSHAL of each emitted object. The loop below — every store read, every dedup decision,
// every emission — stays where it is, in this order, on the thread holding the write txn. The
// emitted sequence is identical item for item. See toolkit.harness.GenParallel for the ordering
// contract and the marshal-before-extract hazard.
//
// THE SIGNATURE FORMAT IS ID-AFFECTING, SO IT IS FROZEN. The pattern hash is an md5 over a
// ';'-joined signature, truncated to its first four bytes as lowercase hex, and it ends up in
// GENERATED IDS that are already in published exports. Every component's stringification is
// therefore load-bearing to the byte, and none of it may be "tidied":
//   - stopUse:     the enum member name, or "None".
//   - requestStop: "True" / "False", or "None".
//   - order:       decimal digits, or "None".
//   - ddSig:       a display ref with a non-empty ref -> the ref string; otherwise a tuple form
//                  whose elements render as the multilingual-string repr, with an empty or null
//                  value rendering an empty content list. Strings use single quotes unless the
//                  text contains one and no double quote, with escapes for backslash, quote,
//                  \n, \r, \t and \xNN.
//   - notices:     ref or id or the object, ","-joined after a lexicographic sort.
//
// Binding decisions:
//  - the pattern ref is a JAXBElement union, so reads go through getValue() and writes wrap in the
//    typed factory element;
//  - the line, validity, vehicle-type and route unions each split into a ref half and a view or
//    embedded half, so clearing one means clearing both sides;
//  - there is no element subclass for a day-type or pattern ref: they are built as the structure
//    and wrapped in the typed factory element of the same name, so the serialized element is
//    identical;
//  - directionType is set as the enum, and JAXB serializes its value.
//
// missingOk semantics: the pattern and time-demand loads DROP the journey on a dangling ref, so
// they use the tolerant load plus a null check. The unguarded loads use the throwing form.
//
// EVERY DROP IS COUNTED and the totals reported once when the pass runs dry, under the
// droppedOutput concern, which strictness can turn into a refusal.

import jakarta.xml.bind.JAXBElement;
import noi.netex.calls.Calls;
import noi.netex.model.AvailabilityCondition;
import noi.netex.model.AvailabilityConditionRefStructure;
import noi.netex.model.Block;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.DayTypeRefs_RelStructure;
import noi.netex.model.DestinationDisplayRefStructure;
import noi.netex.model.DestinationDisplayView;
import noi.netex.model.EntityStructure;
import noi.netex.model.JourneyPattern_VersionStructure;
import noi.netex.model.LineRefStructure;
import noi.netex.text.Mls;
import noi.netex.model.MultilingualString;
import noi.netex.model.NoticeAssignment;
import noi.netex.model.NoticeAssignment_VersionStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.OperatingPeriodRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.PointsInJourneyPattern_RelStructure;
import noi.netex.model.Route;
import noi.netex.model.RoutePointRefStructure;
import noi.netex.model.RouteView;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceJourneyPatternRefStructure;
import noi.netex.model.ServiceJourneyRefStructure;
import noi.netex.model.ServiceLink;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.TimeDemandType;
import noi.netex.model.TimetabledPassingTimes_RelStructure;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.model.ValidityConditions_RelStructure;
import noi.netex.model.VehicleTypeRefStructure;
import noi.netex.model.VersionOfObjectRefStructure;
import noi.netex.time.XmlDateTime;
import toolkit.harness.GenParallel;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import toolkit.util.Strictness;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class EpipJourneys {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// Log-once keys already emitted.
    private static final Set<String> LOGGED_ONCE = new HashSet<>();

    private EpipJourneys() {}

    private static synchronized void logOnce(String key, String message) {
        if (LOGGED_ONCE.add(key)) Log.warn("%s:%s", key, message);   // aux_logging: key + ":" + message
    }

    private static synchronized void logOnceError(String key, String message) {
        if (LOGGED_ONCE.add(key)) Log.error("%s:%s", key, message);  // aux_logging: key + ":" + message
    }

    /// The tag the generator's end-of-pass aggregate is reported under.
    private static final String TAG = "epip-journeys";

    /// What one generator pass deleted from — or emitted incomplete into — its own output.
    ///
    /// NONE OF THESE THROWS. They are corpus facts, not defects: a dangling pattern or time-demand
    /// ref is what one feed of a multi-feed corpus looks like, and the day-type arms fire on feeds
    /// that carry a validity window instead of availability conditions.
    ///
    /// Plain longs, not atomics: the loop that writes them is serial on the txn thread by
    /// construction, and one bag belongs to one pass rather than to the JVM.
    public static final class Dropped {

        // --- journeys DELETED from the output ---
        /// Dangling journeyPatternRef on a journey that carries passing times.
        public long unresolvedPattern;
        /// Dangling journeyPatternRef or timeDemandTypeRef on the expand-from-TimeDemandType arm.
        public long unresolvedTiming;
        /// Neither passing times, nor calls, nor a journey-pattern/time-demand pair.
        public long noTiming;
        /// The resolved pattern has no pointsInSequence, so no timetable can be built from it.
        public long patternWithoutPoints;

        // --- journeys EMITTED, but missing something they should have carried ---
        /// A ValidityCondition of a kind this generator has no branch for; its days are lost.
        public long unhandledValidityCondition;
        /// No AvailabilityCondition and no DayTypes: the journey is emitted with no calendar.
        public long journeyWithoutDayType;
        /// The AvailabilityCondition yielded no valid days; the period is emitted as bits "0".
        public long operatingPeriodWithoutDays;
        /// A RouteView with no line to recover, so the pattern is emitted without a LineRef.
        public long routeViewWithoutLine;

        /// Journeys this pass deleted from the output.
        public long droppedJourneys() {
            return unresolvedPattern + unresolvedTiming + noTiming + patternWithoutPoints;
        }

        /// Journeys this pass emitted with something missing.
        public long incompleteJourneys() {
            return unhandledValidityCondition + journeyWithoutDayType
                    + operatingPeriodWithoutDays + routeViewWithoutLine;
        }

        /// One line per non-zero counter, naming the opt-in. Separate calls rather than one sum, so
        /// each count carries its reason.
        void report(String tag) {
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", unresolvedPattern, tag,
                    "ServiceJourneys were dropped for an unresolved ServiceJourneyPatternRef");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", unresolvedTiming, tag,
                    "ServiceJourneys were dropped for an unresolved journey-pattern/time-demand ref");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", noTiming, tag,
                    "ServiceJourneys were dropped because no timing transformation matched them");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", patternWithoutPoints, tag,
                    "ServiceJourneys were dropped because their pattern has no points_in_sequence");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", unhandledValidityCondition, tag,
                    "ValidityConditions were dropped from a journey's calendar (no branch handles "
                    + "their type, so the days they carried are not in the export)");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", journeyWithoutDayType, tag,
                    "ServiceJourneys were emitted with NO day type (no AvailabilityCondition and "
                    + "no DayTypes to derive one from)");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", operatingPeriodWithoutDays, tag,
                    "UicOperatingPeriods were emitted with no valid days (bits \"0\"), so every "
                    + "journey on them runs on zero days");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", routeViewWithoutLine, tag,
                    "ServiceJourneyPatterns were emitted with a RouteView carrying no LineRef");
        }
    }

    // ------------------------------------------------------------ _point_in_pattern_signature

    /// The pattern-identity content of a projected call: everything that ends up on the
    /// stop-point-in-pattern and distinguishes one pattern from another. Provenance ids are
    /// deliberately excluded.
    ///
    /// ID-AFFECTING: these strings feed the pattern hash, so every component uses the exact
    /// rendering the class header pins.
    static String pointInPatternSignature(StopPointInJourneyPattern pis) {
        Object dd = pis.getDestinationDisplayRef() != null
                ? pis.getDestinationDisplayRef() : pis.getDestinationDisplayView();
        String ddSig;
        if (dd == null) {
            ddSig = "";
        } else {
            String ref = dd instanceof DestinationDisplayRefStructure r ? r.getRef() : null;
            if (truthy(ref)) {
                ddSig = ref;
            } else if (dd instanceof DestinationDisplayView v) {
                ddSig = "(" + mlsRepr(v.getFrontText()) + ", " + mlsRepr(v.getName()) + ")";
            } else {
                // A ref with a falsy @ref: neither tuple element resolves.
                ddSig = "(None, None)";
            }
        }

        List<String> notices = new ArrayList<>();
        if (pis.getNoticeAssignments() != null) {
            for (JAXBElement<?> el : pis.getNoticeAssignments().getNoticeAssignment_OrNoticeAssignmentView()) {
                Object na = el.getValue();
                // A notice-assignment VIEW lacks the union field entirely.
                Object target = null;
                if (na instanceof NoticeAssignment_VersionStructure n) {
                    target = n.getNoticeRef() != null ? n.getNoticeRef()
                            : n.getGroupOfNoticesRef() != null ? n.getGroupOfNoticesRef() : n.getNotice();
                }
                String s;
                String tref = target instanceof VersionOfObjectRefStructure r ? r.getRef() : null;
                if (truthy(tref)) {
                    s = tref;
                } else {
                    String tid = target instanceof EntityStructure e ? e.getId() : null;
                    if (truthy(tid)) s = tid;
                    else if (target == null) s = "None";
                    // A ref-less, id-less target renders as a reflective dump of its non-null
                    // fields. NOT toString(), which is Object's — a NON-DETERMINISTIC identity
                    // hash that would split or merge journey patterns at random.
                    else s = beanRepr(target);
                }
                notices.add(s);
            }
        }
        Collections.sort(notices);   // sorted(notices) — natural String order

        return String.join("|",
                sigStr(pis.getScheduledStopPointRef().getValue().getRef()),
                sigStr(pis.getOrder()),
                pis.getStopUse() == null ? "None" : "StopUseEnumeration." + pis.getStopUse().name(),
                sigBool(pis.isRequestStop()),
                ddSig,
                String.join(",", notices));
    }

    // ------------------------------------------------------ service_journey_pattern_from_calls

    /// Synthesize a ServiceJourneyPattern from a journey's calls. The pattern id is the truncated
    /// md5 over [line ref, direction, per-point signatures]; the projected points and notice
    /// assignments are numbered from that identity, and the notices' journey-level validity
    /// conditions are cleared. The journey's own pattern ref is NOT set here; the caller wires it.
    public static ServiceJourneyPattern serviceJourneyPatternFromCalls(
            ServiceJourney sj, GeneratorDefaults generatorDefaults) {
        List<StopPointInJourneyPattern> piss = new ArrayList<>();

        // Because NeTEx only supports LineRef from a Route, and a ServiceJourneyPattern can
        // refer to a single Route, we must make our new ServiceJourneyPattern unique by Line.
        // (A journey holding only a LineView NPEs here.)
        List<String> lSsp = new ArrayList<>();
        lSsp.add(sj.getLineRef().getValue().getRef());
        // ID-AFFECTING: the direction renders as the ENUM FORM, class name plus member name, not
        // as the bare XML token.
        lSsp.add(sj.getDirectionType() != null
                ? "DirectionTypeEnumeration." + sj.getDirectionType().name() : "None");

        // The calls list is homogeneous at 2.0, which deletes DatedCall and CallZ.
        for (Call_VersionedChildStructure call : Calls.of(sj.getCalls())) {
            call.setExtensions(null);
            StopPointInJourneyPattern pis = NetexUtils.project(call, StopPointInJourneyPattern.class);
            pis.setScheduledStopPointRef(call.getScheduledStopPointRef());
            piss.add(pis);
            lSsp.add(pointInPatternSignature(pis));
        }

        String id = md5Head8(String.join(";", lSsp));

        for (StopPointInJourneyPattern pis : piss) {
            pis.setId(NetexUtils.getId(generatorDefaults.codespace(), StopPointInJourneyPattern.class,
                    id + "-" + sigStr(pis.getOrder())));
            // Projected notice-assignment ids embed the CREATING journey's id, which is meaningless
            // on a pattern shared by many. Renumber from the pattern identity: the notice targets
            // are part of the hash, so equal-id patterns carry equal notices.
            if (pis.getNoticeAssignments() != null) {
                List<JAXBElement<?>> nas = pis.getNoticeAssignments().getNoticeAssignment_OrNoticeAssignmentView();
                for (int i = 0; i < nas.size(); i++) {
                    if (nas.get(i).getValue() instanceof NoticeAssignment na) {
                        na.setId(NetexUtils.getId(generatorDefaults.codespace(), NoticeAssignment.class,
                                id + "-" + sigStr(pis.getOrder()) + "-" + (i + 1)));
                        // The projected validity conditions are the creating journey's operating
                        // days — journey-level data that is wrong on a shared pattern.
                        na.setValidityConditions(null);
                        na.getValidBetween().clear();
                    }
                }
            }
        }

        ServiceJourneyPattern sjp = new ServiceJourneyPattern();
        sjp.setId(NetexUtils.getId(generatorDefaults.codespace(), ServiceJourneyPattern.class, id));
        sjp.setVersion(sj.getVersion());
        RouteView routeView = new RouteView();
        routeView.setLineRef(sj.getLineRef());
        sjp.setRouteView(routeView);
        sjp.setDirectionType(sj.getDirectionType());   // the enum's value() is what marshals
        if (sj.getJourneyPatternView() != null) {
            sjp.setDestinationDisplayRef(sj.getJourneyPatternView().getDestinationDisplayRef());
            sjp.setDestinationDisplayView(sj.getJourneyPatternView().getDestinationDisplayView());
        }
        PointsInJourneyPattern_RelStructure points = new PointsInJourneyPattern_RelStructure();
        points.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern().addAll(piss);
        sjp.setPointsInSequence(points);
        return sjp;
    }

    // -------------------------------------------------------- service_journey_ac_to_day_type

    /// Derive a DayType, its operating period and its assignment from a journey's availability
    /// conditions, and point the journey at the — possibly already emitted — DayType. The caller
    /// drains this fully BEFORE emitting the journey, since the last of it mutates the journey.
    static List<Object> serviceJourneyAcToDayType(
            Store dbRead, Txn txn, ServiceJourney serviceJourney,
            Map<String, AvailabilityCondition> availabilityConditions,
            Set<String> availabilityConditionsIds,
            Set<String> dayTypesIds,
            Set<String> uicOperatingPeriodsIds,
            Set<String> dayTypeAssignmentsIds,
            Dropped dropped) {
        List<Object> out = new ArrayList<>();
        List<AvailabilityCondition> acs = new ArrayList<>();

        ValidityConditions_RelStructure vcs = serviceJourney.getValidityConditions();
        if (vcs != null) {   // ValidBetween entries have no choice and contribute nothing
            for (Object choice : vcs.getValidityConditionRefOrValidBetweenOrValidityCondition_()) {
                Object a = choice instanceof JAXBElement<?> je ? je.getValue() : choice;
                if (a instanceof AvailabilityConditionRefStructure ref) {
                    // Pre-indexed lookup; the store load is a last-resort fallback for
                    // multi-versioned ids the by-id index collapsed.
                    AvailabilityCondition thisAc = availabilityConditions.get(ref.getRef());
                    if (thisAc == null) {
                        logOnce("ac-index-miss", "AvailabilityCondition " + ref.getRef()
                                + " missed the pre-index; falling back to a DB load");
                        thisAc = (AvailabilityCondition) dbRead.loadObjectByReference(txn, ref);
                    }
                    acs.add(thisAc);
                } else if (a instanceof AvailabilityCondition ac) {
                    acs.add(ac);
                } else {
                    // The condition is DISCARDED — its days are not in the export — and the
                    // journey is emitted with whatever calendar the others gave it.
                    dropped.unhandledValidityCondition++;
                    Log.warn("Unhandled ValidityCondition in %s", serviceJourney.getId());
                }
            }
        }

        if (serviceJourney.getDayTypes() != null) {
            if (acs.isEmpty()) {
                // There are no AvailabilityConditions, there may be ValidBetweens; trust the DayType
                return out;
            }
            // Both exist, maybe trigger a warning?
        }

        String dayTypeId;
        if (!acs.isEmpty()) {
            // KNOWN OPEN QUESTION (OPEN.md §16): the id comes from the FIRST availability
            // condition, which is arbitrary when a journey has several.
            dayTypeId = acs.get(0).getId().replace("AvailabilityCondition", "DayType");
        } else {
            // No availability condition to derive a DayType from, and no DayTypes either: the
            // journey is emitted with NO calendar at all and runs on no day a consumer can name.
            dropped.journeyWithoutDayType++;
            Log.warn("Check %s", serviceJourney.getId());
            return out;
        }

        VersionOfObjectRefStructure dayTypeRef;
        if (!dayTypesIds.contains(dayTypeId)) {
            ServiceCalendarEpip.Positive positive = ServiceCalendarEpip.positiveAvailabilityCondition(acs);
            List<java.time.LocalDateTime> validDays = positive.validDays();

            UicOperatingPeriod uicOperatingPeriod = new UicOperatingPeriod();
            uicOperatingPeriod.setId(acs.get(0).getId().replace("AvailabilityCondition", "UicOperatingPeriod"));
            uicOperatingPeriod.setVersion(acs.get(0).getVersion());
            uicOperatingPeriod.setDerivedFromObjectRef(acs.get(0).getId());
            uicOperatingPeriod.setDerivedFromVersionRef_BasicModificationDetailsGroup(acs.get(0).getVersion());
            // The two branches treat the date DIFFERENTLY on purpose: copy versus synthesis
            // decides whether the output carries the SOURCE lexical or a canonical one.
            if (validDays.isEmpty()) {
                // The period is still emitted, with bits "0" — a calendar that runs on zero days,
                // so every journey pointed at this DayType is in the export and operates NEVER.
                dropped.operatingPeriodWithoutDays++;
                Log.warn("%s does not have any valid days", dayTypeId);
                // PURE COPY: the parsed carrier goes straight across and re-emits the source
                // lexical verbatim, offset and all. Do NOT "normalise" this.
                uicOperatingPeriod.setFromDate(acs.get(0).getFromDate());
                uicOperatingPeriod.setToDate(acs.get(0).getFromDate());   // the string is empty anyway
                uicOperatingPeriod.setValidDayBits("0");
            } else {
                // SYNTHESIS: these are computed values with no source lexical, so the carrier is
                // built from a datetime and serialises CANONICALLY.
                uicOperatingPeriod.setFromDate(XmlDateTime.from(validDays.get(0)));
                uicOperatingPeriod.setToDate(XmlDateTime.from(validDays.get(validDays.size() - 1)));
                uicOperatingPeriod.setValidDayBits(ServiceCalendarEpip.validDaysToBits(validDays));
            }
            uicOperatingPeriod.getDaysOfWeek().addAll(positive.daysOfWeek());
            uicOperatingPeriodsIds.add(uicOperatingPeriod.getId());
            out.add(uicOperatingPeriod);

            // Derive from the AvailabilityCondition (stable), not the journey: many journeys share
            // one AC-derived DayType, so journey-id provenance would depend on iteration order.
            DayType dayType = new DayType();
            dayType.setId(dayTypeId);
            dayType.setVersion(serviceJourney.getVersion());
            dayType.setDerivedFromObjectRef(acs.get(0).getId());
            dayType.setDerivedFromVersionRef_BasicModificationDetailsGroup(acs.get(0).getVersion());
            dayTypesIds.add(dayType.getId());
            out.add(dayType);

            DayTypeAssignment dayTypeAssignment = new DayTypeAssignment();
            dayTypeAssignment.setId(acs.get(0).getId().replace("AvailabilityCondition", "DayTypeAssignment"));
            dayTypeAssignment.setVersion(acs.get(0).getVersion());
            dayTypeAssignment.setOrder(BigInteger.ONE);
            dayTypeAssignment.setDerivedFromObjectRef(acs.get(0).getId());
            dayTypeAssignment.setDerivedFromVersionRef_BasicModificationDetailsGroup(acs.get(0).getVersion());
            // An OperatingPeriodRef element carrying nameOfRefClass="UicOperatingPeriod".
            dayTypeAssignment.setOperatingPeriodRef(FACTORY.createOperatingPeriodRef(
                    NetexUtils.getRef(uicOperatingPeriod, OperatingPeriodRefStructure.class)));
            dayTypeAssignment.setDayTypeRef(FACTORY.createDayTypeRef(
                    (DayTypeRefStructure) NetexUtils.getRef(dayType)));
            dayTypeAssignmentsIds.add(dayTypeAssignment.getId());
            out.add(dayTypeAssignment);
            dayTypeRef = NetexUtils.getRef(dayType);
        } else {
            // KNOWN OPEN QUESTION (OPEN.md §16): this emits a ref to a DayType that is never
            // constructed, so it can only resolve if something else supplies that id.
            dayTypeRef = NetexUtils.getFakeRef(dayTypeId, DayTypeRefStructure.class, serviceJourney.getVersion());
        }

        DayTypeRefs_RelStructure dayTypes = new DayTypeRefs_RelStructure();
        dayTypes.getDayTypeRef().add(FACTORY.createDayTypeRef((DayTypeRefStructure) dayTypeRef));
        serviceJourney.setDayTypes(dayTypes);
        return out;
    }

    // ------------------------------------------------------------------ build_journey_indexes

    /// The query-phase materials of [#epipServiceJourneyGenerator].
    record Indexes(Map<String, RoutePointRefStructure> routePointProjection,
                   Map<String, AvailabilityCondition> availabilityConditions,
                   Map<String, Route> routes) {}

    /// Pre-index what the per-journey pass reads. Patterns and time-demand types are deliberately
    /// NOT indexed: they stay keyed on-demand loads, guarded by the pattern dedup set.
    static Indexes buildJourneyIndexes(Store dbRead, Txn txn) {
        Log.info("Indexing RoutePoint to ScheduledStopPoint ");
        Map<String, RoutePointRefStructure> routePointProjection = new LinkedHashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
            ScheduledStopPoint ssp = (ScheduledStopPoint) o;
            List<RoutePointRefStructure> rpToSsp = RoutesProfile.routePointProjection(ssp);
            if (!rpToSsp.isEmpty()) {
                routePointProjection.put(NetexUtils.getRef(ssp).getRef(), rpToSsp.get(0));
            }
        }

        Log.info("Indexing AvailabilityConditions / Routes");
        // id -> object, insertion-ordered, duplicate ids last-win.
        Map<String, AvailabilityCondition> availabilityConditions = new LinkedHashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, AvailabilityCondition.class)) {
            AvailabilityCondition ac = (AvailabilityCondition) o;
            availabilityConditions.put(ac.getId(), ac);
        }
        Map<String, Route> routes = new LinkedHashMap<>();
        if (dbRead.dbNames(txn).contains(Route.class)) {   // Route in present_classes
            for (Object o : dbRead.iterOnlyObjects(txn, Route.class)) {
                Route route = (Route) o;
                routes.put(route.getId(), route);
            }
        }
        return new Indexes(routePointProjection, availabilityConditions, routes);
    }

    // ------------------------------------------------------- the service-journey generator

    /// The per-run state the nested closures share.
    private record Ctx(Store dbRead, Txn txn, GeneratorDefaults generatorDefaults,
                       Indexes indexes,
                       Set<String> sjpIds,
                       Set<String> availabilityConditionsIds,
                       Set<String> dayTypesIds,
                       Set<String> uicOperatingPeriodsIds,
                       Set<String> dayTypeAssignmentsIds,
                       Map<String, JAXBElement<? extends VehicleTypeRefStructure>> vehicleTypes,
                       Dropped dropped) {}

    /// SERIAL ONLY. Per journey, a four-way dispatch:
    ///
    /// 1. passing times present → load the referenced pattern once per pattern; a dangling ref
    ///    drops the journey, as a consumer would;
    /// 2. calls present → keep an existing pattern ref, else synthesize the pattern from the
    ///    calls, and derive passing times from them;
    /// 3. pattern ref plus time-demand ref → load both, a dangling one dropping the journey, and
    ///    expand;
    /// 4. none of the above → drop: there is nothing to derive a timetable from.
    ///
    /// A first-seen pattern is emitted after route→link projection, filtering its points to
    /// stop-points-in-pattern and recovering the line ref; then the derived calendar objects; then
    /// the stripped journey, its vehicle type backfilled from the block index only when absent.
    public static Iterator<Object> epipServiceJourneyGenerator(
            Store dbRead, Txn txn, GeneratorDefaults generatorDefaults) {
        return epipServiceJourneyGenerator(dbRead, txn, generatorDefaults, new Dropped());
    }

    /// The generator, writing its drop counters into a caller-supplied bag so a caller can inspect
    /// what a pass dropped. The pass still reports it.
    public static Iterator<Object> epipServiceJourneyGenerator(
            Store dbRead, Txn txn, GeneratorDefaults generatorDefaults, Dropped dropped) {
        Ctx ctx = new Ctx(dbRead, txn, generatorDefaults, buildJourneyIndexes(dbRead, txn),
                new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(),
                new HashMap<>(), dropped);

        Log.info("Service journeys for now ");

        // The Block scan feeds the vehicle-type backfill. (A Block without journeys NPEs here.)
        for (Object o : dbRead.iterOnlyObjects(txn, Block.class)) {
            Block block = (Block) o;
            for (JAXBElement<?> el : block.getJourneys().getJourneyRefOrJourneyDesignatorOrServiceDesignator()) {
                // The element name separates a plain journey ref from its dated siblings.
                if (el.getValue() instanceof ServiceJourneyRefStructure sjr
                        && "ServiceJourneyRef".equals(el.getName().getLocalPart())) {
                    ctx.vehicleTypes().put(sjr.getRef(), block.getVehicleTypeRef());
                }
            }
        }

        // The source scan. Serial by default; -Dtoolkit.epip.parallelJourneys=N moves the JAXB
        // DECODE (and only the decode) onto N threads, cursor order preserved — the cursor itself
        // stays here, because it belongs to the write txn and a write txn is thread-bound.
        Iterator<Object> journeys =
                GenParallel.decodeAhead(dbRead, txn, ServiceJourney.class, "journeys");
        Deque<Object> pending = new ArrayDeque<>();
        Iterator<Object> emitted = new Iterator<>() {
            private boolean reported;

            @Override
            public boolean hasNext() {
                while (pending.isEmpty() && journeys.hasNext()) {
                    process(ctx, (ServiceJourney) journeys.next(), pending);
                }
                if (pending.isEmpty() && !reported) {
                    // END OF THE PASS, and the ONLY boundary the generator can see: it hands
                    // objects to an insert loop and is never told the driver finished.
                    reported = true;
                    ctx.dropped().report(TAG);
                    // Reported here: the passing-times profile has no pass of its own and this is
                    // its only caller.
                    TimetabledPassingTimesProfile.reportDropped(TAG);
                }
                return !pending.isEmpty();
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return pending.poll();
            }
        };
        // The OUTPUT half of the off-load is NOT applied here: it wraps each object in a form only
        // an insert path understands, so it belongs at the call site that feeds the insert.
        return emitted;
    }

    /// The nested process() closure; each yield becomes an `out` append.
    private static void process(Ctx ctx, ServiceJourney sj, Deque<Object> out) {
        JourneyPattern_VersionStructure serviceJourneyPattern = null;

        if (sj.getPassingTimes() != null) {
            // (a null journeyPatternRef here NPEs)
            if (!ctx.sjpIds().contains(sj.getJourneyPatternRef().getValue().getRef())) {
                // Loaded once per PATTERN, not per journey.
                serviceJourneyPattern = (JourneyPattern_VersionStructure) ctx.dbRead()
                        .loadObjectByReference(ctx.txn(), sj.getJourneyPatternRef().getValue(), /*missingOk=*/true);
                if (serviceJourneyPattern == null) {
                    // Dangling pattern ref: the pattern is not in the feed. Drop the journey
                    // rather than abort the run, and count it.
                    ctx.dropped().unresolvedPattern++;
                    Log.error("Dropping ServiceJourney %s: unresolved ServiceJourneyPattern %s",
                            sj.getId(), sj.getJourneyPatternRef().getValue().getRef());
                    return;
                }
            }

        } else if (sj.getCalls() != null) {
            if (sj.getJourneyPatternRef() != null) {
                // keep the existing ref
            } else {
                serviceJourneyPattern = serviceJourneyPatternFromCalls(sj, ctx.generatorDefaults());
                sj.setJourneyPatternRef(FACTORY.createServiceJourneyPatternRef(
                        (ServiceJourneyPatternRefStructure) NetexUtils.getRef(serviceJourneyPattern)));
            }

            TimetabledPassingTimes_RelStructure tpts = new TimetabledPassingTimes_RelStructure();
            tpts.getTimetabledPassingTime().addAll(
                    TimetabledPassingTimesProfile.getTimetabledPassingtimesFromCalls(sj, serviceJourneyPattern));
            sj.setPassingTimes(tpts);

        } else if (sj.getJourneyPatternRef() != null && sj.getTimeDemandTypeRef() != null) {
            serviceJourneyPattern = (JourneyPattern_VersionStructure) ctx.dbRead()
                    .loadObjectByReference(ctx.txn(), sj.getJourneyPatternRef().getValue(), /*missingOk=*/true);
            TimeDemandType timeDemandType = (TimeDemandType) ctx.dbRead()
                    .loadObjectByReference(ctx.txn(), sj.getTimeDemandTypeRef(), /*missingOk=*/true);
            if (serviceJourneyPattern == null || timeDemandType == null) {
                ctx.dropped().unresolvedTiming++;
                Log.error("Dropping ServiceJourney %s: unresolved journey-pattern/time-demand ref", sj.getId());
                return;
            }
            CallsProfile.getPassingTimesFromTimeDemandType(
                    sj, (ServiceJourneyPattern) serviceJourneyPattern, timeDemandType);

        } else {
            // No passing times, no calls, no journey-pattern/time-demand pair: nothing to derive
            // a timetable from. Drop the journey like the other unresolvable cases.
            ctx.dropped().noTiming++;
            Log.error("Dropping ServiceJourney %s: no matching timing transformation found", sj.getId());
            return;
        }

        // If we already know that this generated SJP already exists, we should not even add it.
        if (ctx.sjpIds().contains(sj.getJourneyPatternRef().getValue().getRef())) {
            // pass
        } else if (serviceJourneyPattern == null) {
            // (the two ids stand in for a full object dump)
            Log.error("No service journey pattern for journey: %s %s",
                    sj.getId(), sj.getJourneyPatternRef().getValue().getRef());
        }

        if (serviceJourneyPattern != null && serviceJourneyPattern.getPointsInSequence() == null) {
            // The log line is keyed on the PATTERN, so one covers every journey on it.
            ctx.dropped().patternWithoutPoints++;
            logOnceError("pattern-no-points:" + serviceJourneyPattern.getId(),
                    "Dropping ServiceJourney " + sj.getId() + ": pattern "
                            + serviceJourneyPattern.getId() + " has no points_in_sequence");
            return;
        }

        if (serviceJourneyPattern != null && !ctx.sjpIds().contains(serviceJourneyPattern.getId())) {

            if (!ctx.indexes().routePointProjection().isEmpty()) {
                if (serviceJourneyPattern.getRouteRef() != null) {
                    Route route = ctx.indexes().routes().get(serviceJourneyPattern.getRouteRef().getRef());
                    if (route == null) {
                        route = (Route) ctx.dbRead().loadObjectByReference(
                                ctx.txn(), serviceJourneyPattern.getRouteRef());
                    }
                    for (ServiceLink sl : RoutesProfile.projectRouteToServiceLinks(
                            ctx.dbRead(), ctx.txn(), (ServiceJourneyPattern) serviceJourneyPattern,
                            route, ctx.indexes().routePointProjection(), ctx.generatorDefaults())) {
                        out.add(sl);
                    }
                }
            }

            // Filter the points-in-sequence to StopPointInJourneyPatterns, written back in
            // place (clear + addAll; JAXB lists have no setter).
            List<PointInLinkSequence_VersionedChildStructure> points = serviceJourneyPattern
                    .getPointsInSequence()
                    .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
            List<PointInLinkSequence_VersionedChildStructure> onlyStops = new ArrayList<>();
            for (PointInLinkSequence_VersionedChildStructure p : points) {
                if (p instanceof StopPointInJourneyPattern) onlyStops.add(p);
            }
            points.clear();
            points.addAll(onlyStops);

            // After the Routes to ServiceLinks!
            recoverLineRef(ctx, sj, serviceJourneyPattern);

            // KNOWN GAP (OPEN.md §16): LinkSequenceProjectionRef / LinkSequenceProjection are
            // not carried onto the generated pattern.

            out.add(serviceJourneyPattern);
            ctx.sjpIds().add(serviceJourneyPattern.getId());
        }

        out.addAll(serviceJourneyAcToDayType(ctx.dbRead(), ctx.txn(), sj,
                ctx.indexes().availabilityConditions(), ctx.availabilityConditionsIds(),
                ctx.dayTypesIds(), ctx.uicOperatingPeriodsIds(), ctx.dayTypeAssignmentsIds(),
                ctx.dropped()));

        sj.setValidityConditions(null);            // validity_conditions_or_valid_between = []
        sj.getValidBetween().clear();
        sj.setTimeDemandTypeRef(null);
        sj.setKeyList(null);
        sj.setPrivateCode(null);
        // Keep the train numbers: they are carried into the target store.
        sj.setExtensions(null);
        sj.setNoticeAssignments(null);
        sj.setCalls(null);
        sj.setLinkSequenceProjectionRef(null);     // link_sequence_projection_ref_or_... = None
        sj.setLinkSequenceProjection(null);
        sj.setJourneyPatternView(null);
        sj.setDirectionType(null);
        // Keep an existing vehicle-type/train ref; only backfill from the Block index when absent.
        if (sj.getVehicleTypeRef() == null) {
            sj.setVehicleTypeRef(ctx.vehicleTypes().get(sj.getId()));
        }

        out.add(sj);
    }

    /// The nested recoverLineRef closure.
    private static void recoverLineRef(Ctx ctx, ServiceJourney serviceJourney,
                                       JourneyPattern_VersionStructure serviceJourneyPattern) {
        // A FlexibleLineRef and a LineRef both substitute into getLineRef(); a
        // LineView/FlexibleLineView lives in its own slot and contributes nothing.
        JAXBElement<? extends LineRefStructure> sjLineRef = serviceJourney.getLineRef();

        if (serviceJourneyPattern.getRouteRef() != null || serviceJourneyPattern.getRouteView() != null) {
            if (serviceJourneyPattern.getRouteView() != null) {
                RouteView routeView = serviceJourneyPattern.getRouteView();
                if (routeView.getLineRef() != null) {
                    // There is already an existing LineRef, overwriting is not smart.
                } else if (sjLineRef != null) {
                    // The RouteView splits the union into lineRef + lineView slots, so null the
                    // view explicitly.
                    routeView.setLineRef(sjLineRef);
                    routeView.setLineView(null);
                } else {
                    // The pattern is emitted with a RouteView that names no Line, so nothing
                    // downstream can attribute its journeys to a line.
                    ctx.dropped().routeViewWithoutLine++;
                    Log.warn("RouteView: no other option to recover the line");
                }

            } else {
                Route route = ctx.indexes().routes().get(serviceJourneyPattern.getRouteRef().getRef());
                if (route == null) {
                    route = (Route) ctx.dbRead().loadObjectByReference(
                            ctx.txn(), serviceJourneyPattern.getRouteRef());
                }
                RouteView routeView = new RouteView();
                routeView.setLineRef(route.getLineRef());
                serviceJourneyPattern.setRouteRef(null);   // the union re-assignment clears the ref arm
                serviceJourneyPattern.setRouteView(routeView);

                if (serviceJourneyPattern.getDirectionType() == null && route.getDirectionType() != null) {
                    serviceJourneyPattern.setDirectionType(route.getDirectionType());   // .value — wire-identical
                }
                if (serviceJourneyPattern.getDirectionRef() == null
                        && serviceJourneyPattern.getDirectionView() == null) {
                    serviceJourneyPattern.setDirectionRef(route.getDirectionRef());
                }
                if (serviceJourneyPattern.getDistance() == null) {
                    // KNOWN BUG (OPEN.md §16): the assignment is reversed. The guard tests the
                    // PATTERN's distance for null, then writes that null onto the ROUTE.
                    route.setDistance(serviceJourneyPattern.getDistance());
                }
            }
        } else {
            RouteView routeView = new RouteView();
            routeView.setLineRef(sjLineRef);
            serviceJourneyPattern.setRouteView(routeView);
        }
    }

    // ---------------------------------------------------------------- string primitives

    /// Non-null AND non-empty — the test the fallback chains below use.
    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    /// Renders a nullable scalar into the frozen signature format: null → the literal `"None"`.
    private static String sigStr(Object o) {
        return o == null ? "None" : o.toString();
    }

    /// Renders a nullable boolean into the frozen signature format: "True"/"False", null → "None".
    private static String sigBool(Boolean b) {
        return b == null ? "None" : (b ? "True" : "False");
    }

    /// A DETERMINISTIC, content-bearing string for a model bean that carries neither a `ref` nor an
    /// `id` — the one place [#pisSignature]'s `or` chain falls all the way through.
    ///
    /// **Determinism is the whole requirement.** This string feeds a journey-pattern identity
    /// signature, and a value that varied between runs — which `Object.toString()`'s identity hash
    /// is — would merge or split ServiceJourneyPatterns at random. So: fields sorted by name
    /// (declaration order is not specified by reflection), superclass fields included, nulls
    /// omitted, no identity hash anywhere.
    ///
    /// The recursion depth cap bounds a reflective walk that sits inside a per-call signature
    /// function; a parsed NeTEx tree has no cycles.
    private static String beanRepr(Object bean) {
        return beanRepr(bean, 4);
    }

    private static String beanRepr(Object bean, int depth) {
        if (bean == null) return "null";
        Class<?> type = bean.getClass();
        if (depth <= 0 || !type.getPackageName().startsWith("noi.netex.")) {
            return String.valueOf(bean);
        }
        java.util.TreeMap<String, Object> fields = new java.util.TreeMap<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(bean);
                    if (value != null) fields.putIfAbsent(f.getName(), value);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    fields.putIfAbsent(f.getName(), "?");   // inaccessible, but still deterministic
                }
            }
        }
        StringBuilder sb = new StringBuilder(64).append(type.getSimpleName()).append('[');
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(beanRepr(e.getValue(), depth - 1));
        }
        return sb.append(']').toString();
    }

    /// A MultilingualString (or null) as it appears inside the ddSig component of the frozen
    /// signature: `MultilingualString(lang=None, text_id_type=None, content=['text'])`, in that
    /// field order. The value string renders as a one-element content list; empty or null is `[]`.
    private static String mlsRepr(MultilingualString m) {
        if (m == null) return "None";
        String content = Mls.hasText(m) ? "[" + sigRepr(Mls.text(m)) + "]" : "[]";
        return "MultilingualString(lang=" + (m.getLang() == null ? "None" : sigRepr(m.getLang()))
                + ", text_id_type=" + (m.getTextIdType() == null ? "None" : sigRepr(m.getTextIdType()))
                + ", content=" + content + ")";
    }

    /// String rendering: single quotes, switching to double when the text contains a
    /// single quote but no double quote; backslash/quote/\n/\r/\t escaped explicitly; every
    /// other char where `str.isprintable()` is FALSE (Unicode categories Cc/Cf/Cs/Co/Cn/
    /// Zl/Zp/Zs, except U+0020) escaped in lowercase hex: `\xNN` below 0x100, the 4-digit
    /// backslash-u form on the rest of the BMP, the 8-digit backslash-U form for astral chars —
    /// NBSP → `\xa0`, C1 → `\x85`, U+200B → backslash-u200b, U+E0041 → backslash-
    /// U000e0041; printable non-ASCII (incl. astral emoji) stays literal.
    ///
    /// The printability categories come from Java's Unicode tables, which can lag a freshly
    /// assigned codepoint (category Cn).
    static String sigRepr(String s) {
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append(quote);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\\' || cp == quote) sb.append('\\').appendCodePoint(cp);
            else if (cp == '\n') sb.append("\\n");
            else if (cp == '\r') sb.append("\\r");
            else if (cp == '\t') sb.append("\\t");
            else if (!isPyPrintable(cp)) {
                if (cp < 0x100) sb.append(String.format("\\x%02x", cp));
                else if (cp < 0x10000) sb.append(String.format("\\u%04x", cp));
                else sb.append(String.format("\\U%08x", cp));
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.append(quote).toString();
    }

    /// Printability per code point: false for categories C* and Z* except U+0020.
    private static boolean isPyPrintable(int cp) {
        if (cp == ' ') return true;
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /// The first 8 hex digits of the MD5 of the UTF-8 bytes — lowercase, first 4 bytes.
    private static String md5Head8(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
