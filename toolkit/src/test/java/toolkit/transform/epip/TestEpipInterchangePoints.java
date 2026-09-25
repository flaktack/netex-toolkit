package toolkit.transform.epip;

// The EPIP interchange-point repair.
//
// A feed's JourneyMeeting gives one station-level AtStopPointRef for both sides, but each journey
// passes its own platform ScheduledStopPoint at that station. The fix (a) resolves the from/to
// point to the SSP each journey actually uses (matched by StopPlace via PassengerStopAssignment),
// and (b) drops interchanges whose journeys are not present in the data.
//
// The point refs carry no xsi:type: the model has no ScheduledStopPointRef class, only the
// structure, so the projected point ref's runtime class equals the declared
// ScheduledStopPointRefStructure field type and JAXB has no subtype to stamp. EXPECTED below
// therefore carries neither `xsi:type="ScheduledStopPointRef"` nor its `xmlns:xsi` declaration on
// the From/ToPointRef lines. It does carry nameOfRefClass="ServiceJourney" on the journey refs,
// which is set explicitly.

import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;
import toolkit.test.Xml;
import toolkit.util.Conf;

import java.util.List;

public class TestEpipInterchangePoints {

    // Station ST: aggregate point ssp_st_gen + two platforms ssp_st_1 / ssp_st_2, all assigned to
    // StopPlace ST. sj_a passes platform 1, sj_b passes platform 2. jm1 couples them at ST; jm2
    // references an absent journey.
    static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <scheduledStopPoints>
    <ScheduledStopPoint id="ssp_st_gen" version="1"><Name>ST</Name></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp_st_1" version="1"><Name>ST pf1</Name></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp_st_2" version="1"><Name>ST pf2</Name></ScheduledStopPoint>
  </scheduledStopPoints>
  <stopAssignments>
    <PassengerStopAssignment id="psa0" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_gen" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
    <PassengerStopAssignment id="psa1" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_1" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
    <PassengerStopAssignment id="psa2" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_2" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
  </stopAssignments>
  <journeyPatterns>
    <ServiceJourneyPattern id="pat_a" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="sp_a1" version="1" order="1"><ScheduledStopPointRef ref="ssp_st_1" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="pat_b" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="sp_b1" version="1" order="1"><ScheduledStopPointRef ref="ssp_st_2" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="sj_a" version="1"><ServiceJourneyPatternRef ref="pat_a" version="1"/></ServiceJourney>
    <ServiceJourney id="sj_b" version="1"><ServiceJourneyPatternRef ref="pat_b" version="1"/></ServiceJourney>
  </vehicleJourneys>
  <journeyInterchanges>
    <JourneyMeeting id="jm1" version="1">
      <AtStopPointRef ref="ssp_st_gen" versionRef="1"/>
      <FromJourneyRef ref="sj_a" version="1"/><ToJourneyRef ref="sj_b" version="1"/>
    </JourneyMeeting>
    <JourneyMeeting id="jm2" version="1">
      <AtStopPointRef ref="ssp_st_gen" versionRef="1"/>
      <FromJourneyRef ref="sj_a" version="1"/><ToJourneyRef ref="sj_absent" version="1"/>
    </JourneyMeeting>
  </journeyInterchanges>
</TimetableFrame>
""";

    // Only jm1 survives (jm2 references an absent journey -> dropped). from/to resolve to the
    // platform SSP each journey actually passes (ssp_st_1 / ssp_st_2), not the station aggregate.
    static final String EXPECTED = """
<ServiceJourneyInterchange xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="jm1" version="1">
  <StaySeated>true</StaySeated>
  <Planned>true</Planned>
  <Advertised>true</Advertised>
  <FromPointRef version="1" ref="ssp_st_1"/>
  <ToPointRef version="1" ref="ssp_st_2"/>
  <FromJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_a"/>
  <ToJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_b"/>
</ServiceJourneyInterchange>""";

    @Test
    void testInterchangePointsResolvedAndDanglingDropped(TestStore db) throws Exception {
        db.loadNetex(NETEX);

        List<Object> result = Gen.run(db, (store, txn) ->
                EpipInterchange.epipServiceJourneyInterchange(store, txn, GeneratorDefaults.TEST));

        Xml.assertXmlEqual(Xml.toXmlAll(result), EXPECTED);
    }

    /// Identity arm. The generator's ServiceJourney index pass reads each journey's id and
    /// JourneyPatternRef off the compressed bytes; with `-Dtoolkit.epip.peekInterchangeIndex=false`
    /// it unmarshals the whole journey instead. Both must emit the same interchanges.
    ///
    /// Both patterns exist and are matched here, so EXPECTED's `ssp_st_1` / `ssp_st_2` are
    /// reachable only through `journeyToPattern`; an index that lost the pattern refs falls back to
    /// the station aggregate `ssp_st_gen` on both sides. The ref is spelt
    /// `ServiceJourneyPatternRef`, a substitution-group member rather than the head element, which
    /// a name-equality peek gets wrong.
    @Test
    void testPeekedIndexEmitsWhatTheDecodedIndexEmits(TestStore db) throws Exception {
        db.loadNetex(NETEX);

        // This fixture has patterns, so the `_id_idx` guard is closed on both arms and the knob
        // below is the only thing that varies.
        String peeked = runWith(db, null, null);      // unset = the shipped default, the peek path
        String decoded = runWith(db, "false", null);  // the full-decode path

        Check.equals(decoded, peeked, "peeked index vs decoded index");
        Xml.assertXmlEqual(peeked, EXPECTED);   // and both are the RESOLVED output, not a fallback
    }

    // -------------------------------------------------------------------------------
    //
    // The same station and the same two meetings, with no ServiceJourneyPattern in the store — the
    // shape a pattern-less feed has. The journeys still carry a ServiceJourneyPatternRef, so the
    // two blob routes build a non-empty journeyToPattern while the `_id_idx` route builds none at
    // all, and all three must still emit the same bytes.
    //
    // Both sides therefore fall back to the meeting's own AtStopPointRef, and two of the three
    // meetings are dropped: jm2's ToJourneyRef names `sj_absent`, an id no row of any class
    // carries, and jm3's names `ssp_st_gen`, an id that is in `_id_idx` and is a
    // ScheduledStopPoint. `_id_idx` is the one index that spans every class, which the
    // ServiceJourney class map the blob routes walk does not.
    static final String NETEX_NO_PATTERNS = """
<ServiceFrame id="sf" version="1">
  <scheduledStopPoints>
    <ScheduledStopPoint id="ssp_st_gen" version="1"><Name>ST</Name></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp_st_1" version="1"><Name>ST pf1</Name></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp_st_2" version="1"><Name>ST pf2</Name></ScheduledStopPoint>
  </scheduledStopPoints>
  <stopAssignments>
    <PassengerStopAssignment id="psa0" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_gen" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
    <PassengerStopAssignment id="psa1" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_1" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
    <PassengerStopAssignment id="psa2" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_2" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
  </stopAssignments>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="sj_a" version="1"><ServiceJourneyPatternRef ref="pat_a" version="1"/></ServiceJourney>
    <ServiceJourney id="sj_b" version="1"><ServiceJourneyPatternRef ref="pat_b" version="1"/></ServiceJourney>
  </vehicleJourneys>
  <journeyInterchanges>
    <JourneyMeeting id="jm1" version="1">
      <AtStopPointRef ref="ssp_st_gen" versionRef="1"/>
      <FromJourneyRef ref="sj_a" version="1"/><ToJourneyRef ref="sj_b" version="1"/>
    </JourneyMeeting>
    <JourneyMeeting id="jm2" version="1">
      <AtStopPointRef ref="ssp_st_gen" versionRef="1"/>
      <FromJourneyRef ref="sj_a" version="1"/><ToJourneyRef ref="sj_absent" version="1"/>
    </JourneyMeeting>
    <JourneyMeeting id="jm3" version="1">
      <AtStopPointRef ref="ssp_st_gen" versionRef="1"/>
      <FromJourneyRef ref="sj_a" version="1"/><ToJourneyRef ref="ssp_st_gen" version="1"/>
    </JourneyMeeting>
  </journeyInterchanges>
</TimetableFrame>
""";

    /// jm1 with both sides on the station aggregate — the pattern-less fallback. jm2 dropped. The
    /// `version="1"` next to the source's `versionRef="1"` is the loader's own class/version repair
    /// on the meeting's `AtStopPointRef`, not this generator's doing; the fallback hands that very
    /// object straight through.
    static final String EXPECTED_FALLBACK = """
<ServiceJourneyInterchange xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="jm1" version="1">
  <StaySeated>true</StaySeated>
  <Planned>true</Planned>
  <Advertised>true</Advertised>
  <FromPointRef version="1" versionRef="1" ref="ssp_st_gen"/>
  <ToPointRef version="1" versionRef="1" ref="ssp_st_gen"/>
  <FromJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_a"/>
  <ToJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_b"/>
</ServiceJourneyInterchange>""";

    /// Identity arm. With `patternPoints` empty the index pass takes `_id_idx` and opens no blob;
    /// the two blob routes still decompress (peek) or unmarshal (decode) every journey. All three
    /// must emit the same interchanges, the dangling meetings dropped and the fallback point
    /// chosen.
    @Test
    void testIdIndexRouteEmitsWhatTheBlobRoutesEmit(TestStore db) throws Exception {
        db.loadNetex(NETEX_NO_PATTERNS);

        String viaIdx = runWith(db, null, null);            // both unset = the shipped default
        String viaPeek = runWith(db, null, "false");        // idIndexJourneys off -> the peek
        String viaDecode = runWith(db, "false", "false");   // and the full decode

        Check.equals(viaPeek, viaIdx, "_id_idx route vs peek route");
        Check.equals(viaDecode, viaIdx, "_id_idx route vs full-decode route");
        Xml.assertXmlEqual(viaIdx, EXPECTED_FALLBACK);
    }

    /// The verify arm on the suite path. `-Dtoolkit.epip.idIndexJourneys.verify` builds the id set
    /// both ways and throws on the first id they disagree about, in either direction; here it must
    /// run clean and must not change a byte of the output.
    ///
    /// On this fixture `_id_idx` holds rows for `ssp_st_gen`, the three PassengerStopAssignments
    /// and the ScheduledStopPoints, and only the `full_key` class filter keeps them out of
    /// `presentJourneys`. Drop that filter and this test fails on the index arm alone.
    @Test
    void testIdIndexVerifyArmRunsCleanAndChangesNothing(TestStore db) throws Exception {
        db.loadNetex(NETEX_NO_PATTERNS);

        String plain = runWith(db, null, null);
        String verified = withProps(db, new String[][] {
                {"toolkit.epip.idIndexJourneys.verify", "true"},
        });

        Check.equals(plain, verified, "the verify arm must not change the generator's output");
        Xml.assertXmlEqual(verified, EXPECTED_FALLBACK);
    }

    /// The guard, watched to fail. `_id_idx` carries ids and nothing else, so the route is correct
    /// only where `journeyToPattern` cannot reach an output — which is exactly
    /// `patternPoints.isEmpty()`. Take the same route with the guard forced open on the fixture
    /// that has patterns and the resolved `ssp_st_1`/`ssp_st_2` collapse to the station aggregate.
    @Test
    void testIdIndexRouteIsWrongWhenPatternsExistWhichIsWhyItIsGuarded(TestStore db)
            throws Exception {
        db.loadNetex(NETEX);

        String guarded = runWith(db, null, null);           // patternPoints non-empty -> peek route
        Xml.assertXmlEqual(guarded, EXPECTED);              // ... and the points resolve

        String forced = runWithForcedIdIndex(db);           // guard removed on the same store
        Check.that(!forced.equals(guarded), "forcing the _id_idx route past its guard must change the output");
        Xml.assertXmlEqual(forced, EXPECTED_FALLBACK);      // and it changes it to the fallback
    }

    // -------------------------------------------------------------------------------
    //
    // A circular route: sj_c opens at platform 1 and closes at platform 2 of the same station, so
    // StopPlace ST matches twice on one pattern and the two sides must not pick the same call.
    // sj_d starts there. jm_turn couples them.
    static final String NETEX_TURNBACK = """
<ServiceFrame id="sf" version="1">
  <scheduledStopPoints>
    <ScheduledStopPoint id="ssp_st_gen" version="1"><Name>ST</Name></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp_st_1" version="1"><Name>ST pf1</Name></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp_st_2" version="1"><Name>ST pf2</Name></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp_far" version="1"><Name>FAR</Name></ScheduledStopPoint>
  </scheduledStopPoints>
  <stopAssignments>
    <PassengerStopAssignment id="psa0" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_gen" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
    <PassengerStopAssignment id="psa1" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_1" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
    <PassengerStopAssignment id="psa2" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_st_2" version="1"/><StopPlaceRef ref="ST" version="1"/>
    </PassengerStopAssignment>
    <PassengerStopAssignment id="psa3" version="1" order="1">
      <ScheduledStopPointRef ref="ssp_far" version="1"/><StopPlaceRef ref="FAR" version="1"/>
    </PassengerStopAssignment>
  </stopAssignments>
  <journeyPatterns>
    <ServiceJourneyPattern id="pat_c" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="sp_c1" version="1" order="1"><ScheduledStopPointRef ref="ssp_st_1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="sp_c2" version="1" order="2"><ScheduledStopPointRef ref="ssp_far" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="sp_c3" version="1" order="3"><ScheduledStopPointRef ref="ssp_st_2" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="pat_d" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="sp_d1" version="1" order="1"><ScheduledStopPointRef ref="ssp_st_1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="sp_d2" version="1" order="2"><ScheduledStopPointRef ref="ssp_far" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="sj_c" version="1"><ServiceJourneyPatternRef ref="pat_c" version="1"/></ServiceJourney>
    <ServiceJourney id="sj_d" version="1"><ServiceJourneyPatternRef ref="pat_d" version="1"/></ServiceJourney>
  </vehicleJourneys>
  <journeyInterchanges>
    <JourneyMeeting id="jm_turn" version="1">
      <AtStopPointRef ref="ssp_st_gen" versionRef="1"/>
      <FromJourneyRef ref="sj_c" version="1"/><ToJourneyRef ref="sj_d" version="1"/>
    </JourneyMeeting>
  </journeyInterchanges>
</TimetableFrame>
""";

    /// The feeder hands over at `ssp_st_2`, its last call at ST; the distributor takes over at
    /// `ssp_st_1`, its first. Either platform resolves to a call the journey makes, so a side taken
    /// from the wrong end reaches a consumer as a valid ref — which is what this fixture pins.
    ///
    static final String EXPECTED_TURNBACK = """
<ServiceJourneyInterchange xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="jm_turn" version="1">
  <StaySeated>true</StaySeated>
  <Planned>true</Planned>
  <Advertised>true</Advertised>
  <FromPointRef version="1" ref="ssp_st_2"/>
  <ToPointRef version="1" ref="ssp_st_1"/>
  <FromJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_c"/>
  <ToJourneyRef nameOfRefClass="ServiceJourney" version="1" ref="sj_d"/>
</ServiceJourneyInterchange>""";

    @Test
    void testFeederTakesItsLastCallAndDistributorItsFirst(TestStore db) throws Exception {
        db.loadNetex(NETEX_TURNBACK);

        List<Object> result = Gen.run(db, (store, txn) ->
                EpipInterchange.epipServiceJourneyInterchange(store, txn, GeneratorDefaults.TEST));

        Xml.assertXmlEqual(Xml.toXmlAll(result), EXPECTED_TURNBACK);
    }

    /// The generator's output as XML with the two index knobs set for that run only; `null`
    /// means "unset", i.e. the shipped default.
    private static String runWith(TestStore db, String peek, String idIndex) throws Exception {
        return withProps(db, new String[][] {
                {"toolkit.epip.peekInterchangeIndex", peek},
                {"toolkit.epip.idIndexJourneys", idIndex},
        });
    }

    /// The diagnostic arm: the `_id_idx` route with its `patternPoints` guard removed.
    private static String runWithForcedIdIndex(TestStore db) throws Exception {
        return withProps(db, new String[][] {
                {"toolkit.epip.idIndexJourneys.force", "true"},
        });
    }

    private static String withProps(TestStore db, String[][] props) throws Exception {
        for (String[] prop : props) {
            Conf.Flag f = flag(prop[0]);
            if (prop[1] == null) Conf.clearOverride(f); else Conf.override(f, prop[1]);
        }
        try {
            return Xml.toXmlAll(Gen.run(db, (store, txn) ->
                    EpipInterchange.epipServiceJourneyInterchange(store, txn, GeneratorDefaults.TEST)));
        } finally {
            for (String[] prop : props) Conf.clearOverride(flag(prop[0]));
        }
    }

    /// The declared knob behind a property name, refusing rather than silently doing nothing.
    private static Conf.Flag flag(String property) {
        Conf.Flag f = Conf.byName(property.substring(Conf.PREFIX.length()));
        if (f == null) throw new IllegalStateException("no such knob: " + property);
        return f;
    }
}
