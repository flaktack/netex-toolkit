package toolkit.codec;

// Regression test. An implementation that re-serialises the whole repaired object has no fragment
// to preserve; this one does.
//
// WHAT IT GATES. resolve()/resolveEmbeddings() repair a reference by setting `version` and/or
// `nameOfRefClass` on it. The ONLY way they used to persist that was FragmentCodec.encode(obj) — a
// full JAXB re-marshal of the whole fragment to change one start-tag attribute, and every lost
// value in the round-trip harness was inside that population.
//
// toolkit.codec.RefPatcher replaces the re-marshal with a start-tag attribute rewrite over the
// stored bytes. The properties below are the four ways that can go wrong:
//   1. the repair must actually reach the stored bytes (it is what resolve() is FOR);
//   2. everything else in the fragment must survive verbatim — comments, entity references,
//      attribute order, quoting and element names are all things a JAXB re-marshal changes;
//   3. a reference that needs NO repair must not cause a rewrite at all;
//   4. re-running resolve() over an already-repaired database must be a no-op on the bytes.
//
// The `xmlns:ns2=` assertions are the re-marshal's own fingerprint:
// JAXB's auto-generated prefix appears in every re-marshalled fragment and in no captured one.

import noi.netex.model.Route;
import org.junit.jupiter.api.Test;
import toolkit.store.RawRow;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class TestRefPatcher {

    /// A Route whose LineRef declares a version the Line does not have, so resolve()'s "id+version
    /// missing, id exists" tier fires and rewrites `version`. The Route also carries a comment and
    /// a CDATA section — two things no JAXB re-marshal reproduces. `__V__` is the version the
    /// LineRef declares: "3" needs the repair, "7" is already right.
    ///
    /// What is deliberately NOT asserted below is anything CopyingCapture itself normalises
    /// (its declared set N1-N7: hoisted namespace declarations, attribute re-quoting, resolved
    /// character references). Those are load-time properties, already gated by TestLoaderSrsName
    /// and measured as lossless by the round-trip harness; the patcher only has to leave them
    /// alone, which the byte-equality test below states far more strongly than a substring could.
    private static final String FRAME = """
        <ServiceFrame id="svf" version="1">
          <lines><Line id="l1" version="7"><Name>Linea</Name></Line></lines>
          <routes>
            <Route id="r1" version="1"><!--keep me--><Name><![CDATA[Rotta & co]]></Name>
              <LineRef ref="l1" version="__V__"/>
            </Route>
          </routes>
        </ServiceFrame>
        """;

    private static final String VERSION_REPAIR = FRAME.replace("__V__", "3");

    /// The same frame, but the LineRef already names the right version: nothing to repair.
    private static final String NO_REPAIR = FRAME.replace("__V__", "7");

    /// The stored fragment bytes of the single object of `clazz` (never a bean re-marshal).
    private static String fragmentOf(TestStore db, Class<?> clazz) {
        FragmentCodec codec = new FragmentCodec();
        try (Txn tx = db.store.roTxn()) {
            for (RawRow row : db.store.iterRawObjects(tx, clazz)) {
                return new String(codec.decompress(row.blob()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no stored " + clazz.getSimpleName());
    }

    // ------------------------------------------------------------------ end-to-end, through resolve()

    @Test
    void testResolveRepairsTheStoredBytesWithoutRemarshalling(TestStore db) throws Exception {
        db.loadNetex(VERSION_REPAIR);
        String frag = fragmentOf(db, Route.class);
        Check.that(frag.contains("<LineRef ref=\"l1\" version=\"7\"/>"),
                "the repaired version reaches the stored fragment: " + frag);
        Check.that(!frag.contains("xmlns:ns2="),
                "the stored fragment is NOT a JAXB re-marshal: " + frag);
        Check.that(frag.contains("<!--keep me-->"), "the comment survived: " + frag);
        Check.that(frag.contains("<![CDATA[Rotta & co]]>"), "the CDATA section survived: " + frag);
    }

    /// The strongest statement of the whole fix: the bytes stored for a repaired object are the
    /// bytes that would have been stored had the source declared the repaired value in the first
    /// place. Nothing but the one attribute moves.
    @Test
    void testOnlyTheReferenceAttributeDiffersFromAnUnrepairedCapture(TestStore db)
            throws Exception {
        db.loadNetex(VERSION_REPAIR);
        String repaired = fragmentOf(db, Route.class);
        try (TestStore clean = TestStore.create(db.format)) {
            clean.loadNetex(NO_REPAIR);
            Check.equals(fragmentOf(clean, Route.class), repaired,
                    "a repaired fragment is byte-identical to the capture of the already-correct source");
        }
    }

    @Test
    void testTheRepairedBeanAgreesWithTheRepairedBytes(TestStore db) throws Exception {
        db.loadNetex(VERSION_REPAIR);
        try (Txn tx = db.store.roTxn()) {
            for (Object o : db.store.iterOnlyObjects(tx, Route.class)) {
                Check.equals("7", ((Route) o).getLineRef().getValue().getVersion(),
                        "decoding the patched fragment yields the repaired reference");
            }
        }
    }

    @Test
    void testAReferenceNeedingNoRepairLeavesTheBytesAlone(TestStore db) throws Exception {
        db.loadNetex(NO_REPAIR);
        String frag = fragmentOf(db, Route.class);
        Check.that(frag.contains("<LineRef ref=\"l1\" version=\"7\"/>"), "reference unchanged: " + frag);
        Check.that(!frag.contains("xmlns:ns2="), "no re-marshal for an unrepaired object: " + frag);
        Check.that(frag.contains("<!--keep me-->"), "the comment survived: " + frag);
    }

    @Test
    void testResolveIsIdempotentOnTheStoredBytes(TestStore db) throws Exception {
        db.loadNetex(VERSION_REPAIR);
        String once = fragmentOf(db, Route.class);
        db.store.resolve();
        db.store.resolveEmbeddings();
        Check.equals(once, fragmentOf(db, Route.class),
                "a second resolve() over an already-repaired database changes no byte");
    }

    // ------------------------------------------------------------------ the patcher itself

    private static String patched(String xml, RefPatcher.Key k, RefPatcher.Val v) {
        Map<RefPatcher.Key, RefPatcher.Val> m = new LinkedHashMap<>();
        m.put(k, v);
        byte[] out = new RefPatcher().patch(xml.getBytes(StandardCharsets.UTF_8), m);
        return out == null ? null : new String(out, StandardCharsets.UTF_8);
    }

    @Test
    void testAttributeIsReplacedInPlaceAddedOrRemoved() {
        Check.equals("<A><R ref=\"X\" version=\"7\" order=\"3\"/></A>",
                patched("<A><R ref=\"X\" version=\"1\" order=\"3\"/></A>",
                        new RefPatcher.Key("X", "1", null), new RefPatcher.Val("7", null)),
                "an existing attribute is replaced in place, later attributes keep their position");
        Check.equals("<A><R ref=\"X\" version=\"7\" nameOfRefClass=\"Line\"/></A>",
                patched("<A><R ref=\"X\"/></A>",
                        new RefPatcher.Key("X", null, null), new RefPatcher.Val("7", "Line")),
                "absent attributes are appended after the last existing one");
        Check.equals("<A><R ref=\"X\"/></A>",
                patched("<A><R ref=\"X\" version=\"1\"/></A>",
                        new RefPatcher.Key("X", "1", null), new RefPatcher.Val(null, null)),
                "an attribute whose new value is null is removed with its leading whitespace");
        Check.equals("<A><R ref=\"X\" version=\"7\"/></A>",
                patched("<A><R ref=\"X\" version=\"7\"/></A>",
                        new RefPatcher.Key("X", "7", null), new RefPatcher.Val("7", null)),
                "present-and-already-correct is left byte-identical");
    }

    @Test
    void testEverythingOutsideThePatchedTagIsVerbatim() {
        Check.equals("<a:A xmlns:a=\"n\"><b>x &amp; &#65;</b><![CDATA[<z>]]><!--c-->"
                        + "<a:R ref='X' foo='q' version='2'/></a:A>",
                patched("<a:A xmlns:a=\"n\"><b>x &amp; &#65;</b><![CDATA[<z>]]><!--c-->"
                                + "<a:R ref='X' foo='q' version='1'/></a:A>",
                        new RefPatcher.Key("X", "1", null), new RefPatcher.Val("2", null)),
                "prefixed elements, CDATA, comments, entities and single quotes all pass through");
        Check.equals("<A><R\n  ref=\"X\"\n  version=\"2\"/></A>",
                patched("<A><R\n  ref=\"X\"\n  version=\"1\"/></A>",
                        new RefPatcher.Key("X", "1", null), new RefPatcher.Val("2", null)),
                "a start tag spread over several lines keeps its layout");
    }

    @Test
    void testEveryElementWithTheSameTripleIsPatched() {
        Check.equals("<A><R ref=\"X\" version=\"1\"/><Q ref=\"X\" version=\"1\"/></A>",
                patched("<A><R ref=\"X\"/><Q ref=\"X\"/></A>",
                        new RefPatcher.Key("X", null, null), new RefPatcher.Val("1", null)),
                "two references indistinguishable in the XML are patched identically");
    }

    @Test
    void testUnsafeInputFallsBackToTheRemarshal() {
        Check.isNull(patched("<A><R ref=\"X\"/></A>",
                        new RefPatcher.Key("OTHER", null, null), new RefPatcher.Val("1", null)),
                "a repair that lands on no element refuses to patch");
        Check.isNull(patched("<A><R ref=\"X\"/>",
                        new RefPatcher.Key("X", null, null), new RefPatcher.Val("1", null)),
                "a fragment that is not well-formed refuses to patch");
        Check.isNull(patched("<A>\r\n<R ref=\"X\"/></A>",
                        new RefPatcher.Key("X", null, null), new RefPatcher.Val("1", null)),
                "a fragment containing CR refuses to patch (line-ending normalisation moves offsets)");
    }

    @Test
    void testPatchSetDiffingAndConflictDetection() {
        String[][] before = {{"X", "1", null}, {"Y", null, "Line"}};
        String[][] after = {{"X", "2", "Line"}, {"Y", null, "Line"}};
        Map<RefPatcher.Key, RefPatcher.Val> ps = RefPatcher.patchSet(before, after, true);
        Check.equals(1, ps.size(), "only the reference that moved is patched");
        Check.equals(new RefPatcher.Val("2", "Line"), ps.get(new RefPatcher.Key("X", "1", null)),
                "both attributes of the moved reference are carried");
        Check.that(RefPatcher.patchSet(before, before, true).isEmpty(),
                "nothing moved -> empty patch set -> the caller writes nothing at all");
        // whether resolve() INVENTS nameOfRefClass on references that never had one.
        Check.isNull(RefPatcher.patchSet(before, after, false)
                        .get(new RefPatcher.Key("X", "1", null)).nameOfRefClass(),
                "with the invention switched off an absent nameOfRefClass is not added");
        Check.isNull(RefPatcher.patchSet(new String[][] {{"X", null, null}, {"X", null, null}},
                        new String[][] {{"X", "1", null}, {"X", "2", null}}, true),
                "two identical triples wanting different outcomes refuse to patch");
        Check.isNull(RefPatcher.patchSet(new String[][] {{"X", null, null}, {"X", null, null}},
                        new String[][] {{"X", "1", null}, {"X", null, null}}, true),
                "one of two identical triples changing refuses to patch");
    }
}
