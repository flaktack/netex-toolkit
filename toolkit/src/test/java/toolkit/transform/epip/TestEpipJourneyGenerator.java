package toolkit.transform.epip;

// The EPIP journey generator, serial only.
//
// testMd5SignatureIsStable and testMd5SignatureNonPrintableRepr freeze the pointInPatternSignature
// -> md5 pattern-hash bytes, because those bytes end up in generated ids that are already in
// published exports. Any change to the rendering changes ids.

import jakarta.xml.bind.JAXBElement;
import noi.netex.calls.Calls;
import noi.netex.model.ArrivalStructure;
import noi.netex.model.Call;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.Calls_RelStructure;
import noi.netex.model.DepartureStructure;
import noi.netex.model.DestinationDisplayView;
import noi.netex.model.DirectionTypeEnumeration;
import noi.netex.model.LineRefStructure;
import noi.netex.model.MultilingualString;
import noi.netex.model.NoticeAssignment;
import noi.netex.model.NoticeRefStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.StopUseEnumeration;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import noi.netex.time.XmlTime;
import toolkit.test.TestStore;

import java.math.BigInteger;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import noi.netex.text.Mls;

public class TestEpipJourneyGenerator {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    // One pattern shared by two timetabled journeys: sj_own brings its own VehicleTypeRef
    // (vt_own), sj_block has none. A Block schedules BOTH journeys with vt_block, so the
    // Block-derived ref is available for either — only sj_block may receive it. sj_bare has no
    // timing source at all.
    private static final String NETEX = """
        <ResourceFrame id="rf" version="1">
          <vehicleTypes>
            <VehicleType id="vt_own" version="1"/>
            <VehicleType id="vt_block" version="1"/>
          </vehicleTypes>
        </ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>A</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="ssp2" version="1"><Name>B</Name></ScheduledStopPoint>
          </scheduledStopPoints>
          <journeyPatterns>
            <ServiceJourneyPattern id="pat1" version="1"><pointsInSequence>
              <StopPointInJourneyPattern id="sp1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
              <StopPointInJourneyPattern id="sp2" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
            </pointsInSequence></ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="sj_own" version="1">
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <VehicleTypeRef ref="vt_own" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_own_1" version="1"><StopPointInJourneyPatternRef ref="sp1" version="1" order="1"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
                <TimetabledPassingTime id="tpt_own_2" version="1"><StopPointInJourneyPatternRef ref="sp2" version="1" order="2"/><ArrivalTime>10:30:00</ArrivalTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj_block" version="1">
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <passingTimes>
                <TimetabledPassingTime id="tpt_blk_1" version="1"><StopPointInJourneyPatternRef ref="sp1" version="1" order="1"/><DepartureTime>11:00:00</DepartureTime></TimetabledPassingTime>
                <TimetabledPassingTime id="tpt_blk_2" version="1"><StopPointInJourneyPatternRef ref="sp2" version="1" order="2"/><ArrivalTime>11:30:00</ArrivalTime></TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            <ServiceJourney id="sj_bare" version="1"/>
          </vehicleJourneys>
        </TimetableFrame>
        <VehicleScheduleFrame id="vsf" version="1">
          <blocks>
            <Block id="b1" version="1">
              <VehicleTypeRef ref="vt_block" version="1"/>
              <journeys>
                <ServiceJourneyRef ref="sj_own" version="1"/>
                <ServiceJourneyRef ref="sj_block" version="1"/>
              </journeys>
            </Block>
          </blocks>
        </VehicleScheduleFrame>
        """;

    /// Load the fixture, run the generator, index the emitted ServiceJourneys by id.
    private static Map<String, ServiceJourney> journeys(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        List<Object> result = Gen.run(db, (s, txn) ->
                EpipJourneys.epipServiceJourneyGenerator(s, txn, GeneratorDefaults.TEST));
        Map<String, ServiceJourney> out = new LinkedHashMap<>();
        for (Object o : result) {
            if (o instanceof ServiceJourney sj) out.put(sj.getId(), sj);
        }
        return out;
    }

    /// A journey keeps its own VehicleTypeRef; one without gets its Block's.
    @Test
    void testVehicleTypeKeptWhenPresentAndBackfilledWhenAbsent(TestStore db) throws Exception {
        Map<String, ServiceJourney> journeys = journeys(db);

        Check.equals("vt_own", journeys.get("sj_own").getVehicleTypeRef().getValue().getRef(),
                "sj_own must KEEP its own VehicleTypeRef");
        Check.equals("vt_block", journeys.get("sj_block").getVehicleTypeRef().getValue().getRef(),
                "sj_block is backfilled from the Block");
    }

