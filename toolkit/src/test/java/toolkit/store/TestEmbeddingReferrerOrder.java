package toolkit.store;

// The order in which resolveEmbeddings drains the referrers of one embedded target. Order is
// load-bearing, and this is the half of that contract a representation change can break without
// breaking anything else. Rows arrive from AbstractLmdbStore.sortedUnresolvedRows sorted by
// (referrer full_key unsigned, encoded target), and the per-target referrer set preserves that
// arrival order; matchContainer then walks the set writing one edge per referrer, so the set's
// iteration order is the order edges land in `edgeSrc`, and iterEdges yields slot order.
//
// The set holds primitive longs (LongLinkedOpenHashSet) rather than boxed ones: on a converted
// national corpus it holds 83,894,176 referrers. The LINKED variant is the load-bearing part — a
// plain LongOpenHashSet passes everything else here while the edges come out in hash order.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.StopPlace;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.test.Check;
import toolkit.test.TestStore;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;

public class TestEmbeddingReferrerOrder {

    private static final int REFERRERS = 12;

    private static StopPlace stopPlaceWithQuay() {
        Quay quay = new Quay();
        quay.setId("Q:1"); // version-less, matching the refs below
        StopPlace sp = new StopPlace();
        sp.setId("SP:1");
        sp.setVersion("1");
        Quays_RelStructure quays = new Quays_RelStructure();
        quays.getQuayRefOrQuay().add(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "Quay"), Quay.class, quay));
        sp.setQuays(quays);
        return sp;
    }

    private static PassengerStopAssignment psaReferencingQuay(int n) {
        PassengerStopAssignment psa = new PassengerStopAssignment();
        psa.setId("PSA:" + n);
        psa.setVersion("1");
        QuayRefStructure qr = new QuayRefStructure();
        qr.setRef("Q:1");
        psa.setQuayRef(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "QuayRef"), QuayRefStructure.class, qr));
        return psa;
    }

    /// Twelve referrers onto one embedded Quay. Twelve is not decoration: with three or four, a
    /// hash-ordered set can come out ascending by luck.
    @Test
    void testReferrersAreDrainedInSortedRowOrder(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("order.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(stopPlaceWithQuay()));
                for (int i = 1; i <= REFERRERS; i++) {
                    store.insertAnyObjects(txn, List.of(psaReferencingQuay(i)));
                }
                txn.commit();
            }

            Check.equals(0L, store.resolve(),
                    "resolve() cannot see an embedded object: it is not a row in the id index");
            // One rather than REFERRERS: the return counts matched target keys, and all twelve
            // referrers name the same Q:1, so they are one resolution draining a twelve-deep
            // referrer set.
            Check.equals(1L, store.resolveEmbeddings(), "one target key resolved");

            try (Txn txn = store.roTxn()) {
                long spFk = store.lookupFullKey(txn, "SP:1", "1", StopPlace.class);

                // What sortedUnresolvedRows would have produced: ascending referrer full_key, built
                // from the store rather than assumed from insert order.
                List<Long> ascending = new ArrayList<>();
                for (int i = 1; i <= REFERRERS; i++) {
                    ascending.add(store.lookupFullKey(
                            txn, "PSA:" + i, "1", PassengerStopAssignment.class));
                }
                ascending.sort(Long::compareUnsigned);

                List<String> expected = new ArrayList<>();
                for (long fk : ascending) expected.add(fk + "->" + spFk);

                List<String> actual = new ArrayList<>();
                for (long[] e : store.iterEdges(txn)) actual.add(e[0] + "->" + e[1]);

                Check.equals(expected, actual,
                        "edges land in sorted-row order, one per referrer, all onto the CONTAINER");
                Check.equals(0, count(store.iterUnresolved(txn)),
                        "and every row was drained, not just the first of the bucket");
            }
        }
    }

    private static int count(Iterable<?> it) {
        int n = 0;
        for (Object ignored : it) n++;
        return n;
    }
}
