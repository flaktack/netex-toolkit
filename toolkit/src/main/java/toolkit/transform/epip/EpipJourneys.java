package toolkit.transform.epip;

// The EPIP journey-generator cluster: the serial service-journey generator, the pattern synthesis
// its calls arm needs, and the day-type derivation.
//
// -Dtoolkit.epip.parallelJourneys=N (default off) moves the decode of each stored journey and the
// marshal of each emitted object off the transaction thread. Every store read, dedup decision and
// emission stays on the thread holding the write txn, and the emitted sequence is identical item
// for item; toolkit.harness.GenParallel holds the ordering contract and the marshal-before-extract
// hazard.
//
// The pattern hash is an md5 over a ';'-joined signature, truncated to its first four bytes as
// lowercase hex, and it lands in generated ids that are already in published exports. Every
// component's stringification is therefore load-bearing to the byte and frozen: the renderings
// pinned by sigStr, sigBool, mlsRepr and sigRepr may not be tidied.
//
// Refs bind as JAXBElement unions: reads go through getValue(), writes wrap the structure in the
// typed factory element of the same name. A union that splits into a ref half and a view or
// embedded half needs both sides cleared.

import jakarta.xml.bind.JAXBElement;
import noi.netex.calls.Calls;
import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.AvailabilityCondition;
import noi.netex.model.AvailabilityConditionRefStructure;
import noi.netex.model.Block;
import noi.netex.model.FlexibleLine;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.DayTypeRefs_RelStructure;
import noi.netex.model.DestinationDisplayRefStructure;
import noi.netex.model.DestinationDisplayView;
import noi.netex.model.EntityStructure;
import noi.netex.model.HeadwayJourneyGroup;
import noi.netex.model.JourneyPattern_VersionStructure;
import noi.netex.model.Line;
import noi.netex.model.LineRefStructure;
import noi.netex.model.Line_VersionStructure;
import noi.netex.text.Codes;
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
import noi.netex.model.ServiceJourney_VersionStructure;
import noi.netex.model.ServiceLink;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.TimeDemandType;
import noi.netex.model.TemplateServiceJourney;
import noi.netex.model.TimetabledPassingTimes_RelStructure;
import noi.netex.model.TrainNumber;
import noi.netex.model.TrainNumberRefStructure;
import noi.netex.model.TypeOfProductCategoryRefStructure;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.model.ValidityConditions_RelStructure;
import noi.netex.model.VehicleTypeRefStructure;
import noi.netex.model.VersionOfObjectRefStructure;
import noi.netex.time.XmlDateTime;
import toolkit.harness.GenParallel;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.JourneyLines;
import toolkit.util.Conf;
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

    private static final Set<String> LOGGED_ONCE = new HashSet<>();

    private EpipJourneys() {}

    private static synchronized void logOnce(String key, String message) {
        if (LOGGED_ONCE.add(key)) Log.warn("%s:%s", key, message);
    }

    private static synchronized void logOnceError(String key, String message) {
        if (LOGGED_ONCE.add(key)) Log.error("%s:%s", key, message);
    }

    /// The tag the generator's end-of-pass aggregate is reported under.
    private static final String TAG = "epip-journeys";

    /// What one generator pass deleted from — or emitted incomplete into — its own output.
    ///
    /// None of these throws: a dangling pattern or time-demand ref is what one feed of a multi-feed
    /// corpus looks like, and the day-type arms fire on feeds that carry a validity window instead
    /// of availability conditions.
    ///
    /// The counters are plain longs, correct only while the loop that writes them stays serial on
    /// the txn thread.
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
        /// A TemplateServiceJourney frequency group of a kind the profile's timetable frame does
        /// not list; the template is emitted without it.
        public long nonHeadwayFrequencyGroup;

        // --- what the pass did, where a reader wants the count ---
        /// A journey naming several train numbers; the first became its PublicCode.
        public long multipleTrainNumbers;

        /// Journeys this pass deleted from the output.
        public long droppedJourneys() {
            return unresolvedPattern + unresolvedTiming + noTiming + patternWithoutPoints;
        }

        /// Journeys this pass emitted with something missing.
        public long incompleteJourneys() {
            return unhandledValidityCondition + journeyWithoutDayType
                    + operatingPeriodWithoutDays + routeViewWithoutLine
                    + nonHeadwayFrequencyGroup;
        }

        /// One line per non-zero counter, naming the opt-in.
        void report(String tag) {
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", unresolvedPattern, tag,
                    "ServiceJourneys were dropped for an unresolved ServiceJourneyPatternRef");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", unresolvedTiming, tag,
                    "ServiceJourneys were dropped for an unresolved journey-pattern/time-demand ref");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", noTiming, tag,
                    "ServiceJourneys were dropped because no timing transformation matched them");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", patternWithoutPoints, tag,
                    "ServiceJourneys were dropped because their pattern has no points_in_sequence");
            Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", nonHeadwayFrequencyGroup,
                    tag, "TemplateServiceJourney frequency groups were dropped for not being a "
                            + "HeadwayJourneyGroup, the only kind this profile's timetable frame lists");
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
            if (multipleTrainNumbers > 0) {
                Log.info("[%s] %,d journeys name several train numbers; each took the first as its "
                        + "PublicCode", tag, multipleTrainNumbers);
            }
        }
    }

    // ------------------------------------------------------------ _point_in_pattern_signature

    /// The pattern-identity content of a projected call: everything that ends up on the
    /// stop-point-in-pattern and distinguishes one pattern from another. Provenance ids are
    /// deliberately excluded.
    ///
    /// Id-affecting: these strings feed the pattern hash, so every component uses the frozen
    /// rendering.
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
                // A notice-assignment view lacks the union field entirely.
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
                    else s = beanRepr(target);
                }
                notices.add(s);
            }
        }
        Collections.sort(notices);

        return String.join("|",
                sigStr(pis.getScheduledStopPointRef().getValue().getRef()),
                sigStr(pis.getOrder()),
                pis.getStopUse() == null ? "None" : "StopUseEnumeration." + pis.getStopUse().name(),
                sigBool(pis.isRequestStop()),
                ddSig,
                String.join(",", notices));
    }

    // ------------------------------------------------------ service_journey_pattern_from_calls

    /// Hex digits of the md5 kept in a synthesized pattern id — 8 bytes. A collision is silent
    /// data loss: the journey is repointed at the taken id and its passing times are derived from
    /// a pattern that is then never emitted, leaving a timetable against somebody else's stop
    /// sequence.
    private static final int ID_HEX = 16;

    /// Synthesize a ServiceJourneyPattern from a journey's calls. The pattern id is the truncated
    /// md5 over [line ref, direction, per-point signatures]; the projected points and notice
    /// assignments are numbered from that identity, and the notices' journey-level validity
    /// conditions are cleared. The journey's own pattern ref is not set here; the caller wires it.
    public static ServiceJourneyPattern serviceJourneyPatternFromCalls(
            ServiceJourney_VersionStructure sj, GeneratorDefaults generatorDefaults) {
        return serviceJourneyPatternFromCalls(sj, generatorDefaults, null);
    }

    /// The generator's entry point, carrying the per-run map from an issued id to the full digest
    /// behind it. A second signature landing on an id another one already holds widens that one
    /// pattern to the full digest rather than colliding; passing null skips the check, which is
    /// what a caller synthesizing a single pattern in isolation wants.
    static ServiceJourneyPattern serviceJourneyPatternFromCalls(
            ServiceJourney_VersionStructure sj, GeneratorDefaults generatorDefaults,
            Map<String, String> fullById) {
        List<StopPointInJourneyPattern> piss = new ArrayList<>();

        // Because NeTEx only supports LineRef from a Route, and a ServiceJourneyPattern can
        // refer to a single Route, we must make our new ServiceJourneyPattern unique by Line.
        // (A journey holding only a LineView NPEs here.)
        List<String> lSsp = new ArrayList<>();
        lSsp.add(sj.getLineRef().getValue().getRef());
        // Id-affecting: the direction renders as the enum form, class name plus member name, not
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

        String full = md5Hex(String.join(";", lSsp));
        String id = full.substring(0, ID_HEX);
        if (fullById != null) {
            // The map holds the digest of whichever signature claimed the id first, so every
            // later journey on the widened pattern re-derives the same widened id.
            String seen = fullById.putIfAbsent(id, full);
            if (seen != null && !seen.equals(full)) {
                Log.error("ServiceJourneyPattern id collision at %d hex digits on %s; "
                        + "widening this pattern to the full digest %s", ID_HEX, id, full);
                id = full;
            }
        }

        // Every id below derives from the journey's, not from the pattern's: the pattern's own id is
        // not built until after this loop, and both resolve to the same prefix anyway.
        for (StopPointInJourneyPattern pis : piss) {
            pis.setId(NetexUtils.deriveId(sj.getId(), StopPointInJourneyPattern.class,
                    id + "-" + sigStr(pis.getOrder()), generatorDefaults.codespace()));
            // Projected notice-assignment ids embed the creating journey's id, which is meaningless
            // on a pattern shared by many; they are renumbered from the pattern identity.
            if (pis.getNoticeAssignments() != null) {
                List<JAXBElement<?>> nas = pis.getNoticeAssignments().getNoticeAssignment_OrNoticeAssignmentView();
                for (int i = 0; i < nas.size(); i++) {
                    if (nas.get(i).getValue() instanceof NoticeAssignment na) {
                        na.setId(NetexUtils.deriveId(sj.getId(), NoticeAssignment.class,
                                id + "-" + sigStr(pis.getOrder()) + "-" + (i + 1),
                                generatorDefaults.codespace()));
                        // The projected validity conditions are the creating journey's operating
                        // days — journey-level data that is wrong on a shared pattern.
                        na.setValidityConditions(null);
                        na.getValidBetween().clear();
                    }
                }
            }
        }

        ServiceJourneyPattern sjp = new ServiceJourneyPattern();
        sjp.setId(NetexUtils.deriveId(sj.getId(), ServiceJourneyPattern.class, id,
                generatorDefaults.codespace()));
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
    /// drains this fully before emitting the journey, since the last of it mutates the journey.
    static List<Object> serviceJourneyAcToDayType(
            Store dbRead, Txn txn, ServiceJourney_VersionStructure serviceJourney,
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
        }

        String dayTypeId;
        if (!acs.isEmpty()) {
            // KNOWN OPEN QUESTION: the id comes from the first availability
            // condition, which is arbitrary when a journey has several.
            dayTypeId = acs.get(0).getId().replace("AvailabilityCondition", "DayType");
        } else {
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
            if (validDays.isEmpty()) {
                dropped.operatingPeriodWithoutDays++;
                Log.warn("%s does not have any valid days", dayTypeId);
                // Pure copy: the parsed carrier goes straight across and re-emits the source
                // lexical verbatim, offset and all. Do not normalise it.
                uicOperatingPeriod.setFromDate(acs.get(0).getFromDate());
                uicOperatingPeriod.setToDate(acs.get(0).getFromDate());   // the string is empty anyway
                uicOperatingPeriod.setValidDayBits("0");
            } else {
                // Synthesis: these are computed values with no source lexical, so the carrier is
                // built from a datetime and serialises canonically.
                uicOperatingPeriod.setFromDate(XmlDateTime.from(validDays.get(0)));
                uicOperatingPeriod.setToDate(XmlDateTime.from(validDays.get(validDays.size() - 1)));
                uicOperatingPeriod.setValidDayBits(ServiceCalendarEpip.validDaysToBits(validDays));
            }
            uicOperatingPeriod.getDaysOfWeek().addAll(positive.daysOfWeek());
            uicOperatingPeriodsIds.add(uicOperatingPeriod.getId());
            out.add(uicOperatingPeriod);

            // Provenance comes from the AvailabilityCondition, not the journey: many journeys share
            // one AC-derived DayType, so a journey id here would depend on iteration order.
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
            // KNOWN OPEN QUESTION: this emits a ref to a DayType that is never
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
    ///
    /// @param routePointProjection the ScheduledStopPoint a RoutePoint projects onto, by RoutePoint
    ///     ref
    /// @param availabilityConditions AvailabilityCondition by id
    /// @param routes Route by id
    /// @param trainNumbers the advertised number of a TrainNumber, by TrainNumber id
    /// @param lineModes the transport mode a Line declares, by Line id
    /// @param journeyLines the hops from a journey to its Line
    /// @param codeCategories the product categories whose journeys take their train number as a
    ///     PublicCode
    record Indexes(Map<String, RoutePointRefStructure> routePointProjection,
                   Map<String, AvailabilityCondition> availabilityConditions,
                   Map<String, Route> routes,
                   Map<String, String> trainNumbers,
                   Map<String, String> lineModes,
                   JourneyLines.Index journeyLines,
                   Set<String> codeCategories) {}

    /// Pre-index what the per-journey pass reads. Patterns and time-demand types are not indexed;
    /// they stay on-demand loads, guarded by the pattern dedup set.
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
        if (dbRead.dbNames(txn).contains(Route.class)) {
            for (Object o : dbRead.iterOnlyObjects(txn, Route.class)) {
                Route route = (Route) o;
                routes.put(route.getId(), route);
            }
        }

        // The advertised number a journey's trainNumbers reach, and the mode of the line it runs
        // on. A Mentz journey carries no TransportMode of its own, so the line's is what decides
        // whether its number is an advertised code.
        Map<String, String> trainNumbers = new LinkedHashMap<>();
        if (dbRead.dbNames(txn).contains(TrainNumber.class)) {
            for (Object o : dbRead.iterOnlyObjects(txn, TrainNumber.class)) {
                TrainNumber tn = (TrainNumber) o;
                if (tn.getId() != null && tn.getForAdvertisement() != null) {
                    trainNumbers.put(tn.getId(), tn.getForAdvertisement());
                }
            }
        }
        Map<String, String> lineModes = new LinkedHashMap<>();
        for (Class<?> c : List.of(Line.class, FlexibleLine.class)) {
            for (Object o : dbRead.iterOnlyObjects(txn, c)) {
                Line_VersionStructure line = (Line_VersionStructure) o;
                if (line.getTransportMode() != null) {
                    lineModes.put(line.getId(), line.getTransportMode().value());
                }
            }
        }
        return new Indexes(routePointProjection, availabilityConditions, routes,
                trainNumbers, lineModes, JourneyLines.index(dbRead, txn),
                codeCategories());
    }

    /// The product categories [Conf#EPIP_JOURNEY_CODE_CATEGORIES] admits, as a set.
    private static Set<String> codeCategories() {
        String raw = Conf.strOf(Conf.EPIP_JOURNEY_CODE_CATEGORIES);
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String s : raw.split(",")) {
            String t = s.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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
                       // Issued synthesized-pattern id to the full md5 behind it; the collision
                       // check in serviceJourneyPatternFromCalls.
                       Map<String, String> sjpFullHash,
                       Dropped dropped) {}

    /// Serial only. Per journey, a four-way dispatch:
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
                new HashMap<>(), new HashMap<>(), dropped);

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

        // The source scan. Only the JAXB decode moves onto the parallel threads; the cursor stays
        // here, because it belongs to the write txn and a write txn is thread-bound.
        //
        // TemplateServiceJourney is a ServiceJourney with frequencyGroups added -- the two are
        // siblings under ServiceJourney_VersionStructure -- so it carries the calls this pass turns
        // into a pattern and the availability conditions it turns into a calendar, and wants the
        // same treatment. frequencyGroups is on the subtype alone, so the shared code leaves the
        // headway intact. Iterating ServiceJourney only dropped every template.
        Iterator<Object> plain =
                GenParallel.decodeAhead(dbRead, txn, ServiceJourney.class, "journeys");
        Iterator<Object> templates =
                GenParallel.decodeAhead(dbRead, txn, TemplateServiceJourney.class, "templates");
        Iterator<Object> journeys = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return plain.hasNext() || templates.hasNext();
            }

            @Override
            public Object next() {
                return plain.hasNext() ? plain.next() : templates.next();
            }
        };
        Deque<Object> pending = new ArrayDeque<>();
        Iterator<Object> emitted = new Iterator<>() {
            private boolean reported;

            @Override
            public boolean hasNext() {
                while (pending.isEmpty() && journeys.hasNext()) {
                    process(ctx, (ServiceJourney_VersionStructure) journeys.next(), pending);
                }
                if (pending.isEmpty() && !reported) {
                    // End of the pass, and the only boundary the generator can see: it hands
                    // objects to an insert loop and is never told the driver finished.
                    reported = true;
                    ctx.dropped().report(TAG);
                    // Reported here: the passing-times profile has no pass of its own.
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
        // The output half of the off-load is not applied here: it wraps each object in a form only
        // an insert path understands, so it belongs at the call site that feeds the insert.
        return emitted;
    }

    /// The value inside a JAXBElement, or the object itself — a frequency-group list is a union and
    /// binds its members either way depending on the element name.
    private static Object unwrap(Object o) {
        return o instanceof JAXBElement<?> el ? el.getValue() : o;
    }

    private static void process(Ctx ctx, ServiceJourney_VersionStructure sj, Deque<Object> out) {
        JourneyPattern_VersionStructure serviceJourneyPattern = null;

        if (sj.getPassingTimes() != null) {
            // (a null journeyPatternRef here NPEs)
            if (!ctx.sjpIds().contains(sj.getJourneyPatternRef().getValue().getRef())) {
                // Loaded once per pattern, not per journey.
                serviceJourneyPattern = (JourneyPattern_VersionStructure) ctx.dbRead()
                        .loadObjectByReference(ctx.txn(), sj.getJourneyPatternRef().getValue(), /*missingOk=*/true);
                if (serviceJourneyPattern == null) {
                    // Dangling pattern ref: the pattern is not in the feed.
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
                serviceJourneyPattern =
                        serviceJourneyPatternFromCalls(sj, ctx.generatorDefaults(), ctx.sjpFullHash());
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
            ctx.dropped().noTiming++;
            Log.error("Dropping ServiceJourney %s: no matching timing transformation found", sj.getId());
            return;
        }

        if (ctx.sjpIds().contains(sj.getJourneyPatternRef().getValue().getRef())) {
            // already emitted
        } else if (serviceJourneyPattern == null) {
            Log.error("No service journey pattern for journey: %s %s",
                    sj.getId(), sj.getJourneyPatternRef().getValue().getRef());
        }

        if (serviceJourneyPattern != null && serviceJourneyPattern.getPointsInSequence() == null) {
            // The log line is keyed on the pattern, so one covers every journey on it.
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

            // Must run after the route-to-ServiceLink projection above.
            recoverLineRef(ctx, sj, serviceJourneyPattern);

            // KNOWN GAP: LinkSequenceProjectionRef / LinkSequenceProjection are
            // not carried onto the generated pattern.

            out.add(serviceJourneyPattern);
            ctx.sjpIds().add(serviceJourneyPattern.getId());
        }

        out.addAll(serviceJourneyAcToDayType(ctx.dbRead(), ctx.txn(), sj,
                ctx.indexes().availabilityConditions(), ctx.availabilityConditionsIds(),
                ctx.dayTypesIds(), ctx.uicOperatingPeriodsIds(), ctx.dayTypeAssignmentsIds(),
                ctx.dropped()));

        // A template's frequency groups are what make it one, and the profile's timetable frame
        // lists exactly one kind. Anything else -- a rhythmical group, a bare ref -- is a class the
        // frame does not carry, so it is dropped here and counted rather than emitted into a frame
        // with no place for it.
        if (sj instanceof TemplateServiceJourney tsj && tsj.getFrequencyGroups() != null) {
            List<Object> groups = tsj.getFrequencyGroups()
                    .getHeadwayJourneyGroupRefOrHeadwayJourneyGroupOrRhythmicalJourneyGroupRef();
            int before = groups.size();
            groups.removeIf(g -> !(unwrap(g) instanceof HeadwayJourneyGroup));
            ctx.dropped().nonHeadwayFrequencyGroup += before - groups.size();
            if (groups.isEmpty()) tsj.setFrequencyGroups(null);
        }

        // Before the strip: the advertised number is the journey's public code.
        adoptTrainNumberAsPublicCode(ctx, sj);

        sj.setValidityConditions(null);
        sj.getValidBetween().clear();
        sj.setTimeDemandTypeRef(null);
        sj.setKeyList(null);
        sj.setPrivateCode(null);
        // Keep the train numbers: they are carried into the target store.
        sj.setExtensions(null);
        sj.setNoticeAssignments(null);
        sj.setCalls(null);
        sj.setLinkSequenceProjectionRef(null);
        sj.setLinkSequenceProjection(null);
        sj.setJourneyPatternView(null);
        sj.setDirectionType(null);
        if (sj.getVehicleTypeRef() == null) {
            sj.setVehicleTypeRef(ctx.vehicleTypes().get(sj.getId()));
        }

        out.add(sj);
    }

    /// Give `sj` the number its trainNumbers advertise as its PublicCode, where it has none of its
    /// own and its product is one whose number the public reads.
    ///
    /// A journey naming several train numbers takes the first, counted under
    /// [Dropped#multipleTrainNumbers].
    ///
    /// OTP maps a journey's PublicCode to a trip's short name and reads TrainNumber nowhere, so
    /// this is what carries a train number into a graph.
    private static void adoptTrainNumberAsPublicCode(Ctx ctx, ServiceJourney_VersionStructure sj) {
        if (Codes.hasText(sj.getPublicCode()) || sj.getTrainNumbers() == null) return;
        if (!advertisesItsTrainNumber(ctx, sj)) return;
        List<TrainNumberRefStructure> refs = sj.getTrainNumbers().getTrainNumberRef();
        if (refs.isEmpty()) return;
        if (refs.size() > 1) ctx.dropped().multipleTrainNumbers++;
        String number = ctx.indexes().trainNumbers().get(refs.get(0).getRef());
        if (number != null && !number.isBlank()) sj.setPublicCode(Codes.of(number));
    }

    /// Whether `sj`'s train number is the number the public reads: a rail journey, or one whose
    /// product category the run admits through [Conf#EPIP_JOURNEY_CODE_CATEGORIES].
    ///
    /// A Mentz export populates trainNumbers on funiculars, trams and plain buses too, where the
    /// value counts courses within a day rather than naming a service.
    ///
    /// The journey's own TransportMode decides where it has one. Every ÖBB journey ships without
    /// one, so its line's mode is what answers.
    private static boolean advertisesItsTrainNumber(Ctx ctx, ServiceJourney_VersionStructure sj) {
        String mode = sj.getTransportMode() != null ? sj.getTransportMode().value() : null;
        if (mode == null) {
            String lineId = JourneyLines.lineIdOf(sj, ctx.indexes().journeyLines());
            if (lineId != null) mode = ctx.indexes().lineModes().get(lineId);
        }
        if (AllVehicleModesOfTransportEnumeration.RAIL.value().equals(mode)) return true;
        TypeOfProductCategoryRefStructure cat = sj.getTypeOfProductCategoryRef();
        return cat != null && ctx.indexes().codeCategories().contains(cat.getRef());
    }

    private static void recoverLineRef(Ctx ctx, ServiceJourney_VersionStructure serviceJourney,
                                       JourneyPattern_VersionStructure serviceJourneyPattern) {
        // A FlexibleLineRef and a LineRef both substitute into getLineRef(); a
        // LineView/FlexibleLineView lives in its own slot and contributes nothing.
        JAXBElement<? extends LineRefStructure> sjLineRef = serviceJourney.getLineRef();

        if (serviceJourneyPattern.getRouteRef() != null || serviceJourneyPattern.getRouteView() != null) {
            if (serviceJourneyPattern.getRouteView() != null) {
                RouteView routeView = serviceJourneyPattern.getRouteView();
                if (routeView.getLineRef() != null) {
                    // An existing LineRef is left alone.
                } else if (sjLineRef != null) {
                    // The RouteView splits the union into lineRef + lineView slots, so null the
                    // view explicitly.
                    routeView.setLineRef(sjLineRef);
                    routeView.setLineView(null);
                } else {
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
                    serviceJourneyPattern.setDirectionType(route.getDirectionType());
                }
                if (serviceJourneyPattern.getDirectionRef() == null
                        && serviceJourneyPattern.getDirectionView() == null) {
                    serviceJourneyPattern.setDirectionRef(route.getDirectionRef());
                }
                if (serviceJourneyPattern.getDistance() == null) {
                    // KNOWN BUG: the assignment is reversed. The guard tests the
                    // pattern's distance for null, then writes that null onto the route.
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

    /// Non-null and non-empty.
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

    /// A deterministic, content-bearing string for a model bean that carries neither a `ref` nor an
    /// `id`, which is where the fallback chain in the notice signature ends up.
    ///
    /// This string feeds a journey-pattern identity signature, so a value that varied between runs
    /// — which `Object.toString()`'s identity hash is — would merge or split ServiceJourneyPatterns
    /// at random. Fields are sorted by name (declaration order is not specified by reflection),
    /// superclass fields included, nulls omitted, no identity hash anywhere.
    ///
    /// The depth cap bounds the reflective walk; a parsed NeTEx tree has no cycles.
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

    /// String rendering: single quotes, switching to double when the text contains a single quote
    /// but no double quote; backslash/quote/\n/\r/\t escaped explicitly; every char in Unicode
    /// categories Cc/Cf/Cs/Co/Cn/Zl/Zp/Zs except U+0020 escaped in lowercase hex — `\xNN` below
    /// 0x100, the 4-digit backslash-u form on the rest of the BMP, the 8-digit backslash-U form
    /// for astral chars, so NBSP → `\xa0`, C1 → `\x85`, U+200B → backslash-u200b, U+E0041 →
    /// backslash-U000e0041; printable non-ASCII (incl. astral emoji) stays literal.
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

    /// The MD5 of the UTF-8 bytes, all 32 hex digits, lowercase. Callers truncate to [#ID_HEX] and
    /// keep the untruncated digest to tell a collision from the same pattern seen twice.
    private static String md5Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * digest.length);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
