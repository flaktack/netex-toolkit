package toolkit.harness;

// The multi-feed clone chain must survive degenerate feed lists: an empty feed must not reset the
// cumulative key offset (the next feed's local keys would collide with feed 1), and a duplicate
// (id, version) across feeds is kept ONCE, first feed wins.
//
// The merge is exercised through MergeToDb.driverMain -- the whole of scripts/MergeDbToDb.java --
// with the sources built as closed db files via TestStore.buildDbFile and the target a fresh path
// in the same temp dir.

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

    /// The merge through the CLI, one layer below the driver.
    ///
    /// `scripts/MergeDbToDb.java` is `MergeToDb.driverMain("merge-db", args)` and nothing else, so
    /// this is that call verbatim — a driver in the default package is unimportable from here. What
    /// is lost is the two-line wrapper; CLI parsing and argument order are not.
    private static void merge(Path target, Path... feeds) throws Exception {
        String[] args = new String[feeds.length + 1];
        for (int i = 0; i < feeds.length; i++) args[i] = feeds[i].toString();
        args[feeds.length] = target.toString();
        MergeToDb.driverMain("merge-db", args);
    }

    /// merge() with the driver's log captured, standing in for mocking the blob-repair sweep out:
    /// resolve() logs [#SWEEP_LINE] once per sweep and ONLY from inside the marker-gated branch
    /// that runs it, so the count of that line in the captured log is the sweep call count.
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

    @Test
    void testMergeDuplicateIdKeptOnceFirstFeedWins(TestStore db) throws Exception {
        Path f1 = db.buildDbFile(linesFrame("dup", "first", "a1", "A1"), "f1.lmdb");
        Path f2 = db.buildDbFile(linesFrame("dup", "second", "b1", "B1"), "f2.lmdb");
        Path merged = f1.getParent().resolve("merged.lmdb");
        merge(merged, f1, f2);
        Map<String, String> names = lineNames(merged); // asserts one blob per id
        Check.equals(Map.of("dup", "first", "a1", "A1", "b1", "B1"), names, "first feed wins");
    }

    /// A reference from the LOSING source onto the duplicate must end up on the copy that was kept.
    ///
    /// `cloneFrom` copies edge rows rather than re-deriving them, so dropping a duplicate row also
    /// drops every edge INTO it — and, unlike an ordinary miss, leaves no unresolved row for the
    /// `resolve()` that follows to find. The referrer keeps its ref STRING either way, so the
    /// objects look right and only the reference graph is short.
    @Test
    void testAReferenceOntoADroppedDuplicateSurvivesOnTheKeptCopy(TestStore db) throws Exception {
        // f2 declares the same StopPlace id as f1 AND an assignment pointing at it. f1's copy wins,
        // so f2's copy is dropped -- and f2's assignment must still reach f1's.
        Path f1 = db.buildDbFile(stopPlaceFrame("SP:dup"), "f1.lmdb");
        Path f2 = db.buildDbFile(stopPlaceFrame("SP:dup") + assignmentFrame("PSA:2", "SP:dup"),
                "f2.lmdb");
        Path merged = f1.getParent().resolve("merged.lmdb");
        merge(merged, f1, f2);
        try (Store s = Stores.open(merged, true); Txn t = s.roTxn()) {
            long sp = s.lookupFullKey(t, "SP:dup", "1", noi.netex.model.StopPlace.class);
            long psa = s.lookupFullKey(t, "PSA:2", "1",
                    noi.netex.model.PassengerStopAssignment.class);
            Check.that(sp != -1 && psa != -1, "both the kept StopPlace and the assignment are rows");
            boolean wired = false;
            for (long[] e : s.iterEdges(t)) {
                if (e[0] == psa && e[1] == sp) wired = true;
            }
            Check.that(wired, "the assignment from the losing source is wired to the KEPT StopPlace");
        }
    }

    /// ...and the repair must not resurrect what the clone dropped. A skipped row referencing
    /// another skipped row is ordinary, and re-inserting it as a "referrer" would undo the
    /// de-duplication.
    @Test
    void testTheRepairDoesNotResurrectADroppedDuplicate(TestStore db) throws Exception {
        Path f1 = db.buildDbFile(stopPlaceFrame("SP:dup") + stopPlaceFrame("SP:other"), "f1.lmdb");
        Path f2 = db.buildDbFile(stopPlaceFrame("SP:dup") + stopPlaceFrame("SP:other")
                + assignmentFrame("PSA:2", "SP:dup"), "f2.lmdb");
        Path merged = f1.getParent().resolve("merged.lmdb");
        merge(merged, f1, f2);
        try (Store s = Stores.open(merged, true); Txn t = s.roTxn()) {
            int stops = 0;
            for (Object ignored : s.iterOnlyObjects(t, noi.netex.model.StopPlace.class)) stops++;
            Check.equals(2, stops, "one row per id: the duplicates stay dropped, not re-inserted");
        }
    }

    private static String stopPlaceFrame(String id) {
        return "<SiteFrame id=\"site-" + id + "\" version=\"1\"><stopPlaces>"
                + "<StopPlace id=\"" + id + "\" version=\"1\"><Name>" + id + "</Name></StopPlace>"
                + "</stopPlaces></SiteFrame>";
    }

    private static String assignmentFrame(String id, String stopPlaceId) {
        return "<ServiceFrame id=\"sf-" + id + "\" version=\"1\"><stopAssignments>"
                + "<PassengerStopAssignment id=\"" + id + "\" version=\"1\" order=\"1\">"
                + "<StopPlaceRef ref=\"" + stopPlaceId + "\" version=\"1\"/>"
                + "</PassengerStopAssignment></stopAssignments></ServiceFrame>";
    }

    // resolve() SKIPS the full blob-repair sweep when every source carries the marker: bind-time
    // repair in pass-1 covers the only refs the merge touches, the cross-feed edges. buildDbFile
    // stamps the marker (load + resolve()), so both sources carry it and so does the merged db.
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

    // The marker is NOT inherited when any source lacks it: the second source is built WITHOUT
    // resolve(), so resolve() runs its one-shot sweep on the merged db. Negative control for the
    // zero-sweep assertion above.
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

    // The same pair the other way round. cloneFrom carries the marker per source, so the ORDER
    // decides whether a last-writer rule would hold: an un-swept source first, then a swept one
    // that skips nothing, and the target must still sweep. The test above cannot see that — its
    // un-swept source is last, so the final write withholds the marker whatever the rule is.
    @Test
    void testAnUnsweptFirstSourceStillCostsTheMergeASweep(TestStore db) throws Exception {
        Path f2 = db.buildDbFile(FEED2, "f2.lmdb");
        Path f1 = f2.getParent().resolve("f1.lmdb");
        try (Store raw = Stores.create(f1, db.format)) { // direct inserts, no resolve(): no marker
            try (Txn txn = raw.rwTxn()) {
                raw.insertAnyObjects(txn, List.of(
                        new Line().withId("a1").withVersion("1").withName(Mls.of("A1")),
                        new Line().withId("a2").withVersion("1").withName(Mls.of("A2")),
                        new Line().withId("a3").withVersion("1").withName(Mls.of("A3"))));
                txn.commit();
            }
        }
        Check.that(!markerPresent(f1), "precondition: first source un-swept");
        Check.that(markerPresent(f2), "precondition: second source swept");
        Path merged = f2.getParent().resolve("merged.lmdb");
        Check.equals(1, mergeCountingSweeps(merged, f1, f2),
                "the swept second source must not re-grant what the first one withheld");
        Check.equals(List.of("a1", "a2", "a3", "b1", "b2", "b3"),
                new ArrayList<>(lineNames(merged).keySet()), "every line survives with one blob each");
    }
}
