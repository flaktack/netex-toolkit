package toolkit.util;

// The strictness policy: a degradation is either refused or counted, and which one it is must be
// a decision the run records rather than an accident of who wrote the catch block.
//
// WHY THESE TESTS EXIST. Every case below was previously a counter and a warning, and the run
// exited 0. toolkit.util.Strictness makes it a property, and a policy nobody has watched refuse
// anything is not a policy.
//
// WHY THE POLICY METHODS TAKE `fatal` AS A PARAMETER. The flags are `static final`, read at
// class-init, so a disabled arm folds away and the store's flag discipline is preserved. That
// also means a test in this JVM cannot flip one: surefire hosts the whole suite in ONE JVM and
// System.setProperty after class-init changes nothing. Passing the decision in keeps the policy
// itself fully testable, and the wiring is then covered end-to-end by the default-on arms below.

import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TestStrictness {

    // ---------------------------------------------------------------- the policy itself

    /// fatal + a non-zero count is a refusal, and the message has to be actionable.
    @Test
    void testFatalConcernRefuses(TestStore db) {
        IllegalStateException e = null;
        try {
            Strictness.require(true, "remarshal", 7, "tag",
                    "objects were stored as a JAXB re-marshal instead of their source bytes");
        } catch (IllegalStateException ex) {
            e = ex;
        }
        Check.that(e != null, "a fatal concern with a non-zero count must THROW, not warn");
        String m = e.getMessage();
        Check.that(m.contains("REFUSED"), "the message says it refused: " + m);
        Check.that(m.contains("7"), "…and how many times it happened: " + m);
        Check.that(m.contains("[tag]"), "…and which stage refused, for a chained run: " + m);
        Check.that(m.contains("-Dtoolkit.strict.remarshal=false"),
                "…and NAMES THE OPT-OUT. A refusal an operator cannot act on is a wall: " + m);
    }

    /// Zero is silent whether or not the concern is fatal — nothing happened.
    @Test
    void testZeroCountNeverRefuses(TestStore db) {
        Strictness.require(true, "remarshal", 0, "tag", "objects were re-marshalled");
        Strictness.refuseIf(true, "emptyOutput", false, "tag", "this load inserted no objects");
        Check.that(true, "a zero count and a false condition are both silent under a fatal concern");
    }

    /// Not fatal: it still happened, it is still counted, but the run continues.
    @Test
    void testTolerantConcernWarnsAndContinues(TestStore db) {
        Strictness.require(false, "bindEvents", 1058, "tag",
                "source values did not parse into their bound type and were left empty");
        Strictness.refuseIf(false, "emptyOutput", true, "tag", "this load inserted no objects");
        Check.that(true, "a tolerated concern returns normally — the counter is the record");
    }

    /// refuseIf is the boolean shape: "produced nothing" is a state, not a count of anything.
    @Test
    void testRefuseIfIsAConditionNotACount(TestStore db) {
        IllegalStateException e = null;
        try {
            Strictness.refuseIf(true, "emptyOutput", true, "obb",
                    "this load inserted no objects — the feed matched nothing loadable");
        } catch (IllegalStateException ex) {
            e = ex;
        }
        Check.that(e != null, "a fatal condition that holds must throw");
        Check.that(!e.getMessage().contains(" 1 "),
                "…and must NOT render as a count. `require` would have said '1 objects', which is "
                + "why this second shape exists: " + e.getMessage());
    }

    // ---------------------------------------------------------------- the wiring, end to end

    /// A zip with no loadable member inserted nothing, logged "loaded 0 objects", and exited 0 —
    /// the driver did not check the count, so an empty store was resolved and published.
    /// EMPTY_OUTPUT is fatal by default, so the harness must now refuse.
    @Test
    void testEmptyLoadIsRefusedByDefault(TestStore db) throws Exception {
        Check.that(Strictness.EMPTY_OUTPUT,
                "emptyOutput must default to FATAL — there is no feed for which zero is right");

        // A member that PARSES FINE and yields nothing: a well-formed PublicationDelivery whose
        // frames are empty. A zip with no loadable member is refused a layer earlier by ZipLoad
        // ("no loadable XML members"), which is the same invariant and not this post-condition —
        // so the fixture has to get past the loader and still insert zero.
        Path zip = db.tempPath("loads-but-empty.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("feed.xml"));
            zos.write(TestStore.SKELETON.replace("__FRAMES__", "")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        IllegalStateException e = null;
        try {
            toolkit.harness.FeedToDb.driverMain("strict-test",
                    new String[] {zip.toString(), db.tempPath("out.lmdb").toString()},
                    toolkit.load.ZipLoad.Options.DEFAULTS);
        } catch (IllegalStateException ex) {
            e = ex;
        }
        Check.that(e != null,
                "a load that parsed its input and inserted NOTHING must refuse. It used to log "
                + "\"loaded 0 objects\" and exit 0, and the driver did not check the count, so the "
                + "empty store was resolved and published.");
        Check.that(e.getMessage().contains("emptyOutput"),
                "…and the refusal names the concern and its opt-out: " + e.getMessage());
    }

    /// The tail of FeedToDb.driverMain — BindEvents.report, the bindEvents check, and
    /// Strictness.report — runs only on a load that SUCCEEDS. testEmptyLoadIsRefusedByDefault
    /// throws inside the try, so the rethrow skips the whole tail, and it was the suite's only
    /// driverMain call: every line after the catch block was uncovered. A load that works is the
    /// only thing that reaches them.
    @Test
    void testSuccessfulLoadReachesTheReportTail(TestStore db) throws Exception {
        Path zip = db.tempPath("loads-fine.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("feed.xml"));
            zos.write(TestStore.SKELETON.replace("__FRAMES__",
                    "<ResourceFrame id=\"rf\" version=\"1\"><organisations>"
                    + "<Operator id=\"op:1\" version=\"1\"><Name>Op</Name></Operator>"
                    + "</organisations></ResourceFrame>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path out = db.tempPath("loaded.lmdb");
        toolkit.harness.FeedToDb.driverMain("strict-tail", new String[] {zip.toString(), out.toString()},
                toolkit.load.ZipLoad.Options.DEFAULTS);

        // Reaching here at all is the assertion: driverMain ran to completion, so every statement
        // after its catch block executed without throwing.
        try (toolkit.store.Store s = toolkit.store.Stores.open(out, true);
             toolkit.store.Txn t = s.roTxn()) {
            boolean any = false;
            for (Class<?> c : s.dbNames(t)) {
                if (s.scanObjects(t, c).iterator().hasNext()) { any = true; break; }
            }
            Check.that(any, "the fixture must actually load something, or this test proves nothing "
                    + "-- an empty load would have been refused before the tail");
        }
    }

    /// The load report is the other half of tolerating something: where Strictness carries on, the
    /// STORE must still say what it swallowed, or a downstream stage cannot tell a clean build from
    /// one that absorbed a thousand bind losses. Absence of the file means "no report", never
    /// "nothing happened" — the same rule the provenance sidecar uses for absent lineage.
    @Test
    void testLoadReportRecordsWhatWasTolerated(TestStore db) throws Exception {
        Path out = db.tempPath("reported.lmdb");
        Path zip = db.tempPath("reported.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("feed.xml"));
            zos.write(TestStore.SKELETON.replace("__FRAMES__",
                    "<ResourceFrame id=\"rf2\" version=\"1\"><organisations>"
                    + "<Operator id=\"op:2\" version=\"1\"><Name>Op2</Name></Operator>"
                    + "</organisations></ResourceFrame>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        toolkit.harness.FeedToDb.driverMain("report-test",
                new String[] {zip.toString(), out.toString()}, toolkit.load.ZipLoad.Options.DEFAULTS);

        Check.that(Files.exists(out.resolve(toolkit.prov.LoadReport.FILE)),
                "a completed load must leave a .loadreport.json beside the provenance sidecar");
        java.util.Map<String, Long> t = toolkit.prov.LoadReport.read(out);
        Check.that(t != null, "…and it must parse back");
        Check.that(!t.containsKey("remarshal"),
                "a clean load records NO remarshal key — zero-valued concerns are dropped, so the "
                + "file lists what happened rather than a vector of mostly-zeroes: " + t);

        Check.that(toolkit.prov.LoadReport.read(db.tempPath("no-such-store")) == null,
                "no report reads as null = UNKNOWN, which is not the same as zero");
    }

    /// The remarshal concern is fatal by default: the store's central claim depends on it.
    @Test
    void testRemarshalIsFatalByDefault(TestStore db) {
        Check.that(Strictness.REMARSHAL,
                "remarshal must default to FATAL. A failed fragment capture stores a JAXB "
                + "re-marshal instead of the source bytes, which is exactly the round-trip loss "
                + "the storage design exists to prevent — and it was a bare catch(Exception).");
    }

    /// bindEvents and unresolvedRefs default to TOLERATED, and that is deliberate, not an
    /// oversight. Pinning it here so a later "tighten everything" pass has to argue with the
    /// measurement rather than silently flip it: a ValidationEventHandler returning false was
    /// measured to throw on fragments the loader accepts today, and cross-border work reads feeds
    /// that genuinely reference objects published by another feed.
    @Test
    void testMeasuredExceptionsStayTolerantByDefault(TestStore db) {
        Check.that(!Strictness.BIND_EVENTS,
                "bindEvents defaults OFF: failing on every bind event refuses data that loads "
                + "today. The gap it closes is that there was no way to ASK — now there is.");
        Check.that(!Strictness.UNRESOLVED_REFS,
                "unresolvedRefs defaults OFF: a cross-feed reference into data this store does "
                + "not hold is a fact about the corpus, not a defect.");
    }

    private TestStrictness() {}
}
