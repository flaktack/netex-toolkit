package toolkit.transform.epip;

// The routes slice the EPIP journey generator needs: routePointProjection and
// projectRouteToServiceLinks.
//
// gml choice-group note: the model splits the LineString pos/pointProperty/posList choice into
// `posOrPointProperty` (List<Object>) plus a dedicated `posList` slot. The helpers below rebuild the
// single document-ordered view as posOrPointProperty entries followed by posList. That holds only
// while a LineString carries either pos elements or a single posList and never both, as real NeTEx
// data does: document order across mixed forms is not recoverable from JAXB.
//
// The ServiceLink dedup hash feeds each posList float through pyFloatStr() into sha256, and those
// hashes become the ServiceLink and LineString ids.

import jakarta.xml.bind.JAXBElement;
import noi.netex.gml.DirectPositionListType;
import noi.netex.gml.DirectPositionType;
import noi.netex.gml.LineStringType;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.PointOnRoute;
import noi.netex.model.PointProjection;
import noi.netex.model.Route;
import noi.netex.model.RouteLink;
import noi.netex.model.RouteLinkRefStructure;
import noi.netex.model.RoutePointRefStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLink;
import noi.netex.model.ServiceLinkRefStructure;
import noi.netex.model.StopPointInJourneyPattern;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RoutesProfile {

    private RoutesProfile() {}

    /// The RoutePoint refs a ScheduledStopPoint projects to: every PointProjection under
    /// `ssp.projections` whose projected point ref names class RoutePoint, projected to a
    /// [RoutePointRefStructure].
    ///
    /// Only an EXPLICIT `nameOfRefClass="RoutePoint"` matches; JAXB leaves the attribute null when
    /// the XML omits it.
    public static List<RoutePointRefStructure> routePointProjection(ScheduledStopPoint ssp) {
        List<RoutePointRefStructure> out = new ArrayList<>();
        if (ssp.getProjections() != null) {
            for (JAXBElement<?> element : ssp.getProjections().getProjectionRefOrProjection()) {
                Object projection = element.getValue();
                if (projection instanceof PointProjection pp) {
                    if (pp.getProjectToPointRef() != null) {
                        if ("RoutePoint".equals(pp.getProjectToPointRef().getNameOfRefClass())) {
                            out.add(NetexUtils.project(pp.getProjectToPointRef(), RoutePointRefStructure.class));
                        }
                    }
                }
            }
        }
        return out;
    }

    /// Aggregate a Route's pointsInSequence/onwardRouteLinkRef chain into ServiceLinks between the
    /// consecutive StopPointInJourneyPatterns of `sjp`, wiring each SPIJP's onwardServiceLinkRef
    /// (and nulling its onwardTimingLinkRef) as it goes. Side effects:
    ///
    /// - `sjp`'s points list is REPLACED by its StopPointInJourneyPattern subset, but only when
    ///    both route and sjp have pointsInSequence;
    /// - the single-link case stamps `LineString_<hash>` onto the SOURCE RouteLink's own (shared)
    ///    LineString and hands that same instance to the ServiceLink;
    /// - the multi-link case works on a deep copy of the first link's LineString. KNOWN BUG: the
    ///    `lineStringValue[-srsDimension : 0]` slice is ALWAYS empty, so the merge loop breaks on
    ///    its first candidate, leaving distance at zero and the copied geometry unextended;
    /// - a missing `routePointProjection` entry for a from/to stop throws NullPointerException.
    public static List<ServiceLink> projectRouteToServiceLinks(
            Store db, Txn txn, ServiceJourneyPattern sjp, Route route,
            Map<String, RoutePointRefStructure> routePointProjection,
            GeneratorDefaults generatorDefaults) {

        List<ServiceLink> yielded = new ArrayList<>();

        // A Route arrives in one of two shapes — pointsInSequence with PointOnRoute/
        // OnwardRouteLinkRef, or linksInSequence — and both aggregate to linksInSequence here.

        if (route.getPointsInSequence() == null) return yielded;
        List<RouteLinkRefStructure> linksInSequence = new ArrayList<>();
        for (PointOnRoute por : route.getPointsInSequence().getPointOnRoute()) {
            if (por.getOnwardRouteLinkRef() != null) linksInSequence.add(por.getOnwardRouteLinkRef());
        }
        List<RouteLink> routeLinksInSequence = new ArrayList<>();
        for (RouteLinkRefStructure lis : linksInSequence) {
            routeLinksInSequence.add((RouteLink) db.loadObjectByReference(txn, lis));
        }
        int routeI = 0;
        if (sjp.getPointsInSequence() == null) return yielded;
        List<PointInLinkSequence_VersionedChildStructure> points =
                sjp.getPointsInSequence().getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        List<StopPointInJourneyPattern> spijps = new ArrayList<>();
        for (PointInLinkSequence_VersionedChildStructure p : points) {
            if (p instanceof StopPointInJourneyPattern sp) spijps.add(sp);
        }
        for (int i = 0; i < spijps.size() - 1; i++) {
            ScheduledStopPointRefStructure fromSsp = value(spijps.get(i).getScheduledStopPointRef());
            ScheduledStopPointRefStructure toSsp = value(spijps.get(i + 1).getScheduledStopPointRef());
            // Find a routelink that matches from + to, through the route-point projection
            List<RouteLink> combinedRouteLinks = new ArrayList<>();
            boolean success = false;
            for (int j = routeI; j < routeLinksInSequence.size(); j++) {
                if (!routeLinksInSequence.get(j).getFromPointRef().getRef()
                        .equals(routePointProjection.get(fromSsp.getRef()).getRef())) {
                    continue;
                }
                for (int k = j; k < routeLinksInSequence.size(); k++) {
                    combinedRouteLinks.add(routeLinksInSequence.get(k));
                    if (!routeLinksInSequence.get(k).getToPointRef().getRef()
                            .equals(routePointProjection.get(toSsp.getRef()).getRef())) {
                        continue;
                    }
                    success = true;
                    routeI = k + 1;
                    break;
                }
                break;
            }

            if (!success) continue;
            ServiceLink sl = null;
            if (combinedRouteLinks.isEmpty()) {
                continue;
            } else if (combinedRouteLinks.size() == 1) {
                RouteLink only = combinedRouteLinks.get(0);
                // The id hashes (from, to, coordinates), so ServiceLinks with the same SSPs and the
                // same LineString collapse to one.
                String objectId = sha256Head8(
                        fromSsp.getRef() + "\n" + toSsp.getRef() + "\n"
                                + joinPyFloats(posValues(firstChoiceItem(only.getLineString()))));
                only.getLineString().setId("LineString_" + objectId);
                sl = new ServiceLink();
                sl.setId(NetexUtils.deriveId(route.getId(), ServiceLink.class, objectId,
                        generatorDefaults.codespace()));
                sl.setVersion(route.getVersion());
                sl.setDistance(only.getDistance());
                sl.setFromPointRef(NetexUtils.project(fromSsp, ScheduledStopPointRefStructure.class));
                sl.setToPointRef(NetexUtils.project(toSsp, ScheduledStopPointRefStructure.class));
                sl.setLineString(only.getLineString());
                sl.setDerivedFromObjectRef(only.getId());
                sl.setDerivedFromVersionRef_BasicModificationDetailsGroup(only.getVersion());
            } else {
                // KNOWN LIMIT: only a DirectPositionList is merged. A geometry built from
                // individual <pos> elements falls through to the ref-only branch.
                if (firstChoiceItem(combinedRouteLinks.get(0).getLineString())
                        instanceof DirectPositionListType) {
                    LineStringType lineString =
                            deepCopyLineString(combinedRouteLinks.get(0).getLineString());
                    // The loader does not default srsDimension, so it is defaulted here.
                    if (lineString.getSrsDimension() == null) {
                        lineString.setSrsDimension(BigInteger.valueOf(2));
                    }

                    // Aliases the SOURCE link's list.
                    List<Double> lineStringValue = posValues(
                            firstChoiceItem(combinedRouteLinks.get(0).getLineString()));
                    BigDecimal distance = BigDecimal.ZERO;
                    int dim = lineString.getSrsDimension().intValue();

                    for (int k = 1; k < combinedRouteLinks.size(); k++) {
                        LineStringType kls = combinedRouteLinks.get(k).getLineString();
                        Object kFirst = firstChoiceItem(kls);
                        if (!(kFirst instanceof DirectPositionListType)
                                || firstChoiceItem(lineString).getClass() != kFirst.getClass()
                                || !Objects.equals(lineString.getSrsName(), kls.getSrsName())
                                || dim != srsDimensionOr2(kls.getSrsDimension())
                                || choiceCount(kls) > 1) {
                            break;
                        } else if (!pySlice(lineStringValue, -dim, 0)
                                .equals(pySlice(posValues(kFirst), 0, dim))) {
                            // The [-dim:0] slice is always empty, so for non-empty coordinates this
                            // branch always breaks (KNOWN BUG).
                            break;
                        } else {
                            // Mutates the SOURCE list.
                            lineStringValue.addAll(
                                    pySlice(posValues(kFirst), dim, posValues(kFirst).size()));
                            distance = distance.add(combinedRouteLinks.get(k).getDistance());
                        }
                    }

                    // The id hashes (from, to, coordinates of the DEEP COPY), so ServiceLinks with
                    // the same SSPs and the same LineString collapse to one.
                    String objectId = sha256Head8(
                            fromSsp.getRef() + "\n" + toSsp.getRef() + "\n"
                                    + joinPyFloats(posValues(firstChoiceItem(lineString))));
                    lineString.setId("LineString_" + objectId);
                    sl = new ServiceLink();
                    sl.setId(NetexUtils.deriveId(route.getId(), ServiceLink.class, objectId,
                            generatorDefaults.codespace()));
                    sl.setVersion(route.getVersion());
                    sl.setDistance(distance);
                    sl.setFromPointRef(NetexUtils.project(fromSsp, ScheduledStopPointRefStructure.class));
                    sl.setToPointRef(NetexUtils.project(toSsp, ScheduledStopPointRefStructure.class));
                    sl.setLineString(lineString);
                    sl.setDerivedFromObjectRef(route.getId());
                    sl.setDerivedFromVersionRef_BasicModificationDetailsGroup(route.getVersion());
                }
            }

            if (sl != null) {
                spijps.get(i).setOnwardServiceLinkRef(
                        NetexUtils.getRef(sl, ServiceLinkRefStructure.class));
                // KNOWN OPEN QUESTION: the timing link ref is cleared whenever a service link is
                // set, without asking whether the profile wanted it kept.
                spijps.get(i).setOnwardTimingLinkRef(null);
                yielded.add(sl);
            }
        }

        points.clear();                        // JAXB lists have no setter
        points.addAll(spijps);
        return yielded;
    }

    // ---------------------------------------------------------------- gml choice-group helpers

    /// The first entry of the rebuilt choice list — posOrPointProperty entries first, then posList
    /// (see the class note). Null when the LineString has neither; callers then NPE.
    private static Object firstChoiceItem(LineStringType ls) {
        if (!ls.getPosOrPointProperty().isEmpty()) return ls.getPosOrPointProperty().get(0);
        return ls.getPosList();
    }

    private static int choiceCount(LineStringType ls) {
        return ls.getPosOrPointProperty().size() + (ls.getPosList() != null ? 1 : 0);
    }

    /// The item's value — both gml pos and posList carry a List of doubles.
    private static List<Double> posValues(Object item) {
        if (item instanceof DirectPositionListType pl) return pl.getValue();
        if (item instanceof DirectPositionType p) return p.getValue();
        throw new IllegalArgumentException("Not a pos/posList item: " + item);
    }

    /// The srsDimension, defaulting to 2 — null AND 0 both yield 2.
    private static int srsDimensionOr2(BigInteger srsDimension) {
        return (srsDimension == null || srsDimension.signum() == 0) ? 2 : srsDimension.intValue();
    }

    /// Deep copy of the fields the multi-link branch reads/writes: id, srsName, srsDimension, the
    /// posList (fresh instance, coordinates copied) and any posOrPointProperty pos entries (fresh
    /// instances). Other gml header fields are left null on the copy.
    private static LineStringType deepCopyLineString(LineStringType ls) {
        LineStringType copy = new LineStringType();
        copy.setId(ls.getId());
        copy.setSrsName(ls.getSrsName());
        copy.setSrsDimension(ls.getSrsDimension());
        if (ls.getPosList() != null) {
            DirectPositionListType pl = ls.getPosList();
            DirectPositionListType plCopy = new DirectPositionListType();
            plCopy.getValue().addAll(pl.getValue());
            plCopy.setCount(pl.getCount());
            plCopy.setSrsName(pl.getSrsName());
            plCopy.setSrsDimension(pl.getSrsDimension());
            copy.setPosList(plCopy);
        }
        for (Object item : ls.getPosOrPointProperty()) {
            if (item instanceof DirectPositionType p) {
                DirectPositionType pCopy = new DirectPositionType();
                pCopy.getValue().addAll(p.getValue());
                pCopy.setSrsName(p.getSrsName());
                pCopy.setSrsDimension(p.getSrsDimension());
                copy.getPosOrPointProperty().add(pCopy);
            } else {
                copy.getPosOrPointProperty().add(item);
            }
        }
        return copy;
    }

    // ---------------------------------------------------------------- string/list primitives

    private static <T> T value(JAXBElement<? extends T> element) {
        return element == null ? null : element.getValue();
    }

    /// A list slice `l[start:stop]` with clamping and negative-index semantics: a negative start
    /// counts from the end, out-of-range indexes clamp, start >= stop is empty.
    private static List<Double> pySlice(List<Double> l, int start, int stop) {
        int size = l.size();
        if (start < 0) start += size;
        if (stop < 0) stop += size;
        start = Math.max(0, Math.min(start, size));
        stop = Math.max(0, Math.min(stop, size));
        if (start >= stop) return new ArrayList<>();
        return new ArrayList<>(l.subList(start, stop));
    }

    /// A space-joined float list, each element rendered by [#pyFloatStr].
    private static String joinPyFloats(List<Double> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(pyFloatStr(values.get(i)));
        }
        return sb.toString();
    }

    /// The required float rendering: shortest round-trip digits, positional when the decimal
    /// exponent is in [-4, 16), scientific otherwise (`1e+16`, `1e-05`: no trailing `.0` on the
    /// mantissa, sign always present, exponent zero-padded to two digits).
    static String pyFloatStr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        String java = Double.toString(d);
        boolean neg = java.startsWith("-");
        String s = neg ? java.substring(1) : java;
        if (s.equals("0.0")) return neg ? "-0.0" : "0.0";

        int ePos = s.indexOf('E');
        String mantissa = ePos >= 0 ? s.substring(0, ePos) : s;
        int exp10 = ePos >= 0 ? Integer.parseInt(s.substring(ePos + 1)) : 0;
        int dot = mantissa.indexOf('.');
        String digits = mantissa.replace(".", "");
        // Decimal exponent of the value (floor(log10(|d|))).
        int lead = 0;
        while (lead < digits.length() && digits.charAt(lead) == '0') lead++;
        int decExp = (dot - 1 - lead) + exp10;
        // Significant digits, no leading/trailing zeros.
        String sig = digits.substring(lead);
        int end = sig.length();
        while (end > 1 && sig.charAt(end - 1) == '0') end--;
        sig = sig.substring(0, end);

        String out;
        if (decExp >= -4 && decExp < 16) {
            if (decExp >= 0) {
                if (sig.length() > decExp + 1) {
                    out = sig.substring(0, decExp + 1) + "." + sig.substring(decExp + 1);
                } else {
                    out = sig + "0".repeat(decExp + 1 - sig.length()) + ".0";
                }
            } else {
                out = "0." + "0".repeat(-decExp - 1) + sig;
            }
        } else {
            String m = sig.length() > 1 ? sig.charAt(0) + "." + sig.substring(1) : sig;
            out = m + "e" + (decExp < 0 ? "-" : "+")
                    + (Math.abs(decExp) < 10 ? "0" : "") + Math.abs(decExp);
        }
        return neg ? "-" + out : out;
    }

    /// The first 8 hex digits of the SHA-256 of the UTF-8 bytes, upper-cased.
    private static String sha256Head8(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) hex.append(String.format("%02X", digest[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
