package toolkit.transform.epip;

// The EPIP interchange cluster: the interchange generator and the interchange-point repair.
//
// A meeting's 8-way ref choice binds to four typed fields, two journey refs and two
// point-in-pattern refs. The model declares no service-journey or stop-point-in-pattern spellings
// at all, so a source that used them would be unrepresentable: the loader drops the unknown
// element and the meeting is skipped as ref-less. No such spelling occurs in the data.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.FareScheduledStopPointRefStructure;
import noi.netex.model.JourneyMeeting;
import noi.netex.model.JourneyPatternRefStructure;
import noi.netex.model.JourneyPattern_VersionStructure;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.codec.FragmentCodec;
import toolkit.codec.FragmentPeek;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.NetexUtils;
import toolkit.model.RefElementIndex;
import toolkit.store.IdRow;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class EpipInterchange {

    private EpipInterchange() {}

    /// The element names a ServiceJourney's `journeyPatternRef` can be written with —
    /// `JourneyPatternRef` and its substitution group, which is exactly what an
    /// `@XmlElementRef(name = "JourneyPatternRef")` field accepts (JourneyPatternRef,
    /// ServiceJourneyPatternRef, DeadRunJourneyPatternRef, ServicePatternRef).
    private static final Set<String> JOURNEY_PATTERN_REF =
            RefElementIndex.substitutionGroup("JourneyPatternRef");

    /// -Dtoolkit.epip.peekJourneyPattern=false reverts to unmarshalling the whole ServiceJourney
    /// to read its pattern ref. Either way the repair must produce exactly the same interchanges.
    private static final boolean PEEK_JOURNEY_PATTERN =
            Conf.on(Conf.EPIP_PEEK_JOURNEY_PATTERN);

    /// "the journey has no JourneyPatternRef element at all" — distinct from a null @ref.
    private static final String NO_PATTERN = new String("\0no-journey-pattern-ref");

    /// -Dtoolkit.epip.peekInterchangeIndex=false reverts the index pass to unmarshalling every
    /// journey to read its id and pattern ref. Read per generator construction rather than at class
    /// init, so an identity arm can run both paths in one JVM.
    private static boolean peekInterchangeIndex() {
        return Conf.on(Conf.EPIP_PEEK_INTERCHANGE_INDEX);
    }

    /// -Dtoolkit.epip.idIndexJourneys=false reverts the index pass to reading every stored journey
    /// blob even when the pattern map is empty. Read per generator construction, so an identity arm
    /// can run every route in one JVM.
    private static boolean idIndexJourneys() {
        return Conf.on(Conf.EPIP_ID_INDEX_JOURNEYS);
    }

    /// Diagnostic only: `-Dtoolkit.epip.idIndexJourneys.force=true` takes the id-index route with
    /// the guard removed. It then produces a wrong answer by construction — the pattern map stays
    /// empty, so every side falls back to the meeting's own stop-point ref.
    private static boolean idIndexJourneysForced() {
        return Conf.on(Conf.EPIP_ID_INDEX_JOURNEYS_FORCE);
    }

    /// `-Dtoolkit.epip.idIndexJourneys.verify=true` builds the journey set BOTH ways and fails
    /// loudly on the first id the two disagree about, naming the id and the direction. It does the
    /// index walk and the whole peek pass, so it is slower than either path alone.
    private static boolean idIndexJourneysVerify() {
        return Conf.on(Conf.EPIP_ID_INDEX_JOURNEYS_VERIFY);
    }

    // ---------------------------------------------------- the service-journey interchanges

    /// Project every JourneyMeeting whose two journeys are both present into a stay-seated
    /// interchange, resolving each side's point to the ScheduledStopPoint that journey actually
    /// passes at the meeting station — matched through the stop assignments, NOT by parsing ids —
    /// and falling back to the meeting's shared stop-point ref when it cannot be resolved.
    ///
    /// Lazy: the index passes run on the first pull, each cursor fully drained before the next
    /// opens, and then the meetings stream.
    public static Iterator<Object> epipServiceJourneyInterchange(
            Store dbRead, Txn txn, GeneratorDefaults generatorDefaults) {
        return new Iterator<>() {
            Map<String, String> sspToStopPlace;
            Map<String, List<ScheduledStopPointRefStructure>> patternPoints;
            Set<String> presentJourneys;
            Map<String, String> journeyToPattern;
            Iterator<Object> meetings;
            Object ready;

            private void init() {
                Log.info("[epip] epip_service_journey_interchange");

                Map<String, StopPlace> stopPlaces = new LinkedHashMap<>();
                for (Object o : dbRead.iterOnlyObjects(txn, StopPlace.class)) {
                    StopPlace sp = (StopPlace) o;
                    stopPlaces.put(sp.getId(), sp);
                }
                // Quay id -> owning StopPlace id.
                Map<String, String> quayToStopPlace = new HashMap<>();
                for (StopPlace sp : stopPlaces.values()) {
                    if (sp.getQuays() == null) continue;
                    for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                        if (el.getValue() instanceof Quay q) quayToStopPlace.put(q.getId(), sp.getId());
                    }
                }
                sspToStopPlace = new HashMap<>();
                for (Object o : dbRead.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
                    PassengerStopAssignment sa = (PassengerStopAssignment) o;
                    // The ref half of each split choice; the embedded half carries no ref.
                    JAXBElement<? extends ScheduledStopPointRefStructure> sspEl = sa.getScheduledStopPointRef();
                    String sspRef = sspEl != null ? sspEl.getValue().getRef() : null;
                    if (sspRef == null) continue;
                    JAXBElement<? extends StopPlaceRefStructure> spEl = sa.getStopPlaceRef();
                    String spRef = spEl != null ? spEl.getValue().getRef() : null;
                    JAXBElement<? extends QuayRefStructure> qEl = sa.getQuayRef();
                    String qRef = qEl != null ? qEl.getValue().getRef() : null;
                    if (spRef != null) {
                        sspToStopPlace.put(sspRef, spRef);
                    } else if (qRef != null && quayToStopPlace.containsKey(qRef)) {
                        sspToStopPlace.put(sspRef, quayToStopPlace.get(qRef));
                    }
                }

                // ServiceJourneyPattern id -> its stops' ScheduledStopPointRef objects (in order)
                patternPoints = new HashMap<>();
                for (Object o : dbRead.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                    ServiceJourneyPattern pattern = (ServiceJourneyPattern) o;
                    var seq = pattern.getPointsInSequence();
                    List<PointInLinkSequence_VersionedChildStructure> pisList = seq != null
                            ? seq.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                            : List.of();
                    List<ScheduledStopPointRefStructure> points = new ArrayList<>();
                    for (PointInLinkSequence_VersionedChildStructure pis : pisList) {
                        // The ref accessor's sibling fare-ref is excluded here, unlike in the
                        // repair below.
                        if (!(pis instanceof StopPointInJourneyPattern spijp)) continue;
                        JAXBElement<? extends ScheduledStopPointRefStructure> el = spijp.getScheduledStopPointRef();
                        ScheduledStopPointRefStructure r = el != null ? el.getValue() : null;
                        if (r != null && !(r instanceof FareScheduledStopPointRefStructure)) points.add(r);
                    }
                    patternPoints.put(pattern.getId(), points);
                }

                // ServiceJourney id -> its pattern id, plus the set of journeys actually present.
                //
                // Three routes. The first is guarded on the pattern map being empty: there the map
                // is unreachable and the id set can come from the id index without opening a
                // single blob. Otherwise the pass still needs the pattern ref per journey, read off
                // the stored bytes: one StAX scan per row for the root's id and the first
                // pattern-ref-group child, no bean built and nothing kept.
                presentJourneys = new HashSet<>();
                journeyToPattern = new HashMap<>();
                // A correctness guard, not a performance one: with a non-empty pattern map this
                // route collapses every resolved stop point to the station aggregate. Do not open
                // it.
                if ((patternPoints.isEmpty() || idIndexJourneysForced()) && idIndexJourneys()) {
                    // With patternPoints empty this pass owes the loop one thing: the set of stored
                    // ServiceJourney ids. The pattern lookup returns an empty list for every key,
                    // so the inner map cannot reach an output, and the id index already holds that
                    // set — one cursor walk that decompresses nothing.
                    //
                    // A class purge deletes every index row whose full key names that class, so the
                    // filter below is on the full key's class rather than the encoded key's
                    // trailer.
                    int sjIdx = dbRead.classIdxInt(ServiceJourney.class);
                    for (IdRow row : dbRead.iterIdIndex(txn)) {
                        if (NulKeyCodec.fullKeyClassIdx(row.fullKey()) != sjIdx) continue;
                        presentJourneys.add(NulKeyCodec.idPart(row.encodedKey()));
                    }
                    if (idIndexJourneysVerify()) verifyIdIndexAgainstPeek(dbRead, txn, presentJourneys);
                } else if (peekInterchangeIndex()) {
                    // Per-iterator: neither a StAX factory nor a JAXB unmarshaller is thread-safe.
                    FragmentCodec codec = new FragmentCodec();
                    FragmentPeek peek = new FragmentPeek();
                    for (RawRow row : dbRead.iterRawObjects(txn, ServiceJourney.class)) {
                        FragmentPeek.RootChild rc = peek.rootIdAndFirstChildRef(
                                codec.decompress(row.blob()), JOURNEY_PATTERN_REF);
                        presentJourneys.add(rc.id());
                        // Presence of the element is the question, whatever its @ref, so a child
                        // with no ref attribute still maps the id to null.
                        if (rc.child() != null) journeyToPattern.put(rc.id(), rc.child().ref());
                    }
                } else {
                    for (Object o : dbRead.iterOnlyObjects(txn, ServiceJourney.class)) {
                        ServiceJourney sj = (ServiceJourney) o;
                        presentJourneys.add(sj.getId());
                        JAXBElement<? extends JourneyPatternRefStructure> jp = sj.getJourneyPatternRef();
                        if (jp != null) journeyToPattern.put(sj.getId(), jp.getValue().getRef());
                    }
                }

                meetings = dbRead.iterOnlyObjects(txn, JourneyMeeting.class).iterator();
            }

            /// The stop point this journey passes at the meeting station, matched by StopPlace.
            private ScheduledStopPointRefStructure pointFor(String journeyRef, String meetingStopPlace) {
                if (journeyRef == null || meetingStopPlace == null) return null;
                for (ScheduledStopPointRefStructure sspRef
                        : patternPoints.getOrDefault(journeyToPattern.getOrDefault(journeyRef, ""), List.of())) {
                    if (meetingStopPlace.equals(sspToStopPlace.get(sspRef.getRef()))) {
                        return pointRef(sspRef);
                    }
                }
                return null;
            }

            private void pump() {
                if (meetings == null) init();
                while (ready == null && meetings.hasNext()) {
                    JourneyMeeting jm = (JourneyMeeting) meetings.next();
                    // Typed on the base ref structure: the newer schema widens these two elements
                    // to the base and deprecates them, and only getRef() is read, which lives on
                    // the base at both versions.
                    JourneyRefStructure fromJourneyRef = jm.getFromJourneyRef();
                    JourneyRefStructure toJourneyRef = jm.getToJourneyRef();

                    // A meeting referencing a journey not in this extract is unusable — skip it
                    // instead of emitting a dangling interchange.
                    if (fromJourneyRef == null || toJourneyRef == null
                            || !presentJourneys.contains(fromJourneyRef.getRef())
                            || !presentJourneys.contains(toJourneyRef.getRef())) {
                        continue;
                    }

                    ScheduledStopPointRefStructure atPoint = jm.getAtStopPointRef();
                    String meetingStopPlace = atPoint != null ? sspToStopPlace.get(atPoint.getRef()) : null;
                    ScheduledStopPointRefStructure fromPoint = pointFor(fromJourneyRef.getRef(), meetingStopPlace);
                    if (fromPoint == null) fromPoint = atPoint;
                    ScheduledStopPointRefStructure toPoint = pointFor(toJourneyRef.getRef(), meetingStopPlace);
                    if (toPoint == null) toPoint = atPoint;

                    Map<String, Object> overrides = new HashMap<>();   // null values allowed
                    overrides.put("fromPointRef", fromPoint);
                    overrides.put("toPointRef", toPoint);
                    ServiceJourneyInterchange sji =
                            NetexUtils.project(jm, ServiceJourneyInterchange.class, overrides);
                    // A meeting maps a through-service constraint, so the constraint flags are
                    // asserted — but not Guaranteed, which would promise a wait it does not.
                    sji.setStaySeated(Boolean.TRUE);
                    sji.setPlanned(Boolean.TRUE);
                    sji.setAdvertised(Boolean.TRUE);
                    // A derived name would come out "FromJourney"/"ToJourney", which is not a
                    // class. The projection copies the flattened refs by alias, and these fresh
                    // copies replace them.
                    sji.setFromJourneyRef(journeyRef(fromJourneyRef));
                    sji.setToJourneyRef(journeyRef(toJourneyRef));
                    ready = sji;
                }
            }

            @Override
            public boolean hasNext() {
                pump();
                return ready != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object out = ready;
                ready = null;
                return out;
            }
        };
    }

    /// The verify arm for the id-index route's set: rebuild it the other way and fail on the first
    /// id the two routes disagree about.
    ///
    /// Both subtractions are taken: a missing id drops interchanges and shows up in any output
    /// digest, while an extra id does not, on a store that drops no meeting.
    ///
    /// - peek-only — a stored row with no index row behind it. Detected in class-map cursor order,
    ///    so the id named is deterministic.
    /// - index-only — an index row naming a journey the class map does not hold. Detected in
    ///    hash-set order, so which id is named is unspecified when there are several.
    private static void verifyIdIndexAgainstPeek(Store dbRead, Txn txn, Set<String> fromIndex) {
        long t0 = System.nanoTime();
        FragmentCodec codec = new FragmentCodec();
        FragmentPeek peek = new FragmentPeek();
        Set<String> fromPeek = new HashSet<>();
        for (RawRow row : dbRead.iterRawObjects(txn, ServiceJourney.class)) {
            String id = peek.rootIdAndFirstChildRef(codec.decompress(row.blob()), JOURNEY_PATTERN_REF).id();
            fromPeek.add(id);
            if (!fromIndex.contains(id)) {
                throw new IllegalStateException("[epip] ID-INDEX MISMATCH, direction PEEK-ONLY: id "
                        + id + " is a stored ServiceJourney row with no _id_idx row behind it — "
                        + "the interchange generator's _id_idx route would DROP every meeting on it. "
                        + "The class map and the index are expected to agree; on this store they "
                        + "do not.");
            }
        }
        for (String id : fromIndex) {
            if (!fromPeek.contains(id)) {
                throw new IllegalStateException("[epip] ID-INDEX MISMATCH, direction INDEX-ONLY: id "
                        + id + " came out of the _id_idx walk as a ServiceJourney but has no row in "
                        + "the ServiceJourney class map — the SUPERSET direction, which no output digest "
                        + "on a store that drops no meeting can see. "
                        + "This is not expected to happen.");
            }
        }
        Log.info("[epip] idIndexJourneys VERIFY: %d ids from _id_idx, %d from the peek route, "
                        + "0 index-only, 0 peek-only, in %.2f s",
                fromIndex.size(), fromPeek.size(), (System.nanoTime() - t0) / 1e9);
    }

    // ------------------------------------------------------- repairing interchange points

    /// Repair ServiceJourneyInterchange point refs that are not on the referenced journey's
    /// pattern. When the referenced SSP is absent from the journey's pattern but the pattern has
    /// exactly one stop assigned to the same StopPlace, rewrite the ref to that stop's SSP; a
    /// cross-border interchange with one side off-pattern is re-anchored at the valid endpoint's
    /// station (or, failing that, both endpoints at the feeder leg's last shared station);
    /// anything else is left untouched. Must run after the journey transform, when every journey
    /// carries a pattern. Yields only the changed interchanges; the summary log line fires on
    /// exhaustion.
    public static Iterator<Object> epipRepairInterchangePoints(
            Store dbRead, Txn txn, GeneratorDefaults generatorDefaults) {
        return new Iterator<>() {
            Map<String, String> sspToStop;
            final FragmentCodec codec = new FragmentCodec();
            // Per-iterator: neither a StAX factory nor a JAXB unmarshaller is thread-safe.
            final FragmentPeek peek = new FragmentPeek();
            final Map<String, Map<String, ScheduledStopPointRefStructure>> patCache = new HashMap<>();
            long repaired, handover, unrepairable, unresolved;
            Iterator<Object> sjis;
            Object ready;
            boolean logged;

            private void init() {
                sspToStop = new HashMap<>();
                for (Object o : dbRead.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
                    PassengerStopAssignment psa = (PassengerStopAssignment) o;
                    // The choice values: an embedded stop point or stop place carries no ref and
                    // is skipped.
                    JAXBElement<? extends ScheduledStopPointRefStructure> sspEl = psa.getScheduledStopPointRef();
                    JAXBElement<? extends StopPlaceRefStructure> stopEl = psa.getStopPlaceRef();
                    String sspRef = sspEl != null ? sspEl.getValue().getRef() : null;
                    if (sspRef != null && !sspRef.isEmpty() && stopEl != null) {
                        sspToStop.put(sspRef, stopEl.getValue().getRef());
                    }
                }
                sjis = dbRead.iterOnlyObjects(txn, ServiceJourneyInterchange.class).iterator();
            }

            /// The journey's pattern ref, or [#NO_PATTERN] when the row is not a ServiceJourney or
            /// carries no pattern-ref element at all — which is not the same as an element whose
            /// `@ref` is absent.
            ///
            /// Read off the stored bytes: the field is an `@XmlElementRef`, so the element is the
            /// head or one of its substitution group as a DIRECT child of the root, and the scan
            /// stops there.
            private String journeyPatternRef(long sjKey) {
                if (!PEEK_JOURNEY_PATTERN) {
                    Object sjObj = dbRead.loadObjectByFullKey(txn, sjKey);
                    JAXBElement<? extends JourneyPatternRefStructure> pref =
                            sjObj instanceof ServiceJourney sj ? sj.getJourneyPatternRef() : null;
                    return pref == null ? NO_PATTERN : pref.getValue().getRef();
                }
                Class<?> sjClass = dbRead.classForIdx(NulKeyCodec.fullKeyClassIdx(sjKey));
                if (sjClass == null || !ServiceJourney.class.isAssignableFrom(sjClass)) return NO_PATTERN;
                byte[] blob = dbRead.loadRawObject(txn, sjClass,
                        NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(sjKey)));
                if (blob == null) return NO_PATTERN; // loadObjectByFullKey would return null too
                FragmentPeek.ChildRef pref =
                        peek.firstChildRef(codec.decompress(blob), JOURNEY_PATTERN_REF);
                return pref == null ? NO_PATTERN : pref.ref();
            }

            /// The journey's pattern stops as an ordered first-wins map from stop-point id to ref
            /// object; null when the journey or its pattern ref is unresolvable.
            private Map<String, ScheduledStopPointRefStructure> journeyPoints(String sjId) {
                // Keyless load: the first id-index row at the NUL-terminated id prefix, whatever
                // its class; a same-id row of a foreign class yields null here.
                long sjKey = dbRead.lookupFullKey(txn, sjId, null, ServiceJourney.class);
                if (sjKey == -1) return null;
                String patId = journeyPatternRef(sjKey);
                if (patId == NO_PATTERN) return null;
                if (!patCache.containsKey(patId)) {
                    long patKey = dbRead.lookupFullKey(txn, patId, null, ServiceJourneyPattern.class);
                    Object patObj = patKey != -1 ? dbRead.loadObjectByFullKey(txn, patKey) : null;
                    Map<String, ScheduledStopPointRefStructure> refs = null;
                    if (patObj instanceof JourneyPattern_VersionStructure pat
                            && pat.getPointsInSequence() != null) {
                        refs = new LinkedHashMap<>();
                        for (PointInLinkSequence_VersionedChildStructure p : pat.getPointsInSequence()
                                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
                            // Only StopPointInJourneyPattern carries the field, and no fare-ref
                            // filter here, unlike the generator above.
                            ScheduledStopPointRefStructure r = null;
                            if (p instanceof StopPointInJourneyPattern spijp
                                    && spijp.getScheduledStopPointRef() != null) {
                                r = spijp.getScheduledStopPointRef().getValue();
                            }
                            if (r != null && !refs.containsKey(r.getRef())) refs.put(r.getRef(), r);
                        }
                    }
                    patCache.put(patId, refs);
                }
                return patCache.get(patId);
            }

            private void pump() {
                if (sjis == null) init();
                while (ready == null && sjis.hasNext()) {
                    ServiceJourneyInterchange sji = (ServiceJourneyInterchange) sjis.next();
                    // From/To discrimination, flattened to the two typed fields.
                    String fromJourney = sji.getFromJourneyRef() != null ? sji.getFromJourneyRef().getRef() : null;
                    String toJourney = sji.getToJourneyRef() != null ? sji.getToJourneyRef().getRef() : null;
                    Map<String, ScheduledStopPointRefStructure> fromPts =
                            fromJourney != null ? journeyPoints(fromJourney) : null;
                    Map<String, ScheduledStopPointRefStructure> toPts =
                            toJourney != null ? journeyPoints(toJourney) : null;

                    boolean changed = false;
                    List<String> offSides = new ArrayList<>();
                    for (String side : new String[] {"from", "to"}) {
                        boolean isFrom = side.equals("from");
                        ScheduledStopPointRefStructure point = isFrom ? sji.getFromPointRef() : sji.getToPointRef();
                        Map<String, ScheduledStopPointRefStructure> points = isFrom ? fromPts : toPts;
                        if (point == null || (isFrom ? fromJourney : toJourney) == null) continue;
                        if (points == null) {
                            unresolved++;
                            continue;
                        }
                        if (points.containsKey(point.getRef())) continue;
                        // Same-station remap: the referenced SSP is not on the pattern but the
                        // pattern has exactly one stop at the same StopPlace.
                        String stop = sspToStop.get(point.getRef());
                        List<ScheduledStopPointRefStructure> candidates = new ArrayList<>();
                        for (Map.Entry<String, ScheduledStopPointRefStructure> e : points.entrySet()) {
                            if (stop != null && stop.equals(sspToStop.get(e.getKey()))) candidates.add(e.getValue());
                        }
                        if (candidates.size() == 1) {
                            setPoint(sji, side, candidates.get(0));
                            repaired++;
                            changed = true;
                        } else {
                            offSides.add(side);
                        }
                    }

                    // Shared-station handover fallback, cross-border only: each ref ends up in its
                    // own journey's coding.
                    if (!offSides.isEmpty() && Boolean.TRUE.equals(sji.isCrossBorder())
                            && fromPts != null && toPts != null) {
                        boolean repairedSide = false;
                        if (offSides.size() == 1) {
                            String off = offSides.get(0);
                            boolean offFrom = off.equals("from");
                            ScheduledStopPointRefStructure validPoint =
                                    offFrom ? sji.getToPointRef() : sji.getFromPointRef();
                            String validStation = validPoint != null ? sspToStop.get(validPoint.getRef()) : null;
                            if (validStation != null) {
                                Map<String, ScheduledStopPointRefStructure> offPts = offFrom ? fromPts : toPts;
                                List<ScheduledStopPointRefStructure> cands = new ArrayList<>();
                                for (Map.Entry<String, ScheduledStopPointRefStructure> e : offPts.entrySet()) {
                                    if (validStation.equals(sspToStop.get(e.getKey()))) cands.add(e.getValue());
                                }
                                if (!cands.isEmpty()) {
                                    setPoint(sji, off, offFrom ? cands.get(cands.size() - 1) : cands.get(0));
                                    repaired++;
                                    changed = true;
                                    repairedSide = true;
                                }
                            }
                        }
                        if (!repairedSide) {
                            Set<String> toStations = new HashSet<>();
                            for (String ref : toPts.keySet()) {
                                String s = sspToStop.get(ref);
                                if (s != null) toStations.add(s);
                            }
                            String sharedFromRef = null;
                            String station = null;
                            for (String ref : fromPts.keySet()) {       // last shared one wins
                                String s = sspToStop.get(ref);
                                if (s != null && toStations.contains(s)) {
                                    sharedFromRef = ref;
                                    station = s;
                                }
                            }
                            if (sharedFromRef != null) {
                                String sharedToRef = null;
                                for (String ref : toPts.keySet()) {     // first match wins
                                    if (station.equals(sspToStop.get(ref))) {
                                        sharedToRef = ref;
                                        break;
                                    }
                                }
                                setPoint(sji, "from", fromPts.get(sharedFromRef));
                                setPoint(sji, "to", toPts.get(sharedToRef));
                                handover++;
                                changed = true;
                            } else {
                                unrepairable++;
                            }
                        }
                    } else if (!offSides.isEmpty()) {
                        unrepairable++;
                    }

                    if (changed) ready = sji;
                }
                if (ready == null && !logged && sjis != null) {
                    logged = true;
                    Log.info("[epip] repair_interchange_points: rewrote %d point refs onto "
                            + "the referenced journey's pattern, re-anchored %d cross-border "
                            + "interchanges at a shared handover station "
                            + "(%d left as-is: no shared/same-station stop; "
                            + "%d journey/pattern refs unresolved)",
                            repaired, handover, unrepairable, unresolved);
                }
            }

            @Override
            public boolean hasNext() {
                pump();
                return ready != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object out = ready;
                ready = null;
                return out;
            }
        };
    }

    // ---------------------------------------------------------------------------- ref helpers

    /// A fresh two-attribute point ref. The model has no element subclass for it, so the
    /// structure is used and no xsi:type is marshalled.
    private static void setPoint(ServiceJourneyInterchange sji, String side,
                                 ScheduledStopPointRefStructure sspRef) {
        ScheduledStopPointRefStructure fresh = new ScheduledStopPointRefStructure();
        fresh.setRef(sspRef.getRef());
        fresh.setVersion(sspRef.getVersion());
        if (side.equals("from")) sji.setFromPointRef(fresh);
        else sji.setToPointRef(fresh);
    }

    /// A fresh point ref copying the source's attributes. `nameOfRefClass` is not set: the derived
    /// name lands in a fixed single-value enum that the serializer suppresses as a schema default,
    /// so the wire never carries it.
    private static ScheduledStopPointRefStructure pointRef(ScheduledStopPointRefStructure src) {
        ScheduledStopPointRefStructure ref = new ScheduledStopPointRefStructure();
        copyRefAttributes(src, ref);
        return ref;
    }

    /// A fresh journey ref with `nameOfRefClass` set explicitly to `"ServiceJourney"`: without the
    /// correct name a downstream consumer cannot resolve the ref.
    ///
    /// Constructed as the declared base type. The newer schema widens these elements to the base,
    /// so a narrower value would become a subtype of the declared type and JAXB would stamp
    /// `xsi:type` on every one of them.
    ///
    /// This does not compile at the older schema version, where the setter takes the narrower type.
    private static JourneyRefStructure journeyRef(JourneyRefStructure src) {
        JourneyRefStructure ref = new JourneyRefStructure();
        copyRefAttributes(src, ref);
        ref.setNameOfRefClass("ServiceJourney");
        return ref;
    }

    /// The version-of-object attributes a projected ref copies. `nameOfRefClass` is handled per
    /// call site above.
    private static void copyRefAttributes(VersionOfObjectRefStructure src, VersionOfObjectRefStructure dst) {
        dst.setValue(src.getValue());
        // Pure copies: the timestamps are carriers assigned straight across, so each keeps its
        // source lexical — offset, milliseconds and all.
        dst.setCreated(src.getCreated());
        dst.setChanged(src.getChanged());
        dst.setVersion(src.getVersion());
        dst.setModification(src.getModification());
        dst.setRef(src.getRef());
        dst.setVersionRef(src.getVersionRef());
    }
}
