package toolkit.codec;

// Regression test. A serializer that re-emits every object in FIELD order silently repairs a
// source feed whose children are out of the XSD sequence; the fragment format inherits the
// source's order instead, so the repair has to be explicit.
//
// WHAT IT GATES. Two Java-only schema violations, invisible to every other gate — the export A/B
// declares element order a normalisation, the round-trip harness absorbs it, graph dumps carry only
// Class|id|version, and OTP's reader accepts out-of-order children:
//
//   1. `z-links.xml`'s TimetableFrame emitted `journeyInterchanges` before `trainNumbers`.
//      TimetableFrameGroup's xsd:sequence puts TimetableJourneyInFrameGroup (trainNumbers) before
//      InterchangeInFrameGroup (journeyInterchanges), so this arm was the invalid one. z-links has
//      since been split — interchanges ride with their journeys in a group file, trainNumbers are
//      shared — so the same sequence is now asserted on both frames that replaced it.
//
//   2. VehicleTypes in `shared-01-resource.xml` carried FuelType before EuroClass, an order that
//      comes STRAIGHT FROM THE SOURCE FEED, because the fragment format stores the object's
//      captured XML and the exporter copies those bytes. toolkit.codec.FragmentOrder is the missing
//      normalisation: it reorders a fragment's top-level children into the class's declared JAXB
//      emission order — which IS the XSD sequence — at emission time, and returns the fragment
//      untouched otherwise.
//
// The unit methods below pin FragmentOrder's contract from both sides: it must repair a real
// misordering, and it must be a byte-level no-op on everything else — including the cases where it
// cannot rank what it sees (comment/CDATA, unknown child element), where "leave it alone" is the
// only safe answer. The two store-taking methods pin the end-to-end statement the XSD gate makes:
// the STORED fragment is still in source order and the EXPORTED one is in schema order.

