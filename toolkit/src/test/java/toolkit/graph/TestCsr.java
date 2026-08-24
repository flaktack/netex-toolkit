package toolkit.graph;

// THE GRAPH READER NOTHING ELSE IN THE SUITE TOUCHED.
//
// Csr is what `nx db` walks references with and what Store.csr(txn) hands out. The rest of the
// suite exercises edges only through iterEdges/scanEdges, which is the FLAT pair sequence, never
// the adjacency built from it.
//
// The two things a CSR build can get wrong and still look plausible:
//
//   * DEDUP. Csr.build collapses exact duplicate pairs, because the edge map is DUPSORT and a pair
//     stored twice is one row. A build that kept both would leave `e` and every out()/in() list
//     silently multi-valued, which no count-based assertion downstream would notice.
//   * THE INVERSE. fwdKeys is a copy of the co-sorted dst array, so the forward direction is
//     nearly free and nearly impossible to get wrong; invKeys is a separate counting sort by
//     target, and that is the half that carries the risk. in() is asserted against brute force at
//     every node, not just at the one node a hand-picked example would reach.
//
// Csr.build is DESTRUCTIVE on its input arrays (it sorts and compacts in place), so every case
// below hands it a fresh copy — the brute-force ground truth is computed from the originals first.

import noi.netex.model.Line;
import noi.netex.model.Route;
import noi.netex.model.ServiceJourneyPattern;
import org.junit.jupiter.api.Test;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TestCsr {

    /// The toy graph, with the duplicate pair (1,2) appearing twice in a deliberately mixed order.
    private static final long[][] TOY = {{3, 1}, {1, 2}, {1, 3}, {2, 3}, {1, 2}};

    @Test
    void testDuplicatePairsCollapseAsTheEdgeMapWould() {
        Csr csr = build(TOY);
        Check.equals(4, csr.e, "five pairs with one duplicate build four edges");
        Check.equals(Set.of(2L, 3L), setOf(csr.out(1)), "out(1) lists the duplicate target once");
    }

    @Test
    void testForwardAndInverseAgreeWithBruteForceAtEveryNode() {
        Csr csr = build(TOY);
        Map<Long, Set<Long>> outs = new HashMap<>();
        Map<Long, Set<Long>> ins = new HashMap<>();
        for (long[] p : TOY) {
            outs.computeIfAbsent(p[0], k -> new HashSet<>()).add(p[1]);
            ins.computeIfAbsent(p[1], k -> new HashSet<>()).add(p[0]);
        }
        for (long node : csr.nodes) {
            Check.equals(outs.getOrDefault(node, Set.of()), setOf(csr.out(node)),
                    "out(" + node + ") is the brute-force forward neighbourhood");
            Check.equals(ins.getOrDefault(node, Set.of()), setOf(csr.in(node)),
                    "in(" + node + ") is the brute-force inverse neighbourhood");
        }
        Check.equals(3, csr.nodes.length, "the node table is the distinct endpoints, deduped");
    }

    /// Input order must not matter — build co-sorts. The same edges shuffled by a fixed permutation
    /// have to produce the same adjacency, which is what lets a caller pass the store's blocks in
    /// whatever order the reader hands them over.
    @Test
    void testBuildAcceptsEdgesInAnyOrder() {
        long[][] shuffled = {{1, 2}, {2, 3}, {1, 2}, {1, 3}, {3, 1}};
        Csr a = build(TOY);
        Csr b = build(shuffled);
        Check.equals(a.e, b.e, "the same edge multiset builds the same edge count");
        for (long node : a.nodes) {
            Check.equals(setOf(a.out(node)), setOf(b.out(node)), "out(" + node + ") is order-independent");
            Check.equals(setOf(a.in(node)), setOf(b.in(node)), "in(" + node + ") is order-independent");
        }
    }

    /// A key with no edges is not in the node table at all; both directions answer empty, not null.
    @Test
    void testAKeyWithNoEdgesIsEmptyInBothDirections() {
        Csr csr = build(TOY);
        Check.equals(-1, csr.nodeIndex(99), "an absent key has no node index");
        Check.equals(0, csr.out(99).length, "out() of an absent key is empty");
        Check.equals(0, csr.in(99).length, "in() of an absent key is empty");
    }

    /// Store.csr(txn) over a real store: the adjacency must be exactly the store's own edge pairs.
    /// Built from iterEdges rather than from a hand-written expectation, so this stays true of any
    /// fixture; the concrete SJP -> Route -> Line assertions below keep it from being vacuous.
    @Test
    void testStoreCsrIsTheStoresEdgeSet(TestStore db) throws Exception {
        db.loadNetex("""
            <ServiceFrame id="sf" version="1">
              <lines>
                <Line id="l1" version="1"><Name>L</Name></Line>
              </lines>
              <routes>
                <Route id="r1" version="1"><LineRef ref="l1" version="1"/></Route>
              </routes>
              <journeyPatterns>
                <ServiceJourneyPattern id="sjp1" version="1"><RouteRef ref="r1" version="1"/></ServiceJourneyPattern>
              </journeyPatterns>
            </ServiceFrame>
            """);

        try (Txn tx = db.store.roTxn()) {
            Map<Long, Set<Long>> outs = new HashMap<>();
            Map<Long, Set<Long>> ins = new HashMap<>();
            int pairs = 0;
            for (long[] e : db.store.iterEdges(tx)) {
                outs.computeIfAbsent(e[0], k -> new HashSet<>()).add(e[1]);
                ins.computeIfAbsent(e[1], k -> new HashSet<>()).add(e[0]);
                pairs++;
            }
            Check.equals(2, pairs, "the fixture wires SJP -> Route and Route -> Line");

            Csr csr = db.store.csr(tx);
            for (Map.Entry<Long, Set<Long>> en : outs.entrySet()) {
                Check.equals(en.getValue(), setOf(csr.out(en.getKey())),
                        "csr.out agrees with iterEdges at " + en.getKey());
            }
            for (Map.Entry<Long, Set<Long>> en : ins.entrySet()) {
                Check.equals(en.getValue(), setOf(csr.in(en.getKey())),
                        "csr.in agrees with iterEdges at " + en.getKey());
            }

            long sjp = db.store.lookupFullKey(tx, "sjp1", "1", ServiceJourneyPattern.class);
            long route = db.store.lookupFullKey(tx, "r1", "1", Route.class);
            long line = db.store.lookupFullKey(tx, "l1", "1", Line.class);
            Check.equals(Set.of(route), setOf(csr.out(sjp)), "the journey pattern points at the route");
            Check.equals(Set.of(line), setOf(csr.out(route)), "the route points at the line");
            Check.equals(Set.of(route), setOf(csr.in(line)), "and the line is reached FROM the route");
            Check.equals(0, csr.in(sjp).length, "nothing references the journey pattern");
        }
    }

    // ---------------------------------------------------------------- helpers

    /// A fresh pair of arrays every time: build sorts and compacts them in place.
    private static Csr build(long[][] pairs) {
        long[] src = new long[pairs.length];
        long[] dst = new long[pairs.length];
        for (int i = 0; i < pairs.length; i++) {
            src[i] = pairs[i][0];
            dst[i] = pairs[i][1];
        }
        return Csr.build(src, dst);
    }

    private static Set<Long> setOf(long[] xs) {
        Set<Long> out = new HashSet<>();
        for (long x : xs) out.add(x);
        return out;
    }
}
