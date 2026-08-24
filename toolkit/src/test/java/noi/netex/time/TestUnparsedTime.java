package noi.netex.time;

// The blind spot underneath the instrument.
//
// XmlTime / XmlDateTime / XmlDuration answer an unparseable lexical with an UNPARSED CARRIER: the
// raw text is kept, a re-marshal is byte-exact, and toLocalTime()/toLocalDateTime()/toDuration()
// return null. Storage and export are therefore unharmed — and that is why it was silent. From
// JAXB's point of view the adapter SUCCEEDED and returned an object, so no ValidationEvent is
// raised and toolkit.codec.BindEvents counts zero over a feed in which every departure time is
// junk.
//
// noi.netex.time.UnparsedTally is the count. These tests pin the three claims that make it worth
// having:
//   1. an unparseable value really does produce an unparsed carrier (behaviour UNCHANGED);
//   2. that carrier is counted, per kind, with a sample of the raw text;
//   3. a VALID value is NOT counted — and "valid" here includes the corpus's hour-24..47 times,
//      the extended xs:duration forms and the offset-bearing values the CARRIERS EXIST FOR. A tally
//      that fired on those would make -Dtoolkit.strict.unparsedTime=true refuse every feed.
//
// WHY DELTAS AND NOT ABSOLUTE COUNTS. The tally is per JVM, like BindEvents', and surefire hosts
// many files in ONE JVM. Every assertion below reads total() before and after and asserts on the
// DIFFERENCE, which is also the only shape that stays true when this file is run on its own.
//
// LEXICAL FORMS. The valid ones are real corpus values. The junk ones are shaped after what a feed
// gets wrong: the literal "None" some exporters write for a missing value, an hour-24 value with a
// bad minute field, and a date-only lexical in an xs:dateTime field.

import noi.netex.model.ServiceJourney;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

public class TestUnparsedTime {

    // ---------------------------------------------------------------- fixtures

    /// One journey carrying a junk value in each of the three bindings, in the elements the corpus
    /// actually puts them in. It must LOAD — nothing here is a rejection — and each junk value must
    /// come back as a carrier holding its source text.
    public static final String JUNK = """
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="IT:ITI1:ServiceJourney:junk" version="any">
              <ValidBetween>
                <FromDate>2026-07-19</FromDate>
              </ValidBetween>
              <TransportMode>bus</TransportMode>
              <DepartureTime>None</DepartureTime>
              <JourneyDuration>PT36</JourneyDuration>
            </ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    /// The corpus values the carriers were built to preserve — after-midnight hours, the end-of-day
    /// form, an offset-bearing passing time, both extended xs:duration forms and an offset-bearing
    /// ValidBetween. Every one of these parses; none may be counted.
    public static final String VALID = """
        <TimetableFrame id="tf" version="380">
          <vehicleJourneys>
            <ServiceJourney id="IT:ITI1:ServiceJourney:54004985" version="any"
                            created="2026-07-12T04:31:07.000+02:00">
              <ValidBetween>
                <FromDate>2026-07-19T00:00:00.000+02:00</FromDate>
                <ToDate>2026-07-26T23:59:59.000+02:00</ToDate>
              </ValidBetween>
              <TransportMode>bus</TransportMode>
              <DepartureTime>24:09:00</DepartureTime>
              <JourneyDuration>P0Y0M0DT0H36M0.000S</JourneyDuration>
              <passingTimes>
                <TimetabledPassingTime id="IT:ITI1:TimetabledPassingTime:54004985:0" version="any">
                  <ArrivalTime>25:15:00</ArrivalTime>
                  <DepartureTime>30:05:00</DepartureTime>
                </TimetabledPassingTime>
                <TimetabledPassingTime id="IT:ITF6:TimetabledPassingTime:busAZ-006:75" version="any">
                  <ArrivalTime>24:00:00</ArrivalTime>
                  <DepartureTime>18:51:00+01:00</DepartureTime>
                </TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    // ---------------------------------------------------------------- 1. the carrier is counted

