package toolkit.harness;

// ONE SEQUENCE, TWO WAYS IN.
//
// FeedToDb takes a .zip (via ZipLoad.run, which walks members) or a bare .xml/.xml.gz (via
// NetexLoader.load). Everything after the bytes are in -- the post pass, then the mandatory
// resolve()/resolveEmbeddings() pair -- must be identical, because none of it is a property of the
// container format.
//
// IT WAS NOT IDENTICAL. The resolve pair lived inside ZipLoad.run, so the single-file arm restated
// it wrongly: postLoad AFTER the resolve instead of before. A post pass that inserts objects
// therefore had its references resolved when the feed arrived as a zip and left dangling when the
// same bytes arrived as a bare .xml -- decided by the file extension.
//
// testPostLoadRunsBeforeResolveOnBothArms is that bug, executable, and the reason the sequence now
// lives in the harness.
//
// NEUTER PROOF: move the postLoad call in FeedToDb.finishLoad below store.resolve() and the
// single-arm assertion fails on the dangling reference.

import noi.netex.model.Operator;
import org.junit.jupiter.api.Test;
import toolkit.load.ZipLoad;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TestFeedToDbArms {

    /// A Line whose OperatorRef points at an Operator the feed does NOT contain, so the reference
    /// lands in `_unresolved` and stays there until something supplies the target.
    ///
    /// This shape is load-bearing. An earlier fixture put the Operator IN the feed and had
    /// postLoad insert the referrer — which proves nothing, because `insertOne` resolves
    /// eagerly against the id index when the target is already present. `_unresolved` only
    /// ever holds FORWARD references, so the ordering was invisible and the test passed with the
    /// bug reintroduced.
    private static String feedXml() {
        return TestStore.SKELETON.replace("__FRAMES__",
                "<ServiceFrame id=\"sf\" version=\"1\"><lines>"
                + "<Line id=\"line:1\" version=\"1\"><Name>L</Name>"
                + "<OperatorRef ref=\"op:1\" version=\"1\"/></Line>"
                + "</lines></ServiceFrame>");
    }

    /// The post pass supplies the Operator the feed referenced and did not contain. If postLoad runs
    /// BEFORE the resolve pair, resolve() finds the target and clears the row; if it runs after,
    /// resolve() has already been and gone and the row is still there when the store closes.
    private static ZipLoad.Options postLoadSupplyingTheMissingTarget() {
        return new ZipLoad.Options(null, null, null, store -> {
            try (Txn txn = store.rwTxn()) {
                Operator o = new Operator();
                o.setId("op:1");
                o.setVersion("1");
                store.insertAnyObjects(txn, List.of(o));
                txn.commit();
            }
        });
    }

    private static long unresolvedRows(Store store) {
        long n = 0;
        try (Txn t = store.roTxn()) {
            for (@SuppressWarnings("unused") var row : store.iterUnresolved(t)) n++;
        }
        return n;
    }

    /// Both arms, same fixture bytes, same answer.
    @Test
    void testPostLoadRunsBeforeResolveOnBothArms(TestStore ts) throws Exception {
        Path zip = ts.tempPath("arms.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("feed.xml"));
            zos.write(feedXml().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path xml = ts.tempPath("arms.xml");
        Files.writeString(xml, feedXml());

        for (Path feed : List.of(zip, xml)) {
            Path out = ts.tempPath("arms-out-" + feed.getFileName() + ".lmdb");
            toolkit.harness.FeedToDb.driverMain("arms", new String[] {feed.toString(), out.toString()},
                    postLoadSupplyingTheMissingTarget());
            try (Store s = Stores.open(out, true)) {
                Check.equals(0L, unresolvedRows(s),
                        feed.getFileName() + ": the feed's dangling OperatorRef must be RESOLVED by "
                        + "the target the post pass supplied. A leftover unresolved row means "
                        + "postLoad ran AFTER the resolve pair — exactly what the single-file arm "
                        + "used to do.");
            }
        }
    }

    /// A .xml.gz is the third shape the loader accepts, and the driver must take it too.
    @Test
    void testGzippedSingleFileLoads(TestStore ts) throws Exception {
        Path gz = ts.tempPath("arms.xml.gz");
        try (var os = new GZIPOutputStream(Files.newOutputStream(gz))) {
            os.write(feedXml().getBytes(StandardCharsets.UTF_8));
        }
        Path out = ts.tempPath("arms-gz.lmdb");
        toolkit.harness.FeedToDb.driverMain("arms-gz",
                new String[] {gz.toString(), out.toString()}, ZipLoad.Options.DEFAULTS);
        try (Store s = Stores.open(out, true); Txn t = s.roTxn()) {
            boolean any = false;
            for (Class<?> c : s.dbNames(t)) {
                if (s.scanObjects(t, c).iterator().hasNext()) { any = true; break; }
            }
            Check.that(any, ".xml.gz must load — it is one of the three shapes NetexLoader "
                    + "dispatches on, and the reason NetexToDb had its own main");
        }
    }

    /// A zip-only option on a single-file feed REFUSES rather than being ignored. A filter that
    /// silently does not apply is how a load quietly takes in more than it was configured to.
    @Test
    void testZipOnlyOptionsRefuseOnASingleFileFeed(TestStore ts) throws Exception {
        Path xml = ts.tempPath("filtered.xml");
        Files.writeString(xml, feedXml());
        ZipLoad.Options withFilter = new ZipLoad.Options(name -> true, null, null, null);
        boolean threw = false;
        try {
            toolkit.harness.FeedToDb.driverMain("arms-filter",
                    new String[] {xml.toString(), ts.tempPath("nope.lmdb").toString()}, withFilter);
        } catch (IllegalArgumentException e) {
            threw = true;
            Check.that(e.getMessage().contains("memberFilter"),
                    "…and says which option it refused: " + e.getMessage());
        }
        Check.that(threw, "a memberFilter on a single-file feed must refuse, not be dropped");
    }

    private TestFeedToDbArms() {}
}