    /// A journey with no timing source at all is dropped, not fatal.
    @Test
    void testJourneyWithoutTimingSourceIsDropped(TestStore db) throws Exception {
        Map<String, ServiceJourney> journeys = journeys(db);

        // sj_bare has neither passing times, calls, nor a journey-pattern/time-demand pair: it is
        // dropped, and the generator completes instead of dying on its absent pattern ref.
        List<String> ids = new ArrayList<>(journeys.keySet());
        Collections.sort(ids);
        Check.equals(List.of("sj_block", "sj_own"), ids, "only the timetabled journeys survive");
    }

    /// The md5-signature freeze: a hand-built calls journey whose points carry an enum stop use, a
    /// boolean request stop, a DestinationDisplayView (the tuple arm of ddSig) and a
    /// NoticeAssignment. Every id asserted below embeds the pattern hash, so these ids change if
    /// any signature byte does — 'StopUseEnumeration.ACCESS', 'True'/'False', the
    /// MultilingualString rendering, the notice target ref.
    ///
    /// The signatures hashed. A line wrap inside these quoted transcriptions stands for exactly one
    /// space (sig2 wraps after "lang=None," — the true string continues " text_id_type=...";
    /// joining the lines without that space rederives 5dcf3d17add71818, not the asserted hash):
    ///
    ///     'ssp1|1|StopUseEnumeration.ACCESS|True||'
    ///     "ssp2|2|StopUseEnumeration.NO_BOARDING_OR_ALIGHTING|False|(MultilingualString(lang=None,
    ///      text_id_type=None, content=['Bolzano/Bozen']), None)|not1"
    ///
    /// and md5(';'.join(['line1', 'DirectionTypeEnumeration.OUTBOUND', sig1, sig2]))[0:16] =
    /// "3b4597d7c4bc884e". The direction component hashed is the enum repr
    /// "DirectionTypeEnumeration.OUTBOUND", not the XML token 'outbound'.
    @Test
    void testMd5SignatureIsStable() {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("sj1");
        sj.setVersion("1");
        sj.setDirectionType(DirectionTypeEnumeration.OUTBOUND);
        LineRefStructure line = new LineRefStructure();
        line.setRef("line1");
        line.setVersion("1");
        sj.setLineRef(FACTORY.createLineRef(line));

        Call c1 = new Call();
        c1.setId("c1");
        c1.setVersion("1");
        c1.setOrder(BigInteger.valueOf(1));
        ScheduledStopPointRefStructure s1 = new ScheduledStopPointRefStructure();
        s1.setRef("ssp1");
        s1.setVersion("1");
        c1.setScheduledStopPointRef(FACTORY.createScheduledStopPointRef(s1));
        c1.setStopUse(StopUseEnumeration.ACCESS);
        c1.setRequestStop(Boolean.TRUE);
        DepartureStructure dep = new DepartureStructure();
        dep.setTime(XmlTime.of(10, 0, 0));
        c1.setDeparture(dep);

        Call c2 = new Call();
        c2.setId("c2");
        c2.setVersion("1");
        c2.setOrder(BigInteger.valueOf(2));
        ScheduledStopPointRefStructure s2 = new ScheduledStopPointRefStructure();
        s2.setRef("ssp2");
        s2.setVersion("1");
        c2.setScheduledStopPointRef(FACTORY.createScheduledStopPointRef(s2));
        c2.setStopUse(StopUseEnumeration.NO_BOARDING_OR_ALIGHTING);
        c2.setRequestStop(Boolean.FALSE);
        DestinationDisplayView ddv = new DestinationDisplayView();
        MultilingualString frontText = Mls.of("Bolzano/Bozen");
        ddv.setFrontText(frontText);
        c2.setDestinationDisplayView(ddv);
        NoticeAssignment na = new NoticeAssignment();
        na.setId("test:NoticeAssignment:sj1-2-1");   // journey-derived provenance, renumbered below
        na.setVersion("1");
        NoticeRefStructure noticeRef = new NoticeRefStructure();
        noticeRef.setRef("not1");
        noticeRef.setVersion("1");
        na.setNoticeRef(noticeRef);
        Call_VersionedChildStructure.NoticeAssignments nas = new Call_VersionedChildStructure.NoticeAssignments();
        nas.getNoticeAssignment_OrNoticeAssignmentView().add(FACTORY.createNoticeAssignment(na));
        c2.setNoticeAssignments(nas);
        ArrivalStructure arr = new ArrivalStructure();
        arr.setTime(XmlTime.of(10, 30, 0));
        c2.setArrival(arr);

        Calls_RelStructure calls = new Calls_RelStructure();
        Calls.add(calls, c1);
        Calls.add(calls, c2);
        sj.setCalls(calls);

        ServiceJourneyPattern sjp = EpipJourneys.serviceJourneyPatternFromCalls(sj, GeneratorDefaults.TEST);

        Check.equals("test:ServiceJourneyPattern:3b4597d7c4bc884e", sjp.getId(),
                "pattern id must equal the md5 of the signature bytes");
        Check.equals("1", sjp.getVersion(), "pattern version is the journey's");

        List<PointInLinkSequence_VersionedChildStructure> points = sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        Check.equals(2, points.size(), "two projected points");
        StopPointInJourneyPattern p1 = (StopPointInJourneyPattern) points.get(0);
        StopPointInJourneyPattern p2 = (StopPointInJourneyPattern) points.get(1);
        Check.equals("test:StopPointInJourneyPattern:3b4597d7c4bc884e-1", p1.getId(), "point 1 id from hash-order");
        Check.equals("test:StopPointInJourneyPattern:3b4597d7c4bc884e-2", p2.getId(), "point 2 id from hash-order");

        // The projected NoticeAssignment is renumbered from the pattern identity and its
        // journey-calendar validity conditions are cleared.
        NoticeAssignment projected = (NoticeAssignment) ((JAXBElement<?>) p2.getNoticeAssignments()
                .getNoticeAssignment_OrNoticeAssignmentView().get(0)).getValue();
        Check.equals("test:NoticeAssignment:3b4597d7c4bc884e-2-1", projected.getId(),
                "notice assignment renumbered from the pattern identity");
        Check.isNull(projected.getValidityConditions(), "journey validity conditions cleared");
        Check.that(projected.getValidBetween().isEmpty(), "journey validBetween cleared");
    }

