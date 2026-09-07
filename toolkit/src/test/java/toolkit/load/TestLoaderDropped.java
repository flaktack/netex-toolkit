package toolkit.load;

// What the loader throws away, and which of it is a fact about feeds and which is a defect.
//
// Two counters ride in LoadStats: `unmapped` (an element the loader itself declared interesting,
// whose subtree is skipped whole because the model package has no class for it) and `skippedNoId`
// (a bound EntityStructure with a null id, which does not reach the store).
//
// A minority of the names in interesting.txt have no class in noi.netex.model, and every one is a
// NeTEx abstract substitution-group head -- Entity, Point, Link, Place, Zone, Site, Equipment,
// PassingTime, Projection, GroupOfEntities ... XJC generates no class for them because no instance
// document may contain them. So `unmapped > 0` means either a schema-invalid element name in the
// feed or a skew between the bundled resource and the model jar, and the loader cannot tell those
// apart. Counted, named and refusable (DROPPED_OUTPUT), not fatal.
//
// Every interesting name that has a model class binds to an EntityStructure subclass, so the arm of
// `advance()` that would count "the bound object is not an EntityStructure at all" is unreachable
// and is a throw: if it fires, the interesting set and the model have diverged and every instance
// of that element is being dropped in silence.

import noi.netex.model.EntityStructure;
import noi.netex.model.ScheduledStopPoint;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.util.Log;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TestLoaderDropped {

    // ---------------------------------------------------------------- fixtures

    /// TestStore.SKELETON cannot be used for the id-less cases: its CompositeFrame carries
    /// `id="cf"`, and CopyingCapture's arm 1 denormalises an absent id down from the nearest
    /// id-bearing ancestor, so no descendant can reach the loader with a null id. An id-less
    /// ancestor chain is the only shape arm 1 declines to inject in.
    private static final String ID_LESS_SKELETON =
            "<PublicationDelivery xmlns=\"http://www.netex.org.uk/netex\" version=\"ntx:1.1\">"
            + "<PublicationTimestamp>2026-01-01T00:00:00</PublicationTimestamp>"
            + "<ParticipantRef>test</ParticipantRef>"
            + "<dataObjects><CompositeFrame version=\"1\"><frames>__FRAMES__</frames>"
            + "</CompositeFrame></dataObjects></PublicationDelivery>";

    private static LoadStats load(TestStore ts, String skeleton, String frames) throws Exception {
        String xml = skeleton.replace("__FRAMES__", frames);
        return new NetexLoader().loadStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), ts.store, "dropped");
    }

    // ---------------------------------------------------------------- unmapped

    /// `<Site>` is in the interesting set and has no model class, so its subtree is skipped whole.
    @Test
    void testUnmappedElementsAreCountedAndNamed(TestStore ts) throws Exception {
        LoadStats s = load(ts, TestStore.SKELETON,
                "<ServiceFrame id=\"svf\" version=\"1\">"
                + "<scheduledStopPoints>"
                + "<ScheduledStopPoint id=\"ssp1\" version=\"1\"><Name>kept</Name></ScheduledStopPoint>"
                + "</scheduledStopPoints>"
                + "<Site id=\"site1\" version=\"1\"><Name>dropped whole</Name></Site>"
                + "</ServiceFrame>");
        Check.equals(1L, s.unmapped(), "the <Site> subtree was skipped and counted");
        Check.equals(Set.of("Site"), s.unmappedNames(),
                "…and the element NAME rides with the count: it is what tells an abstract "
                + "substitution-group head apart from a model the loader has drifted away from");
        Check.equals(1L, s.objects(), "the rest of the stream still loaded — one stray element "
                + "must not cost the 779 names that do map");
    }

    /// The names survive the sum across a feed's streams: that is where the aggregate lives.
    @Test
    void testUnmappedNamesSurviveTheSumAcrossStreams(TestStore ts) throws Exception {
        LoadStats a = new LoadStats(1, 0, 0, 1, 0, 0, Set.of("Site"));
        LoadStats b = new LoadStats(1, 0, 2, 1, 0, 0, Set.of("Zone"));
        LoadStats total = LoadStats.ZERO.plus(a).plus(b);
        Check.equals(2L, total.unmapped(), "counts add");
        Check.equals(Set.of("Site", "Zone"), total.unmappedNames(),
                "and the names UNION — a per-stream warning could never say this");
        Check.equals(2L, total.skippedNoId(), "the other dropped-output counter adds too");
    }

    /// The measurement the `unmapped` verdict rests on. If this ever reads 0, every remaining
    /// unmapped element is a resource/jar skew rather than an abstract head, and the concern's
    /// tolerant default no longer holds.
    @Test
    void testTheInterestingSetStillHasUnmappableNames() throws Exception {
        List<String> missing = unmappableInterestingNames();
        Check.that(missing.size() >= 50,
                "60 of the 780 interesting names had no model class when this was measured; "
                + "got " + missing.size() + ": " + missing);
        for (String head : List.of("Site", "Zone", "Point", "Link", "Place", "Entity")) {
            Check.that(missing.contains(head),
                    head + " is a NeTEx abstract substitution-group head and must be among them: "
                    + missing);
        }
    }

    /// What keeps the throw in `advance()` unreachable. Every interesting name that binds at all
    /// binds to something that can carry an id; the counter-example shape, RouteView (a
    /// DerivedViewStructure with no getId(), plentiful in the 317 source feeds), is kept out of the
    /// set on purpose.
    @Test
    void testEveryInterestingNameThatBindsCanCarryAnId() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String name : NetexLoader.NameSets.fromResources().interesting) {
            Class<?> c;
            try {
                c = Class.forName(ModelRegistry.MODEL_PKG + "." + name);
            } catch (ClassNotFoundException e) {
                continue; // the unmapped population, covered above
            }
            if (!EntityStructure.class.isAssignableFrom(c)) offenders.add(name + " -> " + c.getName());
        }
        Check.equals(List.of(), offenders,
                "an interesting element whose class cannot carry an id would be dropped from every "
                + "store in silence; the loader now refuses instead, and this is why that refusal "
                + "never fires on the shipped set");
        Check.that(!NetexLoader.NameSets.fromResources().interesting.contains("RouteView"),
                "RouteView is the counter-example shape and is kept out of the set on purpose");
    }

    // ---------------------------------------------------------------- no id

    /// A bound object with no id never reaches the store, and the loss is now a number.
    @Test
    void testIdlessObjectVanishesAndIsCounted(TestStore ts) throws Exception {
        LoadStats s = load(ts, ID_LESS_SKELETON,
                "<ServiceFrame version=\"1\"><scheduledStopPoints>"
                + "<ScheduledStopPoint version=\"1\"><Name>no id</Name></ScheduledStopPoint>"
                + "</scheduledStopPoints></ServiceFrame>");
        Check.equals(0L, s.objects(), "nothing was inserted");
        Check.equals(1L, s.skippedNoId(), "…and the one object that vanished is counted");
        Check.equals(0L, s.unmapped(), "ScheduledStopPoint maps fine; this is the other counter");
        ts.store.resolve();
        try (toolkit.store.Txn tx = ts.store.roTxn()) {
            Check.that(!ts.store.scanObjects(tx, ScheduledStopPoint.class).iterator().hasNext(),
                    "the object really is gone — a null id is not a key");
        }
    }

    /// An id-bearing sibling in the same stream still loads: the skip is per object.
    @Test
    void testTheSkipIsPerObjectNotPerStream(TestStore ts) throws Exception {
        LoadStats s = load(ts, ID_LESS_SKELETON,
                "<ServiceFrame version=\"1\"><scheduledStopPoints>"
                + "<ScheduledStopPoint version=\"1\"><Name>no id</Name></ScheduledStopPoint>"
                + "<ScheduledStopPoint id=\"ssp2\" version=\"1\"><Name>kept</Name></ScheduledStopPoint>"
                + "</scheduledStopPoints></ServiceFrame>");
        Check.equals(1L, s.objects(), "the one with an id loaded");
        Check.equals(1L, s.skippedNoId(), "the one without did not");
    }

    // ---------------------------------------------------------------- the report

    /// The post-condition: one line per concern on the run's transcript, naming the count, the
    /// element names and the flag that turns the tolerance off. DROPPED_OUTPUT is tolerated by
    /// default, so the report must say something and must not throw.
    @Test
    void testReportDroppedNamesTheOptIn(TestStore ts) throws Exception {
        Path log = ts.tempPath("dropped.log");
        try {
            Log.toFile(log);
            NetexLoader.reportDropped("droptest",
                    new LoadStats(5, 0, 7, 3, 0, 0, Set.of("Site", "Zone")));
        } finally {
            Log.toFile(null);
        }
        String text = Files.readString(log);
        Check.that(text.contains("[droptest]"), "the stage is named: " + text);
        Check.that(text.contains("Site") && text.contains("Zone"),
                "…and so are the unmapped element names: " + text);
        Check.that(text.contains("-Dtoolkit.strict.droppedOutput=true"),
                "…and the opt-in. A counter nobody can act on is the state this replaced: " + text);
        Check.that(text.contains("7"), "…and the id-less count: " + text);
    }

    /// Nothing dropped is silent — the counters must not manufacture a warning out of zero.
    @Test
    void testACleanLoadReportsNothing(TestStore ts) throws Exception {
        Path log = ts.tempPath("clean.log");
        try {
            Log.toFile(log);
            NetexLoader.reportDropped("droptest", LoadStats.ZERO);
        } finally {
            Log.toFile(null);
        }
        Check.equals("", Files.readString(log).trim(),
                "a load that dropped nothing says nothing about dropping");
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> unmappableInterestingNames() throws Exception {
        List<String> out = new ArrayList<>();
        for (String name : NetexLoader.NameSets.fromResources().interesting) {
            try {
                Class.forName(ModelRegistry.MODEL_PKG + "." + name);
            } catch (ClassNotFoundException e) {
                out.add(name);
            }
        }
        return out;
    }
}