import noi.netex.model.ServiceJourney;
import noi.netex.model.VehicleType;
import org.junit.jupiter.api.Test;
import toolkit.Drivers;
import toolkit.store.RawRow;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TestFragmentOrder {

    static final String NS = "http://www.netex.org.uk/netex";

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the rank table

    /// The ranks are read off the generated model's own JAXB annotations, so this asserts the ONE
    /// property the whole fix rests on: the model's declared emission order is the XSD sequence.
    /// TransportTypeGroup is Name, ShortName, Description, PrivateCode, TransportTypePropertiesGroup
    /// (EuroClass ... MaximumRange), TransportMode, VehicleTypeTotalCapacityGroup — then
    /// VehicleTypeGroup (capacities, LowFloor, ...) — after EntityInVersionGroup (ValidBetween) and
    /// DataManagedObjectGroup (keyList).
    @Test
    void testRanksFollowTheDeclaredXsdSequence() {
        Map<String, Integer> r = FragmentOrder.ranks(VehicleType.class);
        List<String> sequence = List.of("validityConditions", "ValidBetween", "keyList", "Name",
                "ShortName", "PrivateCode", "EuroClass", "FuelType", "MaximumRange", "TransportMode",
                "capacities", "LowFloor", "Length");
        for (int i = 1; i < sequence.size(); i++) {
            Integer a = r.get(sequence.get(i - 1));
            Integer b = r.get(sequence.get(i));
            Check.that(a != null, "VehicleType declares " + sequence.get(i - 1));
            Check.that(b != null, "VehicleType declares " + sequence.get(i));
            Check.that(a < b, sequence.get(i - 1) + " must rank before " + sequence.get(i)
                    + " (got " + a + " vs " + b + ")");
        }
    }

    // ------------------------------------------------------------------ the rewrite

    /// The measured defect, verbatim from the IT:ITC3 source feed: FuelType before EuroClass.
    private static final String MISORDERED_VEHICLE_TYPE =
            "<VehicleType id=\"IT:ITC3:VehicleType:AMT:232\" version=\"240303\">\n"
                    + "\t<ValidBetween id=\"vb\" version=\"240303\"><FromDate>2024-01-01T00:00:00+02:00</FromDate></ValidBetween>\n"
                    + "\t<PrivateCode>232</PrivateCode>\n"
                    + "\t<FuelType>diesel</FuelType>\n"
                    + "\t<EuroClass>EURO 5</EuroClass>\n"
                    + "\t<capacities><PassengerCapacity id=\"232\" version=\"240303\"><TotalCapacity>91</TotalCapacity></PassengerCapacity></capacities>\n"
                    + "\t<LowFloor>false</LowFloor>\n"
                    + "</VehicleType>";

    @Test
    void testTheMeasuredMisorderingIsRepaired() {
        byte[] in = utf8(MISORDERED_VEHICLE_TYPE);
        String out = str(FragmentOrder.normalise(VehicleType.class, in));
        Check.that(out.indexOf("<EuroClass>") < out.indexOf("<FuelType>"),
                "EuroClass now precedes FuelType: " + out);
    }

    /// The strongest statement available: the repaired fragment is the source fragment with the two
    /// children SWAPPED and nothing else — same attributes, same quoting, same tabs and newlines,
    /// same nested content. A re-marshal would have changed all four.
    @Test
    void testNothingButTheChildOrderChanges() {
        String expected = MISORDERED_VEHICLE_TYPE
                .replace("\t<FuelType>diesel</FuelType>\n\t<EuroClass>EURO 5</EuroClass>\n",
                        "\t<EuroClass>EURO 5</EuroClass>\n\t<FuelType>diesel</FuelType>\n");
        Check.equals(expected, str(FragmentOrder.normalise(VehicleType.class, utf8(MISORDERED_VEHICLE_TYPE))),
                "only the two children move");
    }

    /// The common case, and the one the export's throughput depends on: an in-order fragment is
    /// returned as the SAME ARRAY — no scan result is copied, nothing is allocated.
    @Test
    void testAnOrderedFragmentIsReturnedUnchangedAndUncopied() {
        byte[] in = utf8("<VehicleType id=\"v\"><EuroClass>EURO 6</EuroClass>"
                + "<FuelType>diesel</FuelType><LowFloor>true</LowFloor></VehicleType>");
        Check.that(FragmentOrder.normalise(VehicleType.class, in) == in,
                "an already-ordered fragment is the identical array");
    }

    /// Equal-rank siblings (repeated elements, xsd:choice members) must keep their source order.
    @Test
    void testEqualRankSiblingsKeepSourceOrder() {
        byte[] in = utf8("<VehicleType id=\"v\">"
                + "<FuelType>diesel</FuelType>"
                + "<ValidBetween id=\"a\"/><ValidBetween id=\"b\"/><ValidBetween id=\"c\"/>"
                + "</VehicleType>");
        String out = str(FragmentOrder.normalise(VehicleType.class, in));
        Check.equals("<VehicleType id=\"v\"><ValidBetween id=\"a\"/><ValidBetween id=\"b\"/>"
                        + "<ValidBetween id=\"c\"/><FuelType>diesel</FuelType></VehicleType>", out,
                "the three ValidBetweens move as a stable block, ahead of FuelType");
    }

    /// A comment or CDATA section is content the byte scanner does not model, so it declines rather
    /// than guesses. Declining is always safe.
    @Test
    void testAFragmentCarryingACommentOrCdataIsLeftVerbatim() {
        byte[] comment = utf8("<VehicleType id=\"v\"><!--note--><FuelType>diesel</FuelType>"
                + "<EuroClass>EURO 5</EuroClass></VehicleType>");
        Check.that(FragmentOrder.normalise(VehicleType.class, comment) == comment,
                "a comment makes the fragment untouchable");
        byte[] cdata = utf8("<VehicleType id=\"v\"><FuelType>diesel</FuelType>"
                + "<Name><![CDATA[a<b]]></Name><EuroClass>EURO 5</EuroClass></VehicleType>");
        Check.that(FragmentOrder.normalise(VehicleType.class, cdata) == cdata,
                "a CDATA section makes the fragment untouchable");
    }

    /// A child the class does not declare — a substitution-group member reached through
    /// @XmlElementRef, a foreign-namespace element such as gml:LineString — cannot be ranked, so the
    /// whole fragment is declined. Partial knowledge must never produce a partial permutation.
    @Test
    void testAnUnrankableChildDeclinesTheWholeFragment() {
        byte[] in = utf8("<VehicleType id=\"v\"><FuelType>diesel</FuelType>"
                + "<NotAThing>x</NotAThing><EuroClass>EURO 5</EuroClass></VehicleType>");
        Check.that(FragmentOrder.normalise(VehicleType.class, in) == in,
                "an unknown child leaves the whole fragment verbatim");
    }

    /// Self-closing children are spans like any other and must move correctly.
    @Test
    void testSelfClosingChildrenMove() {
        byte[] in = utf8("<VehicleType id=\"v\"><FuelType>diesel</FuelType><ValidBetween id=\"a\"/></VehicleType>");
        Check.equals("<VehicleType id=\"v\"><ValidBetween id=\"a\"/><FuelType>diesel</FuelType></VehicleType>",
                str(FragmentOrder.normalise(VehicleType.class, in)), "a self-closing child moves");
    }

    /// A '>' inside an attribute value must not be read as the end of a tag.
    @Test
    void testAGreaterThanInsideAnAttributeValueDoesNotEndTheTag() {
        byte[] in = utf8("<VehicleType id=\"v\"><FuelType>diesel</FuelType>"
                + "<Name lang=\"a&gt;b\">x&gt;y</Name></VehicleType>");
        Check.equals("<VehicleType id=\"v\"><Name lang=\"a&gt;b\">x&gt;y</Name>"
                        + "<FuelType>diesel</FuelType></VehicleType>",
                str(FragmentOrder.normalise(VehicleType.class, in)), "attribute values are opaque");
    }

    /// Fragments with fewer than two children have no order to fix and must never be copied.
    @Test
    void testDegenerateFragmentsAreUntouched() {
        for (String s : List.of("<VehicleType id=\"v\"/>",
                "<VehicleType id=\"v\"></VehicleType>",
                "<VehicleType id=\"v\"><LowFloor>true</LowFloor></VehicleType>")) {
            byte[] in = utf8(s);
            Check.that(FragmentOrder.normalise(VehicleType.class, in) == in, "untouched: " + s);
        }
    }

    /// A journey's passingTimes are ONE child (a container), so the biggest fragments in the export
    /// present the scanner with a handful of top-level children, not thousands.
    @Test
    void testAJourneyFragmentIsRankedAndUnchanged() {
        byte[] in = utf8("<ServiceJourney id=\"sj\" version=\"1\"><Name>25</Name>"
                + "<dayTypes><DayTypeRef ref=\"dt1\" version=\"1\"/></dayTypes>"
                + "<ServiceJourneyPatternRef ref=\"p\" version=\"1\"/>"
                + "<passingTimes><TimetabledPassingTime id=\"pt0\" version=\"1\">"
                + "<DepartureTime>09:40:00</DepartureTime></TimetabledPassingTime></passingTimes>"
                + "</ServiceJourney>");
        Check.that(FragmentOrder.normalise(ServiceJourney.class, in) == in,
                "an in-order ServiceJourney is untouched: " + str(in));
    }

    /// The rank table is derived from the model's annotations; the JAXB MARSHALLER is the reference
    /// implementation of the same annotations, and is the path every mutated object already takes
    /// ([FragmentCodec#encode]). So the two must agree on the permutation — if they ever diverge,
    /// one of them is emitting an order the schema did not declare.
    @Test
    void testTheRewriteAgreesWithTheJaxbMarshaller() {
        FragmentCodec codec = new FragmentCodec();
        byte[] in = utf8(MISORDERED_VEHICLE_TYPE.replaceFirst(
                "<VehicleType ", "<VehicleType xmlns=\"" + NS + "\" "));
        List<String> ours = topLevelNames(str(FragmentOrder.normalise(VehicleType.class, in)));
        VehicleType bean = codec.decode(codec.encodeRaw(in), VehicleType.class);
        List<String> jaxb = topLevelNames(str(codec.marshalFragment(bean)));
        Check.equals(jaxb, ours, "FragmentOrder's order is the marshaller's order");
    }

    /// Direct child element names of a fragment's root, via a namespace-aware parse.
    private static List<String> topLevelNames(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document d = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(utf8(xml)));
            List<String> out = new ArrayList<>();
            for (Node c = d.getDocumentElement().getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof Element el) out.add(el.getLocalName());
            }
            return out;
        } catch (Exception e) {
            throw new AssertionError("cannot parse " + xml, e);
        }
    }

    // ------------------------------------------------------------------ end to end, through the exporter

    private static final String MISORDERED_RESOURCE_FRAME = """
        <ResourceFrame id="rf" version="1">
          <vehicleTypes>
            <VehicleType id="IT:ITC3:VehicleType:AMT:232" version="240303">
              <PrivateCode>232</PrivateCode>
              <FuelType>diesel</FuelType>
              <EuroClass>EURO 5</EuroClass>
              <LowFloor>false</LowFloor>
            </VehicleType>
          </vehicleTypes>
        </ResourceFrame>
        """;

    /// Every entry of a split export, parsed (parsing IS the well-formedness assertion).
    private static Map<String, Document> export(TestStore ts, String frames, String... args)
            throws Exception {
        Path source = ts.buildDbFile(frames, "source.lmdb");
        Path out = ts.tempPath("split.zip");
        List<String> argv = new ArrayList<>(List.of(source.toString(), out.toString()));
        argv.addAll(List.of(args));
        // Source-launched, in its own JVM: the export profile lives in scripts/EpipDbToXml.java and
        // nothing compiled can import it. See toolkit.Drivers.
        Drivers.run("EpipDbToXml", argv);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Map<String, Document> entries = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                entries.put(e.getName(),
                        dbf.newDocumentBuilder().parse(new ByteArrayInputStream(zin.readAllBytes())));
            }
        }
        return entries;
    }

    /// Local names of the direct child elements of the first `parent` in the document.
    private static List<String> childNames(Document doc, String parent) {
        NodeList nl = doc.getElementsByTagNameNS(NS, parent);
        Check.that(nl.getLength() > 0, "no <" + parent + "> in the entry");
        List<String> names = new ArrayList<>();
        for (Node c = nl.item(0).getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el) names.add(el.getLocalName());
        }
        return names;
    }

    /// The whole point, end to end: the source order survives INTO the database (the fragment format
    /// is still a verbatim capture — that is what makes the round trip lossless) and is repaired ON
    /// THE WAY OUT. Both halves are asserted, because fixing the first would be the wrong fix.
    @Test
    void testTheExportRepairsWhatTheStoreKeptVerbatim(TestStore ts) throws Exception {
        ts.loadNetex(MISORDERED_RESOURCE_FRAME);
        FragmentCodec codec = new FragmentCodec();
        String stored = null;
        try (Txn tx = ts.store.roTxn()) {
            for (RawRow row : ts.store.iterRawObjects(tx, VehicleType.class)) {
                stored = str(codec.decompress(row.blob()));
            }
        }
        Check.that(stored != null, "the VehicleType was stored");
        Check.that(stored.indexOf("<FuelType>") < stored.indexOf("<EuroClass>"),
                "the STORE still holds the source's order — the fragment is a verbatim capture: " + stored);

        Map<String, Document> entries = export(ts, MISORDERED_RESOURCE_FRAME);
        List<String> children = childNames(entries.get("shared-01-resource.xml"), "VehicleType");
        Check.equals(List.of("PrivateCode", "EuroClass", "FuelType", "LowFloor"), children,
                "the EXPORT emits the XSD sequence");
    }

    // A cross-border ServiceFrame/TimetableFrame pair: IT train 25 and CH train 25 sharing stops
    // 85_300 / 85_400 on 2026-01-05. Only the SHAPE matters here -- this test reads the order the
    // exporter emits children in, not what they mean.
    //
    // Duplicated rather than shared with ../noi-pipeline's copy, nothing here being able to reach
    // across a repository. The two are free to drift: this one only has to stay valid enough to
    // export.
    private static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <journeyPatterns>
    <ServiceJourneyPattern id="IT:Pat:1" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="ip0" version="1" order="1"><ScheduledStopPointRef ref="830000020" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip1" version="1" order="2"><ScheduledStopPointRef ref="830000010" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip2" version="1" order="2"><ScheduledStopPointRef ref="850000300" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip3" version="1" order="3"><ScheduledStopPointRef ref="850000400" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
  <validityConditions>
    <AvailabilityCondition id="ac1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></AvailabilityCondition>
  </validityConditions>
