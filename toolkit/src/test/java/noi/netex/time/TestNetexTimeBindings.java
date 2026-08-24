package noi.netex.time;

// THE ACCEPTANCE TEST of the date/time bindings: it asserts that REAL CORPUS VALUES SURVIVE A
// ROUND TRIP, not that any particular mechanism is in place. The bindings themselves live in
// model/src/main/xjb/00-global.xjb.
//
// Every fixture value below is REAL, lifted verbatim from source feeds: the two xs:duration forms
// java.time.Duration.parse rejects, the after-midnight and exactly-24:00:00 times, an
// offset-bearing xs:time, an offset-bearing ValidBetween pair, and a multi-line
// xs:normalizedString whose line breaks a normalising binding eats.
//
// WHY THE TESTS RE-MARSHAL. The values are not lost at export; they are lost the first time an
// object is bound and written back — which happens at LOAD time, because resolve()'s reference
// repair re-marshals the WHOLE fragment to fix one ref attribute. So each test asserts on the
// bytes a re-marshal produces, which is what every stored ServiceJourney fragment is.

import noi.netex.model.AvailabilityCondition;
import noi.netex.model.Operator;
import noi.netex.model.ServiceJourney;
import noi.netex.model.TimetabledPassingTime;
import org.junit.jupiter.api.Test;
import toolkit.codec.FragmentCodec;
import toolkit.store.RawRow;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;
import toolkit.transform.epip.ServiceCalendarEpip;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import noi.netex.text.Mls;

public class TestNetexTimeBindings {

    // ---------------------------------------------------------------- fixtures (real values)

    /// Two Italian journeys with the two xs:duration forms java.time.Duration.parse rejects, plus
    /// the DayType they reference so resolve() performs its version repair and re-marshals them —
    /// the exact path that destroys the value in production.
    public static final String DURATIONS = """
        <ServiceCalendarFrame id="scf" version="396">
          <dayTypes>
            <DayType id="IT:ITI4:DayType:OP1_1032" version="396"><Name>feriale</Name></DayType>
          </dayTypes>
        </ServiceCalendarFrame>
        <TimetableFrame id="tf" version="396">
          <vehicleJourneys>
            <ServiceJourney id="IT:ITI4:ServiceJourney:111644710R66082_OP1" version="396">
              <Name>CARINI - LENIN</Name>
              <TransportMode>bus</TransportMode>
              <DepartureTime>16:44:00</DepartureTime>
              <JourneyDuration>P0Y0M0DT0H36M0.000S</JourneyDuration>
              <dayTypes><DayTypeRef ref="IT:ITI4:DayType:OP1_1032"/></dayTypes>
            </ServiceJourney>
            <ServiceJourney id="IT:ITI4:ServiceJourney:1063_9857202_Cotral" version="61">
              <Name>VALMONTONE | Parco Rainbow - VALMONTONE | stazione FS</Name>
              <TransportMode>bus</TransportMode>
              <DepartureTime>17:25:00</DepartureTime>
              <JourneyDuration>-P0Y0M0DT0H10M0.000S</JourneyDuration>
            </ServiceJourney>
            <ServiceJourney id="IT:ITI1:ServiceJourney:54004985" version="any">
              <Name>Corsa 54004985</Name>
              <TransportMode>bus</TransportMode>
              <DepartureTime>24:09:00</DepartureTime>
              <JourneyDuration>PT16M</JourneyDuration>
            </ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    /// The five time shapes, all inside ONE ServiceJourney — which is how they occur: a
    /// TimetabledPassingTime is never a stored object of its own, it lives inside its journey's
    /// fragment, so the journey's re-marshal is what has to preserve them.
    /// Ordinary / after-midnight / deep after-midnight / exactly 24:00:00 / offset-bearing.
    public static final String TIMES = """
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="IT:ITI1:ServiceJourney:54004985" version="any">
              <TransportMode>bus</TransportMode>
              <DepartureTime>24:09:00</DepartureTime>
              <passingTimes>
                <TimetabledPassingTime id="IT:ITI1:TimetabledPassingTime:54004985:0" version="any">
                  <ArrivalTime>23:25:00</ArrivalTime>
                  <DepartureTime>23:25:00</DepartureTime>
                </TimetabledPassingTime>
                <TimetabledPassingTime id="IT:ITI1:TimetabledPassingTime:54004985:1" version="any">
                  <DepartureTime>24:09:00</DepartureTime>
                </TimetabledPassingTime>
                <TimetabledPassingTime id="IT:ITI1:TimetabledPassingTime:55060002:1" version="any">
                  <ArrivalTime>25:15:00</ArrivalTime>
                  <DepartureTime>30:05:00</DepartureTime>
                </TimetabledPassingTime>
                <TimetabledPassingTime id="IT:ITF6:TimetabledPassingTime:busAZ-006:75" version="20260718234018">
                  <ArrivalTime>24:00:00</ArrivalTime>
                  <DepartureTime>24:00:00</DepartureTime>
                </TimetabledPassingTime>
                <TimetabledPassingTime id="IT:ITH5:TimetabledPassingTime:START-RA:80_RA2796274_A:passingTimes:1" version="1">
                  <ArrivalTime>18:51:00+01:00</ArrivalTime>
                  <DepartureTime>18:51:00+01:00</DepartureTime>
                </TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    static final String JOURNEY = "IT:ITI1:ServiceJourney:54004985";