    /// The sigRepr battery: the ddSig MultilingualString renderings carry every non-printable
    /// escape class — \xNN (NBSP, C1 NEL, ESC), BMP backslash-u (ZWSP), astral backslash-U (tag
    /// char) — a literal printable astral emoji, and the single-quote → double-quote switch. No
    /// direction (the 'None' arm).
    ///
    /// The expected hash input, with `<bsu>`/`<bsU>` denoting backslash-u/-U:
    ///
    ///     'sspA|1|None|None|(MultilingualString(lang=None, text_id_type=None,
    ///      content=[\'A\xa0B<bsu>200bC\x85D\x1bE<bsU>000e0041F<U+1F68C literal>\']),
    ///      MultilingualString(lang=None, text_id_type=None, content=["it\'s\xa0ok"]))|'
    ///
    /// with direction None -> 'None'; md5(';'.join(['line2', 'None', sig]))[0:16] =
    /// "f079c9aa130ace35".
    @Test
    void testMd5SignatureNonPrintableRepr() {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("sj2");
        sj.setVersion("1");
        LineRefStructure line = new LineRefStructure();
        line.setRef("line2");
        line.setVersion("1");
        sj.setLineRef(FACTORY.createLineRef(line));

        Call c1 = new Call();
        c1.setId("c1");
        c1.setVersion("1");
        c1.setOrder(BigInteger.valueOf(1));
        ScheduledStopPointRefStructure s1 = new ScheduledStopPointRefStructure();
        s1.setRef("sspA");
        s1.setVersion("1");
        c1.setScheduledStopPointRef(FACTORY.createScheduledStopPointRef(s1));
        DestinationDisplayView ddv = new DestinationDisplayView();
        // "A<NBSP>B<ZWSP>C<NEL>D<ESC>E<U+E0041>F<U+1F68C>"
        MultilingualString frontText = Mls.of("A\u00a0B\u200bC\u0085D\u001bE\udb40\udc41F\ud83d\ude8c");
        ddv.setFrontText(frontText);
        MultilingualString name = Mls.of("it's\u00a0ok");     // quote-switch + NBSP
        ddv.setName(name);
        c1.setDestinationDisplayView(ddv);
        DepartureStructure dep = new DepartureStructure();
        dep.setTime(XmlTime.of(9, 0, 0));
        c1.setDeparture(dep);

        Calls_RelStructure calls = new Calls_RelStructure();
        Calls.add(calls, c1);
        sj.setCalls(calls);

        ServiceJourneyPattern sjp = EpipJourneys.serviceJourneyPatternFromCalls(sj, GeneratorDefaults.TEST);

        Check.equals("test:ServiceJourneyPattern:f079c9aa130ace35", sjp.getId(),
                "pattern id must equal the md5 over the escaped-repr signature bytes");
        StopPointInJourneyPattern p1 = (StopPointInJourneyPattern) sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern().get(0);
        Check.equals("test:StopPointInJourneyPattern:f079c9aa130ace35-1", p1.getId(), "point id from hash-order");
    }
}
