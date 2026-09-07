package toolkit.harness;

// The integration gate for toolkit.harness.EpipToDb.inPlace: ONE fixture feeds every phase, the
// transform runs through the REAL phase composition (InPlace.runInPlace x5, resolve=false
// everywhere), and the reopened database is asserted phase by phase:
//
//   A de-embed .......... the ServiceCalendar-embedded DayType/UicOperatingPeriod/
//                         DayTypeAssignment appear as first-class rows;
//   NOT done here ....... psaA3 (QuayRef only) keeps NO StopPlaceRef and the coordinate-less
//                         placeholder quay qA0 stays in spA. Both repairs belong to noi-pipeline's
//                         per-feed sanitize-stop-assignments step;
//   B calendar .......... the regrouped calendar objects survive under their ids;
//   B interchange ....... the JourneyMeeting becomes a stay-seated ServiceJourneyInterchange
//                         (to-side falls back to AtStopPointRef: sj2 has no pattern yet);
//   B lines ............. line1's BrandingRef is dropped and its Operator is demoted into
//                         additionalOperators (AUTHORITY_REFERENCE keeps the Authority primary);
//   B locations ......... location-less sspA/sspA2 inherit quay qA1's (projected-CRS) location;
//   B journeys .......... sj1 keeps its passing times, loses its validity conditions and gains
//                         AC-derived DayType/UicOperatingPeriod/DayTypeAssignment; sj2's calls
//                         are converted to a synthesized pattern + timetabled passing times;
//   C repair ............ the interchange's to-point is re-anchored onto sj2's NEW pattern at
//                         the same station (sspA -> sspA2) — created AND repaired;
//   C ensemble .......... EPSG:32633 locations are reprojected+quantized to WGS84, pat1's
//                         direction type becomes a DirectionRef + synthesized Direction row,
//                         sj2's operator is backfilled from line2, vt1 gets its abbreviated
//                         short name, and nameless quays inherit their StopPlace name;
//   C2 levelrefs ........ qA1's LevelRef at a Level nobody defines is cleared; qB1's, at the
//                         <Level> spB does define, survives, and that <Level> is untouched, with
//                         the G1-G6 guard armed over the whole run.
//
// Each method builds a fresh CLOSED db file (buildDbFile — or, for the A gate, a direct store-API
// insert that bypasses the loader) and runs the exact production path EpipToDb.inPlace on it.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeAssignments_RelStructure;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.DayTypes_RelStructure;
import noi.netex.model.Line;
import noi.netex.model.LocationStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.OperatingPeriods_RelStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceCalendar;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.model.VehicleType;
import noi.netex.time.XmlDateTime;
import org.junit.jupiter.api.Test;
import toolkit.geo.Projection;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.transform.epip.GeneratorDefaults;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import noi.netex.text.Mls;

public class TestEpipTransform {

