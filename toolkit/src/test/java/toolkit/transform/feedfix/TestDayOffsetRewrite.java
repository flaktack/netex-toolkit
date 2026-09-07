package toolkit.transform.feedfix;

// Covers DayOffsetRewrite.
//
// The class under test converts the ILLEGAL after-midnight xs:time encoding into the only one NeTEx
// defines. No byte comparison against an unrewritten run can see a day-losing rewrite, because the
// feed never carries the valid form, so the tests assert what the new data IS, value by value, and
// testInvariantCatchesTheDayLosingRewrite pins the invariant that separates the two outcomes.

import noi.netex.calls.Calls;
import noi.netex.model.ArrivalStructure;
import noi.netex.model.Call;
import noi.netex.model.Calls_RelStructure;
import noi.netex.model.DepartureStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.ServiceJourney;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.TimetabledPassingTimes_RelStructure;
import noi.netex.time.XmlTime;
import org.junit.jupiter.api.Test;
import toolkit.harness.InPlace;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.util.Conf;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

public class TestDayOffsetRewrite {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// The default is OFF: applying this transform changes the encoding the feed used, so enabling
    /// it is a decision the user has not taken.
    @Test
    void testTheFlagDefaultsToOff() {
        Check.equals("toolkit.epip.dayOffsetRewrite", DayOffsetRewrite.PROPERTY, "flag name");
        Check.isNull(System.getProperty(DayOffsetRewrite.PROPERTY),
                "the suite must run with the flag unset");
        Check.that(!DayOffsetRewrite.ENABLED,
                "the EPIP chain hook is OFF unless -Dtoolkit.epip.dayOffsetRewrite=on is passed");
    }

    /// The rewrite must not go through `LocalTime` or `XmlTime.of(h,m,s)`,
    /// both of which drop the fraction and the timezone offset. Lexical surgery keeps them.
    @Test
    void testRewriteKeepsFractionAndZoneOffset() {
        Check.equals("00:20:00", DayOffsetRewrite.rewriteTime(XmlTime.parse("24:20:00")).toXmlFormat(),
                "the plain case");
        Check.equals("00:20:00+01:00",
                DayOffsetRewrite.rewriteTime(XmlTime.parse("24:20:00+01:00")).toXmlFormat(),
                "the zone offset travels verbatim");
        Check.equals("00:00:00.000",
                DayOffsetRewrite.rewriteTime(XmlTime.parse("24:00:00.000")).toXmlFormat(),
                "the fraction travels verbatim, digits and all");
        Check.equals("00:00:00Z", DayOffsetRewrite.rewriteTime(XmlTime.parse("24:00:00Z")).toXmlFormat(),
                "Z travels verbatim");
        Check.equals("02:05:00", DayOffsetRewrite.rewriteTime(XmlTime.parse("26:05:00")).toXmlFormat(),
                "past the boundary");
        Check.equals("06:05:00", DayOffsetRewrite.rewriteTime(XmlTime.parse("30:05:00")).toXmlFormat(),
                "the corpus maximum");
        XmlTime out = DayOffsetRewrite.rewriteTime(XmlTime.parse("24:20:00+01:00"));
        Check.equals("00:20:00+01:00", out.getLexical(), "the new carrier retains its lexical form");
        Check.that(out.isXsdValid(), "the rewrite is legal xs:time");
    }

    /// NEUTER. The day-losing rewrite — `24:20:00 -> 00:20:00` with NO offset — is invisible to
    /// every other gate here, so the invariant must FAIL on it.
    @Test
    void testInvariantCatchesTheDayLosingRewrite() {
        XmlTime src = XmlTime.parse("24:20:00");
        XmlTime good = XmlTime.parse("00:20:00");
        // correct: 24*3600 + 20*60 = 87,600 == 1*86,400 + 1,200
        DayOffsetRewrite.checkInvariant("SJ:1", "TPT:1", "DepartureTime",
                src, null, good, BigInteger.ONE);
        boolean threw = false;
        try {
            DayOffsetRewrite.checkInvariant("SJ:1", "TPT:1", "DepartureTime",
                    src, null, good, BigInteger.ZERO);   // the day dropped on the floor
        } catch (IllegalStateException e) {
            threw = true;
            Check.that(e.getMessage().contains("SECONDS NOT CONSERVED"),
                    "the message names the failure: " + e.getMessage());
        }
        Check.that(threw, "losing the day must be an immediate failure, not a silent -86,400 s");
    }

