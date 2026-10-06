package toolkit.transform.common;

// The three transforms a pipeline driver composes, plus stopplaceCoord:
//
//   synthesizeStopPlacesFromSsps        StopPlace + PassengerStopAssignment for every unassigned
//                                       locatable ScheduledStopPoint (SSP-only feeds)
//   backfillStopplaceCentroidsFromQuays lift a child Quay's Location onto a centroid-less
//                                       StopPlace, so OTP does not see a station without
//                                       coordinates
//   planStopplaceMerge                  merge co-located same-name duplicate StopPlaces, the
//                                       survivor typed by the heaviest mode in its cluster

import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.LocationStructure;
import noi.netex.text.Mls;
import noi.netex.model.MultilingualString;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PrivateCodeStructure;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import jakarta.xml.bind.JAXBElement;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ScheduledStopPoints {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// Same-physical-stop duplicates sit within this radius.
    ///
    /// 30 m, measured over the whole Italian corpus (330,288 StopPlaces from 301 operator feeds, one
    /// pooled pass):
    ///
    /// | radius | dropped | p99 diameter | max | clusters > 100 m |
    /// |--------|---------|--------------|-----|------------------|
    /// | 15 m   |  81,596 |  11.3        |  30.0 | 0 |
    /// | 30 m   |  96,951 |  29.8        |  73.7 | 0 |
    /// | 50 m   | 107,114 |  49.8        | 204.9 | 17 |
    /// | 100 m  | 115,061 |  98.2        | 305.0 | 336 |
    ///
    /// Up to 30 m the union-find barely chains: p99 diameter tracks the radius almost exactly and no
    /// cluster exceeds 100 m. Past 40 m it runs away. A StopPlace is a station and OTP treats its
    /// children as in-station transfers, so a 305 m one makes a five-minute walk look free; the
    /// survivor keeps its own centroid rather than the cluster's, which at 100 m puts the station up
    /// to 299.6 m from its own quays.
    ///
    /// Re-measure before moving this. The worst offenders at wide radii are car-sharing pseudo-stops
    /// and dense urban networks, which share one name across a whole city.
    private static final double MERGE_RADIUS_M = 30.0;
    private static final double M_PER_DEG_LAT = 111320.0; // the figure the distance test uses

    /// The grid cell a `radiusM` probe needs at `maxAbsLat`, in degrees.
    ///
    /// Cells are indexed by `Math.round(deg / cell)`, so two points whose coordinates differ by `d`
    /// degrees sit at most `floor(d/cell) + 1` cells apart — which the 3x3 neighbourhood covers only
    /// while `d < cell`. The binding axis is longitude, the narrow one: a degree of it is
    /// `111320*cos(lat)` m against latitude's flat `111320`, so `d_lon = radiusM/(111320*cos(lat))`
    /// and the cell has to exceed that at the highest latitude in the data. `MARGIN` buys the strict
    /// inequality; beyond that, too large only costs candidates while too small silently loses
    /// pairs — a missed merge is indistinguishable from data that had nothing to merge.
    private static double mergeCellDeg(double radiusM, double maxAbsLat) {
        final double MARGIN = 1.25;
        double lat = Math.min(Math.abs(maxAbsLat), 80.0); // guard cos -> 0; no transit data up there
        return MARGIN * radiusM / (M_PER_DEG_LAT * Math.cos(Math.toRadians(lat)));
    }

    private ScheduledStopPoints() {}

    /// What [#planStopplaceMerge] returns: kept, dropped, repointed.
    public record MergePlan(Map<String, StopPlace> kept, Set<String> dropped,
                            Map<String, PassengerStopAssignment> repointed) {}

    // ---------------------------------------------------------------- small helpers

    /// The text of a MultilingualString, '' if none.
    static String mlText(MultilingualString n) {
        return Mls.textOrEmpty(n);
    }

    /// Accent/punctuation/case-folded StopPlace name for equality; '' if none.
    static String spName(StopPlace sp) {
        return normName(sp.getName());
    }

    /// The same folding for a Quay, so the quay arm compares names the way the StopPlace arm does.
    /// Separators are stripped, not collapsed to a space: "St. Anton" folds to `stanton`.
    static String quayName(Quay q) {
        return normName(q.getName());
    }

    private static String normName(MultilingualString name) {
        String v = mlText(name);
        if (v.isEmpty()) return "";
        String d = Normalizer.normalize(v, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c < 128) sb.append(Character.toLowerCase(c));
        }
        return sb.toString().replaceAll("[^a-z0-9]+", "");
    }

    /// The centroid as {lat, lon}, or null (Latitude/Longitude else gml:pos).
    public static double[] stopplaceCoord(StopPlace sp) {
        return centroidCoord(sp.getCentroid());
    }

    /// The same, for a Quay.
    public static double[] quayCoord(Quay q) {
        return centroidCoord(q.getCentroid());
    }

    private static double[] centroidCoord(SimplePoint_VersionStructure c) {
        LocationStructure loc = c != null ? c.getLocation() : null;
        if (loc == null) return null;
        if (loc.getLatitude() != null && loc.getLongitude() != null) {
            return new double[] {loc.getLatitude().doubleValue(), loc.getLongitude().doubleValue()};
        }
        if (loc.getPos() != null && loc.getPos().getValue() != null && loc.getPos().getValue().size() >= 2) {
            return new double[] {loc.getPos().getValue().get(0), loc.getPos().getValue().get(1)};
        }
        return null;
    }

    private static String version(Object o) {
        return o instanceof EntityInVersionStructure e ? e.getVersion() : null;
    }

    private static String orOne(String v) {
        return v == null || v.isEmpty() ? "1" : v;
    }

    // ---------------------------------------------------------------- 1: SSP synthesis

    /// Feed-specific knobs for the synthesis: an extra per-SSP include guard, an optional
    /// PrivateCode for each synthesised StopPlace, and the completion log line (one `%d` slot for
    /// the count).
    public record SspSynthesis(Predicate<ScheduledStopPoint> include,
                               Function<ScheduledStopPoint, PrivateCodeStructure> privateCode,
                               String logLine) {}

    /// A StopPlace + PassengerStopAssignment for every locatable ScheduledStopPoint not already
    /// bound by a PSA.
    public static List<Object> synthesizeStopPlacesFromSsps(Store db, Txn txn) {
        return synthesizeStopPlacesFromSsps(db, txn, new SspSynthesis(
                ssp -> ssp.getId() != null,
                ssp -> null,
                "[synthesize_stop_places_from_ssps] synthesised %d StopPlaces + PSAs "
                        + "from unassigned locatable ScheduledStopPoints"));
    }

    /// The parameterized body shared by the generic transform above and the feed-specific variants.
    public static List<Object> synthesizeStopPlacesFromSsps(Store db, Txn txn, SspSynthesis opts) {
        Set<String> assigned = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            assigned.addAll(assignedSspRef((PassengerStopAssignment) o));
        }
        Set<String> existingStopPlaces = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            String id = ((StopPlace) o).getId();
            if (id != null) existingStopPlaces.add(id);
        }
        List<ScheduledStopPoint> ssps = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, ScheduledStopPoint.class)) ssps.add((ScheduledStopPoint) o);
        return synthesizeStopPlaces(ssps, assigned, existingStopPlaces, opts);
    }

    /// The ScheduledStopPointRef a PSA binds, as a 0-or-1 set.
    public static Set<String> assignedSspRef(PassengerStopAssignment psa) {
        JAXBElement<? extends ScheduledStopPointRefStructure> refEl = psa.getScheduledStopPointRef();
        ScheduledStopPointRefStructure ref = refEl == null ? null : refEl.getValue();
        return ref != null && ref.getRef() != null && !ref.getRef().isEmpty()
                ? Set.of(ref.getRef()) : Set.of();
    }

    /// The synthesis over collections rather than a store, for a caller holding the corpus in memory
    /// with nothing written yet. Reading `assigned` and `existingStopPlaces` out of a store there
    /// would see neither, and synthesise a duplicate for every already-bound SSP in the country.
    ///
    /// Mints, per locatable ScheduledStopPoint not already bound by a PSA:
    ///
    ///  - a StopPlace, unless one of that id already exists (a feed that models its own stops keeps
    ///    them; we cannot know its quay set does not already cover this SSP);
    ///  - a Quay at the SSP's own position, but only alongside a StopPlace we minted — see above;
    ///  - a PassengerStopAssignment carrying both a StopPlaceRef and, when we minted a Quay, a
    ///    QuayRef.
    ///
    /// The QuayRef is not optional. OTP files a PSA under the quay index or the stop-place index but
    /// never both, and a StopPlace holding two quays without per-PSA QuayRefs fails its "exactly one
    /// child stop" test — so it falls back to one synthetic stop at the station centroid and the
    /// quays become orphans carrying no service.
    public static List<Object> synthesizeStopPlaces(Iterable<ScheduledStopPoint> ssps,
                                                    Set<String> assigned,
                                                    Set<String> existingStopPlaces,
                                                    SspSynthesis opts) {
        List<Object> out = new ArrayList<>();
        // An SSP held at two versions is two rows of one id, and both derive the same StopPlace,
        // Quay and PSA id. Minting twice would put two same-id Quays in two containers, which the
        // embedding index resolves by container scan order -- i.e. arbitrarily.
        Set<String> minted = new HashSet<>();
        long synthesised = 0;
        for (ScheduledStopPoint ssp : ssps) {
            if (!opts.include().test(ssp) || ssp.getLocation() == null || assigned.contains(ssp.getId())) continue;
            if (!minted.add(ssp.getId())) continue;
            String spId = ssp.getId().replace(":ScheduledStopPoint:", ":StopPlace:");
            String quayId = null;
            if (!existingStopPlaces.contains(spId)) {
                StopPlace sp = new StopPlace();
                sp.setId(spId);
                sp.setVersion(orOne(ssp.getVersion()));
                sp.setName(ssp.getName());
                sp.setCentroid(new SimplePoint_VersionStructure().withLocation(ssp.getLocation()));
                sp.setPrivateCode(opts.privateCode().apply(ssp));

                quayId = ssp.getId().replace(":ScheduledStopPoint:", ":Quay:");
                Quay quay = new Quay();
                quay.setId(quayId);
                quay.setVersion(orOne(ssp.getVersion()));
                quay.setName(ssp.getName());
                // A detached Location: planStopplaceMerge mutates survivors in place, and the
                // StopPlace centroid above already aliases the SSP's own instance.
                quay.setCentroid(new SimplePoint_VersionStructure()
                        .withLocation(copyLocation(ssp.getLocation())));
                // No PublicCode: it drives OTP's platformCode and the bus-quay merge's code test.
                Quays_RelStructure quays = new Quays_RelStructure();
                quays.getQuayRefOrQuay().add(FACTORY.createQuay(quay));
                sp.setQuays(quays);
                out.add(sp);
            }
            PassengerStopAssignment psa = new PassengerStopAssignment();
            psa.setId(ssp.getId().replace(":ScheduledStopPoint:", ":PassengerStopAssignment:"));
            psa.setVersion(orOne(ssp.getVersion()));
            psa.setOrder(BigInteger.ONE);
            ScheduledStopPointRefStructure sspRef = new ScheduledStopPointRefStructure();
            sspRef.setRef(ssp.getId());
            sspRef.setVersion(ssp.getVersion());
            psa.setScheduledStopPointRef(FACTORY.createScheduledStopPointRef(sspRef));
            StopPlaceRefStructure spRef = new StopPlaceRefStructure();
            spRef.setRef(spId);
            spRef.setVersion(ssp.getVersion());
            psa.setStopPlaceRef(FACTORY.createStopPlaceRef(spRef));
            if (quayId != null) {
                QuayRefStructure qRef = new QuayRefStructure();
                qRef.setRef(quayId);
                qRef.setVersion(orOne(ssp.getVersion()));
                psa.setQuayRef(FACTORY.createQuayRef(qRef));
            }
            out.add(psa);
            synthesised++;
        }
        Log.info(opts.logLine(), synthesised);
        return out;
    }

    /// Two boarding positions this close, under one station and one name, are one pole published
    /// twice rather than two poles.
    ///
    /// Far tighter than the StopPlace radius: a Quay is where the passenger stands, and the two
    /// directions of a street stop sit 10-25 m apart across the carriageway. Folding at the station
    /// radius would merge those two directions into one boarding point and break direction-specific
    /// transfers.
    private static final double QUAY_FOLD_RADIUS_M = 2.0;

    /// Collapse quays in `quays` that share a normalised name and sit within [#QUAY_FOLD_RADIUS_M],
    /// keeping the smallest id of each group and recording the rest in `remap`.
    ///
    /// Mutates `quays` in place. Only embedded quays are considered: a bare QuayRef carries no
    /// position to compare and is passed through untouched.
    private static void foldCoincidentQuays(List<JAXBElement<?>> quays, Map<String, String> remap) {
        if (quays.size() < 2) return;
        List<Quay> embedded = new ArrayList<>();
        for (JAXBElement<?> el : quays) {
            if (el.getValue() instanceof Quay q && q.getId() != null && quayCoord(q) != null) embedded.add(q);
        }
        if (embedded.size() < 2) return;

        // Group first, then elect: electing pairwise lets a quay that is itself folded away become
        // another quay's recorded survivor (C -> A while A -> B), and the chain is never resolved,
        // so C's referrers end up naming a quay that was removed.
        //
        // Membership is "within the radius of the anchor", not a transitive closure, which bounds a
        // group's diameter at 4 m instead of letting it chain across a plaza.
        Map<String, String> local = new HashMap<>();      // folded id -> survivor id
        boolean[] taken = new boolean[embedded.size()];
        for (int i = 0; i < embedded.size(); i++) {
            if (taken[i]) continue;
            Quay a = embedded.get(i);
            taken[i] = true;
            double[] ca = quayCoord(a);
            String na = quayName(a);
            List<Quay> group = new ArrayList<>();
            group.add(a);
            for (int j = i + 1; j < embedded.size(); j++) {
                if (taken[j]) continue;
                Quay b = embedded.get(j);
                if (!na.equals(quayName(b))) continue;
                double[] cb = quayCoord(b);
                double dlat = (ca[0] - cb[0]) * M_PER_DEG_LAT;
                double dlon = (ca[1] - cb[1]) * M_PER_DEG_LAT * Math.cos(Math.toRadians(ca[0]));
                if (dlat * dlat + dlon * dlon > QUAY_FOLD_RADIUS_M * QUAY_FOLD_RADIUS_M) continue;
                group.add(b);
                taken[j] = true;
            }
            if (group.size() < 2) continue;
            // Smallest id wins, so the choice does not depend on iteration order.
            String keep = group.get(0).getId();
            for (Quay q : group) if (q.getId().compareTo(keep) < 0) keep = q.getId();
            for (Quay q : group) if (!q.getId().equals(keep)) local.put(q.getId(), keep);
        }
        if (local.isEmpty()) return;
        remap.putAll(local);
        quays.removeIf(el -> el.getValue() instanceof Quay q && local.containsKey(q.getId()));
    }

    /// Append `sp`'s quays to `into`, skipping ids already in `seen`. Both arms of the union carry
    /// an id — an embedded `Quay` its own, a `QuayRefStructure` its `ref` — and an element with
    /// neither is passed through rather than silently dropped.
    private static void addQuays(List<JAXBElement<?>> into, Set<String> seen, StopPlace sp) {
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            String id = el.getValue() instanceof Quay q ? q.getId()
                    : el.getValue() instanceof QuayRefStructure r ? r.getRef() : null;
            if (id != null && !seen.add(id)) continue;
            into.add(el);
        }
    }

    /// A detached copy of a Location. `BigDecimal` is immutable so the scalars are shared; the `pos`
    /// list is shared too, and must not be mutated in place.
    private static LocationStructure copyLocation(LocationStructure src) {
        if (src == null) return null;
        LocationStructure c = new LocationStructure();
        c.setLatitude(src.getLatitude());
        c.setLongitude(src.getLongitude());
        c.setAltitude(src.getAltitude());
        c.setPrecision(src.getPrecision());
        c.setSrsName(src.getSrsName());
        c.setPos(src.getPos());
        return c;
    }

    /// Give a Quay to a ScheduledStopPoint the feed already assigned, when the StopPlace it was
    /// assigned to has none.
    ///
    /// The synthesis above leaves a feed-published StopPlace alone, on the argument that a feed
    /// modelling its own stops may model quays we cannot see. On the Italian corpus 41,364 of them
    /// carry no quays at all and not one of the assignments naming them carries a QuayRef: a station
    /// with no boarding position, which OTP papers over by inventing a stop at the centroid.
    ///
    /// A quay is minted per assignment, not per StopPlace: two ScheduledStopPoints on one quay-less
    /// StopPlace are two boarding positions, and each assignment ends up naming its own. Any that
    /// turn out to coincide are collapsed by the fold in [#planStopplaceMerge].
    ///
    /// `codespaceAllowed` gates it by StopPlace id. Mutates `stops` and `psas` in place; returns the
    /// StopPlaces that changed.
    public static List<StopPlace> backfillQuaysForAssignedSsps(
            java.util.Collection<StopPlace> stops,
            java.util.Collection<PassengerStopAssignment> psas,
            Iterable<ScheduledStopPoint> ssps,
            Predicate<String> codespaceAllowed) {
        Map<String, LocationStructure> sspLoc = new HashMap<>();
        for (ScheduledStopPoint ssp : ssps) {
            if (ssp.getId() != null && ssp.getLocation() != null) {
                sspLoc.putIfAbsent(ssp.getId(), ssp.getLocation());
            }
        }
        Map<String, List<StopPlace>> byId = new HashMap<>();
        Set<String> hadQuay = new HashSet<>();
        for (StopPlace sp : stops) {
            if (sp.getId() == null) continue;
            byId.computeIfAbsent(sp.getId(), k -> new ArrayList<>()).add(sp);
            if (sp.getQuays() == null) continue;
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay) hadQuay.add(sp.getId());
            }
        }

        Set<StopPlace> changed = new LinkedHashSet<>();
        long minted = 0;
        for (PassengerStopAssignment psa : psas) {
            if (psa.getQuayRef() != null) continue;
            JAXBElement<? extends StopPlaceRefStructure> srEl = psa.getStopPlaceRef();
            String spId = srEl == null || srEl.getValue() == null ? null : srEl.getValue().getRef();
            // hadQuay is the original state, so a second assignment on the same stop still mints:
            // two ScheduledStopPoints there are two poles, not one.
            if (spId == null || hadQuay.contains(spId) || !codespaceAllowed.test(spId)) continue;
            List<StopPlace> rows = byId.get(spId);
            if (rows == null || rows.isEmpty()) continue;
            JAXBElement<? extends ScheduledStopPointRefStructure> sspEl = psa.getScheduledStopPointRef();
            String sspId = sspEl == null || sspEl.getValue() == null ? null : sspEl.getValue().getRef();
            LocationStructure loc = sspId == null ? null : sspLoc.get(sspId);
            if (loc == null) continue;   // no position: OTP drops a coordinate-less quay outright

            String quayId = sspId.replace(":ScheduledStopPoint:", ":Quay:");
            for (StopPlace sp : rows) {
                Quay quay = new Quay();
                quay.setId(quayId);
                quay.setVersion(orOne(sp.getVersion()));
                quay.setName(sp.getName());
                quay.setCentroid(new SimplePoint_VersionStructure().withLocation(copyLocation(loc)));
                if (sp.getQuays() == null) sp.setQuays(new Quays_RelStructure());
                sp.getQuays().getQuayRefOrQuay().add(FACTORY.createQuay(quay));
                changed.add(sp);
            }
            QuayRefStructure qRef = new QuayRefStructure();
            qRef.setRef(quayId);
            qRef.setVersion(orOne(psa.getVersion()));
            psa.setQuayRef(FACTORY.createQuayRef(qRef));
            minted++;
        }
        Log.info("[backfill_quays_for_assigned_ssps] minted %d Quays onto %d quay-less StopPlaces "
                + "the feed had already assigned", minted, changed.size());
        return new ArrayList<>(changed);
    }

    /// The RAP national id space, `IT:` — all 18 regions.
    public static boolean isRapCodespace(String id) {
        return id != null && id.startsWith("IT:");
    }

    // ---------------------------------------------------------------- 2: centroid backfill

    /// StopPlaces that lack a centroid but whose child Quay carries one, centroid filled from the
    /// first located Quay; only changed ones returned.
    public static List<StopPlace> backfillStopplaceCentroidsFromQuays(Store db, Txn txn) {
        List<StopPlace> out = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            if (sp.getCentroid() != null && sp.getCentroid().getLocation() != null) continue;
            if (sp.getQuays() == null) continue;
            LocationStructure loc = null;
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay q && q.getCentroid() != null
                        && q.getCentroid().getLocation() != null) {
                    loc = q.getCentroid().getLocation();
                    break;
                }
            }
            if (loc == null) continue;
            sp.setCentroid(new SimplePoint_VersionStructure().withLocation(loc));
            out.add(sp);
        }
        Log.info("[backfill_stopplace_centroids_from_quays] filled %d StopPlace centroids "
                + "from a child Quay", out.size());
        return out;
    }

    // ---------------------------------------------------------------- 3: duplicate-stop merge

    /// Cluster StopPlaces that are both within the merge radius and share a normalised name, elect
    /// one survivor per cluster (most quays, tie-broken by id), move quays in, re-point PSAs.
    /// Returns (kept survivors that gained quays, dropped ids, repointed PSAs).
    public static MergePlan planStopplaceMerge(List<StopPlace> stopplacesIn,
                                               List<PassengerStopAssignment> psas) {
        return planStopplaceMerge(stopplacesIn, psas, MERGE_RADIUS_M);
    }

    /// The same merge at a caller-chosen radius. The union-find chains, so a cluster's diameter is
    /// bounded by the chain length rather than by `radiusM`, and the survivor keeps its own centroid
    /// rather than the cluster's.
    public static MergePlan planStopplaceMerge(List<StopPlace> stopplacesIn,
                                               List<PassengerStopAssignment> psas,
                                               double radiusM) {
        List<StopPlace> stopplaces = new ArrayList<>();
        for (StopPlace sp : stopplacesIn) if (sp.getId() != null && !sp.getId().isEmpty()) stopplaces.add(sp);
        Map<String, double[]> coords = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (StopPlace sp : stopplaces) {
            coords.put(sp.getId(), stopplaceCoord(sp));
            names.put(sp.getId(), spName(sp));
        }

        // union-find over ids
        Map<String, String> parent = new HashMap<>();
        for (StopPlace sp : stopplaces) parent.put(sp.getId(), sp.getId());
        java.util.function.Function<String, String> find = new java.util.function.Function<>() {
            @Override
            public String apply(String x) {
                while (!parent.get(x).equals(x)) {
                    parent.put(x, parent.get(parent.get(x)));
                    x = parent.get(x);
                }
                return x;
            }
        };

        double maxAbsLat = 0.0;
        for (double[] c : coords.values()) if (c != null) maxAbsLat = Math.max(maxAbsLat, Math.abs(c[0]));
        double cell = mergeCellDeg(radiusM, maxAbsLat);
        record Cell(long x, long y) {}
        Map<Cell, List<String>> grid = new HashMap<>();
        for (StopPlace sp : stopplaces) {
            double[] c = coords.get(sp.getId());
            if (c == null || names.get(sp.getId()).isEmpty()) continue;
            grid.computeIfAbsent(new Cell(Math.round(c[0] / cell), Math.round(c[1] / cell)),
                    k -> new ArrayList<>()).add(sp.getId());
        }
        for (StopPlace sp : stopplaces) {
            double[] c = coords.get(sp.getId());
            if (c == null || names.get(sp.getId()).isEmpty()) continue;
            double cy = Math.cos(Math.toRadians(c[0]));
            long bx = Math.round(c[0] / cell);
            long by = Math.round(c[1] / cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (String oid : grid.getOrDefault(new Cell(bx + dx, by + dy), List.of())) {
                        if (oid.equals(sp.getId()) || !names.get(oid).equals(names.get(sp.getId()))) continue;
                        double[] oc = coords.get(oid);
                        double dlat = (c[0] - oc[0]) * 111320.0;
                        double dlon = (c[1] - oc[1]) * 111320.0 * cy;
                        if (dlat * dlat + dlon * dlon <= radiusM * radiusM) {
                            String ra = find.apply(sp.getId());
                            String rb = find.apply(oid);
                            if (!ra.equals(rb)) parent.put(rb, ra);
                        }
                    }
                }
            }
        }

        Map<String, List<StopPlace>> clusters = new LinkedHashMap<>();
        for (StopPlace sp : stopplaces) {
            clusters.computeIfAbsent(find.apply(sp.getId()), k -> new ArrayList<>()).add(sp);
        }

        Map<String, StopPlace> kept = new LinkedHashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        Map<String, String> spToSurvivor = new HashMap<>();
        Map<String, String> quayToSurvivor = new HashMap<>(); // folded quay id -> surviving quay id
        long mergedClusters = 0;
        long modesPromoted = 0;
        for (List<StopPlace> members : clusters.values()) {
            if (members.size() < 2) continue;
            mergedClusters++;
            // survivor: most quays, tie-broken by id (the first maximal element)
            StopPlace survivor = members.get(0);
            for (StopPlace m : members.subList(1, members.size())) {
                int cq = nQuays(m) - nQuays(survivor);
                if (cq > 0 || (cq == 0 && m.getId().compareTo(survivor.getId()) > 0)) survivor = m;
            }
            if (TransportModes.adoptHeaviestMode(survivor, members)) modesPromoted++;
            // The survivor takes the union of the cluster's quays, deduped by id. Without the dedup
            // the same quay accumulates a copy per tier the merge runs at, and once PSAs carry
            // QuayRefs a duplicated quay id means two embedding-index rows for one id whose
            // container is then chosen by class scan order.
            List<JAXBElement<?>> survQuays = new ArrayList<>();
            Set<String> seenQuays = new LinkedHashSet<>();
            if (survivor.getQuays() != null) addQuays(survQuays, seenQuays, survivor);
            for (StopPlace m : members) {
                if (m.getId().equals(survivor.getId())) continue;
                dropped.add(m.getId());
                spToSurvivor.put(m.getId(), survivor.getId());
                if (m.getQuays() != null) addQuays(survQuays, seenQuays, m);
            }
            foldCoincidentQuays(survQuays, quayToSurvivor);
            Quays_RelStructure q = new Quays_RelStructure();
            q.getQuayRefOrQuay().addAll(survQuays);
            survivor.setQuays(q);
            kept.put(survivor.getId(), survivor);
        }

        // Re-point PSAs whose StopPlaceRef named a merged-away stop, and whose QuayRef named a
        // folded-away quay. The QuayRef arm is not optional: a folded quay is gone from every
        // container, and OTP discards an unresolvable QuayRef together with the journey's stop
        // times, without a warning.
        Map<String, PassengerStopAssignment> repointed = new LinkedHashMap<>();
        for (PassengerStopAssignment psa : psas) {
            JAXBElement<? extends StopPlaceRefStructure> srEl = psa.getStopPlaceRef();
            StopPlaceRefStructure sr = srEl == null ? null : srEl.getValue();
            if (sr != null && spToSurvivor.containsKey(sr.getRef())) {
                StopPlaceRefStructure repl = new StopPlaceRefStructure();
                repl.setRef(spToSurvivor.get(sr.getRef()));
                repl.setVersion(sr.getVersion());
                psa.setStopPlaceRef(FACTORY.createStopPlaceRef(repl));
                repointed.put(psa.getId(), psa);
            }
            JAXBElement<? extends QuayRefStructure> qrEl = psa.getQuayRef();
            QuayRefStructure qr = qrEl == null ? null : qrEl.getValue();
            if (qr != null && quayToSurvivor.containsKey(qr.getRef())) {
                QuayRefStructure repl = new QuayRefStructure();
                repl.setRef(quayToSurvivor.get(qr.getRef()));
                repl.setVersion(qr.getVersion());
                psa.setQuayRef(FACTORY.createQuayRef(repl));
                repointed.put(psa.getId(), psa);
            }
        }

        Log.info("[merge_duplicate_stopplaces] merged %d duplicate StopPlaces into "
                + "%d survivors (same name, <= %.0f m); folded %d coincident quays (<= %.0f m); "
                + "re-pointed %d PSAs; %d survivors took a member's mode",
                dropped.size(), mergedClusters, radiusM, quayToSurvivor.size(), QUAY_FOLD_RADIUS_M,
                repointed.size(), modesPromoted);
        return new MergePlan(kept, dropped, repointed);
    }

    private static int nQuays(StopPlace sp) {
        return sp.getQuays() == null ? 0 : sp.getQuays().getQuayRefOrQuay().size();
    }
}