    // The corpus: two stops (spA with a placeholder quay and a projected-CRS platform, spB in
    // plain WGS84), two lines (line1 carries the branding/operator/authority trio, line2 an
    // operator only), a pattern journey (sj1, with an embedded AvailabilityCondition) and a
    // calls journey (sj2), a JourneyMeeting coupling them at station spA, a Trenitalia-style
    // ServiceCalendar with embedded children, and four PassengerStopAssignments.
    //
    // The assignments have ALREADY been through noi-pipeline's per-feed sanitize-stop-assignments
    // step, this transform's documented precondition: every one that resolves to a station carries
    // its StopPlaceRef explicitly. psaA2 must keep hers —
    // EpipInterchange.epipRepairInterchangePoints maps ssp -> station through an sspToStop index
    // built ONLY from assignments carrying one. psaA3 is the deliberate exception, quay-only and
    // referenced by nothing else, so "this transform backfills nothing" has a subject.
    //
    // spA deliberately has NO centroid: the location inference must fall back to quay qA1's
    // projected-CRS location for sspA (the PSA's StopPlaceRef arm finds no mapping entry).
    static final String NETEX = """
        <ResourceFrame id="rf" version="1">
          <organisations>
            <Authority id="auth1" version="1"><Name>Verbund</Name></Authority>
            <Operator id="op1" version="1"><Name>Alpha Bus</Name></Operator>
            <Operator id="op2" version="1"><Name>Beta Bus</Name></Operator>
          </organisations>
          <vehicleTypes>
            <VehicleType id="vt1" version="1"><Name>Niederflurbus</Name></VehicleType>
          </vehicleTypes>
        </ResourceFrame>
        <SiteFrame id="site" version="1">
          <stopPlaces>
            <StopPlace id="spA" version="1"><Name>Alpha</Name>
              <quays>
                <Quay id="qA0" version="1"><PublicCode>0</PublicCode></Quay>
                <Quay id="qA1" version="1"><Centroid><Location srsName="EPSG:32633"><gml:pos>500000 5300000</gml:pos></Location></Centroid><LevelRef ref="lvlGone" version="1"/></Quay>
              </quays>
            </StopPlace>
            <StopPlace id="spB" version="1"><Name>Beta</Name>
              <Centroid><Location><Latitude>46.50000</Latitude><Longitude>11.30000</Longitude></Location></Centroid>
              <levels><Level id="lvlB" version="1"><Name>Erdgeschoss</Name></Level></levels>
              <quays>
                <Quay id="qB1" version="1"><Centroid><Location><Latitude>46.50010</Latitude><Longitude>11.30010</Longitude></Location></Centroid><LevelRef ref="lvlB" version="1"/></Quay>
              </quays>
            </StopPlace>
          </stopPlaces>
        </SiteFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="line1" version="1"><Name>L1</Name>
              <BrandingRef ref="brand1"/>
              <OperatorRef ref="op1" version="1"/>
              <AuthorityRef ref="auth1" version="1"/>
            </Line>
            <Line id="line2" version="1"><Name>L2</Name><OperatorRef ref="op2" version="1"/></Line>
          </lines>
          <scheduledStopPoints>
            <ScheduledStopPoint id="sspA" version="1"><Name>Alpha</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="sspA2" version="1"><Name>Alpha 2</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="sspA3" version="1"><Name>Alpha 3</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="sspB" version="1"><Name>Beta</Name><Location><Latitude>46.50010</Latitude><Longitude>11.30010</Longitude></Location></ScheduledStopPoint>
          </scheduledStopPoints>
          <journeyPatterns>
            <ServiceJourneyPattern id="pat1" version="1">
              <DirectionType>outbound</DirectionType>
              <pointsInSequence>
                <StopPointInJourneyPattern id="p1" version="1" order="1"><ScheduledStopPointRef ref="sspA" version="1"/></StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="p2" version="1" order="2"><ScheduledStopPointRef ref="sspB" version="1"/></StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
          <stopAssignments>
            <PassengerStopAssignment id="psaA" version="1" order="1"><ScheduledStopPointRef ref="sspA"/><StopPlaceRef ref="spA"/><QuayRef ref="qA1"/></PassengerStopAssignment>
            <PassengerStopAssignment id="psaA2" version="1" order="2"><ScheduledStopPointRef ref="sspA2"/><StopPlaceRef ref="spA"/><QuayRef ref="qA1"/></PassengerStopAssignment>
            <PassengerStopAssignment id="psaA3" version="1" order="3"><ScheduledStopPointRef ref="sspA3"/><QuayRef ref="qA1"/></PassengerStopAssignment>
            <PassengerStopAssignment id="psaB" version="1" order="4"><ScheduledStopPointRef ref="sspB"/><StopPlaceRef ref="spB"/><QuayRef ref="qB1"/></PassengerStopAssignment>
          </stopAssignments>
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
            <ServiceJourney id="sj1" version="1">
              <validityConditions>
                <AvailabilityCondition id="e1:AvailabilityCondition:1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-07T00:00:00</ToDate><ValidDayBits>111</ValidDayBits></AvailabilityCondition>
              </validityConditions>
              <LineRef ref="line1" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt1" version="1"><StopPointInJourneyPatternRef ref="p1" version="1" order="1"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
                <TimetabledPassingTime id="tpt2" version="1"><StopPointInJourneyPatternRef ref="p2" version="1" order="2"/><ArrivalTime>10:30:00</ArrivalTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj2" version="1">
              <LineRef ref="line2" version="1"/>
              <calls>
                <Call id="c1" version="1" order="1"><ScheduledStopPointRef ref="sspA2" version="1"/><Departure><Time>11:00:00</Time></Departure></Call>
                <Call id="c2" version="1" order="2"><ScheduledStopPointRef ref="sspB" version="1"/><Arrival><Time>11:30:00</Time></Arrival></Call>
              </calls>
            </ServiceJourney>
          </vehicleJourneys>
          <journeyInterchanges>
            <JourneyMeeting id="jm1" version="1">
              <AtStopPointRef ref="sspA" version="1"/>
              <FromJourneyRef ref="sj1" version="1"/>
              <ToJourneyRef ref="sj2" version="1"/>
            </JourneyMeeting>
          </journeyInterchanges>
        </TimetableFrame>
        """;