    /// Multi-day: `floorDiv(hour, 24)`, never `hour == 24 ? 1 : 0`.
    @Test
    void testMultiDayUsesFloorDivNotAHardcodedOne() {
        TimetabledPassingTime t = new TimetabledPassingTime();
        t.setId("TPT:multi");
        t.setArrivalTime(XmlTime.parse("48:10:00"));
        ServiceJourney sj = journeyOf(t);
        DayOffsetRewrite.Stats stats = rewrite(sj);
        Check.equals("00:10:00", t.getArrivalTime().toXmlFormat(), "48:10 reduces twice");
        Check.equals(BigInteger.TWO, t.getArrivalDayOffset(), "two days, not one");
        Check.equals(2, stats.maxDayOffsetWritten, "the stats report the multi-day offset");
    }

    /// `newOffset = oldOffset + d`, ACCUMULATE. A non-zero pre-existing offset means the measured
    /// disjointness (no element carries both a past-boundary hour and a `DayOffset`) has died, so
    /// the pass refuses rather than accumulating into a population nobody has counted.
    @Test
    void testNonZeroPreExistingOffsetThrows() {
        TimetabledPassingTime t = new TimetabledPassingTime();
        t.setId("TPT:both");
        t.setArrivalTime(XmlTime.parse("24:20:00"));
        t.setArrivalDayOffset(BigInteger.ONE);          // both encodings at once
        boolean threw = false;
        try {
            rewrite(journeyOf(t));
        } catch (IllegalStateException e) {
            threw = true;
            Check.that(e.getMessage().contains("PRECONDITION VIOLATED"),
                    "the message names the precondition: " + e.getMessage());
        }
        Check.that(threw, "an hour>=24 alongside a non-zero DayOffset must fail loudly");
        Check.equals("24:20:00", t.getArrivalTime().toXmlFormat(), "and nothing is written");
    }

    /// A real feed writes `<ArrivalTime>24:00:00</ArrivalTime>` beside
    /// `<ArrivalDayOffset>0</ArrivalDayOffset>`, a self-contradictory pair (207 values). The TIME
    /// wins: the explicit zero is the element's schema default, and OTP folds that value to
    /// `00:00`, 24 hours wrong with no diagnostic. The overwrite gets its own counter.
    @Test
    void testExplicitZeroDayOffsetOnMidnightIsOverwrittenAndCounted() {
        TimetabledPassingTime t = new TimetabledPassingTime();
        t.setId("TPT:sardegna");
        t.setArrivalTime(XmlTime.parse("24:00:00"));
        t.setArrivalDayOffset(BigInteger.ZERO);         // the explicit, contradictory 0
        DayOffsetRewrite.Stats stats = rewrite(journeyOf(t));
        Check.equals("00:00:00", t.getArrivalTime().toXmlFormat(), "midnight end-of-day");
        Check.equals(BigInteger.ONE, t.getArrivalDayOffset(), "on day 1, not day 0");
        Check.equals(1L, stats.valuesWithZeroOffsetPresent, "the overwrite is counted separately");
        Check.equals(1L, stats.valuesExactlyMidnight, "24:00:00 counted as the legal-lexical arm");
        Check.equals(0L, stats.valuesPastBoundary, "and not as a past-boundary value");
    }

    // ---------------------------------------------------------------- coverage of the carriers

    /// All six (time, offset) pairs NeTEx defines on a journey, in one pass.
    @Test
    void testEveryCarrierPairIsRewritten() {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("SJ:all");
        sj.setVersion("1");
        sj.setDepartureTime(XmlTime.parse("24:05:00"));

        TimetabledPassingTime t = new TimetabledPassingTime();
        t.setId("TPT:all");
        t.setArrivalTime(XmlTime.parse("24:10:00"));
        t.setDepartureTime(XmlTime.parse("24:11:00"));
        t.setLatestArrivalTime(XmlTime.parse("24:12:00"));
        t.setEarliestDepartureTime(XmlTime.parse("24:13:00"));
        TimetabledPassingTimes_RelStructure pts = new TimetabledPassingTimes_RelStructure();
        pts.getTimetabledPassingTime().add(t);
        sj.setPassingTimes(pts);

        Call call = new Call();
        call.setId("CALL:all");
        ArrivalStructure arr = new ArrivalStructure();
        arr.setTime(XmlTime.parse("25:14:00"));
        call.setArrival(arr);
        DepartureStructure dep = new DepartureStructure();
        dep.setTime(XmlTime.parse("25:15:00"));
        call.setDeparture(dep);
        Calls_RelStructure calls = new Calls_RelStructure();
        Calls.add(calls, call);
        sj.setCalls(calls);

        DayOffsetRewrite.Stats stats = rewrite(sj);
        Check.equals(7L, stats.valuesRewritten, "journey departure + 4 TPT + 2 call times");
        Check.equals("00:05:00", sj.getDepartureTime().toXmlFormat(), "ServiceTimeGroup time");
        Check.equals(BigInteger.ONE, sj.getDepartureDayOffset(), "ServiceTimeGroup offset");
        Check.equals(BigInteger.ONE, t.getArrivalDayOffset(), "ArrivalDayOffset");
        Check.equals(BigInteger.ONE, t.getDepartureDayOffset(), "DepartureDayOffset");
        Check.equals(BigInteger.ONE, t.getLatestArrivalDayOffset(), "LatestArrivalDayOffset");
        Check.equals(BigInteger.ONE, t.getEarliestDepartureDayOffset(), "EarliestDepartureDayOffset");
        Check.equals("01:14:00", arr.getTime().toXmlFormat(), "Call arrival");
        Check.equals(BigInteger.ONE, arr.getDayOffset(), "Call arrival offset");
        Check.equals(BigInteger.ONE, dep.getDayOffset(), "Call departure offset");
    }

