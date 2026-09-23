package toolkit.transform.epip;

// The EPIP backfill ensemble ops:
//   serviceJourneyOperatorOp   (op factory)
//   vehicleTypeShortNameOp     (per-object op)
//   quayNameOp                 (per-object op)
// plus the abbreviation rules they share: makeAbbreviation / abbreviateInitials /
// abbreviateHybrid.
//
// None of the three is gated on a codespace or an id prefix: EpipToDb wires them unconditionally and
// they act on every object of their class.
//
// Union-field mapping: a single-slot choice field maps to a pair of sibling getters, where setting
// the ref also nulls the view — getOperatorRef()/getOperatorView(), getRouteRef()/getRouteView().
// The line union is three-way: getLineRef() is a JAXBElement covering LineRef AND FlexibleLineRef,
// against getLineView() / getFlexibleLineView().

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.FlexibleLine;
import noi.netex.model.Line;
import noi.netex.text.Mls;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.Quay;
import noi.netex.model.Route;
import noi.netex.model.RouteView;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceJourneyPatternRefStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.TransportOrganisationRefs_RelStructure;
import noi.netex.model.VehicleType;
import toolkit.harness.Ensemble;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Backfills {

    private Backfills() {}

    // ------------------------------------------------- serviceJourneyOperatorOp

    /// A line's operator: its own ref, else the first OperatorRef in additionalOperators.
    ///
    /// `EpipLines` runs before this pass and moves the non-primary organisation to the front of
    /// that list, position being the only thing that separates it from refs a feed put there
    /// itself, so once a line carries an authorityRef its operator is reachable only there.
    /// Reading the own ref alone maps every such line to null, and every journey on it then
    /// loses the operator its line names.
    private static OperatorRefStructure operatorOf(
            OperatorRefStructure own, TransportOrganisationRefs_RelStructure additional) {
        if (own != null) return own;
        if (additional == null) return null;
        for (JAXBElement<? extends noi.netex.model.TransportOrganisationRefStructure> el
                : additional.getTransportOrganisationRef()) {
            if (el.getValue() instanceof OperatorRefStructure op) return op;
        }
        return null;
    }

    /// Query phase: build the line/route/pattern -&gt; operator indexes once, and return the
    /// per-object operation (ServiceJourney -&gt; changed?). Everything the operation needs is ready
    /// before the first object is processed.
    ///
    /// FlexibleLines carry operators exactly like Lines, so both classes feed the same index; every
    /// lookup is via `Map.get`, so an unresolvable line/route/pattern ref leaves the journey
    /// unchanged. A line without an operator maps its id to null.
    public static Ensemble.Operation serviceJourneyOperatorOp(Store dbRead, Txn txn) {
        Map<String, OperatorRefStructure> lineOperatorRef = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            lineOperatorRef.put(line.getId(), operatorOf(line.getOperatorRef(),
                    line.getAdditionalOperators()));
        }
        for (Object o : dbRead.iterOnlyObjects(txn, FlexibleLine.class)) {
            FlexibleLine fl = (FlexibleLine) o;
            lineOperatorRef.put(fl.getId(), operatorOf(fl.getOperatorRef(),
                    fl.getAdditionalOperators()));
        }

        Map<String, OperatorRefStructure> routeOperatorRef = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, Route.class)) {
            Route route = (Route) o;
            if (route.getLineRef() != null) {
                OperatorRefStructure op = lineOperatorRef.get(route.getLineRef().getValue().getRef());
                if (op != null) routeOperatorRef.put(route.getId(), op);
            }
        }

        Map<String, OperatorRefStructure> sjpOperatorRef = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
            ServiceJourneyPattern sjp = (ServiceJourneyPattern) o;
            OperatorRefStructure op = null;
            if (sjp.getRouteRef() != null) {
                op = routeOperatorRef.get(sjp.getRouteRef().getRef());
            } else if (sjp.getRouteView() != null) {
                RouteView view = sjp.getRouteView();
                if (view.getLineRef() != null) {
                    op = lineOperatorRef.get(view.getLineRef().getValue().getRef());
                }
            }
            if (op != null) sjpOperatorRef.put(sjp.getId(), op);
        }

        return obj -> {
            ServiceJourney sj = (ServiceJourney) obj;
            // The operator union's single slot, before the backfill: a ref wins over a view.
            Object before = sj.getOperatorRef() != null ? sj.getOperatorRef() : sj.getOperatorView();
            if (sj.getOperatorRef() == null) {
                OperatorRefStructure op = null;
                JAXBElement<? extends noi.netex.model.LineRefStructure> lineRef = sj.getLineRef();
                if (lineRef != null) {
                    op = lineOperatorRef.get(lineRef.getValue().getRef());
                } else if (sj.getRouteRef() != null) {
                    op = routeOperatorRef.get(sj.getRouteRef().getRef());
                } else if (sj.getJourneyPatternRef() != null
                        && sj.getJourneyPatternRef().getValue()
                                instanceof ServiceJourneyPatternRefStructure jpRef) {
                    op = sjpOperatorRef.get(jpRef.getRef());
                }
                if (op != null) {
                    sj.setOperatorRef(op);
                    sj.setOperatorView(null);
                }
            }
            Object after = sj.getOperatorRef() != null ? sj.getOperatorRef() : sj.getOperatorView();
            return after != before;                                // identity comparison
        };
    }

    // --------------------------------------------------------------- abbreviation rules

    /// Word split on \[A-Za-z0-9\]+ — ASCII-only, so umlauts split words.
    private static final Pattern WORDS = Pattern.compile("[A-Za-z0-9]+");

    /// First letter of each ASCII word, truncated to `maxLen`.
    public static String abbreviateInitials(String text, int maxLen) {
        StringBuilder initials = new StringBuilder();
        Matcher m = WORDS.matcher(text);
        while (m.find()) initials.append(m.group().charAt(0));
        return initials.length() > maxLen ? initials.substring(0, maxLen) : initials.toString();
    }

    /// First letter of the first word, then the remaining letters of EVERY word (the first word
    /// included) until `maxLen`; uppercased. A result shorter than `maxLen` is returned as is.
    public static String abbreviateHybrid(String text, int maxLen) {
        java.util.List<String> words = new java.util.ArrayList<>();
        Matcher m = WORDS.matcher(text);
        while (m.find()) words.add(m.group());
        if (words.isEmpty()) return "";

        StringBuilder abb = new StringBuilder().append(words.get(0).charAt(0));
        for (String w : words) {
            for (int i = 1; i < w.length(); i++) {
                abb.append(w.charAt(i));
                if (abb.length() >= maxLen) return upper(abb.toString());
            }
        }
        return upper(abb.toString());
    }

    /// Abbreviate: trim; text already short enough is uppercased verbatim (non-ASCII letters
    /// included); otherwise initials, falling back to the hybrid scheme when the initials come up
    /// short. Locale-independent upper-casing.
    ///
    /// KNOWN LIMIT: `String.strip()` does not treat U+00A0 NBSP as whitespace, so an
    /// NBSP-padded name of ≤ maxLen letters is measured with the padding included.
    public static String makeAbbreviation(String text, int maxLen) {
        text = text.strip();
        if (text.length() <= maxLen) return upper(text);
        String abb = abbreviateInitials(text, maxLen);
        if (abb.length() < maxLen) abb = abbreviateHybrid(text, maxLen);
        return upper(abb);
    }

    /// Unicode full uppercase mapping, no locale (ß -&gt; SS, i -&gt; I).
    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /// Per-object operation: derive the short name from the name. Needs no query phase. A name bean
    /// with a null value — an empty content list — is SKIPPED rather than indexed into.
    public static boolean vehicleTypeShortNameOp(Object obj) {
        VehicleType vt = (VehicleType) obj;
        if (Mls.text(vt.getName()) != null) {
            vt.setShortName(Mls.of(makeAbbreviation(Mls.text(vt.getName()), 5)));
            return true;
        }
        return false;
    }

    /// Per-object operation: backfill missing quay names from the StopPlace name.
    public static boolean quayNameOp(Object obj) {
        StopPlace sp = (StopPlace) obj;
        boolean changed = false;
        if (sp.getQuays() != null) {
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay quay) {
                    if (quay.getName() == null) {
                        quay.setName(sp.getName());
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }
}