    /// Build a closed db file, run the REAL transform on it, and hand it back reopened RO.
    private static Store transformed(TestStore ts, String name) throws Exception {
        Path db = ts.buildDbFile(NETEX, name);
        EpipToDb.inPlace(db, GeneratorDefaults.TEST);
        return Stores.open(db, true);
    }

    private static <T> Map<String, T> byId(Store store, Txn txn, Class<T> clazz) {
        Map<String, T> out = new LinkedHashMap<>();
        if (!store.dbNames(txn).contains(clazz)) return out;
        for (Object o : store.iterOnlyObjects(txn, clazz)) {
            @SuppressWarnings("unchecked")
            T t = (T) o;
            out.put(((noi.netex.model.EntityStructure) o).getId(), t);
        }
        return out;
    }

    /// The embedded Quay of that id, or null. The list order is the source's, so lookup is by id.
    private static Quay quayById(List<JAXBElement<?>> quays, String id) {
        for (JAXBElement<?> el : quays) {
            if (el.getValue() instanceof Quay q && id.equals(q.getId())) return q;
        }
        return null;
    }

    // ----------------------------------------------------- phase A + the calendar/daytype path

    @Test
    void testPrepareAndCalendarPhases(TestStore ts) throws Exception {
        try (Store db = transformed(ts, "epip-a.lmdb"); Txn txn = db.roTxn()) {
            // A: the ServiceCalendar's embedded children are now FIRST-CLASS rows.
            Map<String, DayType> dayTypes = byId(db, txn, DayType.class);
            Map<String, UicOperatingPeriod> uops = byId(db, txn, UicOperatingPeriod.class);
            Map<String, DayTypeAssignment> dtas = byId(db, txn, DayTypeAssignment.class);
            Check.that(dayTypes.containsKey("dt1"), "calendar-embedded DayType de-embedded to a row");
            Check.that(uops.containsKey("uop1"), "calendar-embedded UicOperatingPeriod de-embedded to a row");
            Check.that(dtas.containsKey("dta1"), "calendar-embedded DayTypeAssignment de-embedded to a row");

            // psaA3 has only a QuayRef and KEEPS only a QuayRef: the StopPlaceRef backfill is
            // noi-pipeline's per-feed sanitize-stop-assignments step, not a phase here.
            Map<String, PassengerStopAssignment> psas = byId(db, txn, PassengerStopAssignment.class);
            PassengerStopAssignment psaA3 = psas.get("psaA3");
            Check.isNull(psaA3.getStopPlaceRef(), "psaA3 StopPlaceRef NOT backfilled by this transform");
            Check.equals("qA1", psaA3.getQuayRef().getValue().getRef(), "psaA3's QuayRef intact");
            // Every PSA's stored fragment is the source-faithful original — none is re-inserted.
            Check.equals("spA", psas.get("psaA").getStopPlaceRef().getValue().getRef(),
                    "psaA keeps its explicit StopPlaceRef");
            Check.equals("qA1", psas.get("psaA").getQuayRef().getValue().getRef(),
                    "psaA's QuayRef intact (the untouched stored fragment)");

            // B journeys: the AvailabilityCondition-derived calendar objects exist and sj1
            // points at the derived DayType.
            Check.that(dayTypes.containsKey("e1:DayType:1"), "AC-derived DayType generated");
            UicOperatingPeriod derived = uops.get("e1:UicOperatingPeriod:1");
            Check.that(derived != null, "AC-derived UicOperatingPeriod generated");
            // getFromDate() is a carrier, so the comparison normalises.
            Check.equals(LocalDateTime.of(2026, 1, 5, 0, 0), derived.getFromDate().toLocalDateTime(),
                    "derived period FromDate");
            Check.equals(LocalDateTime.of(2026, 1, 7, 0, 0), derived.getToDate().toLocalDateTime(),
                    "derived period ToDate");
            // The lexical, re-parsed from the stored fragment, is EpipCalendar's: it comes from
            // XmlDateTime.from(...) and not from an input element copied through.
            Check.equals("2026-01-05T00:00:00", derived.getFromDate().getLexical(),
                    "the derived period was STORED in canonical form");
            Check.equals("111", derived.getValidDayBits(), "derived period day bits (3 valid days)");
            Check.that(dtas.containsKey("e1:DayTypeAssignment:1"), "AC-derived DayTypeAssignment generated");

            ServiceJourney sj1 = byId(db, txn, ServiceJourney.class).get("sj1");
            Check.isNull(sj1.getValidityConditions(), "sj1 validity conditions stripped");
            Check.equals("e1:DayType:1",
                    sj1.getDayTypes().getDayTypeRef().get(0).getValue().getRef(),
                    "sj1 day_types points at the AC-derived DayType");
        }
    }

