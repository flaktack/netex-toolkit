package toolkit.harness;

// The EXPORT sandwich and the three pieces it is built from.
//
// COVERAGE GAP: nothing here asserts the EPIP export driver's entry names, ordering or colocation.
// The last two methods do run that driver, but only for the envelope values it decides in setup();
// TestFragmentOrder reaches it for the child-order normalisation. Equivalence of an export across a
// refactor is checked out-of-band with tools/diff/export_zip.py, which is not the same as a test.

import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /// Every entry carries the 1980 DOS epoch, not the clock. That pins the METADATA; the content
    /// is pinned separately, by supplying the envelope's two stamps — see
    /// [#testPinnedEnvelopeStampsMakeTheExportByteReproducible].
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

    /// The totals are per ENTRY and then summed: bytes counted by the writer, objects counted by
    /// the caller.
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

    /// The two stamps are [NetexEnvelope.Options] values like the rest. A supplied timestamp is
    /// emitted VERBATIM — the exporter must not reformat what the caller pinned — and a supplied
    /// frame version reaches both the composite frame and `frameOpen`.
    @Test
    void testTheEnvelopeStampsComeFromItsOptionsWhenSupplied() throws Exception {
        NetexEnvelope env = new NetexEnvelope(
                new NetexEnvelope.Options("P", "d", "CF_ID", "test:CF_TYPE",
                        LocalDateTime.of(2026, 1, 1, 0, 0, 0), "20260101"),
                List.of(), null);
        Check.equals("20260101", env.version(), "the frame version is the supplied one");

        String xml = wrapped(env);
        Check.that(xml.contains("<PublicationTimestamp>2026-01-01T00:00</PublicationTimestamp>"),
                "the supplied timestamp is emitted verbatim: " + xml);
        Check.that(xml.contains("<CompositeFrame id=\"CF_ID\" version=\"20260101\">"),
                "the composite frame carries the supplied version");
        Check.that(xml.contains("<ServiceFrame id=\"SF\" version=\"20260101\">"),
                "frameOpen carries it too");
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
    /// not the `.tmp` either.
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

    /// The default writer emits the STORED bytes. This is what the no-hook constructor exists for,
    /// and what scripts/DbToZipRaw depends on.
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

    /// `Args.dateTimeFlag`, the same three cases as [#testIntFlagReadsAValueOrItsDefault] — and the
    /// same reason the third one is not here.
    @Test
    void testDateTimeFlagReadsAValueOrItsDefault() {
        Args a = Args.parse(new String[] {"--publication-timestamp", "2026-01-01T00:00:00", "p"},
                "test <x> [--publication-timestamp ISO]", "--publication-timestamp=", "--other=");
        Check.equals(LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                a.dateTimeFlag("--publication-timestamp", null), "the given value");
        Check.equals(null, a.dateTimeFlag("--other", null), "null default when the flag is absent");
    }

    // ------------------------------------------------------------ the EPIP envelope, end to end
    // These run the REAL driver surface — DbToZip.driverMain over EpipDbToZip's own flags and
    // setup — because the envelope values are decided in setup(), and a test that built the
    // Options itself would not see a flag that setup forgot to read.

    /// One EPIP export, driven exactly as `EpipDbToXml` drives it.
    private static Path epipExport(TestStore ts, String name, String... envelopeArgs)
            throws Exception {
        Path db = ts.buildDbFile(FRAMES, name + ".lmdb");
        Path out = ts.tempPath(name + ".zip");
        List<String> args = new ArrayList<>(List.of(db.toString(), out.toString()));
        args.addAll(List.of(envelopeArgs));
        DbToZip.driverMain(EpipDbToZip.TAG, args.toArray(new String[0]),
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);
        return out;
    }

    /// Three Networks, two of them named by a Line and one named by nothing. The frame takes one
    /// Network as a direct child and the rest in a container.
    private static final String THREE_NETWORKS = """
        <ServiceFrame id="sf" version="1">
          <Network id="net1" version="1"><Name>First</Name></Network>
          <additionalNetworks>
            <Network id="net2" version="1"><Name>Second</Name></Network>
            <Network id="net3" version="1"><Name>Orphan</Name></Network>
          </additionalNetworks>
          <lines>
            <Line id="l1" version="1"><Name>L1</Name><TransportMode>bus</TransportMode>
              <RepresentedByGroupRef ref="net1" version="1"/></Line>
            <Line id="l2" version="1"><Name>L2</Name><TransportMode>bus</TransportMode>
              <RepresentedByGroupRef ref="net2" version="1"/></Line>
          </lines>
        </ServiceFrame>
        """;

    /// Every REFERENCED Network reaches the export: one as the frame's direct child, the rest in
    /// additionalNetworks. NetworkInFrameGroup is that pair, and the profile's class table for the
    /// network frame bounds neither.
    ///
    /// Emitting only the direct child silently dropped the other Networks a merged corpus carried.
    /// Emitting all of them published orphans instead: a Network is reachable from one place only,
    /// a Line's representedByGroupRef, and one no Line names groups nothing a consumer can act on,
    /// which the profile's data-relevance rule asks a document not to carry.
    @Test
    void testOnlyTheReferencedNetworksAreEmitted(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(THREE_NETWORKS, "networks.lmdb");
        Path out = ts.tempPath("networks.zip");
        DbToZip.driverMain(EpipDbToZip.TAG, new String[] {db.toString(), out.toString()},
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);

        String network = networkFrame(out);
        Set<String> ids = new TreeSet<>();
        Matcher m = Pattern.compile("<Network\\b[^>]*\\bid=\"([^\"]*)\"").matcher(network);
        while (m.find()) ids.add(m.group(1));
        Check.equals(Set.of("net1", "net2"), ids,
                "the two Networks a Line names are emitted, the orphan is not: " + ids);
        Check.that(network.contains("<additionalNetworks>"),
                "they go in additionalNetworks");
        // No direct-child Network: nominating one of several as the frame's principal network is
        // an assertion a merged corpus cannot make, and both halves of the pair are optional.
        Check.that(!network.contains("<Network ") || network.indexOf("<Network ")
                        > network.indexOf("<additionalNetworks>"),
                "no Network precedes the container: none is promoted above the others");
        Check.that(network.indexOf("<lines>") > network.indexOf("</additionalNetworks>"),
                "and the container precedes lines, per the frame sequence");
    }

    /// A Network that NESTS its group of lines, with a Line naming the group.
    private static final String NESTING_NETWORK = """
        <ServiceFrame id="sf" version="1">
          <Network id="net1" version="1"><Name>Nesting</Name>
            <groupsOfLines>
              <GroupOfLines id="gol1" version="1"><Name>Grouped</Name></GroupOfLines>
            </groupsOfLines>
          </Network>
          <lines>
            <Line id="l1" version="1"><Name>L</Name><TransportMode>bus</TransportMode>
              <RepresentedByGroupRef ref="gol1" version="1"/></Line>
          </lines>
        </ServiceFrame>
        """;

    /// A Line that names a NESTED group reaches the Network that holds it. The group owns no row,
    /// so the ref resolves onto its container — which is what puts GroupOfLines in the embedding
    /// scope. Unlisted, the ref resolved to nothing and the Network read as referenced by nobody.
    @Test
    void testALineNamingANestedGroupReferencesItsNetwork(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(NESTING_NETWORK, "nesting.lmdb");
        Path out = ts.tempPath("nesting.zip");
        DbToZip.driverMain(EpipDbToZip.TAG, new String[] {db.toString(), out.toString()},
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);
        String network = networkFrame(out);
        Check.that(network.contains("id=\"net1\""),
                "the nesting Network survives: " + network);
        Check.that(network.contains("id=\"gol1\""),
                "and so does the group it nests");
    }

    /// The same nesting, with nothing naming the group or the Network.
    private static final String NESTING_NETWORK_UNNAMED = """
        <ServiceFrame id="sf" version="1">
          <Network id="net1" version="1"><Name>Nesting</Name>
            <groupsOfLines>
              <GroupOfLines id="gol1" version="1"><Name>Grouped</Name></GroupOfLines>
            </groupsOfLines>
          </Network>
          <lines>
            <Line id="l1" version="1"><Name>L</Name><TransportMode>bus</TransportMode></Line>
          </lines>
        </ServiceFrame>
        """;

    /// A Network nobody names is still emitted when it CONTAINS something, because containment is
    /// not a reference and dropping the Network would drop what it nested.
    @Test
    void testANetworkThatNestsContentIsEmittedThoughUnreferenced(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(NESTING_NETWORK_UNNAMED, "nesting-unnamed.lmdb");
        Path out = ts.tempPath("nesting-unnamed.zip");
        DbToZip.driverMain(EpipDbToZip.TAG, new String[] {db.toString(), out.toString()},
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);
        String network = networkFrame(out);
        Check.that(network.contains("id=\"net1\""),
                "the nesting Network survives on containment alone: " + network);
        Check.that(network.contains("id=\"gol1\""),
                "and so does the group it nests");
    }

    /// The `shared-03-network.xml` entry of an export, as text.
    private static String networkFrame(Path zip) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                if (e.getName().equals("shared-03-network.xml")) {
                    return new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("the export carries no shared-03-network.xml entry");
    }

    /// A journey categorised by a TypeOfService the feed defines, beside a second definition the
    /// feed declares and nothing uses — the shape of a published code list.
    private static final String TYPE_OF_SERVICE = """
        <ResourceFrame id="rf" version="1">
          <typesOfValue>
            <TypeOfService id="tos1" version="1"><Name>Regional</Name></TypeOfService>
            <TypeOfService id="tos2" version="1"><Name>Unused</Name></TypeOfService>
          </typesOfValue>
        </ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="l1" version="1"><Name>L</Name><TransportMode>bus</TransportMode></Line>
          </lines>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="sj1" version="1"><LineRef ref="l1" version="1"/>
              <TypeOfServiceRef ref="tos1" version="1"/></ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    /// A TypeOfService is emitted in the ResourceFrame's `typesOfValue`, not left to the general
    /// frame's closure. `typesOfValueInFrame_RelStructure` is a repeated choice of ValueSet and
    /// TypeOfValue, and TypeOfService substitutes for TypeOfValue, so it belongs there.
    ///
    /// What makes this load-bearing rather than tidying: every ServiceJourney may carry a
    /// TypeOfServiceRef, and the profile requires a ref to resolve inside the same document. While
    /// the definitions lived only in the GeneralFrame, removing that frame would have dangled one
    /// reference per journey.
    ///
    /// Only the REFERENCED definitions go in. The profile's data-relevance rule asks that a
    /// document carry no unused ancillary definition, and a class map has no opinion on which rows
    /// are used — where the closure it replaces here could not reach an unreferenced object at all.
    @Test
    void testATypeOfServiceIsEmittedInTheResourceFrame(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(TYPE_OF_SERVICE, "tos.lmdb");
        Path out = ts.tempPath("tos.zip");
        DbToZip.driverMain(EpipDbToZip.TAG, new String[] {db.toString(), out.toString()},
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);

        Map<String, String> byEntry = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(out.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                byEntry.put(e.getName(),
                        new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        String resource = byEntry.get("shared-01-resource.xml");
        Check.that(resource != null, "the resource frame entry exists: " + byEntry.keySet());
        Check.that(resource.contains("<typesOfValue>") && resource.contains("id=\"tos1\""),
                "the referenced TypeOfService is in typesOfValue: " + resource);
        for (Map.Entry<String, String> e : byEntry.entrySet()) {
            if (e.getKey().equals("shared-01-resource.xml")) continue;
            Check.that(!e.getValue().contains("<TypeOfService "),
                    e.getKey() + " does not emit it a second time: " + e.getValue());
        }
        Check.that(byEntry.values().stream().noneMatch(s -> s.contains("id=\"tos2\"")),
                "and the definition nothing references is emitted nowhere: " + resource);
        // The reference the definition exists for, and the document it has to resolve in.
        Check.that(byEntry.values().stream().anyMatch(s -> s.contains("<TypeOfServiceRef")),
                "the journey still carries its TypeOfServiceRef");
    }

    /// One journey on a ServicePattern, one on nothing at all. EPIP permits ServicePattern beside
    /// ServiceJourneyPattern, and this profile emits only the latter.
    private static final String SERVICE_PATTERN = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="l1" version="1"><Name>L</Name><TransportMode>bus</TransportMode></Line>
          </lines>
          <servicePatterns>
            <ServicePattern id="sp1" version="1"><RouteView><LineRef ref="l1" version="1"/></RouteView>
              <pointsInSequence>
                <StopPointInJourneyPattern id="sp1-1" version="1" order="1">
                  <ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
              </pointsInSequence></ServicePattern>
          </servicePatterns>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="onServicePattern" version="1"><LineRef ref="l1" version="1"/>
              <ServicePatternRef ref="sp1" version="1"/></ServiceJourney>
            <ServiceJourney id="onNothing" version="1"><LineRef ref="l1" version="1"/></ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    /// A journey naming a pattern class this profile does not emit is DROPPED, and counted as a
    /// deletion from the output. A journey naming no pattern at all still spills to the orphan
    /// group and is emitted.
    ///
    /// The two shared a branch before `ServicePattern` was named: neither reached the drop census,
    /// so a feed built on ServicePattern had every journey emitted with no pattern for a consumer
    /// to map it through.
    @Test
    void testAJourneyOnAnUnemittedPatternClassIsDroppedNotOrphaned(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(SERVICE_PATTERN, "sp.lmdb");
        Path out = ts.tempPath("sp.zip");
        Path log = ts.tempPath("sp.log");
        DbToZip.driverMain(EpipDbToZip.TAG, new String[] {db.toString(), out.toString(),
                        "--log-file", log.toString()},
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);

        // The census, not just the absence: a drop that is counted is one a reader can act on,
        // where a drop that is merely silent is indistinguishable from a feed that was short.
        String logged = Files.readString(log);
        Check.that(logged.contains("1 ServiceJourneys named a pattern this profile does not emit"),
                "the drop is counted under droppedOutput, not silent: " + logged);

        StringBuilder all = new StringBuilder();
        try (ZipFile zf = new ZipFile(out.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                all.append(new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Check.that(all.indexOf("\"onNothing\"") >= 0,
                "a journey with no pattern ref at all is still emitted as an orphan");
        Check.that(all.indexOf("\"onServicePattern\"") < 0,
                "a journey on a ServicePattern is dropped, not emitted pattern-less");
    }

    /// Every `<PublicationTimestamp>` in a zip, deduplicated.
    private static Set<String> timestamps(Path zip) throws Exception {
        Set<String> found = new TreeSet<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                String s = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                int i = s.indexOf("<PublicationTimestamp>");
                Check.that(i >= 0, e.getName() + " carries a PublicationTimestamp");
                found.add(s.substring(i + "<PublicationTimestamp>".length(),
                        s.indexOf("</PublicationTimestamp>", i)));
            }
        }
        return found;
    }

    /// With no envelope flags the delivery names the toolkit that produced it and the export
    /// profile that shaped it — NOT the Dutch NDOV sample the profile was first written against.
    /// The absence half is the point: `NDOV` names a real and unrelated organisation, so a
    /// consumer reading it would misattribute the data.
    ///
    /// And every entry of ONE export carries ONE timestamp. `open()` runs per entry, so a clock
    /// read there gave each entry its own.
    @Test
    void testTheEpipEnvelopeDefaultsNameTheToolkitAndTheProfile(TestStore ts) throws Exception {
        Path zip = epipExport(ts, "defaults");
        int entries;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            entries = zf.size();
            for (ZipEntry e : zf.stream().toList()) {
                String s = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                Check.that(s.contains("<ParticipantRef>netex-toolkit</ParticipantRef>"),
                        e.getName() + ": ParticipantRef defaults to the toolkit's name");
                Check.that(s.contains("<Text>epip-db-to-xml</Text>"),
                        e.getName() + ": the description defaults to EpipDbToZip.TAG");
                Check.that(!s.contains("NDOV"), e.getName() + ": no NDOV anywhere");
                Check.that(!s.contains("Huge XML Serializer"),
                        e.getName() + ": no placeholder description anywhere");
            }
        }
        Check.that(entries > 1, "more than one entry, so the next check is not vacuous");
        Check.equals(1, timestamps(zip).size(),
                "every entry of one export shares one timestamp, not one each");
    }

    /// Every frame id carries the profile's frame-id structure — codespace, country, local code,
    /// `FrameType_EPIP_TYPE`, topic — which the profile makes mandatory, unlike the structure for
    /// frame contents. Country and local are empty here and keep their placeholder colons.
    ///
    /// The topic is bounded and sanitised: a shard reaches the minter as `00_0001`, and an
    /// underscore is not among the characters the profile allows.
    @Test
    void testEveryFrameIdCarriesTheProfilesFrameIdStructure(TestStore ts) throws Exception {
        Path zip = epipExport(ts, "frameids");
        Set<String> ids = new TreeSet<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                String s = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile(
                        "<(CompositeFrame|ResourceFrame|SiteFrame|ServiceFrame|TimetableFrame"
                                + "|ServiceCalendarFrame)\\b[^>]*\\bid=\"([^\"]*)\"").matcher(s);
                while (m.find()) ids.add(m.group(2));
            }
        }
        Check.that(!ids.isEmpty(), "the export has frames to check");
        Pattern shape = Pattern.compile("^epd:[A-Z]*:[^:]*:[A-Za-z]+_EU_PI_[A-Z_]+:[A-Za-z0-9-]{1,15}$");
        for (String id : ids) {
            Check.that(shape.matcher(id).matches(), "frame id is structured: " + id);
            Check.that(!id.contains("_EU_PI_NETWORK_00"), "the shard topic is not underscored: " + id);
        }
        Check.that(ids.contains("epd:::CompositeFrame_EU_PI_NETWORK_OFFER:offer"),
                "the composite frame is minted too, not left as a bare literal: " + ids);
        Check.that(ids.contains("epd:::ResourceFrame_EU_PI_COMMON:common"),
                "and the resource frame's EPIP type is EU_PI_COMMON: " + ids);
    }

    /// The EPIP data codespace has to be declared: an id carrying a prefix the document never
    /// defines is what the profile forbids, and the frame ids above all carry it.
    @Test
    void testTheEpipCodespaceIsDeclaredInEveryEntry(TestStore ts) throws Exception {
        Path zip = epipExport(ts, "codespace");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                String s = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                Check.that(s.contains("<Codespace id=\"epip_data\">"),
                        e.getName() + " declares the codespace its frame ids use");
                Check.that(s.contains("<Xmlns>epd</Xmlns>"), e.getName() + " names the prefix");
            }
        }
    }

    /// A publisher naming its own codespace gets it on the frame ids too, and gets it declared —
    /// once. The export cannot read the choice off the store, which may declare several codespaces
    /// and says of none of them that the generated ids were built over it, so it is told.
    ///
    /// The regression this pins: keying the scan off the EPIP codespace's `id` rather than its
    /// xmlns. A custom codespace takes its xmlns for an id, so the scan missed it, declared a second
    /// codespace nothing referenced, and minted frame ids under `epd` while the contents carried
    /// the publisher's.
    @Test
    void testACustomCodespaceReachesTheFrameIdsAndIsDeclaredOnce(TestStore ts) throws Exception {
        Path zip = epipExport(ts, "cs-custom", "--codespace", "NOI");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                String s = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                Check.that(s.contains("<Codespace id=\"NOI\"><Xmlns>NOI</Xmlns></Codespace>"),
                        e.getName() + " declares the publisher's codespace: " + s);
                Check.that(!s.contains("epip_data"),
                        e.getName() + " does not also declare the EPIP one, which nothing uses");
                Matcher m = Pattern.compile("<CompositeFrame\\b[^>]*\\bid=\"([^\"]*)\"").matcher(s);
                Check.that(m.find(), e.getName() + " has a composite frame");
                Check.equals("NOI:::CompositeFrame_EU_PI_NETWORK_OFFER:offer", m.group(1),
                        "the frame id carries the publisher's codespace, not the profile's");
            }
        }
    }

    /// Pin both stamps and two exports of one store are byte-identical. This is the whole point of
    /// making them configurable: it turns the package doc's standing "not reproducible" caveat into
    /// something a caller can switch off.
    @Test
    void testPinnedEnvelopeStampsMakeTheExportByteReproducible(TestStore ts) throws Exception {
        String[] pinned = {"--publication-timestamp", "2026-01-01T00:00:00",
                "--frame-version", "20260101"};
        Path a = epipExport(ts, "repro-a", pinned);
        Path b = epipExport(ts, "repro-b", pinned);
        Check.that(Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)),
                "two exports of one store are byte-identical once both stamps are pinned");
        Check.equals(Set.of("2026-01-01T00:00"), timestamps(a),
                "and the pinned timestamp is what every entry carries, verbatim");
    }
}
