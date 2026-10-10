package toolkit.transform.epip;

// A line name for the feeds that publish none: the two places its service runs between.
//
// Every Austrian Verbund line and every South Tyrol line arrives with Name equal to PublicCode --
// the number twice, and no name at all. Taking the most-travelled destination in each direction and
// joining the two gives the line the pair of places a rider knows it by. The pair is ordered
// return-first, so it reads as the outbound journey's origin and then its destination.
//
// It runs after [DestinationDisplays], in the second ensemble pass, and that is the whole reason the
// pass is here rather than in a feed-stage driver. It reads the display the first stop point of each
// pattern names, and at the feed stage a publisher's displays carry no front text or its patterns
// reference none at all, so a feed-stage pass derives nothing for them. By this point the synthesis
// has minted a display for every pattern that lacked one, and direction is on the pattern for every
// publisher, so one rule replaces what used to be a per-publisher choice between a Route's
// DirectionRef and a pattern's DirectionType.
//
// It must also run before [ProductCategories#lineNameOp], which is the Name backstop: that fills a
// nameless Line from its PublicCode, its ShortName, or failing both the identifier part of its id.
// The last of those is a name [#wantsAName] will not replace, so a Line reaching this pass after the
// backstop would keep a fragment of its id forever.

import noi.netex.model.DestinationDisplay;
import noi.netex.model.DirectionTypeEnumeration;
import noi.netex.model.Line_VersionStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceJourneyPatternRefStructure;
import noi.netex.model.ServiceJourney_VersionStructure;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.TemplateServiceJourney;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.harness.Ensemble;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.JourneyLines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class TerminusNames {

    private TerminusNames() {}

    /// The en dash between the two places. A display's text may itself contain " - ", so the
    /// separator has to be a character the publisher does not use.
    private static final String JOIN = " – ";

    /// The counters the pass reports, for the same reason [DestinationDisplays.Counts] does: the op
    /// is a lambda inside somebody else's scan and has nowhere of its own to log.
    ///
    /// These count ROWS, not line ids. The store keys a Line by (id, version, class) and a Mentz
    /// export ships up to 37 versions of one line, each its own row; the ensemble names every one.
    public static final class Counts {
        public long rowsNamed;
        public long paired;              // both directions contributed a place
        public long single;              // one direction, or both naming the same place
        public long noDestination;       // no pattern of this line reaches a named display

        @Override
        public String toString() {
            return String.format("named=%d (%d as a pair, %d from one direction), %d left as their "
                    + "number for want of a destination display", rowsNamed, paired, single,
                    noDestination);
        }
    }

    /// The per-object operation and its counters.
    public record NameTermini(Ensemble.Operation op, Counts counts) {}

    /// Query phase: index the places each line's patterns run to, per direction, then return the
    /// per-object operation (Line or FlexibleLine -&gt; changed?).
    ///
    /// Register it for both line classes: the Austrian feeds publish 104 FlexibleLines, all
    /// demand-responsive, and they want a name as much as any other.
    ///
    /// @param db the store to read
    /// @param txn the transaction to read at
    public static NameTermini nameTerminiOp(Store db, Txn txn) {
        Map<String, Map<Integer, Map<String, Long>>> perLine = destinationsByLine(db, txn);
        Counts counts = new Counts();

        Ensemble.Operation process = obj -> {
            Line_VersionStructure line = (Line_VersionStructure) obj;
            if (!wantsAName(line)) return false;
            Map<Integer, Map<String, Long>> byDirection = perLine.get(line.getId());
            if (byDirection == null || byDirection.isEmpty()) {
                counts.noDestination++;
                return false;
            }
            List<String> places = new ArrayList<>();
            for (Integer rank : new TreeSet<>(byDirection.keySet())) {
                String place = heaviest(byDirection.get(rank));
                if (place != null && !places.contains(place)) places.add(place);
            }
            if (places.isEmpty()) {
                counts.noDestination++;
                return false;
            }
            if (line.getName() == null) line.setName(Mls.of(null));
            Mls.setText(line.getName(), String.join(JOIN, places));
            if (places.size() > 1) counts.paired++; else counts.single++;
            counts.rowsNamed++;
            return true;
        };
        return new NameTermini(process, counts);
    }

    /// Whether `line` has no name of its own worth keeping: absent, blank, or its own code repeated.
    private static boolean wantsAName(Line_VersionStructure line) {
        String name = Mls.text(line.getName());
        if (name == null || name.isBlank()) return true;
        return name.equals(Codes.text(line.getPublicCode()))
                || name.equals(Mls.text(line.getShortName()));
    }

    /// Line id to direction rank to destination place to the number of journeys that ran to it.
    ///
    /// Keyed by line id and not by row: a journey names a line id, so every version of that line
    /// shares one histogram. That is not the keying hazard it resembles -- the op above writes to
    /// each row separately, and this map is only read.
    private static Map<String, Map<Integer, Map<String, Long>>> destinationsByLine(Store db,
                                                                                   Txn txn) {
        Map<String, String> displayText = displayText(db, txn);
        Map<String, String> patternDisplay = new HashMap<>();
        Map<String, Integer> patternRank = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
            ServiceJourneyPattern sjp = (ServiceJourneyPattern) o;
            String display = firstPointDisplay(sjp);
            if (display != null) patternDisplay.put(sjp.getId(), display);
            Integer rank = rankOf(sjp.getDirectionType());
            if (rank == null && sjp.getDirectionRef() != null) {
                rank = rankOf(sjp.getDirectionRef().getRef());
            }
            if (rank != null) patternRank.put(sjp.getId(), rank);
        }

        JourneyLines.Index ix = JourneyLines.index(db, txn);
        Map<String, Map<Integer, Map<String, Long>>> out = new HashMap<>();
        for (Class<?> c : List.of(ServiceJourney.class, TemplateServiceJourney.class)) {
            for (Object o : db.iterOnlyObjects(txn, c)) {
                ServiceJourney_VersionStructure sj = (ServiceJourney_VersionStructure) o;
                String lineId = JourneyLines.lineIdOf(sj, ix);
                String sjp = patternRefOf(sj);
                if (lineId == null || sjp == null) continue;
                String place = displayText.get(patternDisplay.get(sjp));
                Integer rank = patternRank.get(sjp);
                if (place == null || place.isBlank() || rank == null) continue;
                out.computeIfAbsent(lineId, k -> new LinkedHashMap<>())
                        .computeIfAbsent(rank, k -> new LinkedHashMap<>())
                        .merge(place, 1L, Long::sum);
            }
        }
        return out;
    }

    /// The place with the most journeys, ties broken on the text so a run is repeatable.
    private static String heaviest(Map<String, Long> counts) {
        String best = null;
        long bestCount = -1;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() > bestCount
                    || (e.getValue() == bestCount && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /// The display a pattern's first point names, which is where that pattern is heading.
    private static String firstPointDisplay(ServiceJourneyPattern sjp) {
        if (sjp.getPointsInSequence() == null) return null;
        List<PointInLinkSequence_VersionedChildStructure> points = sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        if (points.isEmpty()) return null;
        if (!(points.get(0) instanceof StopPointInJourneyPattern first)) return null;
        return first.getDestinationDisplayRef() == null
                ? null : first.getDestinationDisplayRef().getRef();
    }

    /// DestinationDisplay id to the place it names: its SideText, else its FrontText. SideText is
    /// the short form, which is what reads as one half of a pair.
    private static Map<String, String> displayText(Store db, Txn txn) {
        Map<String, String> out = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, DestinationDisplay.class)) {
            DestinationDisplay dd = (DestinationDisplay) o;
            String text = Mls.text(dd.getSideText());
            if (text == null || text.isBlank()) text = Mls.text(dd.getFrontText());
            if (text != null && !text.isBlank()) out.put(dd.getId(), text.strip());
        }
        return out;
    }

    /// The return direction ranks before the outbound one, so the pair reads as the outbound
    /// journey's origin and then its destination.
    private static Integer rankOf(DirectionTypeEnumeration type) {
        if (type == DirectionTypeEnumeration.INBOUND) return 0;
        if (type == DirectionTypeEnumeration.OUTBOUND) return 1;
        return null;
    }

    /// The same ranking off a Direction's id, whose last token is the publisher's Hin/Rück letter.
    private static Integer rankOf(String directionRef) {
        if (directionRef == null) return null;
        String token = directionRef.endsWith(":")
                ? directionRef.substring(0, directionRef.length() - 1) : directionRef;
        int at = token.lastIndexOf(':');
        token = at < 0 ? token : token.substring(at + 1);
        if ("R".equalsIgnoreCase(token) || "inbound".equalsIgnoreCase(token)) return 0;
        if ("H".equalsIgnoreCase(token) || "outbound".equalsIgnoreCase(token)) return 1;
        return null;
    }

    private static String patternRefOf(ServiceJourney_VersionStructure sj) {
        if (sj.getJourneyPatternRef() == null) return null;
        return sj.getJourneyPatternRef().getValue()
                instanceof ServiceJourneyPatternRefStructure ref ? ref.getRef() : null;
    }
}