    /// The A de-embed gate: calendar children that exist ONLY embedded. The XML loader materializes
    /// ServiceCalendar-embedded children as first-class rows, so the fixture is inserted DIRECTLY
    /// through the store API instead and dtE/uopE/dtaE live only inside the calendar's embedded
    /// lists. dtaE's DayTypeRef deliberately dangles (ref="nope"), so the phase-B calendar
    /// generator SKIPS its group: the only producer of the asserted rows is phase A.
    @Test
    void testDeembedGatedByEmbeddedOnlyCalendar(TestStore ts) throws Exception {
        ObjectFactory factory = new ObjectFactory();
        DayType dtE = new DayType().withId("dtE").withVersion("1");
        UicOperatingPeriod uopE = new UicOperatingPeriod().withId("uopE").withVersion("1")
                .withFromDate(XmlDateTime.from(LocalDateTime.of(2026, 1, 5, 0, 0)))
                .withToDate(XmlDateTime.from(LocalDateTime.of(2026, 1, 26, 0, 0)))
                .withValidDayBits("1000000100000010000001");
        DayTypeAssignment dtaE = new DayTypeAssignment().withId("dtaE").withVersion("1")
                .withOrder(BigInteger.ONE)
                .withDayTypeRef(factory.createDayTypeRef(
                        new DayTypeRefStructure().withRef("nope").withVersion("1")));
        ServiceCalendar sc = new ServiceCalendar().withId("scE").withVersion("1")
                .withDayTypes(new DayTypes_RelStructure())
                .withOperatingPeriods(new OperatingPeriods_RelStructure())
                .withDayTypeAssignments(new DayTypeAssignments_RelStructure());
        sc.getDayTypes().getDayTypeRefOrDayType_().add(factory.createDayType(dtE));
        sc.getOperatingPeriods().getOperatingPeriodRefOrOperatingPeriodOrUicOperatingPeriod()
                .add(factory.createUicOperatingPeriod(uopE));
        sc.getDayTypeAssignments().getDayTypeAssignment().add(dtaE);

        Path db = ts.tempPath("epip-a1.lmdb");
        try (Store direct = Stores.create(db, ts.format)) {
            try (Txn txn = direct.rwTxn()) {
                direct.insertAnyObjects(txn, List.<Object>of(sc));
                txn.commit();
            }
        }
        // Precondition: embedded ONLY — no first-class child rows before the transform.
        try (Store before = Stores.open(db, true); Txn txn = before.roTxn()) {
            Check.that(byId(before, txn, DayType.class).isEmpty(), "precondition: no DayType row");
            Check.that(byId(before, txn, UicOperatingPeriod.class).isEmpty(),
                    "precondition: no UicOperatingPeriod row");
            Check.that(byId(before, txn, DayTypeAssignment.class).isEmpty(),
                    "precondition: no DayTypeAssignment row");
        }

        EpipToDb.inPlace(db, GeneratorDefaults.TEST);

        try (Store after = Stores.open(db, true); Txn txn = after.roTxn()) {
            Check.that(byId(after, txn, DayType.class).containsKey("dtE"),
                    "A de-embedded the calendar's DayType to a first-class row");
            Check.that(byId(after, txn, UicOperatingPeriod.class).containsKey("uopE"),
                    "A de-embedded the calendar's UicOperatingPeriod to a first-class row");
            Check.that(byId(after, txn, DayTypeAssignment.class).containsKey("dtaE"),
                    "A de-embedded the calendar's DayTypeAssignment to a first-class row");
        }
    }

