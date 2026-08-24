package toolkit.transform.common;

// The four transforms ../noi-pipeline's conv/ItRapDbToDb composes, plus stopplaceCoord:
//
//   synthesizeStopPlacesFromSsps        StopPlace + PassengerStopAssignment for every unassigned
//                                       locatable ScheduledStopPoint (SSP-only feeds)
//   backfillStopplaceCentroidsFromQuays lift a child Quay's Location onto a centroid-less
//                                       StopPlace, so OTP does not see a station without
//                                       coordinates
//   backfillLineAuthorities             give every Line an OTP-resolvable authority
//   planStopplaceMerge                  merge co-located (<= 15 m) same-name duplicate StopPlaces
//
// Union fields are read through the binding's JAXBElement wrappers, and falsy-string checks are
// null-or-empty checks.

import noi.netex.model.Authority;
import noi.netex.model.AuthorityRef;
import noi.netex.model.AuthorityRefStructure;
import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.GroupOfLines;
import noi.netex.model.Line;
import noi.netex.model.LocationStructure;
import noi.netex.text.Mls;
import noi.netex.model.MultilingualString;
import noi.netex.model.Network;
import noi.netex.model.ObjectFactory;
import noi.netex.model.Operator;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PrivateCodeStructure;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.TransportOrganisationRefStructure;
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

    // Same-physical-stop duplicates sit within this radius.
    private static final double MERGE_RADIUS_M = 15.0;
    private static final double MERGE_CELL_DEG = 0.0003; // ~33 m cell; probe the 3x3 neighbourhood

    private ScheduledStopPoints() {}

    /// What [#planStopplaceMerge] returns: kept, dropped, repointed.
    public record MergePlan(Map<String, StopPlace> kept, Set<String> dropped,
                            Map<String, PassengerStopAssignment> repointed) {}

    // ---------------------------------------------------------------- small helpers

    /// The text of a MultilingualString ('' if none), via the accessor seam.
    static String mlText(MultilingualString n) {
        return Mls.textOrEmpty(n);
    }

    /// Accent/punctuation/case-folded StopPlace name for equality; '' if none.
    static String spName(StopPlace sp) {
        String v = mlText(sp.getName());
        if (v.isEmpty()) return "";
        // NFKD-normalise, drop non-ASCII, lower-case
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
        SimplePoint_VersionStructure c = sp.getCentroid();
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

    private static String orOne(String v) { // "1" when null or empty
        return v == null || v.isEmpty() ? "1" : v;
    }

    // ---------------------------------------------------------------- 1: SSP synthesis

    /// Feed-specific knobs for [Txn, SspSynthesis)][#synthesizeStopPlacesFromSsps(Store,]: an extra
    /// per-SSP include guard, an optional PrivateCode for each synthesised StopPlace, and the
    /// completion log line (one `%d` slot for the count).
    public record SspSynthesis(Predicate<ScheduledStopPoint> include,
                               Function<ScheduledStopPoint, PrivateCodeStructure> privateCode,
                               String logLine) {}

    /// A StopPlace + PassengerStopAssignment for every locatable ScheduledStopPoint not already
    /// bound by a PSA.
    public static List<Object> synthesizeStopPlacesFromSsps(Store db, Txn txn) {
        return synthesizeStopPlacesFromSsps(db, txn, new SspSynthesis(
                ssp -> ssp.getId() != null,   // an id-less SSP is skipped
                ssp -> null,                  // the generic synthesis sets no PrivateCode
                "[synthesize_stop_places_from_ssps] synthesised %d StopPlaces + PSAs "
                        + "from unassigned locatable ScheduledStopPoints"));
    }

    /// The parameterized body shared by the generic transform above and the feed-specific variants.
    public static List<Object> synthesizeStopPlacesFromSsps(Store db, Txn txn, SspSynthesis opts) {
        Set<String> assigned = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            JAXBElement<? extends ScheduledStopPointRefStructure> refEl = psa.getScheduledStopPointRef();
            ScheduledStopPointRefStructure ref = refEl == null ? null : refEl.getValue();
            if (ref != null && ref.getRef() != null && !ref.getRef().isEmpty()) assigned.add(ref.getRef());
        }
        Set<String> existingStopPlaces = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            String id = ((StopPlace) o).getId();
            if (id != null) existingStopPlaces.add(id);
        }

        List<Object> out = new ArrayList<>();
        long synthesised = 0;
        for (Object o : db.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
            ScheduledStopPoint ssp = (ScheduledStopPoint) o;
            if (!opts.include().test(ssp) || ssp.getLocation() == null || assigned.contains(ssp.getId())) continue;
            String spId = ssp.getId().replace(":ScheduledStopPoint:", ":StopPlace:");
            if (!existingStopPlaces.contains(spId)) {
                StopPlace sp = new StopPlace();
                sp.setId(spId);
                sp.setVersion(orOne(ssp.getVersion()));
                sp.setName(ssp.getName());
                sp.setCentroid(new SimplePoint_VersionStructure().withLocation(ssp.getLocation()));
                sp.setPrivateCode(opts.privateCode().apply(ssp));  // null for the generic synthesis
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
            out.add(psa);
            synthesised++;
        }
        Log.info(opts.logLine(), synthesised);
        return out;
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

    // ---------------------------------------------------------------- 3: line authorities

    /// Repaired Networks, Lines given an authorityRef, then the synthesised Authorities.
    public static List<Object> backfillLineAuthorities(Store db, Txn txn) {
        Set<String> authorities = new LinkedHashSet<>();
        for (Object o : db.iterOnlyObjects(txn, Authority.class)) {
            String id = ((Authority) o).getId();
            if (id != null && !id.isEmpty()) authorities.add(id);
        }
        // match on the last id segment (operator ids carry a company-code segment the authority
        // id lacks); build order is the authorities iteration order, and a clash keeps the last
        Map<String, String> authBySuffix = new LinkedHashMap<>();
        for (String a : authorities) authBySuffix.put(lastSegment(a), a);
        String soleAuthority = authorities.size() == 1 ? authorities.iterator().next() : null;
        Map<String, Operator> operators = new LinkedHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, Operator.class)) {
            Operator op = (Operator) o;
            if (op.getId() != null && !op.getId().isEmpty()) operators.put(op.getId(), op);
        }
        Map<String, Authority> synthAuthority = new LinkedHashMap<>();
        List<Object> out = new ArrayList<>();

        // operator -> authority lookup
        java.util.function.Function<String, String> authorityForOperator = opId -> {
            if (opId == null || opId.isEmpty() || !opId.contains(":Operator:")) return null;
            String existing = authBySuffix.get(lastSegment(opId));
            if (existing != null) return existing;
            String authId = opId.replaceFirst(":Operator:", ":Authority:");
            if (!synthAuthority.containsKey(authId)) {
                Operator op = operators.get(opId);
                MultilingualString name = op != null
                        ? (op.getName() != null ? op.getName() : op.getLegalName()) : null;
                Authority a = new Authority();
                a.setId(authId);
                a.setVersion(op != null ? op.getVersion() : "1");
                a.setName(name != null ? name : Mls.of(lastSegment(opId)));
                synthAuthority.put(authId, a);
            }
            return authId;
        };

        long netFixed = 0;
        Set<String> resolvableGroups = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, Network.class)) {
            Network net = (Network) o;
            JAXBElement<? extends TransportOrganisationRefStructure> orgEl = net.getTransportOrganisationRef();
            TransportOrganisationRefStructure org = orgEl == null ? null : orgEl.getValue();
            if (org instanceof OperatorRefStructure opRef) { // repair Operator-typed org ref
                String authId = authorityForOperator.apply(opRef.getRef());
                if (authId != null) {
                    AuthorityRef ar = new AuthorityRef();
                    ar.setRef(authId);
                    ar.setVersion(opRef.getVersion());
                    net.setTransportOrganisationRef(FACTORY.createAuthorityRef(ar));
                    org = ar;
                    out.add(net);
                    netFixed++;
                }
            }
            boolean hasAuth = org instanceof AuthorityRefStructure
                    && org.getRef() != null && !org.getRef().isEmpty();
            if (hasAuth && net.getGroupsOfLines() != null) {
                for (GroupOfLines g : net.getGroupsOfLines().getGroupOfLines()) {
                    if (g.getId() != null && !g.getId().isEmpty()) resolvableGroups.add(g.getId());
                }
            }
        }

        long lineFixed = 0;
        for (Object o : db.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            var grp = line.getRepresentedByGroupRef();
            if (line.getAuthorityRef() != null
                    || (grp != null && resolvableGroups.contains(grp.getRef()))) {
                continue; // already resolvable
            }
            String authId = soleAuthority != null ? soleAuthority
                    : (line.getOperatorRef() != null
                            ? authorityForOperator.apply(line.getOperatorRef().getRef()) : null);
            if (authId == null) continue;
            if (grp != null) line.setRepresentedByGroupRef(null); // dead end -> authorityRef wins
            AuthorityRef ar = new AuthorityRef();
            ar.setRef(authId);
            ar.setVersion(line.getVersion());
            line.setAuthorityRef(ar);
            out.add(line);
            lineFixed++;
        }

        out.addAll(synthAuthority.values());
        Log.info("[backfill_line_authorities] gave %d Lines an authorityRef, repaired "
                + "%d Network org refs (%d Authorities synthesised)", lineFixed, netFixed, synthAuthority.size());
        return out;
    }

    private static String lastSegment(String id) {
        int i = id.lastIndexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }

    // ---------------------------------------------------------------- 4: duplicate-stop merge

    /// Cluster StopPlaces both within ~15 m AND with the same normalised
    /// name, elect one survivor per cluster (most quays, tie-broken by id), move quays in, re-point
    /// PSAs. Returns (kept survivors that gained quays, dropped ids, repointed PSAs).
    public static MergePlan planStopplaceMerge(List<StopPlace> stopplacesIn,
                                               List<PassengerStopAssignment> psas) {
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

        double cell = MERGE_CELL_DEG;
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
                        if (dlat * dlat + dlon * dlon <= MERGE_RADIUS_M * MERGE_RADIUS_M) {
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
        long mergedClusters = 0;
        for (List<StopPlace> members : clusters.values()) {
            if (members.size() < 2) continue;
            mergedClusters++;
            // survivor: most quays, tie-broken by id (the FIRST maximal element)
            StopPlace survivor = members.get(0);
            for (StopPlace m : members.subList(1, members.size())) {
                int cq = nQuays(m) - nQuays(survivor);
                if (cq > 0 || (cq == 0 && m.getId().compareTo(survivor.getId()) > 0)) survivor = m;
            }
            List<JAXBElement<?>> survQuays = new ArrayList<>();
            if (survivor.getQuays() != null) survQuays.addAll(survivor.getQuays().getQuayRefOrQuay());
            for (StopPlace m : members) {
                if (m.getId().equals(survivor.getId())) continue;
                dropped.add(m.getId());
                spToSurvivor.put(m.getId(), survivor.getId());
                if (m.getQuays() != null) survQuays.addAll(m.getQuays().getQuayRefOrQuay());
            }
            Quays_RelStructure q = new Quays_RelStructure();
            q.getQuayRefOrQuay().addAll(survQuays);
            survivor.setQuays(q);
            kept.put(survivor.getId(), survivor);
        }

        // re-point PSAs whose StopPlaceRef pointed at a merged-away stop
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
        }

        Log.info("[merge_duplicate_stopplaces] merged %d duplicate StopPlaces into "
                + "%d survivors (same name, <= %.0f m); re-pointed %d PSAs",
                dropped.size(), mergedClusters, MERGE_RADIUS_M, repointed.size());
        return new MergePlan(kept, dropped, repointed);
    }

    private static int nQuays(StopPlace sp) {
        return sp.getQuays() == null ? 0 : sp.getQuays().getQuayRefOrQuay().size();
    }
}
