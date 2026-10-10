package toolkit.transform.epip;

// A pattern whose first stop point names no destination display gets one naming its last stop.
//
// bare has none anywhere. published named its own and is left alone. dangling names one the feed
// never defines, which is no display at all. midrun names one at its SECOND point, which is left
// exactly where it is. shared ends where bare does and takes bare's display rather than a second.
// shuffled publishes its points out of document order and still finds its terminus. unnamed ends at a
// stop with no name, loop starts and ends at one, and lonely has a single point.

import noi.netex.model.DestinationDisplay;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TestDestinationDisplays {

    private static final String NETEX = """
        <ServiceFrame id="sf" version="1">
          <destinationDisplays>
            <DestinationDisplay id="at:x:DestinationDisplay:published" version="1"><FrontText>Klagenfurt Hauptbahnhof</FrontText></DestinationDisplay>
            <!-- the shape that makes borrowing a later display wrong: the line number, not a place -->
            <DestinationDisplay id="at:x:DestinationDisplay:number" version="1"><FrontText>577</FrontText></DestinationDisplay>
          </destinationDisplays>
          <scheduledStopPoints>
            <ScheduledStopPoint id="at:x:ScheduledStopPoint:a" version="1"><Name>Villach Hauptbahnhof</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="at:x:ScheduledStopPoint:b" version="1"><Name>Spittal an der Drau</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="at:x:ScheduledStopPoint:c" version="2"><Name>Kötschach-Mauthen Alter Bahnhof</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="at:x:ScheduledStopPoint:blank" version="1"><Name> </Name></ScheduledStopPoint>
            <!-- no class token to read: the id falls back to the generator's codespace -->
            <ScheduledStopPoint id="plain-ssp" version="1"><Name>Bolzano</Name></ScheduledStopPoint>
          </scheduledStopPoints>
          <journeyPatterns>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:bare" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-bare-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-bare-2" version="1" order="2"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:b" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-bare-3" version="1" order="3"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:c" version="2"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:published" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-pub-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/><DestinationDisplayRef ref="at:x:DestinationDisplay:published" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-pub-2" version="1" order="2"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:dangling" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-dang-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/><DestinationDisplayRef ref="at:x:DestinationDisplay:never-defined" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-dang-2" version="1" order="2"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:midrun" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-mid-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-mid-2" version="1" order="2"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:b" version="1"/><DestinationDisplayRef ref="at:x:DestinationDisplay:number" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-mid-3" version="1" order="3"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:c" version="2"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:shared" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-shared-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:b" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-shared-2" version="1" order="2"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:c" version="2"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:shuffled" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-shuf-2" version="1" order="2"><ScheduledStopPointRef ref="plain-ssp" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-shuf-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:unnamed" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-un-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-un-2" version="1" order="2"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:blank" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:loop" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-loop-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:b" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-loop-2" version="1" order="2"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp-loop-3" version="1" order="3"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="at:x:ServiceJourneyPattern:lonely" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp-lone-1" version="1" order="1"><ScheduledStopPointRef ref="at:x:ScheduledStopPoint:a" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        """;

    private record Result(Map<String, ServiceJourneyPattern> all,
                          Map<String, ServiceJourneyPattern> changed,
                          Map<String, DestinationDisplay> minted,
                          DestinationDisplays.Counts counts) {}

    private static Result run(TestStore ts) throws Exception {
        ts.loadNetex(NETEX);
        try (Txn txn = ts.store.roTxn()) {
            DestinationDisplays.SynthesizeDisplays s = DestinationDisplays.synthesizeDisplaysOp(
                    ts.store, txn, GeneratorDefaults.TEST);
            Map<String, ServiceJourneyPattern> all = new LinkedHashMap<>();
            Map<String, ServiceJourneyPattern> changed = new LinkedHashMap<>();
            for (Object o : ts.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern sjp = (ServiceJourneyPattern) o;
                all.put(sjp.getId(), sjp);
                if (s.op().apply(sjp)) changed.put(sjp.getId(), sjp);
            }
            return new Result(all, changed, s.minted(), s.counts());
        }
    }

    private static List<StopPointInJourneyPattern> points(ServiceJourneyPattern sjp) {
        List<StopPointInJourneyPattern> out = new ArrayList<>();
        for (PointInLinkSequence_VersionedChildStructure p : sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
            if (p instanceof StopPointInJourneyPattern stop) out.add(stop);
        }
        return out;
    }

    /// The display the point at `index` of the document order names, or null.
    private static String displayAt(ServiceJourneyPattern sjp, int index) {
        StopPointInJourneyPattern p = points(sjp).get(index);
        return p.getDestinationDisplayRef() == null ? null : p.getDestinationDisplayRef().getRef();
    }

    @Test
    void testAPatternWithNoDisplayTakesItsLastStop(TestStore ts) throws Exception {
        Result r = run(ts);
        ServiceJourneyPattern bare = r.changed().get("at:x:ServiceJourneyPattern:bare");
        Check.that(bare != null, "the bare pattern is changed: " + r.changed().keySet());
        Check.equals("at:x:DestinationDisplay:c", displayAt(bare, 0),
                "its first point names the display minted for its terminus, which is where a "
                        + "consumer reads the headsign");

        DestinationDisplay dd = r.minted().get("at:x:ScheduledStopPoint:c");
        Check.that(dd != null, "a display is minted for the terminus");
        Check.equals("Kötschach-Mauthen Alter Bahnhof", Mls.text(dd.getFrontText()),
                "FrontText is the destination stop's own name");
        Check.equals("2", dd.getVersion(),
                "and the version is the stop's, the convention every generated object here follows");
    }

    @Test
    void testAPublishedDisplayIsLeftAlone(TestStore ts) throws Exception {
        Result r = run(ts);
        Check.isNull(r.changed().get("at:x:ServiceJourneyPattern:published"),
                "a pattern whose first point already names a defined display is untouched");
        Check.equals("at:x:DestinationDisplay:published",
                displayAt(r.all().get("at:x:ServiceJourneyPattern:published"), 0),
                "the publisher's own wording survives");
    }

    @Test
    void testADanglingFirstRefCountsAsAbsent(TestStore ts) throws Exception {
        Result r = run(ts);
        ServiceJourneyPattern p = r.changed().get("at:x:ServiceJourneyPattern:dangling");
        Check.that(p != null, "a ref naming a display the feed never defines is no display");
        Check.equals("at:x:DestinationDisplay:b", displayAt(p, 0),
                "so the first point is repointed at a minted one");
        Check.equals(1L, r.counts().refsUnresolved, "and the dangling ref is counted");
    }

    @Test
    void testALaterDisplayIsNeitherBorrowedNorMoved(TestStore ts) throws Exception {
        ServiceJourneyPattern p = run(ts).changed().get("at:x:ServiceJourneyPattern:midrun");
        Check.that(p != null, "a pattern displaying only from its second stop still gains a first");
        Check.equals("at:x:DestinationDisplay:c", displayAt(p, 0),
                "the first point takes the terminus, NOT the line number the second point shows");
        Check.equals("at:x:DestinationDisplay:number", displayAt(p, 1),
                "and the publisher's mid-run display stays exactly where it was, to take over there");
    }

    @Test
    void testPatternsSharingATerminusShareOneDisplay(TestStore ts) throws Exception {
        Result r = run(ts);
        Check.equals(displayAt(r.changed().get("at:x:ServiceJourneyPattern:bare"), 0),
                displayAt(r.changed().get("at:x:ServiceJourneyPattern:shared"), 0),
                "one display per destination stop, not per pattern");
        Check.equals(3L, r.counts().displaysMinted,
                "three destinations over six named patterns: " + r.minted().keySet());
        Check.equals(6L, r.counts().patternsNamed,
                "bare, dangling, midrun, shared, shuffled, loop");
    }

    @Test
    void testTheTerminusIsReadInRunningOrderNotDocumentOrder(TestStore ts) throws Exception {
        Result r = run(ts);
        ServiceJourneyPattern p = r.changed().get("at:x:ServiceJourneyPattern:shuffled");
        Check.that(p != null, "the shuffled pattern is named");
        DestinationDisplay dd = r.minted().get("plain-ssp");
        Check.that(dd != null,
                "its terminus is the point with the highest order, not the first in the document");
        Check.equals("Bolzano", Mls.text(dd.getFrontText()), "which is the place it runs to");
        Check.equals("test:DestinationDisplay:plain-ssp", dd.getId(),
                "a stop id carrying no class token falls back to the generator's codespace");
    }

    @Test
    void testAnUnnamedOrSingleStopPatternIsLeftAlone(TestStore ts) throws Exception {
        Result r = run(ts);
        Check.isNull(r.changed().get("at:x:ServiceJourneyPattern:unnamed"),
                "a terminus with a blank Name offers no headsign, so nothing is invented");
        Check.isNull(r.changed().get("at:x:ServiceJourneyPattern:lonely"),
                "and a single-point pattern has no destination distinct from its origin");
        Check.equals(1L, r.counts().destinationUnnamed, "the unnamed terminus is counted");
        Check.equals(1L, r.counts().patternsTooShort, "as is the one-point pattern");
    }

    @Test
    void testALoopIsNamedAfterItsOwnOrigin(TestStore ts) throws Exception {
        Result r = run(ts);
        ServiceJourneyPattern loop = r.changed().get("at:x:ServiceJourneyPattern:loop");
        Check.that(loop != null, "a circular pattern is named too: its destination really is home");
        Check.equals("at:x:DestinationDisplay:b", displayAt(loop, 0), "the stop it returns to");
        Check.equals(1L, r.counts().patternsCircular, "and it is counted, being worth an eyeball");
    }
}
