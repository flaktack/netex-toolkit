package toolkit.store;

// THE DIRTY-BLOCK STATE BELOW THE COMPACTION THRESHOLD, AND WHAT SURVIVES A CLOSE.
//
// V2Store keeps edges and unresolved rows as dense slot arrays serialised into fixed-size blocks.
// A delete does not move anything: it writes a (-1,-1) TOMBSTONE into the slot and rewrites only
// the block that slot lives in. Compaction — rewriting the kind from its live slots and renumbering
// every block — happens only once dead slots reach COMPACT_DEAD_FRACTION (25 %) of the kind.
//
// TestScanEdges covers the ABOVE-threshold half: it resolves exactly half the references, the 50 %
// dead fraction compacts both kinds clean, and every assertion it then makes is on a tombstone-FREE
// store (`Check.equals(0, s[1])`, `Check.equals(0, s[4])`) — which is what makes its slot/block
// correspondence exact. So the state a real store spends most of its life in — a few tombstones,
// no compaction due, blocks whose slot numbering has holes in it — was never written to disk and
// read back by any test. Nor was the intern table, which holds the encoded target key of every
// unresolved row and is the one structure that is pruned by compaction rather than tombstoned.
//
// Three things are asserted here that nothing else asserts:
//
//   * a below-threshold delete leaves the tombstones in place, and they are STILL there after
//     close and reopen — a reload that dropped them would renumber the live slots and every
//     full key already handed out would address a different row;
//   * the intern table tracks the live unresolved rows and shrinks when they compact away;
//   * purging a class (insertObjects with an empty iterator and empty=true) takes every edge and
//     every unresolved row of that class with it, compacting BOTH kinds and the intern table to
//     nothing — while the class maps it does not name are untouched.
//
// Runner sets blockPairs = 128 for the whole suite, so the 700 rows below span six blocks; the
// block-count assertions are written against the property rather than a literal, so raising it
// fails here instead of quietly reducing this to a single-block test.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.store.v2.V2Store;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.util.Conf;

