package toolkit.store;

// WHAT IS PINNED. Store.scanEdges / Store.scanUnresolved are specified as ORDER-IDENTICAL to
// Store.iterEdges / Store.iterUnresolved — same pairs, same order, same tombstone exclusions —
// so that no consumer can branch on which of the two it called and so that the SPI's
// recorded determinism of fetchAllReferencesByClass survives the second reader. The two take
// completely different routes to that sequence: iterEdges concatenates every _edge_blocks block
// through loadEdgeState's TreeMap, scanEdges holds ONE block at a time and recovers block order
// by galloping binary search over the dense numbering. Identity between them is the contract.
//
// WHY blockPairs = 1. The hazard is that a block key is [kind][block# u32 LE] in a BYTE-ordered
// map, so LMDB cursor order stops being numeric order at block 256 — key [k,00,01,00,00] (block
// 256) sorts before [k,01,00,00,00] (block 1). A store with 255 or fewer blocks of both kinds
// CANNOT distinguish a correct reader from a cursor-order one. Runner sets blockPairs = 128 for the
// whole suite, so 300 rows per kind would be 3 blocks; this test drops it to 1 for its own store
// (restored in a finally) and gets 300 blocks per kind, 45 of them past 255.
//
// THE NEGATIVE CONTROL NEEDS NO SECOND READER. At one pair per block, block b holds exactly slot
// b, so the sequence a cursor-ordered reader WOULD have emitted is the correct sequence permuted
// by the cursor order of 0..299 — computable from the correct sequence alone, with no access to
// the format. Digesting it must give a different answer. If it did not, this store could not tell
// the two orders apart and the positive assertions above would prove nothing.
//
// NEUTER PROOF (each test names the edit that must break it):
//   * testScanEdgesIsOrderIdenticalToIterEdges — make V2Store.blockOrder return its block numbers
//     in LMDB cursor order; this test then fails on the pair-for-pair assertion while the counts
//     still match, which is why the assertion is order-sensitive rather than a count.
//   * testTheHazardIsExercised — raise blockPairs back to 128, so 300 rows fit in 3 blocks and
//     numeric and cursor order coincide; the block-count assertion fails rather than the suite
//     going quietly green on an unexercised hazard.
//   * testWritableStoreAnswersFromLoadedState — make V2Store.scanEdges stream on a read-write
//     store too; it then answers from the last commit instead of from the in-memory slot arrays.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.store.v2.V2Store;
import toolkit.test.Check;
import toolkit.util.Conf;

