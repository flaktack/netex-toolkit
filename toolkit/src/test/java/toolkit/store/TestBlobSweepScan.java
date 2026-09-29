package toolkit.store;

// The blob-repair sweep's decode-free path. Rather than decoding every referrer that owns a
// resolved outward edge to read each reference's (ref, version, nameOfRefClass) triple, it answers
// from the stored fragment's own attributes — the read half of what RefPatcher already does — and
// unmarshals only the referrers it cannot settle.

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

    /// A Route whose LineRef carries no `nameOfRefClass` — the corpus-normal shape, and the one
    /// that forces the element-name lookup: with no attribute to trust, the class a reference
    /// declares can only be read off `<LineRef>` itself.
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

    /// An outward edge that is already wired while the blob's self-description no longer matches
    /// the object the id resolves to, reached by cloning without the source's blob-repair marker.
    ///
    /// Built by cloning the Route without its Line and then storing a Network under the Line's id.
    /// The Route's other reference, its Direction, keeps its edge, so the sweep still visits it: a
    /// clone drops an edge whose target it skipped, and an object with no edge at all is not a
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
    ///
    /// The marker is cleared first, or there is nothing to assert: a whole clone of a swept source
    /// inherits it, and `resolve()` would then skip the sweep and pass this vacuously.
    @Test
    void testASweepOverCorrectBlobsChangesNothing(TestStore db) throws Exception {
        db.loadNetex(FRAME);
        TestStore target = db.runDbToDb((src, stx, tgt, dtx) -> tgt.cloneFrom(src, stx, dtx, null, 0));
        clearSweepMarker(target);
        String before = fragmentOf(target, Route.class);
        target.store.resolve();
        Check.equals(before, fragmentOf(target, Route.class),
                "a sweep that finds nothing to repair leaves every byte alone");
    }

    /// Put a store back in the state a pre-marker jar left it in, so the sweep runs. In package
    /// `toolkit.store`, so the marker accessors are reachable; no production path clears it.
    private static void clearSweepMarker(TestStore db) {
        AbstractLmdbStore s = (AbstractLmdbStore) db.store;
        try (Txn tx = s.rwTxn()) {
            s.putMarker(s.cast(tx), AbstractLmdbStore.Marker.BLOB_REPAIR_DONE, null);
            tx.commit();
        }
    }

    /// The saving the marker buys: a clone that drops NO edge reproduces the source's blobs and id
    /// index exactly, and the sweep is a question about those two alone — so the target inherits
    /// the marker and [ReferenceResolver]'s gate skips the sweep entirely.
    ///
    /// The pair with [#testTheSweepStillRepairsAMistypedBlob] is the whole contract: there the
    /// clone skips the Line, the Route's edge to it is dropped while the Route survives, and the
    /// marker is withheld so the sweep still runs. Here nothing is dropped and it does not.
    @Test
    void testAWholeCloneInheritsTheSweepMarker(TestStore db) throws Exception {
        db.loadNetex(FRAME);
        try (Txn tx = db.store.roTxn()) {
            Check.that(db.store.blobRepairMarkerPresent(tx),
                    "the loaded source was swept by its own resolve(), so it carries the marker");
        }
        TestStore target = db.runDbToDb((src, stx, tgt, dtx) -> tgt.cloneFrom(src, stx, dtx, null, 0));
        try (Txn tx = target.store.roTxn()) {
            Check.that(target.store.blobRepairMarkerPresent(tx),
                    "a clone that drops no edge inherits the source's blob-repair marker");
        }
    }

    /// Minimum-taking, the unsound direction: a source that was never swept cannot lend a marker it
    /// does not hold. The unswept source here is the skipping clone from
    /// [#testTheSweepStillRepairsAMistypedBlob], which withholds the marker by construction.
    @Test
    void testACloneFromAnUnsweptSourceStaysUnswept(TestStore db) throws Exception {
        db.loadNetex(FRAME);
        int lineIdx = db.store.classIdxInt(Line.class);
        // runDbToDb resolves embeddings only, so this target is never swept and stays unmarked.
        TestStore unswept = db.runDbToDb((src, stx, tgt, dtx) ->
                tgt.cloneFrom(src, stx, dtx, fk -> NulKeyCodec.fullKeyClassIdx(fk) == lineIdx, 0));
        TestStore target = unswept.runDbToDb(
                (src, stx, tgt, dtx) -> tgt.cloneFrom(src, stx, dtx, null, 0));
        try (Txn tx = target.store.roTxn()) {
            Check.that(!target.store.blobRepairMarkerPresent(tx),
                    "a whole clone of an unswept store is itself unswept, so it must still sweep");
        }
    }

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

    /// StAX's `getAttributeValue(null, name)` matches any namespace, while JAXB binds
    /// `ref`/`version`/`nameOfRefClass` unqualified. A namespaced lookalike is therefore invisible
    /// to the scan, and an element whose only `ref` is namespaced is not a reference at all.
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

    /// The index must agree with [RecursiveAttributes#toRef]'s chain, which is the only thing the
    /// sweep's exact-key guard is built from.
    @Test
    void testIndexDerivesTheSameNameOnlyReferencesWould() {
        Check.equals("[Line]", Arrays.toString(RefElementIndex.candidates("LineRef")),
                "LineRef -> Line, exactly as XxxRefStructure -> Xxx would");
        Check.equals("[DayType]", Arrays.toString(RefElementIndex.candidates("DayTypeRef")), "DayTypeRef");
        // ZoneRefStructure derives "Zone", which the model has no class for.
        Check.equals("[DataManagedObject]", Arrays.toString(RefElementIndex.candidates("ParentZoneRef")),
                "an unregistered derived name falls back to DataManagedObject");
        Check.isNull(RefElementIndex.candidates("NotAnElementInThisModel"),
                "an element the model does not declare answers 'no idea'");
    }

    /// `FromPointRef` is declared with nine different types across NeTEx, but a ServiceLink's
    /// FromPointRef is a ScheduledStopPointRefStructure and nothing else, so the owner class
    /// disambiguates it.
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
        // @XmlElementRef fields resolve through the global declaration, since a substituting
        // subtype may appear under the same field, so the owner does not answer for them.
        Check.isNull(RefElementIndex.candidatesUnder(Route.class, "LineRef"),
                "a substitution-group field is left to the global candidate set");
        Check.isNull(RefElementIndex.candidatesUnder(ServiceJourney.class, "JourneyPatternRef"),
                "…and so is a ServiceJourney's JourneyPatternRef");
    }

    /// One question about a referenced ServiceJourney — which pattern it is on — answered from the
    /// stored bytes instead of from an unmarshalled journey. The field is an `@XmlElementRef`, so
    /// the answer can be written with any member of `JourneyPatternRef`'s substitution group and
    /// nothing else; that set and the direct-child rule are what make the byte read equivalent.
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