</ServiceFrame>
<ResourceFrame id="rf" version="1">
  <trainNumbers><TrainNumber id="ch:Tn:25" version="1"><ForAdvertisement>25</ForAdvertisement></TrainNumber></trainNumbers>
</ResourceFrame>
<ServiceCalendarFrame id="scf" version="1">
  <dayTypes><DayType id="dt1" version="1"/></dayTypes>
  <operatingPeriods><UicOperatingPeriod id="op1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></UicOperatingPeriod></operatingPeriods>
  <dayTypeAssignments><DayTypeAssignment id="dta1" version="1" order="1"><OperatingPeriodRef ref="op1" version="1"/><DayTypeRef ref="dt1" version="1"/></DayTypeAssignment></dayTypeAssignments>
</ServiceCalendarFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="IT:Sj:1" version="1">
      <Name>25</Name>
      <dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes>
      <ServiceJourneyPatternRef ref="IT:Pat:1" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="pt0" version="1"><StopPointInJourneyPatternRef ref="ip0" version="1" order="1"/><DepartureTime>09:40:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt1" version="1"><StopPointInJourneyPatternRef ref="ip1" version="1" order="2"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt2" version="1"><StopPointInJourneyPatternRef ref="ip2" version="1" order="2"/><DepartureTime>11:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt3" version="1"><StopPointInJourneyPatternRef ref="ip3" version="1" order="3"/><ArrivalTime>12:00:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="ch:Sj:1" version="1">
      <validityConditions><AvailabilityConditionRef ref="ac1" version="1"/></validityConditions>
      <trainNumbers><TrainNumberRef ref="ch:Tn:25"/></trainNumbers>
      <calls>
        <Call id="cl1" version="1" order="1"><ScheduledStopPointRef ref="850000300" version="1"/><Departure><Time>11:05:00</Time></Departure></Call>
        <Call id="cl2" version="1" order="2"><ScheduledStopPointRef ref="850000400" version="1"/><Arrival><Time>11:30:00</Time></Arrival></Call>
      </calls>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""";
    /// TimetableFrameGroup's xsd:sequence, asserted where each container now lands.
    ///
    /// The original bug was z-links.xml emitting InterchangeInFrameGroup (journeyInterchanges)
    /// before TimetableJourneyInFrameGroup (trainNumbers) — the cvc-complex-type.2.4.a the XSD
    /// probe found. z-links no longer exists, and its two halves have since separated again: the
    /// export writes THREE TimetableFrames, one per file level, each carrying one container.
    /// trainNumbers are globally shared (every group's journeys reference them); a shard's
    /// interchanges sit in its gSS-shared.xml, which OTP indexes for the whole group and maps at
    /// the group pop; the journeys are in the numbered files, whose index is dropped one file at a
    /// time. Nothing can violate the sequence while no two of those containers share a frame — so
    /// what this pins is exactly that: which container is in which frame, and alone.
    @Test
    void testTimetableFrameContainersFollowTheXsdSequence(TestStore ts) throws Exception {
        String frames = NETEX + """
            <TimetableFrame id="tf2" version="1">
              <journeyInterchanges>
                <ServiceJourneyInterchange id="IT-CH:ServiceJourneyInterchange:25:1:1" version="1">
                  <StaySeated>true</StaySeated>
                  <FromJourneyRef ref="IT:Sj:1"/>
                  <ToJourneyRef ref="ch:Sj:1"/>
                </ServiceJourneyInterchange>
              </journeyInterchanges>
            </TimetableFrame>
            """;
        Map<String, Document> entries = export(ts, frames);
        Check.that(!entries.containsKey("z-links.xml"),
                "z-links is gone, its two halves moved: " + entries.keySet());

        Document shared = entries.get("shared-04-links.xml");
        Check.that(shared != null, "shared-04-links.xml present: " + entries.keySet());
        Check.equals(List.of("TypeOfFrameRef", "trainNumbers"),
                childNames(shared, "TimetableFrame"),
                "the shared entry carries the trainNumbers and nothing else");

        // The interchange rides in its shard's SHARED GROUP file now. Found by content rather than
        // by name: which shard that is depends on the partition, and this test is about element
        // ORDER, not placement.
        String sharedGroup = null;
        String numbered = null;
        for (Map.Entry<String, Document> e : entries.entrySet()) {
            boolean hasInterchange = e.getValue()
                    .getElementsByTagNameNS(NS, "ServiceJourneyInterchange").getLength() > 0;
            if (e.getKey().matches("g\\d\\d-shared\\.xml") && hasInterchange) {
                sharedGroup = e.getKey();
            } else if (e.getKey().matches("g\\d\\d-\\d{4}\\.xml")
                    && e.getValue().getElementsByTagNameNS(NS, "ServiceJourney").getLength() > 0) {
                numbered = e.getKey();
            }
        }
        Check.that(sharedGroup != null,
                "an interchange rides in a shared group file: " + entries.keySet());
        Check.equals(List.of("TypeOfFrameRef", "journeyInterchanges"),
                childNames(entries.get(sharedGroup), "TimetableFrame"),
                "the shared group entry carries the journeyInterchanges and nothing else");

        Check.that(numbered != null, "a numbered group file holds the journeys: " + entries.keySet());
        Check.equals(List.of("TypeOfFrameRef", "vehicleJourneys"),
                childNames(entries.get(numbered), "TimetableFrame"),
                "a numbered group entry carries the vehicleJourneys and nothing else");
    }
}
