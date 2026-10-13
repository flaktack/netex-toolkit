package toolkit.store;

// Embedded ids more than one container claims, counted off the embedding index.
//
// An embedded object has no row of its own, so the id index cannot see it and nothing keyed on
// (id, version, class) rows ever asks whether two containers claim one. resolveEmbeddings picks a
// winner silently; this counts the question instead of answering it.
//
// Two properties of the index decide how it has to be read, and both are pinned below: it is keyed
// (id, version, class) while the resolve selects by id prefix alone, and nothing ever deletes from
// it, so a row may name a container that is gone.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.StopPlace;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.test.Check;
import toolkit.test.TestStore;

import javax.xml.namespace.QName;
import java.util.List;

public class TestEmbeddedDuplicateIds {

    /// A StopPlace carrying one inline Quay, at the given versions.
    private static StopPlace stopPlace(String spId, String spVersion, String quayId,
            String quayVersion) {
        Quay quay = new Quay();
        quay.setId(quayId);
        quay.setVersion(quayVersion);
        StopPlace sp = new StopPlace();
        sp.setId(spId);
        sp.setVersion(spVersion);
        Quays_RelStructure quays = new Quays_RelStructure();
        quays.getQuayRefOrQuay().add(new JAXBElement<>(
                new QName(ModelRegistry.NETEX_NS, "Quay"), Quay.class, quay));
        sp.setQuays(quays);
        return sp;
    }

    private static long duplicatesOf(Store store) {
        try (Txn txn = store.roTxn()) {
            return store.duplicateEmbeddedIds(txn, 3).ids();
        }
    }

    private static void insert(Store store, List<StopPlace> stops) {
        try (Txn txn = store.rwTxn()) {
            store.insertAnyObjects(txn, List.copyOf(stops));
            txn.commit();
        }
    }

    /// The shape two publishers of one national stop register ship: both describe the stop, both
    /// name its platform the same, and only the containers were ever separable by the id index.
    @Test
    void testOneQuayIdUnderTwoStopPlacesIsCounted(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("dup-emb.lmdb"), ts.format)) {
            insert(store, List.of(stopPlace("SP:a", "1", "Q:shared", "1"),
                    stopPlace("SP:b", "1", "Q:shared", "1")));

            Check.equals(1L, duplicatesOf(store), "one id is claimed by two containers");
            try (Txn txn = store.roTxn()) {
                List<String> ex = store.duplicateEmbeddedIds(txn, 3).examples();
                Check.equals(List.of("Q:shared"), ex, "named for the message: " + ex);
            }
        }
    }

    @Test
    void testDistinctQuayIdsCountNothing(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("dup-none.lmdb"), ts.format)) {
            insert(store, List.of(stopPlace("SP:a", "1", "Q:a", "1"),
                    stopPlace("SP:b", "1", "Q:b", "1")));
            Check.equals(0L, duplicatesOf(store), "distinct embedded ids are not a collision");
        }
    }

    /// One id at two versions is two index keys and ONE id to the resolve, which selects containers
    /// by id prefix without reading a version. Counting per key would report this as no collision
    /// at all while a version-less reference still binds to an arbitrary one of the two.
    @Test
    void testOneIdAtTwoVersionsUnderTwoContainersIsOneCollision(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("dup-ver.lmdb"), ts.format)) {
            insert(store, List.of(stopPlace("SP:a", "1", "Q:shared", "1"),
                    stopPlace("SP:b", "1", "Q:shared", "2")));
            Check.equals(1L, duplicatesOf(store),
                    "the two versions are one id, as resolveEmbeddings reads it");
        }
    }

    /// The bound on what the existence probe buys. Nothing deletes an embedding row, so replacing a
    /// container leaves its rows naming a container that no longer holds the child -- and the probe
    /// cannot tell, because the row it checks is still there. Separating those would mean decoding
    /// the container, which is what resolveEmbeddings pays and this does not.
    ///
    /// So the count is an upper bound over rewritten containers and exact over purged ones. Pinned
    /// here rather than left to be discovered.
    @Test
    void testARewrittenContainerStillCountsBecauseItsRowIsLive(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("dup-stale.lmdb"), ts.format)) {
            insert(store, List.of(stopPlace("SP:a", "1", "Q:shared", "1"),
                    stopPlace("SP:b", "1", "Q:shared", "1")));
            Check.equals(1L, duplicatesOf(store), "two containers to begin with");

            // SP:b keeps its (id, version) and stops carrying the Quay. Its old embedding row
            // survives; the container it names is a different object now.
            StopPlace bare = new StopPlace();
            bare.setId("SP:b");
            bare.setVersion("1");
            insert(store, List.of(bare));

            Check.equals(1L, duplicatesOf(store),
                    "the replaced container is still a live row, so the stale claim still counts");
        }
    }

    /// The same, where the container row is gone rather than rewritten: a row naming nothing must
    /// not hold a collision open.
    @Test
    void testARowNamingAPurgedContainerIsNotCounted(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("dup-purge.lmdb"), ts.format)) {
            insert(store, List.of(stopPlace("SP:a", "1", "Q:shared", "1"),
                    stopPlace("SP:b", "1", "Q:shared", "1")));
            try (Txn txn = store.rwTxn()) {
                // empty=true purges the class map and re-inserts, so SP:b's row goes while every
                // embedding row it fed stays.
                store.insertObjects(txn, StopPlace.class,
                        List.<Object>of(stopPlace("SP:a", "1", "Q:shared", "1")).iterator(), true);
                txn.commit();
            }
            Check.equals(0L, duplicatesOf(store),
                    "one container left holding the id, so there is nothing to pick between");
        }
    }
}
