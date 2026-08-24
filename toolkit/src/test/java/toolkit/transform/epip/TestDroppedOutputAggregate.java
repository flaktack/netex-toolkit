package toolkit.transform.epip;

// THE AGGREGATE, for the three script-side places that delete data from the pipeline's own output.
//
// WHY THESE TESTS EXIST. Each site below already said it was dropping something, once per item, at
// warn or error level; what none of them had was a TOTAL. The counters make the number exist and
// route it through toolkit.util.Strictness under the droppedOutput concern, so
// -Dtoolkit.strict.droppedOutput=true turns the number into a refusal.
//
// WHAT IS NOT ASSERTED HERE. The refusal itself is TestStrictness's subject: the flags are
// `static final`, read at class-init, and surefire hosts the suite in ONE JVM, so no test can flip
// droppedOutput and watch a site refuse. What these tests pin is that the counts are COMPLETE and
// correctly attributed, because a policy fed a wrong number refuses the wrong runs.
//
// COUNTER ISOLATION. Projection's and TimetabledPassingTimesProfile's counters are static (they
// belong to classes that own no pass), so every test here zeroes them with reportDropped() before
// it measures. That is also the production reset path, so the reset is under test too.

import noi.netex.calls.Calls;
import noi.netex.model.Call;
import noi.netex.model.Calls_RelStructure;
import noi.netex.model.LocationStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PointsInJourneyPattern_RelStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.TimetabledPassingTime;
import org.junit.jupiter.api.Test;
import toolkit.geo.Projection;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TestDroppedOutputAggregate {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    // One journey per drop reason, so a miscounted site shows up as a wrong FIELD rather than a
    // wrong total:
    //   sj_ok        survives; no calendar of any kind -> journeyWithoutDayType
    //   sj_dangling  passing times + a pattern ref that resolves to nothing -> unresolvedPattern
    //   sj_bare      no passing times, no calls, no pattern/time-demand pair -> noTiming
    //   sj_tdt       pattern ref + a TimeDemandTypeRef that resolves to nothing -> unresolvedTiming
    //   sj_nopoints  a pattern with no pointsInSequence -> patternWithoutPoints
    //   sj_ac0       an AvailabilityCondition whose ValidDayBits are "0" -> operatingPeriodWithoutDays
    //   sj_vb        a ValidBetween inside validityConditions, which no branch handles ->
    //                unhandledValidityCondition (and, having no ACs either, journeyWithoutDayType)
    //   sj_rv        a pattern carrying a RouteView with no line, and no LineRef on the journey
    //                to recover it from -> routeViewWithoutLine
    private static final String NETEX = """
        <ServiceFrame id="sf" version="1">
          <validityConditions>
            <AvailabilityCondition id="ac0" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-05T00:00:00</ToDate><ValidDayBits>0</ValidDayBits></AvailabilityCondition>
          </validityConditions>
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>A</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="ssp2" version="1"><Name>B</Name></ScheduledStopPoint>
          </scheduledStopPoints>
          <journeyPatterns>
            <ServiceJourneyPattern id="pat1" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp2" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
            <ServiceJourneyPattern id="pat_nopoints" version="1"/>
            <ServiceJourneyPattern id="pat_routeview" version="1">
              <RouteView/>
              <pointsInSequence>
                <StopPointInJourneyPattern id="rp1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="rp2" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="sj_ok" version="1">
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_ok_1" version="1"><StopPointInJourneyPatternRef ref="sp1" version="1" order="1"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
                <TimetabledPassingTime id="tpt_ok_2" version="1"><StopPointInJourneyPatternRef ref="sp2" version="1" order="2"/><ArrivalTime>10:30:00</ArrivalTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj_dangling" version="1">
              <ServiceJourneyPatternRef ref="pat_absent" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_dg_1" version="1"><DepartureTime>11:00:00</DepartureTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj_bare" version="1"/>
            <ServiceJourney id="sj_tdt" version="1">
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <TimeDemandTypeRef ref="tdt_absent" version="1"/>
            </ServiceJourney>
            <ServiceJourney id="sj_nopoints" version="1">
              <ServiceJourneyPatternRef ref="pat_nopoints" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_np_1" version="1"><DepartureTime>12:00:00</DepartureTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj_ac0" version="1">
              <validityConditions><AvailabilityConditionRef ref="ac0" version="1"/></validityConditions>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_a0_1" version="1"><StopPointInJourneyPatternRef ref="sp1" version="1" order="1"/><DepartureTime>13:00:00</DepartureTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj_vb" version="1">
              <validityConditions><ValidBetween><FromDate>2026-01-05T00:00:00</FromDate></ValidBetween></validityConditions>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_vb_1" version="1"><StopPointInJourneyPatternRef ref="sp1" version="1" order="1"/><DepartureTime>14:00:00</DepartureTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj_rv" version="1">
              <ServiceJourneyPatternRef ref="pat_routeview" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_rv_1" version="1"><StopPointInJourneyPatternRef ref="rp1" version="1" order="1"/><DepartureTime>15:00:00</DepartureTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    // ------------------------------------------------- the EPIP journey generator

    /// Every drop reason counted, and counted under its OWN field. Four journeys are deleted from
    /// the output and four are emitted with something missing; before this, the first four were
    /// four log lines and the second four were three (one is a logOnce), and nothing anywhere
    /// added them up.
    @Test
    void testGeneratorCountsEveryDrop(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        EpipJourneys.Dropped dropped = new EpipJourneys.Dropped();

        List<Object> emitted = Gen.run(db, (s, txn) ->
                EpipJourneys.epipServiceJourneyGenerator(s, txn, GeneratorDefaults.TEST, dropped));

        Set<String> ids = new LinkedHashSet<>();
        for (Object o : emitted) {
            if (o instanceof ServiceJourney sj) ids.add(sj.getId());
        }
        Check.equals(Set.of("sj_ok", "sj_ac0", "sj_vb", "sj_rv"), ids,
                "the four unresolvable journeys are dropped and the rest survive");

        Check.equals(1L, dropped.unresolvedPattern, "sj_dangling: unresolved ServiceJourneyPatternRef");
        Check.equals(1L, dropped.unresolvedTiming, "sj_tdt: unresolved TimeDemandTypeRef");
        Check.equals(1L, dropped.noTiming, "sj_bare: no timing transformation matches");
        Check.equals(1L, dropped.patternWithoutPoints, "sj_nopoints: the pattern has no points");
        Check.equals(4L, dropped.droppedJourneys(), "four journeys deleted from the output");

        Check.equals(1L, dropped.unhandledValidityCondition,
                "sj_vb: a ValidBetween in validityConditions is dropped, days and all");
        Check.equals(3L, dropped.journeyWithoutDayType,
                "sj_ok, sj_vb and sj_rv are emitted with no day type at all");
        Check.equals(1L, dropped.operatingPeriodWithoutDays,
                "sj_ac0's AvailabilityCondition yields no valid days");
        Check.equals(1L, dropped.routeViewWithoutLine,
                "pat_routeview keeps a RouteView with no line to recover");
        Check.equals(6L, dropped.incompleteJourneys(), "six objects emitted with something missing");
    }

    /// The 3-arg generator — the one every driver calls — still reports at the end of its pass.
    /// It cannot be observed through a bag the caller owns, so what is pinned here is that the
    /// pass RUNS to exhaustion and reports: under a fatal droppedOutput this call is where the
    /// IllegalStateException would come from, and a report that never fires cannot refuse.
    @Test
    void testDefaultGeneratorStillCompletesItsPass(TestStore db) throws Exception {
        db.loadNetex(NETEX);

        List<Object> emitted = Gen.run(db, (s, txn) ->
                EpipJourneys.epipServiceJourneyGenerator(s, txn, GeneratorDefaults.TEST));

        Check.that(!emitted.isEmpty(), "the pass completes and emits the surviving journeys");
    }

    // ------------------------------------- TimetabledPassingTimesProfile (the SHORT timetable)

    /// A call whose stop disagrees with the pattern point at the same order produces NO passing
    /// time — the journey is emitted with a timetable one stop short, which is indistinguishable
    /// from a journey that really calls at fewer stops. Both mismatch arms are known-unfinished
    /// (OPEN.md §16) — they log and skip rather than resolving which ref is right — so the SIZE of
    /// the unfinished arm is exactly the thing worth counting.
    @Test
    void testShortTimetableIsCounted() {
        TimetabledPassingTimesProfile.reportDropped("test-reset");   // zero what this JVM already did

        ServiceJourneyPattern pattern = new ServiceJourneyPattern();
        pattern.setId("pat1");
        pattern.setVersion("1");
        PointsInJourneyPattern_RelStructure points = new PointsInJourneyPattern_RelStructure();
        points.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                .add(point("sp1", 1, "ssp1"));
        points.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                .add(point("sp2", 2, "sspX"));         // the pattern says sspX at order 2
        pattern.setPointsInSequence(points);

        ServiceJourney sj = new ServiceJourney();
        sj.setId("sj1");
        sj.setVersion("1");
        Calls_RelStructure calls = new Calls_RelStructure();
        Calls.add(calls, call("x:Call:1", 1, "ssp1"));
        Calls.add(calls, call("x:Call:2", 2, "ssp2"));  // the journey says ssp2 at order 2
        sj.setCalls(calls);

        List<TimetabledPassingTime> times =
                TimetabledPassingTimesProfile.getTimetabledPassingtimesFromCalls(sj, pattern);

        Check.equals(1, times.size(), "the mismatched call yields no passing time");
        Check.equals(1L, TimetabledPassingTimesProfile.reportDropped("test"),
                "…and the drop is counted, which is what the per-call warning could not say");
        Check.equals(0L, TimetabledPassingTimesProfile.reportDropped("test"),
                "reporting resets: a second pass counts its own drops, not the first pass's");
    }

    // ------------------------------------------------------------------- Projection

    /// The fallback transform is CACHED, so logOnce fires on the BUILD and every later geometry
    /// with the same unparseable srsName goes through it in silence. The count is of USES, which
    /// is the number that says how much of the export rests on "we now assume WGS84, and hope".
    @Test
    void testFallbackTransformCountsEveryUseNotEveryLog() {
        Projection.reportDropped("test-reset");

        Projection.resolveTransform("ITA", Projection.WGS84_URN);   // builds + logs once
        Projection.resolveTransform("ITA", Projection.WGS84_URN);   // cache hit: previously silent
        Projection.resolveTransform("ITA", Projection.WGS84_URN);

        Projection.Dropped d = Projection.reportDropped("test");
        Check.equals(3L, d.fallbackTransforms(),
                "every use of the cached fallback is counted, not just the build that logged");
        Check.equals(0L, Projection.reportDropped("test").total(), "reporting resets the counters");
    }

    /// The two arms that leave coordinates alone. The second is the one that produces WRONG values
    /// rather than missing ones: projectLocation4326's contract is "the result is WGS84", and a
    /// location with a non-WGS84 srsName and no pos keeps its source-CRS numbers while every
    /// consumer downstream reads them as degrees.
    @Test
    void testLocationsLeftUnprojectedAreCounted() {
        Projection.reportDropped("test-reset");

        LocationStructure empty = new LocationStructure();          // no pos, no lat/lon
        Check.that(!Projection.projectLocation(empty, Projection.WGS84_URN),
                "an empty location cannot be projected");

        LocationStructure sourceCrs = new LocationStructure();
        sourceCrs.setSrsName("EPSG:25832");                         // UTM 32N, no pos to transform
        sourceCrs.setLatitude(BigDecimal.valueOf(5_150_000));
        sourceCrs.setLongitude(BigDecimal.valueOf(680_000));
        Projection.projectLocation4326(sourceCrs);

        Projection.Dropped d = Projection.reportDropped("test");
        Check.equals(1L, d.unprojectedLocations(), "the location with no coordinates at all");
        Check.equals(1L, d.leftInSourceCrs(),
                "the location left in EPSG:25832 while the document claims WGS84");
        Check.equals("EPSG:25832", sourceCrs.getSrsName(),
                "…and it really was left alone — the count is not describing a repair");
    }

    // ---------------------------------------------------------------------- fixture helpers

    private static StopPointInJourneyPattern point(String id, int order, String sspRef) {
        StopPointInJourneyPattern p = new StopPointInJourneyPattern();
        p.setId(id);
        p.setVersion("1");
        p.setOrder(BigInteger.valueOf(order));
        p.setScheduledStopPointRef(FACTORY.createScheduledStopPointRef(sspRef(sspRef)));
        return p;
    }

    private static Call call(String id, int order, String sspRef) {
        Call c = new Call();
        c.setId(id);
        c.setVersion("1");
        c.setOrder(BigInteger.valueOf(order));
        c.setScheduledStopPointRef(FACTORY.createScheduledStopPointRef(sspRef(sspRef)));
        return c;
    }

    private static ScheduledStopPointRefStructure sspRef(String ref) {
        ScheduledStopPointRefStructure r = new ScheduledStopPointRefStructure();
        r.setRef(ref);
        r.setVersion("1");
        return r;
    }
}