import javax.xml.namespace.QName;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestScanEdges {

    /// 300 resolved + 300 unresolved rows; at one pair per block that is 300 blocks of each.
    private static final int N = 600;

    // ---------------------------------------------------------------- the fixture

    /// One store, built once per test method, each in its own temp directory. blockPairs is
    /// process-wide state, so it is set and restored around the whole of the build AND the reads:
    /// the value is read per V2Store instance at construction, and reading blocks written under one
    /// chunking with another would be a different defect
    /// from the one under test.
    private static Path build() throws Exception {
        Path dir = Files.createTempDirectory("toolkit-scanedges-").resolve("db");
        try (Store store = Stores.create(dir, Stores.V2)) {
            try (Txn txn = store.rwTxn()) {
                List<Object> objs = new ArrayList<>();
                for (int i = 0; i < N; i++) objs.add(psa(i));
                store.insertAnyObjects(txn, objs);
                txn.commit();
            }
            // Half the targets arrive, so resolve() moves half the rows from kind 1 to kind 0 and
            // the 50 % dead fraction compacts kind 1 clean. Both kinds end up 300 tombstone-free
            // rows, which is what makes the slot/block correspondence below exact.
            try (Txn txn = store.rwTxn()) {
                List<Object> objs = new ArrayList<>();
                for (int i = 0; i < N; i += 2) objs.add(quay(i));
                store.insertAnyObjects(txn, objs);
                txn.commit();
            }
            Check.equals((long) N / 2, store.resolve(), "resolve wires half the references");
        }
        return dir;
    }

    private static PassengerStopAssignment psa(int i) {
        PassengerStopAssignment psa = new PassengerStopAssignment();
        psa.setId("PSA:" + i);
        psa.setVersion("1");
        QuayRefStructure qr = new QuayRefStructure();
        qr.setRef("Q:" + i);
        qr.setVersion("1");
        psa.setQuayRef(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "QuayRef"), QuayRefStructure.class, qr));
        return psa;
    }

    private static Quay quay(int i) {
        Quay q = new Quay();
        q.setId("Q:" + i);
        q.setVersion("1");
        return q;
    }

    private interface Body {
        void run(Path dir) throws Exception;
    }

    /// Run `body` against a store built at one pair per block, then restore the property.
    private static void atOnePairPerBlock(Body body) throws Exception {
        Conf.override(Conf.V2_BLOCK_PAIRS, 1);
        try {
            body.run(build());
        } finally {
            // Back to whatever the suite runs at -- Runner sets 128 for the whole JVM.
            Conf.override(Conf.V2_BLOCK_PAIRS, 128);
        }
    }

    // ---------------------------------------------------------------- the tests

    /// A green here is only worth something if the store can tell numeric block order from cursor
    /// order, so that fact is asserted rather than assumed — `nx db check` makes the same
    /// refusal on a real store.
    @Test
    void testTheHazardIsExercised() throws Exception {
        atOnePairPerBlock(dir -> {
            try (Store store = Stores.open(dir, true)) {
                int[] s = ((V2Store) store).debugStats();
                Check.that(s[2] > 255, "kind-0 blocks past 255 (got " + s[2] + ")");
                Check.that(s[5] > 255, "kind-1 blocks past 255 (got " + s[5] + ")");
                Check.that(cursorOrderDiffers(s[2]),
                        "cursor order of " + s[2] + " block numbers is a DIFFERENT permutation");
                Check.equals(0, s[1], "no edge tombstones, so slot b lives in block b");
                Check.equals(0, s[4], "no unresolved tombstones");
            }
        });
    }

    /// The contract: same pairs, same order.
    @Test
    void testScanEdgesIsOrderIdenticalToIterEdges() throws Exception {
        atOnePairPerBlock(dir -> {
            try (Store store = Stores.open(dir, true); Txn tx = store.roTxn()) {
                // Streaming arm FIRST: on a read-only store scanEdges never calls
                // ensureEdgeState, so it cannot be answered out of state iterEdges materialised.
                List<long[]> scan = edges(store.scanEdges(tx));
                List<long[]> iter = edges(store.iterEdges(tx));
                Check.equals(N / 2, scan.size(), "scanEdges row count");
                Check.equals(iter.size(), scan.size(), "both edge readers agree on the count");
                for (int i = 0; i < scan.size(); i++) {
                    Check.that(scan.get(i)[0] == iter.get(i)[0] && scan.get(i)[1] == iter.get(i)[1],
                            "scanEdges[" + i + "] is iterEdges[" + i + "]: "
                                    + Arrays.toString(scan.get(i)) + " vs " + Arrays.toString(iter.get(i)));
                }
                // The negative control, from the correct sequence alone.
                Check.that(digest(scan) != digest(permuteIntoCursorOrder(scan)),
                        "NEGATIVE CONTROL: the same pairs in cursor block order digest differently");
            }
        });
    }

    /// The same contract for the unresolved rows, whose second column is an interned key.
    @Test
    void testScanUnresolvedIsOrderIdenticalToIterUnresolved() throws Exception {
        atOnePairPerBlock(dir -> {
            try (Store store = Stores.open(dir, true); Txn tx = store.roTxn()) {
                List<String> scan = rows(store.scanUnresolved(tx));
                List<String> iter = rows(store.iterUnresolved(tx));
                Check.equals(N / 2, scan.size(), "scanUnresolved row count");
                Check.equals(iter, scan, "scanUnresolved is iterUnresolved row for row, in order");
                Check.that(!scan.get(0).equals(scan.get(scan.size() - 1)),
                        "the rows are distinguishable, so equality above is not vacuous");
            }
        });
    }

    /// A READ-WRITE store delegates to iterEdges, because the in-memory slot arrays carry appends
    /// and tombstones the blocks do not hold until beforeCommit writes them. Identity still holds;
    /// there is simply nothing to defer, since a writable store loads eagerly.
    @Test
    void testWritableStoreAnswersFromLoadedState() throws Exception {
        atOnePairPerBlock(dir -> {
            try (Store store = Stores.open(dir, false)) {
                try (Txn tx = store.roTxn()) {
                    Check.equals(N / 2, edges(store.scanEdges(tx)).size(),
                            "writable store: scanEdges matches the loaded edge count");
                    Check.equals(N / 2, rows(store.scanUnresolved(tx)).size(),
                            "writable store: scanUnresolved matches the loaded unresolved count");
                }
                // One more unresolved row, uncommitted-then-committed, and both readers see it.
                try (Txn txn = store.rwTxn()) {
                    store.insertAnyObjects(txn, List.of(psa(N)));
                    txn.commit();
                }
                try (Txn tx = store.roTxn()) {
                    Check.equals(N / 2 + 1, rows(store.scanUnresolved(tx)).size(),
                            "writable store: scanUnresolved sees the row just written");
                }
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static List<long[]> edges(Iterable<long[]> it) {
        List<long[]> out = new ArrayList<>();
        for (long[] e : it) out.add(e);
        return out;
    }

    private static List<String> rows(Iterable<UnresolvedRow> it) {
        List<String> out = new ArrayList<>();
        for (UnresolvedRow r : it) {
            out.add(r.referrerFullKey() + ":" + (r.encodedTarget() == null ? "-"
                    : new String(r.encodedTarget(), StandardCharsets.ISO_8859_1)));
        }
        return out;
    }

    /// Order-sensitive rolling digest; two different orders of the same pairs must not collide.
    private static long digest(List<long[]> pairs) {
        long h = 0x9E3779B97F4A7C15L;
        for (long[] p : pairs) {
            h = (h ^ p[0]) * 0x100000001B3L;
            h = (h ^ p[1]) * 0x100000001B3L;
        }
        return h;
    }

    /// `0..n-1` sorted the way a byte-ordered map sorts their u32 LE encodings.
    private static int[] cursorOrder(int n) {
        Integer[] a = new Integer[n];
        for (int i = 0; i < n; i++) a[i] = i;
        Arrays.sort(a, (x, y) -> {
            for (int i = 0; i < 4; i++) {
                int c = Integer.compare((x >>> (8 * i)) & 0xFF, (y >>> (8 * i)) & 0xFF);
                if (c != 0) return c;
            }
            return 0;
        });
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = a[i];
        return out;
    }

    private static boolean cursorOrderDiffers(int n) {
        int[] c = cursorOrder(n);
        for (int i = 0; i < n; i++) if (c[i] != i) return true;
        return false;
    }

    /// At one pair per block, slot b IS block b, so this is what a cursor-order reader would emit.
    private static List<long[]> permuteIntoCursorOrder(List<long[]> pairs) {
        List<long[]> out = new ArrayList<>(pairs.size());
        for (int b : cursorOrder(pairs.size())) out.add(pairs.get(b));
        return out;
    }
}
