package toolkit.transform.epip;

// The EPIP interchange-point repair.
//
// A feed's JourneyMeeting gives one station-level AtStopPointRef for both
// sides, but each journey passes its own platform ScheduledStopPoint at that station. The fix
// (a) resolves the from/to point to the SSP each journey actually uses (matched by StopPlace via
// PassengerStopAssignment), and (b) drops interchanges whose journeys are not present in the data.
//
// No xsi:type on the point refs, as a property of the binding: the model has no
// ScheduledStopPointRef CLASS at all — only the structure — so the projected point ref's runtime
// class equals the declared ScheduledStopPointRefStructure field type and JAXB has no subtype to
// stamp. EXPECTED below therefore carries neither `xsi:type="ScheduledStopPointRef"` nor its
// `xmlns:xsi` declaration on the From/ToPointRef lines. It DOES carry
// nameOfRefClass="ServiceJourney" on the journey refs, which is set explicitly.

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
    // platform SSP each journey actually passes (ssp_st_1 / ssp_st_2), not the station aggregate
    // (From/ToPointRef stripped of the serializer artifacts — header note.)
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

    /// IDENTITY ARM. The generator's ServiceJourney index pass reads each journey's id and
    /// JourneyPatternRef off the compressed bytes; with
    /// `-Dtoolkit.epip.peekInterchangeIndex=false` it unmarshals the whole journey instead. Both
    /// must emit the same interchanges.
    ///
    /// **This fixture is the one that can tell the difference.** Both patterns exist and are
    /// matched, so EXPECTED's `ssp_st_1` / `ssp_st_2` are reachable ONLY through
    /// `journeyToPattern` — an index that lost the pattern refs would fall back to the station
    /// aggregate `ssp_st_gen` on both sides — and the ref is spelt `ServiceJourneyPatternRef`, a
    /// substitution-group member rather than the head element, which a name-equality peek would get
    /// wrong.
    @Test
    void testPeekedIndexEmitsWhatTheDecodedIndexEmits(TestStore db) throws Exception {
        db.loadNetex(NETEX);

        // This fixture HAS patterns, so the `_id_idx` guard is closed on both arms and the
        // knob below is the only thing that varies; it is passed as unset for the same reason.
        String peeked = runWith(db, null, null);      // unset = the shipped default, the peek path
        String decoded = runWith(db, "false", null);  // the full-decode path

        Check.equals(decoded, peeked, "peeked index vs decoded index");
        Xml.assertXmlEqual(peeked, EXPECTED);   // and both are the RESOLVED output, not a fallback
    }

    // -------------------------------------------------------------------------------
    //
    // The same station and the same two meetings, with NO ServiceJourneyPattern in the store —
    // the shape a pattern-less feed has. The journeys still CARRY a ServiceJourneyPatternRef,
    // deliberately: that makes the two blob routes build a NON-EMPTY journeyToPattern while the
    // `_id_idx` route builds none at all, so "the map is dead when patternPoints is empty" is
    // asserted against a route that disagrees with the others and must still emit the same bytes.
    //
    // Both sides therefore fall back to the meeting's own AtStopPointRef, and TWO of the three
    // meetings must still be DROPPED — which is the in-suite control on the id set's other
    // direction, an `_id_idx` route returning a SUPERSET:
    //   jm2's ToJourneyRef names `sj_absent`, an id NO row of any class carries — a route that
    //     invented ids, or read the wrong slice of the key, emits it;
    //   jm3's ToJourneyRef names `ssp_st_gen`, an id that IS in `_id_idx` and is a
    //     ScheduledStopPoint — a route that dropped or mis-spelt the class filter emits it, and
    //     nothing else in this file would notice. It is here because `_id_idx` is the ONE index
    //     that spans every class, which the ServiceJourney class map the blob routes walk does not.
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

    /// jm1 with both sides on the station aggregate — the pattern-less fallback. jm2 dropped.
    /// (`version="1"` next to the source's `versionRef="1"` is the loader's own
    /// class/version repair on the meeting's `AtStopPointRef`, not this generator's doing;
    /// the fallback hands that very object straight through.)
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

    /// IDENTITY ARM. With `patternPoints` empty the index pass takes `_id_idx` and opens no blob;
    /// the two blob routes still decompress (peek) or unmarshal (decode) every journey. All three
    /// must emit the same interchanges, and this arm also watches the dangling meeting get dropped
    /// and the fallback point get chosen.
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

    /// The verify arm on the suite path. `-Dtoolkit.epip.idIndexJourneys.verify`
    /// builds the id set BOTH ways and throws on the first id they disagree about, in either
    /// direction; here it must run clean and must not change a byte of the output.
    ///
    /// **This fixture is one on which the superset direction is REACHABLE**, which is why the arm
    /// is asserted here and not on the patterned store: `_id_idx` holds rows for `ssp_st_gen`, the
    /// three PassengerStopAssignments and the ScheduledStopPoints, and only the `full_key` class
    /// filter keeps them out of `presentJourneys`. Drop that filter and this test fails
    /// INDEX-ONLY.
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

    /// THE GUARD, WATCHED TO FAIL. `_id_idx` carries ids and nothing else, so the route is correct
    /// only where `journeyToPattern` cannot reach an output — which is exactly
    /// `patternPoints.isEmpty()`. Take the same route with the guard forced open on the fixture
    /// that HAS patterns and the resolved `ssp_st_1`/`ssp_st_2` collapse to the station aggregate.
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
