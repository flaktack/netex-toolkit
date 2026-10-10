package toolkit.transform.epip;

// A line that states no name of its own is named after the two places its service runs between.
//
// pattern states direction on the pattern, as the Austrian exports do, and ships TWO version rows of
// one id -- the shape that used to lose its name, because a caller composing through a map keyed by
// id could only ever write one of them back. route states it through a DirectionRef whose last token
// is the publisher's Hin/Rück letter, as South Tyrol does. heaviest runs twice to one place and once
// to another in the same direction. named already carries a real name and is left alone. flex is a
// FlexibleLine, which wants a name as much as a Line.
//
// The second fixture publishes no DestinationDisplay at all and goes through the real
// EpipToDb.inPlace, which is the only way to pin the ordering the pass depends on: the displays it
// reads do not exist until the synthesis mints them, and the Name backstop must not claim a line
// first.

import noi.netex.model.FlexibleLine;
import noi.netex.model.Line;
import noi.netex.model.Line_VersionStructure;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.harness.EpipToDb;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class TestTerminusNames {

    private static final String NETEX = """
        <ServiceFrame id="sf" version="1">
          <destinationDisplays>
            <DestinationDisplay id="dd:villach" version="1"><FrontText>Villach Hauptbahnhof</FrontText></DestinationDisplay>
            <DestinationDisplay id="dd:spittal" version="1"><FrontText>Spittal an der Drau</FrontText></DestinationDisplay>
            <DestinationDisplay id="dd:lienz" version="1"><SideText>Lienz</SideText><FrontText>Lienz Bahnhof</FrontText></DestinationDisplay>
            <DestinationDisplay id="dd:fortezza" version="1"><SideText>Fortezza</SideText></DestinationDisplay>
            <DestinationDisplay id="dd:thal" version="1"><FrontText>Thal</FrontText></DestinationDisplay>
          </destinationDisplays>
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp:a" version="1"><Name>Alpha</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="ssp:b" version="1"><Name>Beta</Name></ScheduledStopPoint>
          </scheduledStopPoints>
          <lines>
            <Line id="at:x:Line:pattern" version="1"><Name>77</Name><PublicCode>77</PublicCode></Line>
            <Line id="at:x:Line:pattern" version="2"><Name>77</Name><PublicCode>77</PublicCode></Line>
            <Line id="it:apb:Line:route" version="1"><Name>REG</Name><ShortName>REG</ShortName><PublicCode>REG</PublicCode></Line>
            <Line id="at:x:Line:heaviest" version="1"><Name>12</Name><PublicCode>12</PublicCode></Line>
            <Line id="at:x:Line:named" version="1"><Name>Real Name</Name><PublicCode>9</PublicCode></Line>
          </lines>
          <flexibleLines>
            <FlexibleLine id="at:x:FlexibleLine:flex" version="1"><Name>R1</Name><PublicCode>R1</PublicCode><FlexibleLineType>mainRouteWithFlexibleEnds</FlexibleLineType></FlexibleLine>
          </flexibleLines>
          <journeyPatterns>
            <ServiceJourneyPattern id="pat:out" version="1"><DirectionType>outbound</DirectionType><pointsInSequence>
              <StopPointInJourneyPattern id="p:out:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:a" version="1"/><DestinationDisplayRef ref="dd:spittal" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:out:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat:in" version="1"><DirectionType>inbound</DirectionType><pointsInSequence>
              <StopPointInJourneyPattern id="p:in:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:b" version="1"/><DestinationDisplayRef ref="dd:villach" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:in:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:a" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat:sta:h" version="1"><DirectionRef ref="it:apb:Direction:H:" version="1"/><pointsInSequence>
              <StopPointInJourneyPattern id="p:sta:h:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:a" version="1"/><DestinationDisplayRef ref="dd:fortezza" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:sta:h:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat:sta:r" version="1"><DirectionRef ref="it:apb:Direction:R:" version="1"/><pointsInSequence>
              <StopPointInJourneyPattern id="p:sta:r:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:b" version="1"/><DestinationDisplayRef ref="dd:lienz" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:sta:r:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:a" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat:heavy" version="1"><DirectionType>outbound</DirectionType><pointsInSequence>
              <StopPointInJourneyPattern id="p:heavy:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:a" version="1"/><DestinationDisplayRef ref="dd:spittal" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:heavy:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat:light" version="1"><DirectionType>outbound</DirectionType><pointsInSequence>
              <StopPointInJourneyPattern id="p:light:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:a" version="1"/><DestinationDisplayRef ref="dd:thal" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:light:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat:flex" version="1"><DirectionType>outbound</DirectionType><pointsInSequence>
              <StopPointInJourneyPattern id="p:flex:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:a" version="1"/><DestinationDisplayRef ref="dd:thal" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:flex:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat:named" version="1"><DirectionType>outbound</DirectionType><pointsInSequence>
              <StopPointInJourneyPattern id="p:named:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:a" version="1"/><DestinationDisplayRef ref="dd:thal" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="p:named:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:b" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="sj:out" version="1"><LineRef ref="at:x:Line:pattern" version="1"/><ServiceJourneyPatternRef ref="pat:out" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:in" version="1"><LineRef ref="at:x:Line:pattern" version="1"/><ServiceJourneyPatternRef ref="pat:in" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:sta:h" version="1"><LineRef ref="it:apb:Line:route" version="1"/><ServiceJourneyPatternRef ref="pat:sta:h" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:sta:r" version="1"><LineRef ref="it:apb:Line:route" version="1"/><ServiceJourneyPatternRef ref="pat:sta:r" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:heavy:1" version="1"><LineRef ref="at:x:Line:heaviest" version="1"/><ServiceJourneyPatternRef ref="pat:heavy" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:heavy:2" version="1"><LineRef ref="at:x:Line:heaviest" version="1"/><ServiceJourneyPatternRef ref="pat:heavy" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:light" version="1"><LineRef ref="at:x:Line:heaviest" version="1"/><ServiceJourneyPatternRef ref="pat:light" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:flex" version="1"><FlexibleLineRef ref="at:x:FlexibleLine:flex" version="1"/><ServiceJourneyPatternRef ref="pat:flex" version="1"/></ServiceJourney>
            <ServiceJourney id="sj:named" version="1"><LineRef ref="at:x:Line:named" version="1"/><ServiceJourneyPatternRef ref="pat:named" version="1"/></ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    /// Line (id, version) to the name the op wrote, over every row it changed.
    private record Named(Map<String, String> byRow, TerminusNames.Counts counts) {}

    private static Named run(TestStore ts) throws Exception {
        ts.loadNetex(NETEX);
        try (Txn txn = ts.store.roTxn()) {
            TerminusNames.NameTermini t = TerminusNames.nameTerminiOp(ts.store, txn);
            Map<String, String> changed = new LinkedHashMap<>();
            for (Class<?> c : java.util.List.of(Line.class, FlexibleLine.class)) {
                for (Object o : ts.store.iterOnlyObjects(txn, c)) {
                    Line_VersionStructure line = (Line_VersionStructure) o;
                    if (t.op().apply(line)) {
                        changed.put(line.getId() + "@" + line.getVersion(),
                                Mls.text(line.getName()));
                    }
                }
            }
            return new Named(changed, t.counts());
        }
    }

    @Test
    void testTheAustrianShapeNamesAPairFromTheDirectionType(TestStore ts) throws Exception {
        Named r = run(ts);
        Check.equals("Villach Hauptbahnhof – Spittal an der Drau",
                r.byRow().get("at:x:Line:pattern@1"),
                "the inbound destination reads first, so the pair is the outbound journey's origin "
                        + "and then its destination");
    }

    @Test
    void testEveryVersionRowOfOneLineIsNamed(TestStore ts) throws Exception {
        Named r = run(ts);
        Check.equals("Villach Hauptbahnhof – Spittal an der Drau",
                r.byRow().get("at:x:Line:pattern@2"),
                "the store keys a Line by (id, version) and a Mentz export ships many versions of "
                        + "one id; every row is its own object and every row gets the name");
        Check.equals(2L, r.byRow().keySet().stream()
                        .filter(k -> k.startsWith("at:x:Line:pattern@")).count(),
                "both rows of the id are changed, not one: " + r.byRow().keySet());
    }

    @Test
    void testTheSouthTyrolShapeNamesAPairFromTheDirectionRef(TestStore ts) throws Exception {
        Named r = run(ts);
        Check.equals("Lienz – Fortezza", r.byRow().get("it:apb:Line:route@1"),
                "direction comes off the DirectionRef's last token where the pattern states no "
                        + "DirectionType, and SideText is preferred as the short form");
    }

    @Test
    void testTheHeaviestDestinationWinsItsDirection(TestStore ts) throws Exception {
        Named r = run(ts);
        Check.equals("Spittal an der Drau", r.byRow().get("at:x:Line:heaviest@1"),
                "two journeys run to Spittal and one to Thal in the same direction, so Thal is not "
                        + "the place the line is known by; one direction yields no pair");
    }

    @Test
    void testAFlexibleLineIsNamedLikeALine(TestStore ts) throws Exception {
        Named r = run(ts);
        Check.equals("Thal", r.byRow().get("at:x:FlexibleLine:flex@1"),
                "the Austrian feeds publish demand-responsive lines as FlexibleLine, and a rider "
                        + "reads their name the same way");
    }

    @Test
    void testALineAlreadyNamedIsLeftAlone(TestStore ts) throws Exception {
        Named r = run(ts);
        Check.isNull(r.byRow().get("at:x:Line:named@1"),
                "a Name that is neither blank nor the line's own code is the publisher's and stays");
    }

    // ------------------------------------------------------------------ the ordering, end to end

    /// No DestinationDisplay anywhere, which is the majority shape: two thirds of the Austrian
    /// patterns publish none. The journeys carry the DirectionType, which is where phase B reads it
    /// from when it mints the pattern.
    ///
    /// numbered repeats its code as its name. nameless states no name, no PublicCode and no
    /// ShortName, so the backstop's last resort for it is a fragment of its own id.
    private static final String NO_DISPLAYS = """
        <ResourceFrame id="rf" version="1">
          <organisations>
            <Authority id="auth1" version="1"><Name>A</Name></Authority>
          </organisations>
        </ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="at:x:Line:numbered" version="1"><Name>77</Name><PublicCode>77</PublicCode><AuthorityRef ref="auth1" version="1"/></Line>
            <Line id="at:x:Line:nameless" version="1"><AuthorityRef ref="auth1" version="1"/></Line>
          </lines>
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp:villach" version="1"><Name>Villach Hauptbahnhof</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="ssp:spittal" version="1"><Name>Spittal an der Drau</Name></ScheduledStopPoint>
          </scheduledStopPoints>
        </ServiceFrame>
        <ServiceCalendarFrame id="scf" version="1">
          <ServiceCalendar id="sc" version="1">
            <dayTypes>
              <DayType id="dt1" version="1"><properties><PropertyOfDay><DaysOfWeek>Monday</DaysOfWeek></PropertyOfDay></properties></DayType>
            </dayTypes>
            <operatingPeriods>
              <UicOperatingPeriod id="uop1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-26T00:00:00</ToDate><ValidDayBits>1000000100000010000001</ValidDayBits></UicOperatingPeriod>
            </operatingPeriods>
            <dayTypeAssignments>
              <DayTypeAssignment id="dta1" version="1" order="1"><OperatingPeriodRef ref="uop1" version="1"/><DayTypeRef ref="dt1" version="1"/></DayTypeAssignment>
            </dayTypeAssignments>
          </ServiceCalendar>
        </ServiceCalendarFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="sj:num:out" version="1"><DirectionType>outbound</DirectionType><LineRef ref="at:x:Line:numbered" version="1"/><dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes><calls>
              <Call id="c:no:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:villach" version="1"/><Departure><Time>10:00:00</Time></Departure></Call>
              <Call id="c:no:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:spittal" version="1"/><Arrival><Time>10:30:00</Time></Arrival></Call>
            </calls></ServiceJourney>
            <ServiceJourney id="sj:num:in" version="1"><DirectionType>inbound</DirectionType><LineRef ref="at:x:Line:numbered" version="1"/><dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes><calls>
              <Call id="c:ni:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:spittal" version="1"/><Departure><Time>11:00:00</Time></Departure></Call>
              <Call id="c:ni:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:villach" version="1"/><Arrival><Time>11:30:00</Time></Arrival></Call>
            </calls></ServiceJourney>
            <ServiceJourney id="sj:nl:out" version="1"><DirectionType>outbound</DirectionType><LineRef ref="at:x:Line:nameless" version="1"/><dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes><calls>
              <Call id="c:lo:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:villach" version="1"/><Departure><Time>12:00:00</Time></Departure></Call>
              <Call id="c:lo:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:spittal" version="1"/><Arrival><Time>12:30:00</Time></Arrival></Call>
            </calls></ServiceJourney>
            <ServiceJourney id="sj:nl:in" version="1"><DirectionType>inbound</DirectionType><LineRef ref="at:x:Line:nameless" version="1"/><dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes><calls>
              <Call id="c:li:1" version="1" order="1"><ScheduledStopPointRef ref="ssp:spittal" version="1"/><Departure><Time>13:00:00</Time></Departure></Call>
              <Call id="c:li:2" version="1" order="2"><ScheduledStopPointRef ref="ssp:villach" version="1"/><Arrival><Time>13:30:00</Time></Arrival></Call>
            </calls></ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    private static Map<String, String> namesAfterTransform(TestStore ts, String name)
            throws Exception {
        Path file = ts.buildDbFile(NO_DISPLAYS, name);
        EpipToDb.inPlace(file, GeneratorDefaults.TEST);
        Map<String, String> out = new LinkedHashMap<>();
        try (Store db = Stores.open(file, true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, Line.class)) {
                Line_VersionStructure line = (Line_VersionStructure) o;
                out.put(line.getId(), Mls.text(line.getName()));
            }
        }
        return out;
    }

    /// The pass runs AFTER the display synthesis, so a feed that publishes no display at all still
    /// gets its lines named. Registered in the same ensemble as the synthesis it reads, it would
    /// have seen an empty index -- the minted values view is populated lazily during that pass and
    /// drained only after it.
    @Test
    void testALineIsNamedFromTheSynthesizedDisplays(TestStore ts) throws Exception {
        Map<String, String> names = namesAfterTransform(ts, "terminus-synth.lmdb");
        Check.equals("Villach Hauptbahnhof – Spittal an der Drau",
                names.get("at:x:Line:numbered"),
                "no DestinationDisplay is published anywhere, so this name can only have come from "
                        + "the ones the synthesis minted off the patterns' terminus stops");
    }

    /// The backstop fills a nameless Line from its code, failing that its ShortName, failing both a
    /// fragment of its id -- and that last one is a name the terminus pass will not replace. The two
    /// operations therefore have to run in this order within the pass, not the other.
    @Test
    void testTheNameBackstopDoesNotClaimALineFirst(TestStore ts) throws Exception {
        Map<String, String> names = namesAfterTransform(ts, "terminus-order.lmdb");
        Check.equals("Villach Hauptbahnhof – Spittal an der Drau",
                names.get("at:x:Line:nameless"),
                "a Line with no code and no ShortName takes the places it runs between, not the "
                        + "identifier part of its own id");
        Set<String> nameless = new LinkedHashSet<>();
        names.forEach((id, n) -> { if (n == null || n.isBlank()) nameless.add(id); });
        Check.that(nameless.isEmpty(),
                "and the backstop still closes whatever the terminus pass left: " + nameless);
    }
}