    /// The core claim: constructing an unparsed carrier increments the tally, and the raw text is
    /// kept as a sample so the count carries a shape to grep for.
    @Test
    void testUnparseableTimeIsCounted() {
        long before = UnparsedTally.total();
        long timesBefore = UnparsedTally.count(UnparsedTally.Kind.TIME);

        XmlTime t = XmlTime.parse("None");
        Check.that(t.isUnparsed(), "'None' must produce an unparsed carrier, not an exception");
        Check.isNull(t.toLocalTime(), "…whose normalisation is null — the silent value downstream");
        Check.equals("None", t.toXmlFormat(), "…while the source text still re-marshals verbatim");

        Check.equals(1L, UnparsedTally.total() - before,
                "and it is COUNTED. JAXB raised nothing here — the adapter returned an object — so "
                + "BindEvents.total() is still zero over this value and this tally is the only "
                + "place it exists");
        Check.equals(1L, UnparsedTally.count(UnparsedTally.Kind.TIME) - timesBefore,
                "…against the xs:time kind");
        Check.that(UnparsedTally.samples(UnparsedTally.Kind.TIME).contains("None")
                        || UnparsedTally.samples(UnparsedTally.Kind.TIME).size() >= UnparsedTally.SAMPLE_CAP,
                "…and the raw lexical is kept as a sample (unless the cap was already reached by "
                + "an earlier file in this JVM)");
    }

    /// Each binding is counted apart: which XSD type failed is the first question asked of a count.
    @Test
    void testEachKindIsCountedSeparately() {
        long time = UnparsedTally.count(UnparsedTally.Kind.TIME);
        long dateTime = UnparsedTally.count(UnparsedTally.Kind.DATE_TIME);
        long date = UnparsedTally.count(UnparsedTally.Kind.DATE);
        long duration = UnparsedTally.count(UnparsedTally.Kind.DURATION);

        Check.that(XmlTime.parse("24:71:00").isUnparsed(), "a minute over 59 does not parse");
        // Strict arms, deliberately: a date-only lexical in an xs:dateTime field is NOT promoted,
        // and a dateTime in an xs:date field is not truncated. Both land in the tally, apart.
        Check.that(XmlDateTime.parse("2026-07-19").isUnparsed(), "a date in an xs:dateTime field");
        Check.that(XmlDateTime.parseDate("2026-07-19T00:00:00").isUnparsed(),
                "and a dateTime in an xs:date field");
        Check.that(XmlDuration.parse("PT36").isUnparsed(), "a duration with no unit does not parse");

        Check.equals(1L, UnparsedTally.count(UnparsedTally.Kind.TIME) - time, "one xs:time");
        Check.equals(1L, UnparsedTally.count(UnparsedTally.Kind.DATE_TIME) - dateTime, "one xs:dateTime");
        Check.equals(1L, UnparsedTally.count(UnparsedTally.Kind.DATE) - date, "one xs:date");
        Check.equals(1L, UnparsedTally.count(UnparsedTally.Kind.DURATION) - duration, "one xs:duration");
        Check.that(!UnparsedTally.summary().equals("0"),
                "and the harness line names them rather than printing a bare total: "
                + UnparsedTally.summary());
    }

    /// Absent and blank values are NOT unparsed values. Every adapter returns null for them, and a
    /// feed that simply omits an optional element must not be reported as a parse failure.
    @Test
    void testAbsentAndBlankValuesAreNotCounted() {
        long before = UnparsedTally.total();

        Check.isNull(XmlTime.parse(null), "null time");
        Check.isNull(XmlTime.parse("   "), "blank time");
        Check.isNull(XmlDateTime.parse(null), "null dateTime");
        Check.isNull(XmlDateTime.parseDate("  "), "blank date");
        Check.isNull(XmlDuration.parse(""), "empty duration");

        Check.equals(0L, UnparsedTally.total() - before,
                "an absent value is not a failed parse; only a carrier that was BUILT counts");
    }

    // ---------------------------------------------------------------- 2. valid values are silent