    /// A legal journey is not touched, and is therefore not re-inserted (no fragment churn).
    @Test
    void testLegalJourneyIsUntouched() {
        TimetabledPassingTime t = new TimetabledPassingTime();
        t.setId("TPT:ok");
        t.setArrivalTime(XmlTime.parse("23:59:59"));
        t.setDepartureTime(XmlTime.parse("00:00:01"));
        t.setDepartureDayOffset(BigInteger.ONE);
        ServiceJourney sj = journeyOf(t);
        DayOffsetRewrite.Stats stats = new DayOffsetRewrite.Stats();
        try (DayOffsetRewrite.Sink sink = DayOffsetRewrite.Sink.open(null)) {
            Check.that(!DayOffsetRewrite.rewriteJourney(sj, stats, sink), "nothing to do");
        }
        Check.equals(0L, stats.valuesRewritten, "no value moved");
        Check.equals("23:59:59", t.getArrivalTime().toXmlFormat(), "carrier untouched");
        Check.equals(BigInteger.ONE, t.getDepartureDayOffset(), "an existing valid offset is left alone");
    }


    // ---------------------------------------------------------------- the store round trip

    /// The production path: `InPlace` + `DayOffsetRewrite.apply` over a real store,
    /// then read the journey back and check the value AND the dump the gate consumes.
    @Test
    void testInPlaceRewriteOverAStore(TestStore db) throws Exception {
        Path dbFile = db.buildDbFile("""
            <TimetableFrame id="TF:1" version="1">
              <vehicleJourneys>
                <ServiceJourney id="SJ:1" version="1">
                  <Name>after midnight</Name>
                  <DepartureTime>23:40:00</DepartureTime>
                  <passingTimes>
                    <TimetabledPassingTime id="TPT:1" version="1">
                      <DepartureTime>23:50:00</DepartureTime>
                    </TimetabledPassingTime>
                    <TimetabledPassingTime id="TPT:2" version="1">
                      <ArrivalTime>24:20:00</ArrivalTime>
                      <DepartureTime>24:22:00</DepartureTime>
                    </TimetabledPassingTime>
                    <TimetabledPassingTime id="TPT:3" version="1">
                      <ArrivalTime>25:05:00</ArrivalTime>
                    </TimetabledPassingTime>
                  </passingTimes>
                </ServiceJourney>
                <ServiceJourney id="SJ:2" version="1">
                  <Name>ordinary</Name>
                  <passingTimes>
                    <TimetabledPassingTime id="TPT:9" version="1">
                      <ArrivalTime>08:00:00</ArrivalTime>
                    </TimetabledPassingTime>
                  </passingTimes>
                </ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            """, "dayoffsetrewrite.lmdb");

        Path dump = db.tempPath("rewritten.tsv");
        Conf.override(Conf.EPIP_DAY_OFFSET_REWRITE_DUMP, dump);
        try {
            InPlace.runInPlace(DayOffsetRewrite::apply, dbFile, "test-day-offset-rewrite", true);
        } finally {
            Conf.clearOverride(Conf.EPIP_DAY_OFFSET_REWRITE_DUMP);
        }

        try (Store s = Stores.open(dbFile, true); Txn tx = s.roTxn()) {
            long fk = s.lookupFullKey(tx, "SJ:1", "1", ServiceJourney.class);
            ServiceJourney sj = (ServiceJourney) s.loadObjectByFullKey(tx, fk);
            List<TimetabledPassingTime> tpts = sj.getPassingTimes().getTimetabledPassingTime();
            Check.equals(3, tpts.size(), "passing times survive");
            Check.equals("23:50:00", tpts.get(0).getDepartureTime().toXmlFormat(), "day-0 stop untouched");
            Check.isNull(tpts.get(0).getDepartureDayOffset(), "and gains no offset");
            Check.equals("00:20:00", tpts.get(1).getArrivalTime().toXmlFormat(), "24:20 -> 00:20");
            Check.equals(BigInteger.ONE, tpts.get(1).getArrivalDayOffset(), "+ ArrivalDayOffset 1");
            Check.equals("00:22:00", tpts.get(1).getDepartureTime().toXmlFormat(), "24:22 -> 00:22");
            Check.equals(BigInteger.ONE, tpts.get(1).getDepartureDayOffset(), "+ DepartureDayOffset 1");
            Check.equals("01:05:00", tpts.get(2).getArrivalTime().toXmlFormat(), "25:05 -> 01:05");
            Check.equals(BigInteger.ONE, tpts.get(2).getArrivalDayOffset(), "+ ArrivalDayOffset 1");
            Check.equals("23:40:00", sj.getDepartureTime().toXmlFormat(), "legal journey time untouched");

            long fk2 = s.lookupFullKey(tx, "SJ:2", "1", ServiceJourney.class);
            ServiceJourney other = (ServiceJourney) s.loadObjectByFullKey(tx, fk2);
            Check.equals("08:00:00",
                    other.getPassingTimes().getTimetabledPassingTime().get(0).getArrivalTime().toXmlFormat(),
                    "an ordinary journey is not rewritten");
        }

        // The gate's own input: every rewritten value, against the SOURCE LEXICAL, >= 86,400 s.
        List<String> rows = java.nio.file.Files.readAllLines(dump);
        Check.equals(4, rows.size(), "header + 3 rewritten values");
        for (String row : rows.subList(1, rows.size())) {
            String[] f = row.split("\t", -1);
            int rawHour = Integer.parseInt(f[3].substring(0, f[3].indexOf(':')));
            int rawMin = Integer.parseInt(f[3].substring(3, 5));
            int rawSec = Integer.parseInt(f[3].substring(6, 8));
            long seconds = Long.parseLong(f[7]);
            Check.equals((long) rawHour * 3600 + rawMin * 60 + rawSec, seconds,
                    "dumped seconds equal the SOURCE lexical's: " + row);
            Check.that(seconds >= 86_400L, "recovered value is after midnight: " + row);
        }
    }

