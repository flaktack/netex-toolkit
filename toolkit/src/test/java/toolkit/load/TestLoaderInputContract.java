package toolkit.load;

// The loader's INPUT contract. Both cases are the same failure shape: an input the loader cannot
// actually read producing a store that reports itself fine.
//
//   1. zip members: NetexLoader's own zip arm accepted `.xml` only and opened members raw, while
//      ZipLoad -- the other loader in the same package -- accepts `.xml` and `.xml.gz`. A zip of
//      gzipped members therefore skipped every entry, logged one warning, and returned 0, and the
//      loader driver does not check the count. Now the two arms share ZipLoad.wrap and the same
//      two-suffix test, and an unreadable zip REFUSES.
//
//   2. truncated input: it must never load quietly. The parse fails, which is the contract this
//      pins; CopyingCapture's END_DOCUMENT guard is the second line of that defence, for the
//      case where a reader hands the capture an unterminated document instead of refusing it.

import noi.netex.model.ScheduledStopPoint;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TestLoaderInputContract {

    private static final String FRAMES = """
        <ServiceFrame id="svf" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>Stazione</Name></ScheduledStopPoint>
          </scheduledStopPoints>
        </ServiceFrame>
        """;

    private static byte[] feed() {
        return TestStore.SKELETON.replace("__FRAMES__", FRAMES).getBytes(StandardCharsets.UTF_8);
    }

    /// A zip whose single member is `.xml.gz` — ZipLoad's contract, now NetexLoader's too.
    @Test
    void testZipOfGzippedMembersLoads(TestStore db) throws Exception {
        Path zip = db.tempPath("gzipped-members.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("feed.xml.gz"));
            try (OutputStream gz = new GZIPOutputStream(zos) {
                @Override public void close() throws java.io.IOException { finish(); }
            }) {
                gz.write(feed());
            }
            zos.closeEntry();
        }

        long n = new NetexLoader().load(zip, db.store).objects();
        Check.that(n > 0, "a zip of .xml.gz members must LOAD — it used to skip every entry, "
                + "warn once, and return 0 with the driver publishing the empty store");
        db.store.resolve();
        Check.that(Gen.byId(db, ScheduledStopPoint.class).get("ssp1") != null,
                "…and the objects are really there, decompressed by the same wrap() ZipLoad uses");
    }

    /// A plain `.xml` member still loads: the fix is an addition, not a swap.
    @Test
    void testZipOfPlainXmlMembersStillLoads(TestStore db) throws Exception {
        Path zip = db.tempPath("plain-members.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("feed.xml"));
            zos.write(feed());
            zos.closeEntry();
        }
        Check.that(new NetexLoader().load(zip, db.store).objects() > 0,
                "the .xml arm is unchanged");
    }

    /// A zip with nothing loadable is a WRONG input, not an empty one.
    @Test
    void testZipWithNoLoadableMemberRefuses(TestStore db) throws Exception {
        Path zip = db.tempPath("no-members.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("not a feed".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        try {
            new NetexLoader().load(zip, db.store);
            throw new AssertionError("must refuse: it used to warn and return 0, and the callers "
                    + "do not check the count");
        } catch (IllegalStateException expected) {
            Check.that(expected.getMessage().contains("no loadable XML members"),
                    "…and says which input it refused: " + expected.getMessage());
        }
    }

    /// Truncated input must never load quietly. The reader refuses it, which is what keeps
    /// CopyingCapture's END_DOCUMENT arm off the live path — see the comment there.
    ///
    /// The refusal must also SAY WHAT FAILED. A corrupt RAP download once surfaced as a bare
    /// "parse failed" plus a Woodstox line reading `{unknown-source}`, several frames below the
    /// only driver that knew the file name — so identifying the feed meant reading the pipeline's
    /// Makefile. The stream label and the parser's Location were both already in hand.
    @Test
    void testTruncatedFeedFailsRatherThanLoadingPartially(TestStore db) {
        String head = TestStore.SKELETON.replace("__FRAMES__", FRAMES);
        String truncated = head.substring(0, head.indexOf("<Name>Stazione</Name>") + 10);
        try {
            new NetexLoader().loadStream(
                    new ByteArrayInputStream(truncated.getBytes(StandardCharsets.UTF_8)),
                    db.store, "truncated");
            throw new AssertionError("truncated input must not load");
        } catch (AssertionError e) {
            throw e;
        } catch (IllegalStateException expected) {
            String m = String.valueOf(expected.getMessage());
            Check.that(m.contains("truncated"),
                    "…and names the stream that failed: " + m);
            Check.that(m.contains("row"),
                    "…and where in it: " + m);
        } catch (Exception e) {
            throw new AssertionError("the parse must fail as IllegalStateException, got "
                    + e.getClass().getName() + ": " + e.getMessage(), e);
        }
    }
}
