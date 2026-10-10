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
// the ref also nulls the view — getOperatorRef() against getOperatorView().

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.FlexibleLine;
import noi.netex.model.Line;
import noi.netex.text.Mls;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.Quay;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPlace;
import noi.netex.model.TransportOrganisationRefs_RelStructure;
import noi.netex.model.TypeOfPlaceRefStructure;
import noi.netex.model.TypeOfPlaceRefs_RelStructure;
import noi.netex.model.VehicleType;
import toolkit.harness.Ensemble;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.JourneyLines;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /// Query phase: build the line -&gt; operator index and the journey -&gt; line hops once, and return
    /// the per-object operation (ServiceJourney -&gt; changed?). Everything the operation needs is
    /// ready before the first object is processed.
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
        JourneyLines.Index lines = JourneyLines.index(dbRead, txn);

        return obj -> {
            ServiceJourney sj = (ServiceJourney) obj;
            // The operator union's single slot, before the backfill: a ref wins over a view.
            Object before = sj.getOperatorRef() != null ? sj.getOperatorRef() : sj.getOperatorView();
            if (sj.getOperatorRef() == null) {
                String lineId = JourneyLines.lineIdOf(sj, lines);
                OperatorRefStructure op = lineId == null ? null : lineOperatorRef.get(lineId);
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

    // ------------------------------------------------- stopPlaceTypeOfPlaceOp

    /// The two TypeOfPlace values the profile reserves for the stop hierarchy, in the `epip`
    /// codespace it keeps for its own metadata.
    private static final String EPIP_GENERAL = "epip:general";
    private static final String EPIP_MONOMODAL = "epip:monomodal";

    /// Query phase: collect the StopPlaces that are somebody's parent, and return the per-object
    /// operation that categorises each one.
    ///
    /// The profile requires every StopPlace to be categorised `epip:general` or `epip:monomodal`,
    /// and bounds the hierarchy at two levels with a meaning for each:
    ///
    ///   - GENERAL may hold quays or contain monomodal StopPlaces, and shall NOT itself be
    ///     contained in one.
    ///   - MONOMODAL shall hold at least one quay, shall contain no StopPlace, and all its quays
    ///     shall serve the same transport mode.
    ///
    /// So a contained StopPlace can only be monomodal -- general is defined as not contained -- and
    /// anything else is general, which the definition permits whether or not it has quays.
    ///
    /// Same-mode is not checked. Nothing in a site frame carries a transport mode: no StopPlace and
    /// no Quay declares one, and the mode is only reachable by joining a quay through its stop
    /// assignment to a scheduled stop point, then to the patterns that call there and the lines
    /// those belong to. For a contained StopPlace the label is forced regardless, since the profile
    /// offers no other value for one; for the rest the general label asserts nothing about mode.
    ///
    /// The category is ADDED to placeTypes, which is a list: a publisher's own classifications are
    /// data, and the profile asks for its category to be present, not for theirs to be removed.
    /// Idempotent -- a StopPlace already carrying an `epip:` category is left alone, so a second run
    /// over the same store writes nothing.
    public static Ensemble.Operation stopPlaceTypeOfPlaceOp(Store dbRead, Txn txn) {
        Set<String> parents = new HashSet<>();
        for (Object o : dbRead.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            if (sp.getParentSiteRef() != null && sp.getParentSiteRef().getRef() != null) {
                parents.add(sp.getParentSiteRef().getRef());
            }
        }
        return obj -> {
            StopPlace sp = (StopPlace) obj;
            TypeOfPlaceRefs_RelStructure types = sp.getPlaceTypes();
            if (types != null) {
                for (TypeOfPlaceRefStructure r : types.getTypeOfPlaceRef()) {
                    if (r.getRef() != null && r.getRef().startsWith("epip:")) return false;
                }
            }
            boolean contained = sp.getParentSiteRef() != null
                    && sp.getParentSiteRef().getRef() != null;
            boolean isParent = parents.contains(sp.getId());
            String category = contained && !isParent ? EPIP_MONOMODAL : EPIP_GENERAL;

            TypeOfPlaceRefStructure ref = new TypeOfPlaceRefStructure();
            ref.setRef(category);
            // An external reference: the profile's own instances are not in this document unless a
            // metadata frame carries them, and it requires the version on a versionRef there.
            ref.setVersionRef("1.0");
            if (types == null) {
                types = new TypeOfPlaceRefs_RelStructure();
                sp.setPlaceTypes(types);
            }
            types.getTypeOfPlaceRef().add(ref);
            return true;
        };
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