    /// Everything below is a real corpus lexical that the old
    /// bindings dropped and these carriers exist to keep: hour 24..47, the end-of-day 24:00:00, an
    /// offset-bearing time, the PnYnMnDTnHnMnS duration and its negative, and an offset-bearing
    /// xs:dateTime. They parse, so they must not touch the tally.
    @Test
    void testValidCorpusValuesAreNeverCounted() {
        long before = UnparsedTally.total();

        for (String lexical : new String[] {
                "23:25:00", "24:00:00", "24:09:00", "25:15:00", "30:05:00", "18:51:00+01:00"}) {
            Check.that(!XmlTime.parse(lexical).isUnparsed(), lexical + " must parse");
        }
        for (String lexical : new String[] {
                "P0Y0M0DT0H36M0.000S", "-P0Y0M0DT0H10M0.000S", "PT16M"}) {
            Check.that(!XmlDuration.parse(lexical).isUnparsed(), lexical + " must parse");
        }
        for (String lexical : new String[] {
                "2026-07-19T00:00:00", "2026-07-19T00:00:00.000+02:00", "2026-07-19T24:00:00"}) {
            Check.that(!XmlDateTime.parse(lexical).isUnparsed(), lexical + " must parse");
        }
        Check.that(!XmlDateTime.parseDate("2026-07-19").isUnparsed(), "an xs:date must parse");

        Check.equals(0L, UnparsedTally.total() - before,
                "not one of these may be counted — they are the values the carriers were built to "
                + "preserve, and a tally that fires on them makes the strictness flag unusable");
    }

    // ---------------------------------------------------------------- 3. through the real loader

    /// End to end, through the loader that reads production feeds: the adapters are what JAXB calls,
    /// so this is the path on which the silence happened. The journey still loads, each junk value
    /// still comes back as its raw text, and the tally now says so.
    ///
    /// The count is asserted as "at least one per kind" rather than exactly one: a fragment is
    /// decoded once by the loader and again by resolve()'s reference repair, so the number of times a
    /// given lexeme is re-parsed is a property of the store path, not of this defect.
    @Test
    void testUnparsedValuesAreCountedWhenAFeedIsLoaded(TestStore db) throws Exception {
        long time = UnparsedTally.count(UnparsedTally.Kind.TIME);
        long dateTime = UnparsedTally.count(UnparsedTally.Kind.DATE_TIME);
        long duration = UnparsedTally.count(UnparsedTally.Kind.DURATION);

        db.loadNetex(JUNK);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class).get("IT:ITI1:ServiceJourney:junk");
        Check.that(sj != null, "a journey with unparseable times must still LOAD — nothing here rejects");
        Check.that(sj.getDepartureTime().isUnparsed(), "DepartureTime 'None' is an unparsed carrier");
        Check.equals("None", sj.getDepartureTime().toXmlFormat(), "…retained verbatim");
        Check.isNull(sj.getDepartureTime().toLocalTime(),
                "…and null at every arithmetic site, which is the damage being surfaced");
        Check.that(sj.getJourneyDuration().isUnparsed(), "JourneyDuration 'PT36' likewise");
        Check.that(sj.getValidBetween().get(0).getFromDate().isUnparsed(),
                "and a date-only FromDate in an xs:dateTime field likewise");

        Check.that(UnparsedTally.count(UnparsedTally.Kind.TIME) > time,
                "the load counted the xs:time — BindEvents cannot, because no ValidationEvent was "
                + "raised for any of these");
        Check.that(UnparsedTally.count(UnparsedTally.Kind.DATE_TIME) > dateTime, "and the xs:dateTime");
        Check.that(UnparsedTally.count(UnparsedTally.Kind.DURATION) > duration, "and the xs:duration");
    }

    /// The control: a feed of nothing but the awkward-but-valid corpus forms leaves the tally still.
    @Test
    void testLoadingValidCorpusValuesLeavesTheTallyUntouched(TestStore db) throws Exception {
        long before = UnparsedTally.total();

        db.loadNetex(VALID);

        ServiceJourney sj = Gen.byId(db, ServiceJourney.class).get("IT:ITI1:ServiceJourney:54004985");
        Check.equals(24, sj.getDepartureTime().getHour(),
                "fixture check: the hour-24 value did bind, as itself");
        Check.equals(0L, UnparsedTally.total() - before,
                "and a whole feed of after-midnight times, extended durations and offset-bearing "
                + "dateTimes counts ZERO. These are valid values; only a failed parse is a failure.");
    }

    private TestUnparsedTime() {}
}