    // ------------------------------------ sites, lines, locations and the ensemble touch-ups

    @Test
    void testSitesLinesLocationsAndEnsemble(TestStore ts) throws Exception {
        try (Store db = transformed(ts, "epip-b.lmdb"); Txn txn = db.roTxn()) {
            // The coordinate-less placeholder qA0 is NOT stripped: that is noi-pipeline's per-feed
            // sanitize-stop-assignments step, run before this transform ever sees a corpus.
            Map<String, StopPlace> stops = byId(db, txn, StopPlace.class);
            List<JAXBElement<?>> quays = stops.get("spA").getQuays().getQuayRefOrQuay();
            Check.equals(2, quays.size(), "spA keeps both quays — this transform strips none");
            Quay qA1 = quayById(quays, "qA1");
            Check.that(quayById(quays, "qA0") != null, "the placeholder quay is still there");

            // C ensemble (quayNameOp): the nameless platform inherits the stop's name.
            Check.equals("Alpha", Mls.text(qA1.getName()), "quay name backfilled from the StopPlace");

            // C ensemble (reprojection): the quay's EPSG:32633 centroid is now WGS84.
            LocationStructure qLoc = qA1.getCentroid().getLocation();
            Check.equals(Projection.WGS84_URN, qLoc.getSrsName(), "quay centroid reprojected to WGS84");

            // C2 (EpipSanitize.stripOrphanLevelRefs): qA1 pointed at a Level nobody defines and
            // has lost the ref; spB defines lvlB, so qB1 keeps its ref and the <Level> stands.
            Check.isNull(qA1.getLevelRef(), "qA1's orphaned LevelRef cleared by phase C2");
            StopPlace spB = stops.get("spB");
            Quay qB1 = (Quay) spB.getQuays().getQuayRefOrQuay().get(0).getValue();
            Check.equals("lvlB", qB1.getLevelRef().getRef(),
                    "qB1's LevelRef resolves inside spB and survives");
            int spbLevels = 0;
            for (Object o : spB.getLevels().getLevelRefOrLevel()) {
                if (o instanceof noi.netex.model.Level) spbLevels++;
            }
            Check.equals(1, spbLevels, "and spB's <Level> definition is untouched");

            // B lines: branding dropped; operator demoted into additionalOperators, authority
            // primary (EpipConfig.AUTHORITY_REFERENCE = true).
            Map<String, Line> lines = byId(db, txn, Line.class);
            Line line1 = lines.get("line1");
            Check.isNull(line1.getBrandingRef(), "line1 BrandingRef dropped");
            Check.isNull(line1.getOperatorRef(), "line1 OperatorRef demoted");
            Check.equals("auth1", line1.getAuthorityRef().getRef(), "line1 Authority stays primary");
            List<JAXBElement<? extends noi.netex.model.TransportOrganisationRefStructure>>
                    additional = line1.getAdditionalOperators().getTransportOrganisationRef();
            Check.equals(1, additional.size(), "one demoted additional operator");
            Check.equals("OperatorRef", additional.get(0).getName().getLocalPart(),
                    "the demoted ref keeps its OperatorRef element name");
            Check.equals("op1", additional.get(0).getValue().getRef(), "the demoted ref is op1");
            Check.equals("op2", lines.get("line2").getOperatorRef().getRef(), "line2 untouched");

            // B locations + C ensemble: sspA had NO location — it inherited quay qA1's
            // projected-CRS location, which the ensemble then reprojected and quantized.
            Map<String, ScheduledStopPoint> ssps = byId(db, txn, ScheduledStopPoint.class);
            LocationStructure aLoc = ssps.get("sspA").getLocation();
            Check.that(aLoc != null, "sspA location inferred from its quay");
            Check.equals(Projection.WGS84_URN, aLoc.getSrsName(), "sspA location reprojected to WGS84");
            List<Double> pos = aLoc.getPos().getValue();   // authority order: (lat, lon)
            Check.that(Math.abs(pos.get(1) - 15.0) < 1e-5,
                    "sspA longitude ~15.0 (UTM33 central meridian), got " + pos.get(1));
            Check.that(pos.get(0) > 47.0 && pos.get(0) < 48.5,
                    "sspA latitude in the UTM33/5300000 band, got " + pos.get(0));
            // ... and sspA2 the same way: psaA2's QuayRef also resolves to qA1, and its
            // StopPlaceRef (spA) finds NO mapping entry — spA has no centroid — so the last-wins
            // StopPlaceRef arm does not overwrite the quay hit.
            LocationStructure a2Loc = ssps.get("sspA2").getLocation();
            Check.that(a2Loc != null, "sspA2 location inferred from its quay");
            Check.equals(Projection.WGS84_URN, a2Loc.getSrsName(), "sspA2 location reprojected to WGS84");
            List<Double> pos2 = a2Loc.getPos().getValue();
            Check.that(Math.abs(pos2.get(1) - 15.0) < 1e-5,
                    "sspA2 longitude ~15.0 (UTM33 central meridian), got " + pos2.get(1));
            Check.that(pos2.get(0) > 47.0 && pos2.get(0) < 48.5,
                    "sspA2 latitude in the UTM33/5300000 band, got " + pos2.get(0));
            Check.that(ssps.get("sspB").getLocation().getPos() == null,
                    "sspB stays in attribute form (already WGS84, untouched by reprojection)");

            // C ensemble (direction inference): pat1's direction TYPE becomes a DirectionRef
            // plus one synthesized Direction row (drained after the pass).
            Map<String, ServiceJourneyPattern> patterns = byId(db, txn, ServiceJourneyPattern.class);
            Check.equals("test:Direction:outbound", patterns.get("pat1").getDirectionRef().getRef(),
                    "pat1 DirectionRef backfilled from its direction type");
            Map<String, noi.netex.model.Direction> directions =
                    byId(db, txn, noi.netex.model.Direction.class);
            Check.that(directions.containsKey("test:Direction:outbound"),
                    "the synthesized Direction row exists");

            // C ensemble (backfills): vehicle-type short name; sj2's operator from line2.
            Check.equals("NIEDE", Mls.text(byId(db, txn, VehicleType.class).get("vt1").getShortName()),
                    "vt1 short name abbreviated from its name");
            Check.equals("op2",
                    byId(db, txn, ServiceJourney.class).get("sj2").getOperatorRef().getRef(),
                    "sj2 operator backfilled from line2 via the ensemble");
        }
    }

