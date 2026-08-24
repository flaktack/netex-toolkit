package toolkit.harness;

// The EXPORT sandwich and the three pieces it is built from.
//
// COVERAGE GAP, stated: TestFragmentOrder is the only test that reaches the EPIP export driver, and
// it reaches it for the child-order normalisation alone. Nothing here asserts the driver's entry
// names, ordering or colocation; equivalence of an export across a refactor is checked out-of-band
// with tools/diff/export_zip.py, which is not the same as a test.
//
// What this file asserts is the part that has no EPIP in it: that entry timestamps are pinned
// rather than a wall clock, that the totals the "done" line reports are the ones actually written,
// that the envelope takes its two profile strings from its Options rather than from a literal, that
// a FAILED export leaves neither a half-written zip nor a .tmp beside it, and that FrameWriter
// applies its profile hook to every emitted fragment and to nothing else.

import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class TestDbToZip {

    private static final NetexEnvelope.Options OPTS =
            new NetexEnvelope.Options("TEST-PART", "a test delivery", "CF_ID", "test:CF_TYPE");

    /// The one frame a fixture store here is worth exporting: a ScheduledStopPoint.
    private static final String FRAMES = """
        <ServiceFrame id="sf" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>Test</Name></ScheduledStopPoint>
          </scheduledStopPoints>
        </ServiceFrame>
        """;

    /// Every entry carries the 1980 DOS epoch, not the clock. This is the ONLY reproducibility the
    /// exporter offers — the entry CONTENT still holds a live PublicationTimestamp — and a
    /// regression here is invisible in any content comparison.
    @Test
    void testEveryEntryCarriesThePinnedTimestamp() throws Exception {
        Path zip = Files.createTempFile("zipwriter-", ".zip");
        try (ZipWriter out = new ZipWriter(zip, 6, "test")) {
            out.openEntry("a.xml");
            out.write("<a/>");
            out.closeEntry();
            out.openEntry("b.xml");
            out.write("<b/>");
            out.closeEntry();
        }
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                names.add(e.getName());
                Check.equals(java.time.LocalDateTime.of(1980, 1, 1, 0, 0, 0), e.getTimeLocal(),
                        e.getName() + ": entry timestamp is the DOS epoch, not the clock");
            }
        }
        Check.equals(List.of("a.xml", "b.xml"), names, "entries written in call order");
        Files.deleteIfExists(zip);
    }

    /// The totals are per ENTRY and then summed — bytes counted by the writer, objects counted by
    /// the caller — because an export that emitted an empty entry and one that emitted a full one
    /// are otherwise indistinguishable from the outside.
    @Test
    void testTotalsCountBytesWrittenAndObjectsDeclared() throws Exception {
        Path zip = Files.createTempFile("zipwriter-", ".zip");
        long bytes;
        long objects;
        try (ZipWriter out = new ZipWriter(zip, 0, "test")) {
            out.openEntry("a.xml");
            out.write("12345");
            out.countObject();
            out.countObject();
            out.closeEntry();
            out.openEntry("b.xml");            // deliberately empty: no bytes, no objects
            out.closeEntry();
            out.openEntry("c.xml");
            out.write("678".getBytes(StandardCharsets.UTF_8));
            out.countObject();
            out.closeEntry();
            bytes = out.totalBytes();
            objects = out.totalObjects();
        }
        Check.equals(8L, bytes, "5 + 0 + 3 bytes across three entries");
        Check.equals(3L, objects, "2 + 0 + 1 objects across three entries");
        Files.deleteIfExists(zip);
    }

    /// open() + close() around one frame, read back out of the zip.
    private static String wrapped(NetexEnvelope env) throws Exception {
        Path zip = Files.createTempFile("envelope-", ".zip");
        try (ZipWriter out = new ZipWriter(zip, 6, "test")) {
            out.openEntry("e.xml");
            env.open(out);
            env.frameOpen(out, "ServiceFrame", "SF", "test:SF_TYPE");
            out.write("</ServiceFrame>");
            env.close(out);
            out.closeEntry();
        }
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            String s = new String(zf.getInputStream(zf.getEntry("e.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            Files.deleteIfExists(zip);
            return s;
        }
    }

    /// The envelope's two profile strings come from its [NetexEnvelope.Options], and what it wraps
    /// is a COMPLETE, independently parseable delivery — which is why the preamble is repeated per
    /// entry instead of written once per zip.
    @Test
    void testTheEnvelopeTakesItsProfileStringsFromItsOptions() throws Exception {
        NetexEnvelope env = new NetexEnvelope(OPTS, List.of(), null);
        String xml = wrapped(env);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        var doc = dbf.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Check.equals("PublicationDelivery", doc.getDocumentElement().getLocalName(),
                "the entry parses as an independent PublicationDelivery");

        Check.that(xml.contains("<ParticipantRef>TEST-PART</ParticipantRef>"),
                "ParticipantRef comes from the options: " + xml);
        Check.that(xml.contains("<Text>a test delivery</Text>"), "description from the options");
        Check.that(xml.contains("<CompositeFrame id=\"CF_ID\" version=\"" + env.version() + "\">"),
                "composite frame id and version from the options: " + xml);
        Check.that(xml.contains("<TypeOfFrameRef ref=\"test:CF_TYPE\" versionRef=\"1.0\"/>"),
                "composite frame type from the options");
        Check.that(xml.contains("<ServiceFrame id=\"SF\" version=\"" + env.version() + "\">"),
                "frameOpen stamps the envelope's version");
        // A <codespaces> element with nothing in it is not schema-valid, so an empty list must
        // write no element at all rather than an empty one.
        Check.that(!xml.contains("<codespaces>"), "no codespaces element when there are none");
        Check.that(!xml.contains("<DefaultLocale>"), "no locale block when none was derived");
    }

    /// Codespaces and the derived locale are repeated verbatim in every entry, and the locale
    /// precedes DefaultLocationSystem — VersionFrameDefaultsStructure's field order, which a
    /// consumer validating against the XSD will reject if it slips.
    @Test
    void testCodespacesAndLocaleAreRepeatedInFrameDefaultsOrder() throws Exception {
        NetexEnvelope env = new NetexEnvelope(OPTS,
                List.of("<Codespace id=\"cs\"/>".getBytes(StandardCharsets.UTF_8)),
                "<DefaultLocale><TimeZone>Europe/Rome</TimeZone></DefaultLocale>"
                        .getBytes(StandardCharsets.UTF_8));
        String xml = wrapped(env);
        Check.that(xml.contains("<codespaces><Codespace id=\"cs\"/></codespaces>"),
                "the codespace fragment is copied in verbatim: " + xml);
        Check.that(xml.indexOf("<DefaultLocale>") > xml.indexOf("<FrameDefaults>"),
                "the locale sits inside FrameDefaults");
        Check.that(xml.indexOf("<DefaultLocale>") < xml.indexOf("<DefaultLocationSystem>"),
                "DefaultLocale precedes DefaultLocationSystem");
    }

    /// The driver-facing surface, with the usage line and extra flags a real driver declares.
    private static void export(Path db, Path out, DbToZip.ExportFn fn, String... argv)
            throws Exception {
        List<String> args = new ArrayList<>(List.of(db.toString(), out.toString()));
        args.addAll(List.of(argv));
        DbToZip.driverMain("test-export", args.toArray(new String[0]),
                "test <db> <out.zip> [--shards N] [--level N (0..9)] [--log-file F]",
                new String[] {"--shards="},
                a -> {
                    Check.equals(7, a.intFlag("--shards", 7), "an absent int flag is its default");
                    return fn;
                });
    }

    /// The happy path: the store opens read-only, the export runs, the zip lands at the target.
    @Test
    void testTheExportLandsAtItsTargetPath(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(FRAMES, "src.lmdb");
        Path out = ts.tempPath("out.zip");
        export(db, out, (store, tx, zip) -> {
            zip.openEntry("only.xml");
            zip.write("<x/>");
            zip.countObject();
            zip.closeEntry();
        });
        Check.that(Files.exists(out), "the zip is at the target path");
        Check.that(!Files.exists(out.resolveSibling("out.zip.tmp")), "the .tmp is gone");
        try (ZipFile zf = new ZipFile(out.toFile())) {
            Check.equals(1, zf.size(), "one entry");
        }
    }

    /// A FAILED export must leave nothing behind — not a truncated zip under the final name, and
    /// not the `.tmp` either. The rename is what makes the first impossible; the
    /// `catch (Throwable)` is what makes the second.
    @Test
    void testAFailedExportLeavesNeitherAZipNorATmp(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(FRAMES, "src.lmdb");
        Path out = ts.tempPath("out.zip");
        boolean threw = false;
        try {
            export(db, out, (store, tx, zip) -> {
                zip.openEntry("partial.xml");
                zip.write("<half-written");
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException e) {
            threw = "boom".equals(e.getMessage());
        }
        Check.that(threw, "the export's failure reaches the caller unchanged");
        Check.that(!Files.exists(out), "no zip under the final name");
        Check.that(!Files.exists(out.resolveSibling("out.zip.tmp")), "no stale .tmp beside it");
    }

    /// A second run replaces the first REPLACE_EXISTING, so a re-export is not an error and never
    /// leaves the previous zip in place next to a fresh `.tmp`.
    @Test
    void testASecondExportReplacesTheFirst(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(FRAMES, "src.lmdb");
        Path out = ts.tempPath("out.zip");
        export(db, out, (store, tx, zip) -> {
            zip.openEntry("one.xml");
            zip.closeEntry();
        });
        export(db, out, (store, tx, zip) -> {
            zip.openEntry("two.xml");
            zip.closeEntry();
            zip.openEntry("three.xml");
            zip.closeEntry();
        });
        try (ZipFile zf = new ZipFile(out.toFile())) {
            Check.equals(2, zf.size(), "the second export's entries, not the first's");
            Check.that(zf.getEntry("one.xml") == null, "nothing survives from the first export");
        }
    }

    // ------------------------------------------------------------ FrameWriter

    /// Two stop points and a Line, so a container plan can name one class that is present and one
    /// that is not.
    private static final String TWO_AND_A_LINE = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="l1" version="1"><Name>L</Name></Line>
          </lines>
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>One</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="ssp2" version="1"><Name>Two</Name></ScheduledStopPoint>
          </scheduledStopPoints>
        </ServiceFrame>
        """;

    /// Run an export over a fixture store and hand back the one entry's text.
    private static String emitted(TestStore ts, FrameWriterBody body) throws Exception {
        Path db = ts.buildDbFile(TWO_AND_A_LINE, "fw.lmdb");
        Path out = ts.tempPath("fw.zip");
        export(db, out, (store, tx, zip) -> {
            NetexEnvelope env = new NetexEnvelope(OPTS, List.of(), null);
            body.run(new FrameWriter(store, tx, zip, env), store, tx, zip, env);
        });
        try (ZipFile zf = new ZipFile(out.toFile())) {
            return new String(zf.getInputStream(zf.getEntry("e.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface FrameWriterBody {
        void run(FrameWriter fw, toolkit.store.Store db, toolkit.store.Txn tx,
                 ZipWriter out, NetexEnvelope env) throws Exception;
    }

    /// The default writer emits the STORED bytes. This is the property the no-hook constructor
    /// exists for, and the one conv/DbToZipRaw depends on: an export that wants the fragments as
    /// the feed wrote them must be able to have them.
    @Test
    void testTheDefaultWriterEmitsTheStoredBytes(TestStore ts) throws Exception {
        String xml = emitted(ts, (fw, db, tx, out, env) -> {
            fw.entry("e.xml");
            out.write("<members>");
            Check.equals(2L, fw.all("ScheduledStopPoint"), "both stop points emitted");
            out.write("</members>");
            fw.end();
        });
        Check.that(xml.contains("<Name>One</Name>") && xml.contains("<Name>Two</Name>"),
                "both fragments landed unchanged: " + xml);
    }

    /// The hook is applied to EVERY emitted fragment and to nothing else — once per object, with
    /// the object's own class, and its return value is what is written.
    ///
    /// This is the assertion the extraction of FrameWriter needed: the normalisation used to be a
    /// call inside each emit method, so "did every path apply it" was a question about six call
    /// sites. It is now one, and this pins it there.
    @Test
    void testTheHookSeesEveryEmittedFragmentAndNothingElse(TestStore ts)
            throws Exception {
        Path db = ts.buildDbFile(TWO_AND_A_LINE, "fw.lmdb");
        Path out = ts.tempPath("fw.zip");
        List<String> seen = new ArrayList<>();
        export(db, out, (store, tx, zip) -> {
            NetexEnvelope env = new NetexEnvelope(OPTS, List.of(), null);
            FrameWriter fw = new FrameWriter(store, tx, zip, env, (clazz, fragment) -> {
                seen.add(clazz.getSimpleName());
                return "<Replaced/>".getBytes(StandardCharsets.UTF_8);
            });
            fw.entry("e.xml");
            zip.write("<members>");
            fw.all("ScheduledStopPoint");
            fw.first("Line");
            zip.write("</members>");
            fw.end();
        });
        Check.equals(List.of("ScheduledStopPoint", "ScheduledStopPoint", "Line"), seen,
                "once per emitted object, with that object's class, in emission order");
        try (ZipFile zf = new ZipFile(out.toFile())) {
            String xml = new String(zf.getInputStream(zf.getEntry("e.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            Check.equals(3, xml.split("<Replaced/>", -1).length - 1,
                    "what the hook returned is what was written: " + xml);
            Check.that(!xml.contains("<Name>One</Name>"), "the stored bytes did not also leak out");
        }
    }

    /// A container tag is written only when something lands in it. An empty `<lines/>` is not the
    /// same document as no `<lines>` at all, and for most NeTEx containers it is not even valid —
    /// so a plan naming an absent class must leave NO trace, not an empty element.
    @Test
    void testAContainerTagIsWrittenOnlyWhenSomethingLandsInIt(TestStore ts)
            throws Exception {
        String xml = emitted(ts, (fw, db, tx, out, env) -> {
            fw.entry("e.xml");
            env.frameOpen(out, "ServiceFrame", "SF", "test:SF_TYPE");
            // "notices" names a class this fixture has no rows for; "lines" has one.
            Map<String, List<String>> plan = new LinkedHashMap<>();
            plan.put("lines", List.of("Line"));
            plan.put("notices", List.of("Notice"));
            fw.containers(plan);
            out.write("</ServiceFrame>");
            fw.end();
        });
        Check.that(xml.contains("<lines>"), "the non-empty container is written: " + xml);
        Check.that(!xml.contains("notices"), "the empty container leaves no trace at all: " + xml);
    }

    /// The probes answer for a class that is absent from the store entirely, rather than throwing —
    /// which is what lets a driver ask before it opens an entry it would have to un-write.
    @Test
    void testTheProbesAnswerForAnAbsentClass(TestStore ts) throws Exception {
        emitted(ts, (fw, db, tx, out, env) -> {
            Check.that(fw.hasRows("Line"), "a class with rows");
            Check.that(!fw.hasRows("Notice"), "a class with no rows in this store");
            Check.that(!fw.hasRows("NoSuchNetexClass"), "a name that is not a NeTEx class at all");
            fw.entry("e.xml");
            Check.equals(0L, fw.all("NoSuchNetexClass"), "emitting an absent class emits nothing");
            Check.equals(0L, fw.first("NoSuchNetexClass"), "and so does asking for its first row");
            fw.end();
        });
    }

    /// `Args.intFlag`: the value when given, the default when not.
    @Test
    void testIntFlagReadsAValueOrItsDefault() {
        Args a = Args.parse(new String[] {"--shards", "12", "positional"},
                "test <x> [--shards N] [--level N]", "--shards=", "--level=");
        Check.equals(12, a.intFlag("--shards", 3), "the given value");
        Check.equals(3, a.intFlag("--level", 3), "the default when the flag is absent");
        // The third case — a non-integer value — prints the usage and exits 2, so it cannot be
        // asserted in-process; it is exercised from the command line instead.
    }
}