    /// The xs:dateTime half of the offset defect — NEW at Stage 4, because no adapter substitution
    /// could have fixed it (see the file header). Two shapes on one journey: an offset-bearing
    /// `Created` attribute and an offset-bearing `ValidBetween` pair, the latter being
    /// the loss the round-trip harness reports.
    public static final String DATETIMES = """
        <TimetableFrame id="tf" version="380">
          <vehicleJourneys>
            <ServiceJourney id="IT:ITI4:ServiceJourney:62-4676-000-0083_pass_0083" version="380"
                            created="2026-07-12T04:31:07.000+02:00"
                            changed="2026-07-12T04:31:07.000+02:00">
              <ValidBetween>
                <FromDate>2026-07-19T00:00:00.000+02:00</FromDate>
                <ToDate>2026-07-26T23:59:59.000+02:00</ToDate>
              </ValidBetween>
              <TransportMode>bus</TransportMode>
              <DepartureTime>06:20:00</DepartureTime>
            </ServiceJourney>
            <ServiceJourney id="IT:ITI4:ServiceJourney:62-4676-000-0083_naive" version="380">
              <ValidBetween>
                <FromDate>2026-07-19T00:00:00</FromDate>
                <ToDate>2026-07-26T23:59:59</ToDate>
              </ValidBetween>
              <TransportMode>bus</TransportMode>
              <DepartureTime>06:20:00</DepartureTime>
            </ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    /// Two AvailabilityConditions naming THE SAME eight days, one with an offset-bearing FromDate
    /// and one naive. The whole EPIP calendar flattener rests on these two flattening to one set.
    public static final String AVAILABILITY = """
        <ServiceCalendarFrame id="scf" version="1">
          <validityConditions>
            <AvailabilityCondition id="IT:ITI4:AvailabilityCondition:aware" version="1">
              <FromDate>2026-07-19T00:00:00.000+02:00</FromDate>
              <ToDate>2026-07-26T23:59:59.000+02:00</ToDate>
              <ValidDayBits>10100001</ValidDayBits>
            </AvailabilityCondition>
            <AvailabilityCondition id="IT:ITI4:AvailabilityCondition:naive" version="1">
              <FromDate>2026-07-19T00:00:00</FromDate>
              <ToDate>2026-07-26T23:59:59</ToDate>
              <ValidDayBits>10100001</ValidDayBits>
            </AvailabilityCondition>
          </validityConditions>
        </ServiceCalendarFrame>
        """;

    // ---------------------------------------------------------------- helpers

    /// The n-th TimetabledPassingTime of a journey (they are embedded, never stored on their own).
    private static TimetabledPassingTime passingTime(ServiceJourney sj, int n) {
        return (TimetabledPassingTime) sj.getPassingTimes().getTimetabledPassingTime().get(n);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /// The object's STORED fragment bytes (no re-marshal): what a byte-copy export would emit.
    private static String stored(TestStore db, Class<?> clazz, String id) {
        FragmentCodec codec = new FragmentCodec();
        try (Txn tx = db.store.roTxn()) {
            for (RawRow row : db.store.iterRawObjects(tx, clazz)) {
                String xml = new String(codec.decompress(row.blob()), StandardCharsets.UTF_8);
                if (xml.contains("\"" + id + "\"")) return xml;
            }
        }
        throw new AssertionError("no stored " + clazz.getSimpleName() + " " + id);
    }

    /// Decode the stored fragment and marshal it straight back — literally what
    /// `AbstractLmdbStore.writeBlob` does after a reference repair, and what every EPIP
    /// transform op does. This is the operation that loses the data.
    private static String remarshalled(TestStore db, Class<?> clazz, String id) {
        FragmentCodec codec = new FragmentCodec();
        try (Txn tx = db.store.roTxn()) {
            for (RawRow row : db.store.iterRawObjects(tx, clazz)) {
                byte[] xml = codec.decompress(row.blob());
                if (!new String(xml, StandardCharsets.UTF_8).contains("\"" + id + "\"")) continue;
                Object bean = codec.decode(row.blob(), clazz);
                return new String(codec.marshalFragment(bean), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no stored " + clazz.getSimpleName() + " " + id);
    }

    // ---------------------------------------------------------------- 1. xs:duration

    /// java.time.Duration.parse accepts only PnDTnHnMnS. The PnYnMnDTnHnMnS form these feeds use
    /// throws, JAXB's default event handler swallows it to null, and the element vanishes.
    /// XmlDuration wraps javax.xml.datatype.Duration, which parses the FULL xs:duration lexical
    /// space.
    @Test
    void testJourneyDurationSurvivesTheExtendedXsDurationForm(TestStore db) throws Exception {
        db.loadNetex(DURATIONS);

        var byId = Gen.byId(db, ServiceJourney.class);
        ServiceJourney sj = byId.get("IT:ITI4:ServiceJourney:111644710R66082_OP1");
        Check.that(sj.getJourneyDuration() != null,
                "P0Y0M0DT0H36M0.000S must bind (java.time.Duration.parse rejects it)");
        Check.equals(36L, sj.getJourneyDuration().toDuration().toMinutes(), "…and must bind to 36 minutes");

        Check.that(stored(db, ServiceJourney.class, "IT:ITI4:ServiceJourney:111644710R66082_OP1")
                        .contains("<JourneyDuration>P0Y0M0DT0H36M0.000S</JourneyDuration>"),
                "the stored fragment keeps the source lexical form");
        Check.that(remarshalled(db, ServiceJourney.class, "IT:ITI4:ServiceJourney:111644710R66082_OP1")
                        .contains("<JourneyDuration>P0Y0M0DT0H36M0.000S</JourneyDuration>"),
                "and so does a decode+re-marshal, which is what resolve()/the EPIP ops do");
    }

    /// The same form with a leading sign — also rejected by the old binding, also silently dropped.
    @Test
    void testNegativeJourneyDurationSurvives(TestStore db) throws Exception {
        db.loadNetex(DURATIONS);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class).get("IT:ITI4:ServiceJourney:1063_9857202_Cotral");
        Check.that(sj.getJourneyDuration() != null, "-P0Y0M0DT0H10M0.000S must bind");
        Check.equals(-10L, sj.getJourneyDuration().toDuration().toMinutes(), "…as minus ten minutes");
        Check.that(remarshalled(db, ServiceJourney.class, "IT:ITI4:ServiceJourney:1063_9857202_Cotral")
                        .contains("<JourneyDuration>-P0Y0M0DT0H10M0.000S</JourneyDuration>"),
                "re-marshal restores the source form (Duration.toString() would say PT-10M)");
    }

    /// The half that already parsed must not change: Duration.toString() canonicalises PT16M to
    /// itself, so this one is a pure no-regression check on the fast path.
    @Test
    void testAlreadyCanonicalDurationIsUnchanged(TestStore db) throws Exception {
        db.loadNetex(DURATIONS);

        Check.that(remarshalled(db, ServiceJourney.class, "IT:ITI1:ServiceJourney:54004985")
                        .contains("<JourneyDuration>PT16M</JourneyDuration>"),
                "PT16M round-trips unchanged");
    }

    // ---------------------------------------------------------------- 2. after midnight

    /// LocalTime.parse rejects hour >= 24, so every 24:xx/25:xx/30:xx call was dropped and the
    /// night services truncated. XmlTime stores the hour raw, so `getHour() == 24` and
    /// `getDayOffset() == 1`; `toLocalTime()` still returns 00:09, which is the normalisation every
    /// consumer calls.
    @Test
    void testAfterMidnightPassingTimesSurvive(TestStore db) throws Exception {
        db.loadNetex(TIMES);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class).get(JOURNEY);
        Check.that(sj.getDepartureTime() != null,
                "ServiceJourney/DepartureTime 24:09:00 must bind (LocalTime.parse rejects it)");
        TimetabledPassingTime one = passingTime(sj, 1);
        Check.that(one.getDepartureTime() != null, "24:09:00 must bind on the passing time too");
        Check.equals(24, one.getDepartureTime().getHour(),
                "…keeping the hour AS WRITTEN — this is the assertion that used to read "
                        + "getMinute() == 9, i.e. the hour had been thrown away");
        Check.equals(1, one.getDepartureTime().getDayOffset(), "…which is a one-day offset");
        Check.equals(java.time.LocalTime.of(0, 9), one.getDepartureTime().toLocalTime(),
                "…while toLocalTime() still yields the old binding's 00:09, so no consumer moved");

        TimetabledPassingTime deep = passingTime(sj, 2);
        Check.that(deep.getArrivalTime() != null && deep.getDepartureTime() != null,
                "25:15:00 and 30:05:00 must bind");
        Check.equals(30, deep.getDepartureTime().getHour(), "30:05:00 keeps hour 30");

        String x = remarshalled(db, ServiceJourney.class, JOURNEY);
        Check.equals(2, count(x, "<DepartureTime>24:09:00</DepartureTime>"),
                "re-marshal restores 24:09:00 verbatim on the journey AND its call "
                        + "(the raw string is kept)");
        Check.that(x.contains("<ArrivalTime>25:15:00</ArrivalTime>"), "25:15:00 verbatim");
        Check.that(x.contains("<DepartureTime>30:05:00</DepartureTime>"), "30:05:00 verbatim");
        Check.equals(10, count(x, "<ArrivalTime>") + count(x, "<DepartureTime>"),
                "no call loses a time element (the journey is not truncated at midnight)");
    }

    /// 24:00:00 is the nastiest of the family: it is the one hour-24 value LocalTime.parse ACCEPTS,
    /// silently turning it into 00:00:00 — a whole day's shift with no error anywhere.
    @Test
    void testMidnightTwentyFourIsNotSilentlyRewrittenToZero(TestStore db) throws Exception {
        db.loadNetex(TIMES);

        String x = remarshalled(db, ServiceJourney.class, JOURNEY);
        Check.equals(2, count(x, "24:00:00"), "24:00:00 must stay 24:00:00 on both times");
        Check.equals(0, count(x, "00:00:00"), "…and must not become 00:00:00");
    }

    // ---------------------------------------------------------------- 3. xs:time offsets

    /// The old bound type was LocalTime, which cannot carry an offset, so offset-bearing times were
    /// silently rewritten to local-only.
    @Test
    void testTimeZoneOffsetOnAPassingTimeIsNotStripped(TestStore db) throws Exception {
        db.loadNetex(TIMES);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class).get(JOURNEY);
        TimetabledPassingTime offsetBearing = passingTime(sj, 4);
        Check.equals("+01:00", offsetBearing.getArrivalTime().getOffset().getId(),
                "the offset is a first-class field on the carrier now, not a re-derived string");

        String x = remarshalled(db, ServiceJourney.class, JOURNEY);
        Check.that(x.contains("<ArrivalTime>18:51:00+01:00</ArrivalTime>"),
                "the +01:00 offset must survive the re-marshal");
        Check.that(x.contains("<DepartureTime>18:51:00+01:00</DepartureTime>"), "on both times");
    }

    /// Ordinary times must not acquire anything or change shape: the retained lexical IS the source
    /// text and there is no offset.
    @Test
    void testOrdinaryTimesAreUntouched(TestStore db) throws Exception {
        db.loadNetex(TIMES);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class).get(JOURNEY);
        TimetabledPassingTime plain = passingTime(sj, 0);
        Check.equals("23:25:00", plain.getArrivalTime().getLexical(),
                "the source text is retained verbatim — this replaces the old getNano() == 0 "
                        + "check, which only meant 'the intern table did not tag this value'");
        Check.isNull(plain.getArrivalTime().getOffset(), "a plain HH:mm:ss has no offset");
        Check.equals(0, plain.getArrivalTime().getNano(), "and no fractional second");
        Check.equals(23, plain.getArrivalTime().getHour(), "and its value is untouched");
        Check.equals(0, plain.getArrivalTime().getDayOffset(), "and it is not an after-midnight time");
        Check.equals(2, count(remarshalled(db, ServiceJourney.class, JOURNEY), "23:25:00"),
                "…and re-marshals byte-identically");
    }

    // ---------------------------------------------------------------- 4. xs:dateTime offsets (NEW)

    /// The xs:dateTime offset. The ValidBetween pair below is what the round-trip harness reports
    /// as CHANGED `2026-07-19T00:00:00.000+02:00 -> 2026-07-19T00:00:00`.
    @Test
    void testDateTimeOffsetsSurviveOnCreatedAndValidBetween(TestStore db) throws Exception {
        db.loadNetex(DATETIMES);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class)
                .get("IT:ITI4:ServiceJourney:62-4676-000-0083_pass_0083");
        Check.equals("+02:00", sj.getCreated().getOffset().getId(), "@created keeps its offset");
        Check.equals("+02:00", sj.getValidBetween().get(0).getFromDate().getOffset().getId(),
                "FromDate keeps its offset");
        Check.equals(LocalDateTime.of(2026, 7, 19, 0, 0, 0),
                sj.getValidBetween().get(0).getFromDate().toLocalDateTime(),
                "…and toLocalDateTime() is still the old binding's offset-less value");

        String x = remarshalled(db, ServiceJourney.class, "IT:ITI4:ServiceJourney:62-4676-000-0083_pass_0083");
        Check.that(x.contains("created=\"2026-07-12T04:31:07.000+02:00\""),
                "@created re-marshals byte-for-byte, milliseconds and offset included");
        Check.that(x.contains("changed=\"2026-07-12T04:31:07.000+02:00\""), "and @changed with it");
        Check.that(x.contains("<FromDate>2026-07-19T00:00:00.000+02:00</FromDate>"),
                "FromDate re-marshals byte-for-byte — the 166,006-occurrence Mode A1 loss");
        Check.that(x.contains("<ToDate>2026-07-26T23:59:59.000+02:00</ToDate>"), "and ToDate with it");
        Check.equals(0, count(x, "2026-07-19T00:00:00<"),
                "…and specifically does NOT degrade to the offset-less form");
    }

    /// The other half: a naive xs:dateTime must stay naive. The carrier retains the source lexical,
    /// so it cannot invent the "+02:00" that the JVM's default zone would otherwise supply — the
    /// failure mode that ruled out binding to OffsetDateTime.
    @Test
    void testNaiveDateTimeDoesNotAcquireAnOffset(TestStore db) throws Exception {
        db.loadNetex(DATETIMES);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class)
                .get("IT:ITI4:ServiceJourney:62-4676-000-0083_naive");
        Check.isNull(sj.getValidBetween().get(0).getFromDate().getOffset(),
                "a source without an offset must not gain one");
        Check.isNull(sj.getCreated(), "and an absent @created stays absent");

        String x = remarshalled(db, ServiceJourney.class, "IT:ITI4:ServiceJourney:62-4676-000-0083_naive");
        Check.that(x.contains("<FromDate>2026-07-19T00:00:00</FromDate>"), "naive FromDate verbatim");
        Check.that(x.contains("<ToDate>2026-07-26T23:59:59</ToDate>"), "naive ToDate verbatim");
        Check.equals(0, count(x, "+02:00"), "no offset anywhere in the fragment");
    }

    /// `ServiceCalendarEpip.flatten` turns an AvailabilityCondition into a SET of dates, and that
    /// set's cardinality becomes the ValidDayBits string of every exported ServiceCalendar. An
    /// offset-bearing condition and a naive one naming the same days must flatten to the SAME eight
    /// dates and union to eight, not sixteen — which is what `toLocalDateTime()` at the boundary
    /// guarantees, and what putting carriers straight into the Set would have broken
    /// (XmlDateTime.hashCode() throws so that mistake cannot be made quietly).
    @Test
    void testFlattenUnifiesOffsetBearingAndNaiveConditions(TestStore db) throws Exception {
        db.loadNetex(AVAILABILITY);

        var byId = Gen.byId(db, AvailabilityCondition.class);
        AvailabilityCondition aware = byId.get("IT:ITI4:AvailabilityCondition:aware");
        AvailabilityCondition naive = byId.get("IT:ITI4:AvailabilityCondition:naive");
        Check.equals("+02:00", aware.getFromDate().getOffset().getId(), "fixture: one is offset-bearing");
        Check.isNull(naive.getFromDate().getOffset(), "fixture: the other is naive");

        Set<LocalDateTime> awareDates = ServiceCalendarEpip.flattenAvailabilityCondition(aware).operationalDates();
        Set<LocalDateTime> naiveDates = ServiceCalendarEpip.flattenAvailabilityCondition(naive).operationalDates();
        Check.equals(3, awareDates.size(), "ValidDayBits 10100001 selects three days");
        Check.equals(awareDates, naiveDates,
                "the offset must not change WHICH days a condition covers");

        ServiceCalendarEpip.Positive both =
                ServiceCalendarEpip.positiveAvailabilityCondition(List.of(aware, naive));
        Check.equals(3, both.validDays().size(),
                "and unioning the two must stay at three — a Set of CARRIERS would say six and "
                        + "silently rewrite every ValidDayBits string downstream");
        Check.equals(LocalDateTime.of(2026, 7, 19, 0, 0), both.validDays().get(0),
                "the first valid day is the FromDate itself, offset discarded");
        Check.equals("10100001", ServiceCalendarEpip.validDaysToBits(both.validDays()),
                "…so the bit string round-trips exactly");
    }

    // ---------------------------------------------------------------- 5. normalizedString

    /// xs:normalizedString fields carry @XmlJavaTypeAdapter(NormalizedStringAdapter) BY DEFAULT,
    /// and its unmarshal() applies the whitespace facet, replacing every CR/LF/tab with a space.
    /// The source does not apply the facet, so the line breaks are kept. The fix is the fifth
    /// binding in 00-global.xjb, VerbatimStringAdapter — and the netex-model build asserts that
    /// the override took (grep NormalizedStringAdapter over the generated sources == 0).
    @Test
    void testMultilingualStringKeepsItsLineBreaks(TestStore db) throws Exception {
        db.loadNetex(MULTILINE);

        Operator op = Gen.byId(db, Operator.class).get("at:stv:Operator:29");
        String town = Mls.text(op.getAddress().getTown());
        Check.equals("Einöd 138\n8965 Michaelerberg-Pruggern\nÖsterreich", town,
                "the two line breaks must survive the bind (the facet turns them into spaces)");
        Check.that(remarshalled(db, Operator.class, "at:stv:Operator:29").contains(
                        "<Town>Einöd 138\n8965 Michaelerberg-Pruggern\nÖsterreich</Town>"),
                "and the re-marshal must write them back out");
    }

    /// A single-line value must be completely unaffected by dropping the facet.
    @Test
    void testSingleLineMultilingualStringIsUnchanged(TestStore db) throws Exception {
        db.loadNetex(MULTILINE);

        Operator op = Gen.byId(db, Operator.class).get("at:stv:Operator:29");
        Check.equals("Österreich", Mls.text(op.getAddress().getCountryName()), "plain text unchanged");
        Check.equals("Postbus", Mls.text(op.getName()), "plain name unchanged");
    }

    /// Byte-for-byte from a real feed. The source uses CRLF; XML 1.0 line-end normalisation turns
    /// that into LF before any binding sees it.
    public static final String MULTILINE = """
        <ResourceFrame id="rf" version="1">
          <organisations>
            <Operator id="at:stv:Operator:29" version="1">
              <Name>Postbus</Name>
              <Address>
                <CountryName>Österreich</CountryName>
                <Street>Einöd</Street>
                <Town>Einöd 138
        8965 Michaelerberg-Pruggern
        Österreich</Town>
                <PostCode>8965</PostCode>
              </Address>
            </Operator>
          </organisations>
        </ResourceFrame>
        """;
}