    // ------------------------------------------------ journeys and the interchange lifecycle

    @Test
    void testJourneysAndInterchange(TestStore ts) throws Exception {
        try (Store db = transformed(ts, "epip-c.lmdb"); Txn txn = db.roTxn()) {
            Map<String, ServiceJourney> journeys = byId(db, txn, ServiceJourney.class);

            // B journeys, pattern arm: sj1 keeps its pattern ref and passing times.
            ServiceJourney sj1 = journeys.get("sj1");
            Check.equals("pat1", sj1.getJourneyPatternRef().getValue().getRef(), "sj1 keeps pat1");
            Check.equals(2, sj1.getPassingTimes().getTimetabledPassingTime().size(),
                    "sj1 passing times kept");

            // B journeys, calls arm: sj2's calls became a synthesized pattern + passing times.
            ServiceJourney sj2 = journeys.get("sj2");
            Check.isNull(sj2.getCalls(), "sj2 calls stripped");
            String genPatId = sj2.getJourneyPatternRef().getValue().getRef();
            Check.that(genPatId.startsWith("test:ServiceJourneyPattern:"),
                    "sj2 points at a synthesized pattern, got " + genPatId);
            Check.equals(2, sj2.getPassingTimes().getTimetabledPassingTime().size(),
                    "sj2 timetabled passing times derived from its calls");
            ServiceJourneyPattern genPat = byId(db, txn, ServiceJourneyPattern.class).get(genPatId);
            Check.that(genPat != null, "the synthesized pattern is a row");
            List<?> points = genPat.getPointsInSequence()
                    .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
            Check.equals(2, points.size(), "synthesized pattern has both stops");
            Check.equals("sspA2", ((StopPointInJourneyPattern) points.get(0))
                    .getScheduledStopPointRef().getValue().getRef(), "pattern stop 1 is sspA2");
            Check.equals("sspB", ((StopPointInJourneyPattern) points.get(1))
                    .getScheduledStopPointRef().getValue().getRef(), "pattern stop 2 is sspB");

            // B interchange + C repair: the JourneyMeeting became a stay-seated interchange;
            // its to-point (created as the AtStopPointRef fallback — sj2 had no pattern in
            // phase B) was re-anchored in phase C onto sj2's NEW pattern at the same station.
            Map<String, ServiceJourneyInterchange> sjis =
                    byId(db, txn, ServiceJourneyInterchange.class);
            ServiceJourneyInterchange sji = sjis.get("jm1");
            Check.that(sji != null, "ServiceJourneyInterchange created from the JourneyMeeting");
            Check.equals(Boolean.TRUE, sji.isStaySeated(), "interchange is stay-seated");
            Check.equals("sj1", sji.getFromJourneyRef().getRef(), "from journey kept");
            Check.equals("ServiceJourney", sji.getFromJourneyRef().getNameOfRefClass(),
                    "journey refs carry nameOfRefClass=ServiceJourney");
            Check.equals("sj2", sji.getToJourneyRef().getRef(), "to journey kept");
            Check.equals("sspA", sji.getFromPointRef().getRef(),
                    "from point resolved on pat1 at the meeting station");
            Check.equals("sspA2", sji.getToPointRef().getRef(),
                    "to point REPAIRED onto sj2's synthesized pattern (same station remap)");
        }
    }
}
