package toolkit.store;

// DETERMINISTIC IS NOT CORRECT.
//
// AbstractLmdbStore.loadObjectByReference resolves a reference on its exact (id, version, class)
// key. When the class trailer cannot be derived it falls through to a prefix scan over the bare id
// and returns THE SMALLEST ROW -- ignoring the class the caller asked for AND the version. It is
// perfectly reproducible and it is perfectly capable of handing back the wrong object.
//
// These tests pin the aggregate that counts those fallbacks, and the shape of the answer the
// fallback gives, so the two are documented together: the count is only interesting BECAUSE the row
// is a guess.
//
// Which row wins is unchanged: every store built through this path depends on it byte for byte, so
// the fallback still takes the first matching-trailer row, else the smallest, and
// testTheFallbackStillReturnsTheSmallestRow is the control that says so.
//
// NEUTER PROOF:
//   * testTheFallbackStillReturnsTheSmallestRow -- change the tie-break in the scan loop.
//   * testAnExactKeyCostsNoFallback             -- count on entry instead of at the fallback.
//   * testFallbacksAreCountedAndReported        -- drop the LongAdder, or the reportRefFallbacks
//     call in DbToDb/InPlace; the transcript stops carrying the number again.

import noi.netex.model.Line;
import noi.netex.model.Operator;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.VersionOfObjectRefStructure;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TestRefFallbackScan {

    /// One id, two classes, two versions — the shape that makes "ignores the class AND the version"
    /// observable. Duplicate ids are legal by design, NeTEx's key being (id, version).
    private static void fill(TestStore ts) {
        try (Txn txn = ts.store.rwTxn()) {
            ts.store.insertAnyObjects(txn, List.of(
                    line("X:1", "1"), line("X:1", "2"), operator("X:1", "1")));
            txn.commit();
        }
    }

    private static Line line(String id, String v) {
        Line l = new Line();
        l.setId(id);
        l.setVersion(v);
        return l;
    }

    private static Operator operator(String id, String v) {
        Operator o = new Operator();
        o.setId(id);
        o.setVersion(v);
        return o;
    }

    /// A ref whose class cannot be derived: the base type derives "VersionOfObject", unregistered.
    private static VersionOfObjectRefStructure classlessRef(String id) {
        VersionOfObjectRefStructure r = new VersionOfObjectRefStructure();
        r.setRef(id);
        return r;
    }

    // ----------------------------------------------------------------

    /// The exact (id, version, class) key never touches the fallback, so it must never count.
    @Test
    void testAnExactKeyCostsNoFallback(TestStore ts) {
        fill(ts);
        AbstractLmdbStore.resetRefFallbacks();
        try (Txn tx = ts.store.roTxn()) {
            OperatorRefStructure exact = new OperatorRefStructure();
            exact.setRef("X:1");
            exact.setVersion("1");
            exact.setNameOfRefClass("Operator");
            Check.that(ts.store.loadObjectByReference(tx, exact) instanceof Operator,
                    "the declared class gives the exact key");
            Check.equals(0L, AbstractLmdbStore.refFallbacks(),
                    "an exact hit is the whole point of the derivation — it may not be counted");
        }
    }

    /// A reference with no derivable class is answered by a row chosen for its
    /// position in the id index, not for matching anything the caller asked. The count is what makes
    /// that visible across a run.
    @Test
    void testAClasslessRefFallsBackAndIsCounted(TestStore ts) {
        fill(ts);
        AbstractLmdbStore.resetRefFallbacks();
        try (Txn tx = ts.store.roTxn()) {
            Object got = ts.store.loadObjectByReference(tx, classlessRef("X:1"));
            Check.that(got != null, "the reference resolves — that is the problem, not the relief");
            Check.equals(1L, AbstractLmdbStore.refFallbacks(),
                    "one reference, one counted fallback");
        }
    }

    /// The control on the choice: three rows carry X:1, and the fallback returns the FIRST at the
    /// prefix regardless of class or version. Pinned so the instrumentation above cannot be mistaken
    /// for a behaviour change, and so a future fix has to state which row it moves to.
    @Test
    void testTheFallbackStillReturnsTheSmallestRow(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            long smallest = ts.store.lookupById(tx, "X:1", null).get(0).fullKey();
            Object got = ts.store.loadObjectByReference(tx, classlessRef("X:1"));
            Object expected = ts.store.loadObjectByFullKey(tx, smallest);
            Check.equals(expected.getClass(), got.getClass(),
                    "the fallback answers with row 0 of the prefix, whatever class that is");
            Check.equals(((noi.netex.model.EntityInVersionStructure) expected).getVersion(),
                    ((noi.netex.model.EntityInVersionStructure) got).getVersion(),
                    "…and whatever version that is: the requested version is not consulted");
        }
    }

    /// A dangling reference is not a fallback that resolved — the scan found nothing to count on.
    @Test
    void testADanglingRefStillThrowsAndIsStillCounted(TestStore ts) {
        fill(ts);
        AbstractLmdbStore.resetRefFallbacks();
        try (Txn tx = ts.store.roTxn()) {
            Check.isNull(ts.store.loadObjectByReference(tx, classlessRef("NOPE:1"), true),
                    "missingOk still returns null");
            boolean threw = false;
            try {
                ts.store.loadObjectByReference(tx, classlessRef("NOPE:1"));
            } catch (IllegalStateException e) {
                threw = true;
            }
            Check.that(threw, "and the strict overload still throws — the one switch that existed");
            Check.equals(2L, AbstractLmdbStore.refFallbacks(),
                    "both attempts entered the fallback, so both are counted: the counter measures "
                    + "how often the exact key failed, not how often it got away with it");
        }
    }

    /// The post-condition on the transcript. REF_FALLBACK is tolerated by default, so a run that
    /// fell back says so once with its total and its opt-in rather than once per reference.
    @Test
    void testFallbacksAreCountedAndReported(TestStore ts) throws Exception {
        fill(ts);
        AbstractLmdbStore.resetRefFallbacks();
        try (Txn tx = ts.store.roTxn()) {
            ts.store.loadObjectByReference(tx, classlessRef("X:1"));
            ts.store.loadObjectByReference(tx, classlessRef("X:1"));
        }
        Path log = ts.tempPath("fallbacks.log");
        try {
            Log.toFile(log);
            AbstractLmdbStore.reportRefFallbacks("reftest");
        } finally {
            Log.toFile(null);
        }
        String text = Files.readString(log);
        Check.that(text.contains("[reftest]"), "the stage is named: " + text);
        Check.that(text.contains("2"), "…with the aggregate, not a line per reference: " + text);
        // refFallback, NOT unresolvedRefs. The two are opposite failures: an unresolved reference
        // is a hole, countable and edge-losing; this one RESOLVED, possibly to the wrong class and
        // version. Sharing a flag would let a clean _unresolved table vouch for this.
        Check.that(text.contains("-Dtoolkit.strict.refFallback=true"),
                "…and the flag that refuses it: " + text);
    }

    /// A clean run must still print a line: a silent instrument and an absent one look the same.
    @Test
    void testACleanRunSaysSoExplicitly(TestStore ts) throws Exception {
        AbstractLmdbStore.resetRefFallbacks();
        Path log = ts.tempPath("clean-fallbacks.log");
        try {
            Log.toFile(log);
            AbstractLmdbStore.reportRefFallbacks("reftest");
        } finally {
            Log.toFile(null);
        }
        Check.that(Files.readString(log).contains("reference fallback scans: 0"),
                "zero is a result and has to be printed");
    }
}