import javax.xml.namespace.QName;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestV2EdgeBlockTombstones {

    /// Enough rows to span several blocks at the suite's blockPairs, and even, so halves are exact.
    private static final int N = 700;

    /// Read per call, not cached: TestScanEdges moves this property and puts it back.
    private static int blocks(int slots) {
        int pairs = Conf.intOf(Conf.V2_BLOCK_PAIRS);
        return (slots + pairs - 1) / pairs;
    }

    private static PassengerStopAssignment psa(int i) {
        PassengerStopAssignment p = new PassengerStopAssignment();
        p.setId("PSA:" + i);
        p.setVersion("1");
        QuayRefStructure qr = new QuayRefStructure();
        qr.setRef("Q:" + i);
        qr.setVersion("1");
        p.setQuayRef(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "QuayRef"), QuayRefStructure.class, qr));
        return p;
    }

    private static Quay quay(int i) {
        Quay q = new Quay();
        q.setId("Q:" + i);
        q.setVersion("1");
        return q;
    }

    private static void insert(Store store, List<Object> objs) {
        try (Txn txn = store.rwTxn()) {
            store.insertAnyObjects(txn, objs);
            txn.commit();
        }
    }

    /// N referrers with nothing to point at, then half their targets, then resolve — the 50 % dead
    /// fraction compacts the unresolved kind, so the store this returns has N/2 edges, N/2
    /// unresolved rows and no tombstones of either kind. Closed, so the next open reloads from disk.
    private static Path seeded(TestStore ts) throws Exception {
        Path dir = ts.tempPath("v2blocks.lmdb");
        try (Store store = Stores.create(dir, ts.format)) {
            List<Object> refs = new ArrayList<>();
            for (int i = 0; i < N; i++) refs.add(psa(i));
            insert(store, refs);

            int[] s = ((V2Store) store).debugStats();
            Check.equals(N, s[3], "every reference is unresolved: " + Arrays.toString(s));
            Check.equals(0, s[4], "and none of them is dead yet");
            Check.equals(blocks(N), s[5], "the unresolved rows span " + blocks(N) + " blocks");
            Check.equals(N, s[6], "the intern table holds one key per unresolved row");

            List<Object> targets = new ArrayList<>();
            for (int i = 0; i < N; i += 2) targets.add(quay(i));
            insert(store, targets);
            Check.equals((long) N / 2, store.resolve(), "resolve wires half the references");
        }
        return dir;
    }

    /// The compaction that fires at 50 % dead prunes the intern table with the slots.
    @Test
    void testCompactionPrunesTheInternTableWithTheSlots(TestStore ts) throws Exception {
        Path dir = seeded(ts);
        try (Store store = Stores.open(dir, false)) {
            int[] s = ((V2Store) store).debugStats();
            Check.equals(N / 2, s[3], "half the unresolved rows remain: " + Arrays.toString(s));
            Check.equals(0, s[4], "the compaction left no tombstones behind");
            Check.equals(blocks(N / 2), s[5], "and the kind shrank to " + blocks(N / 2) + " blocks on disk");
            Check.equals(N / 2, s[6], "the intern table was pruned to the live keys");
            Check.equals(N / 2, s[0], "resolve appended " + N / 2 + " edges");
            Check.equals(0, s[1], "none of which is dead");
        }
    }

    /// One more target arrives and its referrer is overwritten. That is one resolved row out of 350
    /// — far below the 25 % threshold — so nothing compacts: the overwrite tombstones the old edge,
    /// the resolve tombstones the drained unresolved row, and BOTH must still be there after the
    /// store is closed and reopened.
    @Test
    void testTombstonesBelowTheThresholdSurviveAReopen(TestStore ts) throws Exception {
        Path dir = seeded(ts);
        try (Store store = Stores.open(dir, false)) {
            insert(store, List.of(quay(1)));  // an odd target, previously absent
            insert(store, List.of(psa(0)));   // overwrite: its existing edge dies and is re-derived
            Check.equals(1L, store.resolve(), "only the newly satisfiable reference is wired");

            int[] s = ((V2Store) store).debugStats();
            Check.equals(1, s[4], "one unresolved tombstone, below the threshold: " + Arrays.toString(s));
            Check.equals(1, s[1], "and one edge tombstone from the overwrite");
            Check.equals(N / 2 + 1, s[0] - s[1], "live edges = the original half plus the new one");
        }

        try (Store store = Stores.open(dir, false)) { // rw, so debugStats reads the reloaded state
            int[] s = ((V2Store) store).debugStats();
            Check.equals(1, s[4], "the unresolved tombstone round-trips through disk: " + Arrays.toString(s));
            Check.equals(1, s[1], "so does the edge tombstone");
            try (Txn tx = store.roTxn()) {
                Check.equals(N / 2 + 1, count(store.iterEdges(tx)),
                        "and the readers skip the tombstones rather than emitting (-1,-1)");
                Check.equals(N / 2 - 1, count(store.iterUnresolved(tx)), "same for the unresolved rows");
            }
        }
    }

    /// Purging the referrer class: every edge and every unresolved row belonged to it, so both kinds
    /// and the intern table compact to nothing. The Quay map is not named and must be untouched —
    /// a purge that reached further would be indistinguishable here from one that worked.
    @Test
    void testPurgingTheReferrerClassEmptiesBothKinds(TestStore ts) throws Exception {
        Path dir = seeded(ts);
        try (Store store = Stores.open(dir, false)) {
            try (Txn txn = store.rwTxn()) {
                store.insertObjects(txn, PassengerStopAssignment.class, Collections.emptyIterator(), true);
                txn.commit();
            }
            int[] s = ((V2Store) store).debugStats();
            Check.equals("[0, 0, 0, 0, 0, 0, 0]", Arrays.toString(s),
                    "edges, unresolved rows, their blocks and the intern table are all gone");
        }
        try (Store store = Stores.open(dir, true); Txn tx = store.roTxn()) {
            Check.equals(0, count(store.iterEdges(tx)), "the purge persisted: no edges");
            Check.equals(0, count(store.iterUnresolved(tx)), "…and no unresolved rows");
            Check.equals(0, count(store.iterObjects(tx, PassengerStopAssignment.class)),
                    "the purged class map is empty");
            Check.equals(N / 2, count(store.iterObjects(tx, Quay.class)),
                    "and the class it did not name still holds all its rows");
        }
    }

    private static int count(Iterable<?> it) {
        int n = 0;
        for (Object ignored : it) n++;
        return n;
    }
}
