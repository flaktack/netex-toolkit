package toolkit.transform.epip;

// Destination display synthesis -- the headsign backfill.
//
// This class shares its stem with noi.netex.model.DestinationDisplay, the object it synthesizes; the
// plural is the transform, the singular the bean.
//
// A consumer reads a trip's headsign off the display the first stop point of its pattern references
// and off nothing else, then carries that one value forward to every later stop of the run. A
// DestinationDisplayView in the same slot is not read at all, which is why this mints first-class
// objects rather than filling the view.
//
// Most of the corpus arrives without one: Trenitalia, Italo, FlixBus and most of the Italian regional
// feeds publish not a single DestinationDisplay, and a Mentz export leaves about half its patterns
// without one at the first point. Those journeys reach a consumer with no headsign at all, which
// leaves it showing whatever the publisher put in ServiceJourneyPattern/Name -- for Lombardia,
// "da: Piacenza a: Milano Greco Pirelli (10 ferm.)".
//
// Where the first point names no display, this mints one naming the pattern's last stop, which is what
// the publishers who do ship displays mean by one: theirs name the terminus, exactly or within a via
// qualifier of it.
//
// A display at a later point is left where it is and never borrowed, because in the Austrian exports
// those mostly hold the line number -- "577" on a run to Kötschach-Mauthen Alter Bahnhof -- or nothing
// at all. The publisher's own display still takes over mid-run through the carry-forward.
//
// It runs in phase C, after the journeys generator has minted the pattern ids. The display feeds the
// frozen pattern-identity signature ([EpipJourneys#pointInPatternSignature]), so writing one in phase
// B -- or onto a Call ahead of the projection -- would re-id every synthesized pattern in the corpus.
// This must not move.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.DestinationDisplay;
import noi.netex.model.DestinationDisplayRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.text.Mls;
import toolkit.harness.Ensemble;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DestinationDisplays {

    private DestinationDisplays() {}

    /// What a destination stop contributes to the display minted for it: the text, and the version
    /// the display inherits — the convention every other generated object here follows, a
    /// UicOperatingPeriod taking its DayType's and an Authority its Operator's.
    private record Terminus(String name, String version) {}

    /// The counters the pass reports, for the same reason `DayOffsetRewrite.Stats` does: the op is a
    /// lambda inside somebody else's scan and has nowhere of its own to log.
    public static final class Counts {
        public long patternsNamed;
        public long displaysMinted;
        public long destinationUnnamed;    // last stop resolves to no name: left alone
        public long refsUnresolved;        // first point named a display the feed never defines
        public long patternsTooShort;      // fewer than two stop points
        public long patternsCircular;      // first stop == last stop, so the pair names one place

        @Override
        public String toString() {
            return String.format("patterns=%d displays=%d (destination-unnamed=%d "
                            + "dangling-first-ref=%d under-two-points=%d circular=%d)",
                    patternsNamed, displaysMinted, destinationUnnamed, refsUnresolved,
                    patternsTooShort, patternsCircular);
        }
    }

    /// The process, the displays it synthesized, and its counters.
    ///
    /// Drain-after contract, the same as [Direction.InferDirections]: [#op] populates [#minted]
    /// lazily DURING the ensemble pass — a display is synthesized the first time its destination is
    /// seen. Obtain `minted().values().iterator()` only AFTER the [Ensemble#applyOperations] iterator
    /// has been fully drained; iterating the values view concurrently with the pass throws
    /// `ConcurrentModificationException` and misses later insertions.
    public record SynthesizeDisplays(Ensemble.Operation op,
                                     Map<String, DestinationDisplay> minted,
                                     Counts counts) {}

    /// Query phase: index every ScheduledStopPoint's name and the ids the feed defines a display
    /// for, then return the per-object operation (ServiceJourneyPattern -&gt; changed?).
    ///
    /// The defined-id set is what makes a dangling first-point ref count as absent rather than as a
    /// display — the test [EpipJourneys] applies to a journey's type refs, for the same reason: a ref
    /// the document never resolves is read by nobody.
    ///
    /// One display per destination stop: every pattern ending at the same stop shares it.
    public static SynthesizeDisplays synthesizeDisplaysOp(Store dbRead, Txn txn,
                                                          GeneratorDefaults gd) {
        Map<String, Terminus> termini = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
            ScheduledStopPoint ssp = (ScheduledStopPoint) o;
            String name = Mls.text(ssp.getName());
            if (name == null || name.isBlank()) continue;
            termini.putIfAbsent(ssp.getId(), new Terminus(name.strip(), ssp.getVersion()));
        }
        Set<String> defined = new HashSet<>();
        for (Object o : dbRead.iterOnlyObjects(txn, DestinationDisplay.class)) {
            defined.add(((DestinationDisplay) o).getId());
        }

        Map<String, DestinationDisplay> minted = new LinkedHashMap<>();
        Map<String, DestinationDisplayRefStructure> refs = new HashMap<>();
        Counts counts = new Counts();

        Ensemble.Operation process = obj -> {
            ServiceJourneyPattern sjp = (ServiceJourneyPattern) obj;
            List<StopPointInJourneyPattern> points = stopPoints(sjp);
            if (points.size() < 2) {
                counts.patternsTooShort++;
                return false;
            }
            StopPointInJourneyPattern first = points.get(0);
            if (first.getDestinationDisplayView() != null) return false;
            DestinationDisplayRefStructure had = first.getDestinationDisplayRef();
            if (had != null) {
                if (defined.contains(had.getRef())) return false;
                counts.refsUnresolved++;
            }

            String destination = stopPointRef(points.get(points.size() - 1));
            Terminus terminus = destination == null ? null : termini.get(destination);
            if (terminus == null) {
                counts.destinationUnnamed++;
                return false;
            }

            DestinationDisplayRefStructure ref = refs.get(destination);
            if (ref == null) {
                DestinationDisplay display = new DestinationDisplay();
                display.setId(NetexUtils.deriveId(
                        destination, DestinationDisplay.class, gd.codespace()));
                display.setVersion(terminus.version() != null ? terminus.version() : gd.version());
                // FrontText alone: it is the field a consumer reads, and SideText is the half of a
                // terminus pair a line-naming pass consumes, which runs on the source feeds long
                // before this.
                display.setFrontText(Mls.of(terminus.name()));
                minted.put(destination, display);
                ref = NetexUtils.getRef(display, DestinationDisplayRefStructure.class);
                refs.put(destination, ref);
                counts.displaysMinted++;
            }
            first.setDestinationDisplayRef(ref);
            counts.patternsNamed++;
            if (destination.equals(stopPointRef(first))) counts.patternsCircular++;
            return true;
        };

        return new SynthesizeDisplays(process, minted, counts);
    }

    /// A pattern's stop points in running order. The points list is the sequence, which is the
    /// assumption the rest of the profile makes; it is re-sorted only where every point states an
    /// order, so a feed that publishes them shuffled still names its true terminus.
    private static List<StopPointInJourneyPattern> stopPoints(ServiceJourneyPattern sjp) {
        List<StopPointInJourneyPattern> out = new ArrayList<>();
        if (sjp.getPointsInSequence() == null) return out;
        boolean ordered = true;
        for (PointInLinkSequence_VersionedChildStructure p : sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
            if (!(p instanceof StopPointInJourneyPattern stop)) continue;
            ordered &= stop.getOrder() != null;
            out.add(stop);
        }
        if (ordered && out.size() > 1) {
            out.sort(Comparator.comparing(StopPointInJourneyPattern::getOrder,
                    Comparator.nullsLast(BigInteger::compareTo)));
        }
        return out;
    }

    /// The ScheduledStopPoint a stop point stands at, or null. The union's other arm is a
    /// FareScheduledStopPointRef, which names a fare point and no place.
    private static String stopPointRef(StopPointInJourneyPattern point) {
        JAXBElement<? extends ScheduledStopPointRefStructure> el = point.getScheduledStopPointRef();
        if (el == null || el.getValue() == null) return null;
        ScheduledStopPointRefStructure ref = el.getValue();
        return ref instanceof noi.netex.model.FareScheduledStopPointRefStructure ? null : ref.getRef();
    }
}
