package toolkit.store;

// What an embedding edge points at.
//
// A NeTEx feed may carry a Quay inline inside its StopPlace and then reference it by id from a
// PassengerStopAssignment. The referenced object therefore HAS an id but has no store row of its
// own — only its container does — so resolve() cannot see it, and the reference would sit in
// _unresolved forever. resolveEmbeddings() is the second pass that wires it, and it wires the
// referrer to the CONTAINER, because the container is the only thing addressable by a full key.
//
// The suite covers this mechanism's structure — which classes are in scope (TestEmbeddingScope),
// that index rows follow the injected scope (TestEmbeddingScopeInjection), how an absent index is
// distinguished from an unreadable one (TestEmbeddingIndexOpen) — and nowhere its BEHAVIOUR: that
// resolve() returns 0 here, that resolveEmbeddings() returns 1, or which end of the edge the
// container is on. An implementation that pointed the edge at the embedded object's synthesized key
// instead would leave every structural test green and produce edges into rows that do not exist.

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

public class TestEmbeddingResolve {

    /// SP:1 carrying an inline, version-less Quay Q:1 behind the quays rel-structure.
    private static StopPlace stopPlaceWithQuay() {
        Quay quay = new Quay();
        quay.setId("Q:1"); // no version, matching the version-less QuayRef below
        StopPlace sp = new StopPlace();
        sp.setId("SP:1");
        sp.setVersion("1");
        Quays_RelStructure quays = new Quays_RelStructure();
        quays.getQuayRefOrQuay().add(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "Quay"), Quay.class, quay));
        sp.setQuays(quays);
        return sp;
    }

    private static PassengerStopAssignment psaReferencingQuay() {
        PassengerStopAssignment psa = new PassengerStopAssignment();
        psa.setId("PSA:1");
        psa.setVersion("1");
        QuayRefStructure qr = new QuayRefStructure();
        qr.setRef("Q:1");
        psa.setQuayRef(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "QuayRef"), QuayRefStructure.class, qr));
        return psa;
    }

    @Test
    void testTheEmbeddedTargetIsWiredToItsContainer(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("emb.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(stopPlaceWithQuay(), psaReferencingQuay()));
                txn.commit();
            }

            Check.equals(0L, store.resolve(),
                    "resolve() cannot see an embedded object: it is not a row in the id index");
            Check.equals(1L, store.resolveEmbeddings(), "resolveEmbeddings() is what wires it");

            try (Txn txn = store.roTxn()) {
                long spFk = store.lookupFullKey(txn, "SP:1", "1", StopPlace.class);
                long psaFk = store.lookupFullKey(txn, "PSA:1", "1", PassengerStopAssignment.class);
                Check.that(spFk != -1 && psaFk != -1, "both top-level objects are in the id index");
                Check.that(!store.dbNames(txn).contains(Quay.class),
                        "the embedded Quay has no class map: it is not a row, which is the whole "
                                + "reason this second pass exists");

                List<String> edges = new ArrayList<>();
                for (long[] e : store.iterEdges(txn)) edges.add(e[0] + "->" + e[1]);
                Check.equals(List.of(psaFk + "->" + spFk), edges,
                        "the one edge runs PSA -> StopPlace, i.e. to the CONTAINER");
                Check.equals(0, count(store.iterUnresolved(txn)),
                        "and nothing is left waiting in _unresolved");
            }
        }
    }

    /// Nothing left to do the second time: the pass is idempotent, not additive.
    @Test
    void testASecondPassWiresNothingFurther(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("emb2.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(stopPlaceWithQuay(), psaReferencingQuay()));
                txn.commit();
            }
            store.resolve();
            Check.equals(1L, store.resolveEmbeddings(), "the first pass wires the reference");
            Check.equals(0L, store.resolveEmbeddings(), "the second finds nothing left");
            try (Txn txn = store.roTxn()) {
                Check.equals(1, count(store.iterEdges(txn)), "and did not duplicate the edge");
            }
        }
    }

    private static int count(Iterable<?> it) {
        int n = 0;
        for (Object ignored : it) n++;
        return n;
    }
}
