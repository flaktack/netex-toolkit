package toolkit.harness;

// The multi-feed clone chain must survive degenerate feed lists — an empty feed must not reset the cumulative
// key offset (the next feed's local keys would collide with feed 1), and a duplicate
// (id, version) across feeds is kept ONCE, first feed wins.
//
// The blob-repair-sweep tests count sweep calls through the LOG rather than through a mock —
// there is no mocking framework here, and the observable is better anyway. resolve() emits its
// repair line ONLY inside the marker-gated branch that runs the sweep, so the line count in a
// captured driver log is exactly "the sweep ran" / "it did not". The observable end state — marker on sources and
// target, one blob per Line id — is asserted on top.
//
// The path-taking merge is exercised through MergeToDb.driverMain -- the whole of
// scripts/MergeDbToDb.java -- with the sources built as closed db files via TestStore.buildDbFile
// and the target a fresh path in the same temp dir.

import noi.netex.model.Line;
import org.junit.jupiter.api.Test;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import noi.netex.text.Mls;

public class TestMergeDbToDb {

    /// alternating id, name pairs.
    private static String linesFrame(String... idName) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < idName.length; i += 2) {
            lines.append("<Line id=\"").append(idName[i]).append("\" version=\"1\"><Name>")
                    .append(idName[i + 1]).append("</Name></Line>");
        }
        return "<ServiceFrame id=\"sf\" version=\"1\"><lines>" + lines + "</lines></ServiceFrame>";
    }

    static final String FEED1 = linesFrame("a1", "A1", "a2", "A2", "a3", "A3");
    static final String FEED2 = linesFrame("b1", "B1", "b2", "B2", "b3", "B3");

    /// id -> Name for every Line row, asserting one blob per id.
    private static Map<String, String> lineNames(Path dbPath) throws Exception {
        Map<String, String> names = new TreeMap<>(); // sorted keys, so the assertions are stable
        try (Store db = Stores.open(dbPath, true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, Line.class)) {
                Line line = (Line) o;
                Check.that(!names.containsKey(line.getId()), "duplicate blob for " + line.getId());
                names.put(line.getId(), Mls.text(line.getName()));
            }
        }
        return names;
    }

    /// Has this database's one-shot blob sweep already run?
    private static boolean markerPresent(Path dbPath) throws Exception {
        try (Store db = Stores.open(dbPath, true); Txn txn = db.roTxn()) {
            return db.blobRepairMarkerPresent(txn);
        }
    }

    /// merge_db_to_db(feeds, target) through the CLI, one layer below the driver.
    ///
    /// `scripts/MergeDbToDb.java` is `MergeToDb.driverMain("merge-db", args)` and nothing else, so
    /// this is that call verbatim — which it has to be, because a driver in the default package is
    /// unimportable from here. What is lost is the two-line wrapper; what is kept is everything the
    /// wrapper reaches, CLI parsing and argument order included.
    private static void merge(Path target, Path... feeds) throws Exception {
        String[] args = new String[feeds.length + 1];
        for (int i = 0; i < feeds.length; i++) args[i] = feeds[i].toString();
        args[feeds.length] = target.toString();
        MergeToDb.driverMain("merge-db", args);
    }

    /// merge() with the driver's log captured — the observable that stands in for mocking the
    /// blob-repair sweep out: resolve() logs [#SWEEP_LINE] once
    /// per sweep and ONLY from inside the marker-gated branch that runs it, so the count of that
    /// line in the captured log is the sweep call count.
    private static final String SWEEP_LINE = "mis-typed blob repaired";

    private static int mergeCountingSweeps(Path target, Path... feeds) throws Exception {
        Path log = target.getParent().resolve(target.getFileName() + ".log");
        Log.toFile(log);
        try {
            merge(target, feeds);
        } finally {
            Log.toFile(null);
        }
        int n = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.contains(SWEEP_LINE)) n++;
        }
        return n;
    }

    /// a created-then-closed store, nothing loaded.
    private static Path emptyDb(Path dir, String format) throws Exception {
        Path path = dir.resolve("empty.lmdb");
        Stores.create(path, format).close();
        return path;
    }

    @Test
    void testMergeEmptyFeedDoesNotResetOffset(TestStore db) throws Exception {
        Path f1 = db.buildDbFile(FEED1, "f1.lmdb");
        Path f2 = db.buildDbFile(FEED2, "f2.lmdb");
        Path merged = f1.getParent().resolve("merged.lmdb");
        merge(merged, f1, emptyDb(f1.getParent(), db.format), f2);
        Check.equals(List.of("a1", "a2", "a3", "b1", "b2", "b3"),
                new ArrayList<>(lineNames(merged).keySet()), "all six lines survive the empty feed");
    }

    // The FUSED driver's version of the empty-feed offset case lives with that driver, in
    // ../noi-pipeline's tests/TestFeedsToCoupled.

    @Test
    void testMergeDuplicateIdKeptOnceFirstFeedWins(TestStore db) throws Exception {
        Path f1 = db.buildDbFile(linesFrame("dup", "first", "a1", "A1"), "f1.lmdb");
        Path f2 = db.buildDbFile(linesFrame("dup", "second", "b1", "B1"), "f2.lmdb");
        Path merged = f1.getParent().resolve("merged.lmdb");
        merge(merged, f1, f2);
        Map<String, String> names = lineNames(merged); // asserts one blob per id
        Check.equals(Map.of("dup", "first", "a1", "A1", "b1", "B1"), names, "first feed wins");
    }

    // The claim: resolve() SKIPS the full blob-repair sweep when every source
    // carries the marker (bind-time repair in pass-1 covers the only refs the merge touches --
    // the cross-feed edges). Here the sweep is counted from the driver's log (see
    // mergeCountingSweeps): zero "mis-typed blob repaired" lines means zero sweeps.
    // Swept sources (buildDbFile -> load + resolve() stamps the marker) -> the merged db carries
    // the marker, and correctness is unaffected (every line survives with one blob each).
    @Test
    void testMergeSkipsBlobRepairSweepWhenAllSourcesSwept(TestStore db) throws Exception {
        Path f1 = db.buildDbFile(FEED1, "f1.lmdb");
        Path f2 = db.buildDbFile(FEED2, "f2.lmdb");
        Check.that(markerPresent(f1) && markerPresent(f2), "precondition: sources are swept");
        Path merged = f1.getParent().resolve("merged.lmdb");
        Check.equals(0, mergeCountingSweeps(merged, f1, f2),
                "all sources swept -> resolve() must SKIP the full blob-repair sweep");
        Check.that(markerPresent(merged), "target carries the blob-repair marker");
        Check.equals(List.of("a1", "a2", "a3", "b1", "b2", "b3"),
                new ArrayList<>(lineNames(merged).keySet()), "every line survives with one blob each");
    }

    // The un-swept second source is built for real rather than mocked, and the sweep is counted
    // exactly one sweep. Here the second source is built WITHOUT resolve() so it genuinely lacks
    // the marker, exercising the same no-propagation branch (the marker is NOT inherited when any
    // source lacks it; resolve() then runs its one-shot sweep on the merged db). The sweep call
    // count IS observable — resolve() logs "mis-typed blob repaired" only from inside that branch
    // — so this asserts the sweep ran exactly once, and doubles as the negative
    // control for the zero-sweep assertion in the test above.
    @Test
    void testMergeRunsSweepOnceWhenASourceIsUnswept(TestStore db) throws Exception {
        Path f1 = db.buildDbFile(FEED1, "f1.lmdb");
        Path f2 = f1.getParent().resolve("f2.lmdb");
        try (Store raw = Stores.create(f2, db.format)) { // direct inserts, no resolve(): no marker
            try (Txn txn = raw.rwTxn()) {
                raw.insertAnyObjects(txn, List.of(
                        new Line().withId("b1").withVersion("1").withName(Mls.of("B1")),
                        new Line().withId("b2").withVersion("1").withName(Mls.of("B2")),
                        new Line().withId("b3").withVersion("1").withName(Mls.of("B3"))));
                txn.commit();
            }
        }
        Check.that(markerPresent(f1), "precondition: first source swept");
        Check.that(!markerPresent(f2), "precondition: second source un-swept");
        Path merged = f1.getParent().resolve("merged.lmdb");
        Check.equals(1, mergeCountingSweeps(merged, f1, f2),
                "an un-swept source -> resolve() runs the full blob-repair sweep exactly once");
        // (the target still ends up marked — resolve() stamps the marker after running the sweep)
        Check.equals(List.of("a1", "a2", "a3", "b1", "b2", "b3"),
                new ArrayList<>(lineNames(merged).keySet()), "every line survives with one blob each");
    }
}
