package toolkit.store;

// Regression test for the blob-repair sweep.
//
// WHAT IT GATES. The repair sweep would otherwise decode EVERY referrer that owns a resolved
// outward edge, purely to read each reference's (ref, version, nameOfRefClass) triple. The
// decode-free scan answers the same question from the stored fragment's own attributes — the read
// half of what RefPatcher already does — and unmarshals only the referrers it cannot settle.
//
// The sweep MUST STILL FIRE. "Zero repairs on this corpus" is a measurement, not a guarantee: a
// database assembled by a clone from a source whose blobs were never swept is exactly the state
// described below, and the first test builds one and proves the repair still lands in the stored
// bytes. The remaining tests pin the three things the cheap read depends on:
//   1. the fragment scan finds a reference's attributes, and ONLY unqualified ones (the trap:
//      a namespace-agnostic attribute lookup reads gml:id where id was meant);
//   2. RefElementIndex derives the same target-class name reference extraction derives from the
//      Java ref class — including for the element names NeTEx reuses with different types, which is
//      why a DIRECT child of the fragment root is resolved against its owner class;
//   3. an element the model does not declare, or one whose type the owner does not fix, answers
//      "unknown" so the caller falls back to the unmarshal.

import noi.netex.model.Line;
import noi.netex.model.Network;
import noi.netex.model.Route;
import noi.netex.model.ServiceLink;
import noi.netex.model.ServiceJourney;
import org.junit.jupiter.api.Test;
import toolkit.codec.FragmentCodec;
import toolkit.codec.FragmentPeek;
import toolkit.codec.RefPatcher;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.RecursiveAttributes;
import toolkit.model.RefElementIndex;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class TestBlobSweepScan {

    /// A Route whose LineRef carries NO `nameOfRefClass` — the corpus-normal shape, and the one
    /// that forces the element-name lookup: with no attribute to trust, "which class does this
    /// reference declare?" can only be answered from `<LineRef>` itself.
    private static final String FRAME = """
        <ServiceFrame id="svf" version="1">
          <directions><Direction id="d1" version="1"><Name>Andata</Name></Direction></directions>
          <lines><Line id="l1" version="7"><Name>Linea</Name></Line></lines>
          <routes><Route id="r1" version="1"><Name>Rotta</Name>
            <DirectionRef ref="d1" version="1"/>
            <LineRef ref="l1" version="7"/>
          </Route></routes>
        </ServiceFrame>
        """;

    private static String fragmentOf(TestStore db, Class<?> clazz) {
        FragmentCodec codec = new FragmentCodec();
        try (Txn tx = db.store.roTxn()) {
            for (RawRow row : db.store.iterRawObjects(tx, clazz)) {
                return new String(codec.decompress(row.blob()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no stored " + clazz.getSimpleName());
    }

    // ------------------------------------------------------- the sweep still repairs what it must

    /// The named state: an outward edge that is already wired while the blob's self-description no
    /// longer matches the object the id resolves to, reached by cloning without the source's
    /// blob-repair marker (FeedsToCoupledDbToDb's documented deviation is exactly this).
    ///
    /// Built by cloning the Route WITHOUT its Line and then storing a Network under the Line's id.
    /// The Route's OTHER reference (its Direction) keeps its edge, so the sweep still visits it —
    /// a clone drops an edge whose target it skipped, and an object with no edge at all is not a
    /// referrer. Its LineRef declares (by element) a Line, whose exact id-index key no longer
    /// exists; the id prefix now hits a Network. That is a repair, and it must reach the bytes.
    @Test
    void testTheSweepStillRepairsAMistypedBlob(TestStore db) throws Exception {
        db.loadNetex(FRAME);
        int lineIdx = db.store.classIdxInt(Line.class);
        TestStore target = db.runDbToDb((src, stx, tgt, dtx) -> {
            tgt.cloneFrom(src, stx, dtx, fk -> NulKeyCodec.fullKeyClassIdx(fk) == lineIdx, 0);
            tgt.insertAnyObjects(dtx, List.of(new Network().withId("l1").withVersion("7")));
        });
        try (Txn tx = target.store.roTxn()) {
            Check.that(!target.store.blobRepairMarkerPresent(tx),
                    "a plain clone does not inherit the blob-repair marker, so the target sweeps");
        }

        String before = fragmentOf(target, Route.class);
        Check.that(!before.contains("nameOfRefClass"), "the cloned blob is unrepaired: " + before);

        target.store.resolve();

        String after = fragmentOf(target, Route.class);
        Check.that(after.contains("<LineRef ref=\"l1\" version=\"7\" nameOfRefClass=\"Network\"/>"),
                "the sweep repaired the mis-typed reference in the stored bytes: " + after);
        Check.that(!after.contains("xmlns:ns2="),
                "and did it by patching the bytes, not by re-marshalling: " + after);
    }

    /// The complement: a database whose blobs are all correct must come out byte-identical.
    @Test
    void testASweepOverCorrectBlobsChangesNothing(TestStore db) throws Exception {
        db.loadNetex(FRAME);
        TestStore target = db.runDbToDb((src, stx, tgt, dtx) -> tgt.cloneFrom(src, stx, dtx, null, 0));
        String before = fragmentOf(target, Route.class);
        target.store.resolve();
        Check.equals(before, fragmentOf(target, Route.class),
                "a sweep that finds nothing to repair leaves every byte alone");
    }

    // --------------------------------------------------------------------- the fragment scan

    private static RefPatcher.Occurrence only(String xml) {
        List<RefPatcher.Occurrence> refs = new RefPatcher().scan(xml.getBytes(StandardCharsets.UTF_8));
        Check.that(refs != null && refs.size() == 1, "exactly one reference in: " + xml + " -> " + refs);
        return refs.get(0);
    }

    @Test
    void testScanReadsTheAttributesAndThePosition() {
        RefPatcher.Occurrence o = only(
                "<Route id=\"r1\"><LineRef ref=\"l1\" version=\"7\" nameOfRefClass=\"Line\"/></Route>");
        Check.equals("l1", o.ref(), "ref");
        Check.equals("7", o.version(), "version");
        Check.equals("Line", o.nameOfRefClass(), "nameOfRefClass");
        Check.equals("LineRef", o.element(), "element local name");
        Check.equals(1, o.depth(), "a direct child of the fragment root is depth 1");

        RefPatcher.Occurrence deep = only("<Route id=\"r1\"><pointsInSequence><P>"
                + "<ScheduledStopPointRef ref=\"s1\"/></P></pointsInSequence></Route>");
        Check.equals(3, deep.depth(), "depth counts from the fragment root");
        Check.isNull(deep.version(), "an absent attribute reads as null");
        Check.isNull(deep.nameOfRefClass(), "an absent nameOfRefClass reads as null");
    }

    /// The trap, stated as a test. StAX's `getAttributeValue(null, name)` matches ANY
    /// namespace; JAXB binds `ref`/`version`/`nameOfRefClass` unqualified, so a
    /// namespaced lookalike must be invisible to the scan — and an element whose ONLY `ref` is
    /// namespaced is not a reference at all.
    @Test
    void testScanIgnoresNamespacedLookalikeAttributes() {
        RefPatcher.Occurrence o = only("<Route xmlns:x=\"urn:x\" id=\"r1\">"
                + "<LineRef ref=\"l1\" x:version=\"99\" x:nameOfRefClass=\"Bogus\"/></Route>");
        Check.isNull(o.version(), "x:version is not version");
        Check.isNull(o.nameOfRefClass(), "x:nameOfRefClass is not nameOfRefClass");

        List<RefPatcher.Occurrence> none = new RefPatcher().scan(
                ("<Route xmlns:x=\"urn:x\" id=\"r1\"><Thing x:ref=\"l1\"/></Route>")
                        .getBytes(StandardCharsets.UTF_8));
        Check.that(none != null && none.isEmpty(), "x:ref does not make an element a reference: " + none);
    }

    @Test
    void testScanRefusesAMalformedFragment() {
        Check.isNull(new RefPatcher().scan("<Route><LineRef ref=\"l1\"/>".getBytes(StandardCharsets.UTF_8)),
                "an unparseable fragment answers 'no idea' so the caller unmarshals it");
    }

    // ------------------------------------------------------------------- the element-name index

    /// The index must agree with [RecursiveAttributes#toRef]'s chain, which is the ONLY thing
    /// the sweep's exact-key guard is built from.
    @Test
    void testIndexDerivesTheSameNameOnlyReferencesWould() {
        Check.equals("[Line]", Arrays.toString(RefElementIndex.candidates("LineRef")),
                "LineRef -> Line, exactly as XxxRefStructure -> Xxx would");
        Check.equals("[DayType]", Arrays.toString(RefElementIndex.candidates("DayTypeRef")), "DayTypeRef");
        // ZoneRefStructure derives "Zone", which the model has no class for: only_references then
        // falls back to DataManagedObject, and so must the index.
        Check.equals("[DataManagedObject]", Arrays.toString(RefElementIndex.candidates("ParentZoneRef")),
                "an unregistered derived name falls back to DataManagedObject");
        Check.isNull(RefElementIndex.candidates("NotAnElementInThisModel"),
                "an element the model does not declare answers 'no idea'");
    }

    /// The disambiguation that makes the whole thing worth doing: `FromPointRef` is declared
    /// with nine different types across NeTEx, but a ServiceLink's FromPointRef is a
    /// ScheduledStopPointRefStructure and nothing else. Without the owner-class lookup every stored
    /// ServiceLink would have to be unmarshalled — most referrers on a real feed.
    @Test
    void testTheOwnerClassResolvesAReusedElementName() {
        String[] global = RefElementIndex.candidates("FromPointRef");
        Check.that(global != null && global.length > 1,
                "FromPointRef is ambiguous on its own: " + Arrays.toString(global));
        Check.equals("[ScheduledStopPoint]",
                Arrays.toString(RefElementIndex.candidatesUnder(ServiceLink.class, "FromPointRef")),
                "a ServiceLink's own FromPointRef is not ambiguous");
        Check.equals("[Direction]",
                Arrays.toString(RefElementIndex.candidatesUnder(Route.class, "DirectionRef")),
                "a Route's own DirectionRef, whose @XmlElement fixes the type");
        Check.isNull(RefElementIndex.candidatesUnder(Route.class, "FromPointRef"),
                "an element the owner does not declare answers 'no idea'");
        // @XmlElementRef fields resolve through the GLOBAL declaration (a substituting subtype may
        // appear under the same field), so the owner deliberately does not answer for them —
        // Route.LineRef and ServiceJourney.JourneyPatternRef are both of that shape.
        Check.isNull(RefElementIndex.candidatesUnder(Route.class, "LineRef"),
                "a substitution-group field is left to the global candidate set");
        Check.isNull(RefElementIndex.candidatesUnder(ServiceJourney.class, "JourneyPatternRef"),
                "…and so is a ServiceJourney's JourneyPatternRef");
    }

    // ---------------------------------------------- the single-attribute read (FragmentPeek)

    /// `epip_repair_interchange_points` asks each referenced ServiceJourney one question —
    /// which pattern is it on — and used to unmarshal the whole journey, TimetabledPassingTimes and
    /// all, to answer it. The field is an `@XmlElementRef`, so the answer can be written with
    /// any member of `JourneyPatternRef`'s substitution group and nothing else; that set, and
    /// the direct-child rule, are what make the byte read equivalent.
    @Test
    void testPeekReadsTheJourneyPatternRefTheFieldWouldHold() {
        Set<String> group = RefElementIndex.substitutionGroup("JourneyPatternRef");
        Check.equals("[DeadRunJourneyPatternRef, JourneyPatternRef, ServiceJourneyPatternRef,"
                        + " ServicePatternRef]", group.toString(),
                "the substitution group JAXB would accept in ServiceJourney.journeyPatternRef");

        FragmentPeek peek = new FragmentPeek();
        Check.equals("p1", peek.firstChildRef(("<ServiceJourney id=\"j1\"><Name>x</Name>"
                        + "<ServiceJourneyPatternRef ref=\"p1\" version=\"2\"/>"
                        + "<passingTimes><TimetabledPassingTime id=\"t\"/></passingTimes>"
                        + "</ServiceJourney>").getBytes(StandardCharsets.UTF_8), group).ref(),
                "a substituting element is read like the head element");
        Check.isNull(peek.firstChildRef(("<ServiceJourney id=\"j1\"><Name>x</Name></ServiceJourney>")
                        .getBytes(StandardCharsets.UTF_8), group),
                "no such child = the field JAXB would fill is empty");
        // A pattern ref nested inside another object is NOT this journey's pattern.
        Check.isNull(peek.firstChildRef(("<ServiceJourney id=\"j1\"><parts><JourneyPart id=\"a\">"
                        + "<JourneyPatternRef ref=\"deep\"/></JourneyPart></parts></ServiceJourney>")
                        .getBytes(StandardCharsets.UTF_8), group),
                "only a DIRECT child of the fragment root counts");
        Check.isNull(peek.firstChildRef(("<ServiceJourney id=\"j1\"><JourneyPatternRef/></ServiceJourney>")
                        .getBytes(StandardCharsets.UTF_8), group).ref(),
                "an element with no ref attribute reads as a present element with a null ref");
    }

    /// Every ambiguity the model actually has is exposed, not silently collapsed to one answer.
    @Test
    void testAmbiguousNamesKeepEveryCandidate() {
        int[] s = RefElementIndex.stats();
        Check.that(s[0] > 800, "the index covers the model's reference elements: " + s[0]);
        Check.that(s[1] == 13, "13 element names are declared with more than one type, got " + s[1]);
        for (String e : RefElementIndex.elementNames()) {
            for (String c : RefElementIndex.candidates(e)) {
                Check.that(toolkit.model.ModelRegistry.isRegistered(c),
                        "every candidate is a registered class name: " + e + " -> " + c);
            }
        }
    }
}
