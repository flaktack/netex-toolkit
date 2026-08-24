package toolkit.transform.epip;

// The EPIP backfill ensemble ops:
//   serviceJourneyOperatorOp   (op factory)
//   vehicleTypeShortNameOp     (per-object op)
//   quayNameOp                 (per-object op)
//
// None of the three is gated on a codespace or an id prefix: EpipToDb wires them unconditionally
// and they act on every object of their class. They are profile backfills, not a vendor repair,
// which is why they live here and not in feedfix.
//   makeAbbreviation / abbreviateInitials / abbreviateHybrid
//
// Union-field mapping: a single-slot choice field maps to a pair of sibling getters, where
// setting the ref also nulls the view.
//   sj operator union   -> getOperatorRef() / getOperatorView()
//   sj line union       -> getLineRef() (a JAXBElement covering LineRef AND FlexibleLineRef) /
//                          getLineView() / getFlexibleLineView()
//   sjp route union     -> getRouteRef() / getRouteView()
//   RouteView line union-> RouteView.getLineRef() / getLineView()
//   sj journey-pattern  -> JAXBElement value instanceof ServiceJourneyPatternRefStructure
//   sp.quays            -> getQuays().getQuayRefOrQuay(); a Quay is value instanceof Quay

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

    /// Query phase: build the line/route/pattern -&gt; operator indexes once, and return the
    /// per-object operation (ServiceJourney -&gt; changed?). All information the operation needs is
    /// ready before the first object is processed, so it can run in any stream/ensemble/worker.
    ///
    /// FlexibleLines carry operators exactly like Lines, so both classes feed the same index; every
    /// lookup is via `Map.get`, so an unresolvable line/route/pattern ref leaves the journey
    /// unchanged rather than crashing the ensemble. A line without an operator maps its id to null.
    public static Ensemble.Operation serviceJourneyOperatorOp(Store dbRead, Txn txn) {
        Map<String, OperatorRefStructure> lineOperatorRef = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            lineOperatorRef.put(line.getId(), line.getOperatorRef());
        }
        for (Object o : dbRead.iterOnlyObjects(txn, FlexibleLine.class)) {
            FlexibleLine fl = (FlexibleLine) o;
            lineOperatorRef.put(fl.getId(), fl.getOperatorRef());
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
                    sj.setOperatorRef(op);                         // union assignment: the ref
                    sj.setOperatorView(null);                      // replaces any view
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
    /// included) until `maxLen`; uppercased. Both exits uppercase — the early return and the final
    /// one, which also covers a result shorter than `maxLen`.
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
    /// KNOWN LIMIT (OPEN.md §16): `String.strip()` does not treat U+00A0 NBSP as whitespace, so an
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

    // ----------------------------------------------- vehicleTypeShortNameOp

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

    // --------------------------------------------------------------- quayNameOp

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