    /// The exported XML, which is what OTP actually reads: the illegal hour is gone and the
    /// sibling `*DayOffset` element is present, in the schema's element order.
    @Test
    void testExportedXmlCarriesTheValidEncoding(TestStore db) throws Exception {
        db.loadNetex("""
            <TimetableFrame id="TF:1" version="1">
              <vehicleJourneys>
                <ServiceJourney id="SJ:1" version="1">
                  <passingTimes>
                    <TimetabledPassingTime id="TPT:1" version="1">
                      <ArrivalTime>24:20:00</ArrivalTime>
                    </TimetabledPassingTime>
                  </passingTimes>
                </ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            """);
        try (Txn tx = db.store.rwTxn()) {
            DayOffsetRewrite.apply(db.store, tx);
            tx.commit();
        }
        String xml = db.exportNetex();
        Check.that(!xml.contains("24:20:00"), "the illegal lexical is gone from the export");
        Check.that(xml.contains("<ArrivalTime>00:20:00</ArrivalTime>"), "the legal time is emitted");
        Check.that(xml.contains("<ArrivalDayOffset>1</ArrivalDayOffset>"),
                "and the sibling DayOffset element with it");
        Check.that(xml.indexOf("<ArrivalTime>") < xml.indexOf("<ArrivalDayOffset>"),
                "in TimetabledPassingTimeGroup's declared order");
    }

    private static ServiceJourney journeyOf(TimetabledPassingTime... tpts) {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("SJ:unit");
        sj.setVersion("1");
        TimetabledPassingTimes_RelStructure pts = new TimetabledPassingTimes_RelStructure();
        pts.getTimetabledPassingTime().addAll(List.of(tpts));
        sj.setPassingTimes(pts);
        return sj;
    }

    private static DayOffsetRewrite.Stats rewrite(ServiceJourney sj) {
        DayOffsetRewrite.Stats stats = new DayOffsetRewrite.Stats();
        try (DayOffsetRewrite.Sink sink = DayOffsetRewrite.Sink.open(null)) {
            DayOffsetRewrite.rewriteJourney(sj, stats, sink);
        }
        return stats;
    }
}
