package toolkit.store;

// The store contract end to end, on beans rather than on a feed. Every other store test in this
// suite enters through TestStore.loadNetex, which wires most references at insert because the
// targets precede the referrer in the document. This file inserts objects before their targets, so
// the references land in _unresolved and only resolve() can wire them — the versioned reference
// through the exact-key tier, the version-less one through the id-only tier.

import noi.netex.model.Operator;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.TrainNumber;
import noi.netex.model.TrainNumberRefStructure;
import noi.netex.model.TrainNumberRefs_RelStructure;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.harness.Ensemble;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestStoreRoundTrip {

    /// SJ:1 -> Operator O:1 (versioned, tier 1) and -> TrainNumber TN:1 (version-less, tier 3).
    private static ServiceJourney sj() {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("SJ:1");
        sj.setVersion("1");
        sj.setName(Mls.of("test journey"));
        OperatorRefStructure op = new OperatorRefStructure();
        op.setRef("O:1");
        op.setVersion("1");
        sj.setOperatorRef(op);
        TrainNumberRefStructure tn = new TrainNumberRefStructure();
        tn.setRef("TN:1");
        sj.setTrainNumbers(new TrainNumberRefs_RelStructure().withTrainNumberRef(tn));
        return sj;
    }

    private static Operator operator() {
        Operator op = new Operator();
        op.setId("O:1");
        op.setVersion("1");
        op.setName(Mls.of("Op"));
        return op;
    }

    private static TrainNumber trainNumber() {
        TrainNumber tn = new TrainNumber();
        tn.setId("TN:1");
        tn.setVersion("1");
        return tn;
    }

    /// A populated, closed store: the journey is inserted first, so neither reference can be wired
    /// at insert time and both go through resolve(). Returns the path.
    private static Path seeded(TestStore ts, String name) throws Exception {
        Path dir = ts.tempPath(name);
        try (Store store = Stores.create(dir, ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(sj(), operator(), trainNumber()));
                txn.commit();
            }
            Check.equals(2L, store.resolve(), "resolve() wires the versioned AND the version-less ref");
        }
        return dir;
    }

    /// Before resolve(): two unresolved rows and no edges. resolveEmbeddings then has nothing to do.
    @Test
    void testAReferenceInsertedBeforeItsTargetWaitsInUnresolved(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("pre.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(sj(), operator(), trainNumber()));
                txn.commit();
            }
            try (Txn txn = store.roTxn()) {
                Check.equals(2, count(store.iterUnresolved(txn)), "both references are unresolved");
                Check.equals(0, count(store.iterEdges(txn)), "and there are no edges yet");
            }
            Check.equals(2L, store.resolve(), "resolve() reports both");
            Check.equals(0L, store.resolveEmbeddings(),
                    "there is nothing embedded, so resolveEmbeddings is a no-op");
        }
    }

    /// The mirror image, and the case a loaded feed almost always is: the targets are already in
    /// the id index when the referrer arrives, so insertAnyObjects wires the edges itself and
    /// resolve() has nothing left to do.
    @Test
    void testAReferenceInsertedAfterItsTargetIsWiredImmediately(TestStore ts) throws Exception {
        ServiceJourney sj = sj();
        sj.setTrainNumbers(null); // the exact-key arm only: a version-less ref has no key to hit
        try (Store store = Stores.create(ts.tempPath("post.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(operator(), sj));
                txn.commit();
            }
            try (Txn txn = store.roTxn()) {
                Check.equals(0, count(store.iterUnresolved(txn)),
                        "nothing waited: the id index already held the target");
                Check.equals(1, count(store.iterEdges(txn)), "the edge exists before any resolve()");
            }
            Check.equals(0L, store.resolve(), "so resolve() is a no-op");
        }
    }

    /// After a reopen: the edges, the id index and the fragment blob are all on disk, and the
    /// targets are the two objects the journey actually names.
    @Test
    void testTheWiredGraphSurvivesAReopen(TestStore ts) throws Exception {
        Path dir = seeded(ts, "rt.lmdb");
        try (Store store = Stores.open(dir, true); Txn txn = store.roTxn()) {
            Check.that(store.formatMarker() != null, "the reopened store declares its format");
            Check.equals(0, count(store.iterUnresolved(txn)), "nothing is left unresolved");

            long sjFk = store.lookupFullKey(txn, "SJ:1", "1", ServiceJourney.class);
            long opFk = store.lookupFullKey(txn, "O:1", "1", Operator.class);
            long tnFk = store.lookupFullKey(txn, "TN:1", null, TrainNumber.class);
            Check.that(sjFk != -1 && opFk != -1 && tnFk != -1,
                    "all three ids are in the id index (" + sjFk + "," + opFk + "," + tnFk + ")");

            Set<Long> targets = new HashSet<>();
            for (long[] e : store.iterEdges(txn)) {
                Check.equals(sjFk, e[0], "the journey is the referrer of every edge");
                targets.add(e[1]);
            }
            Check.equals(Set.of(opFk, tnFk), targets, "the targets are the Operator and the TrainNumber");

            Object loaded = store.loadObjectByFullKey(txn, sjFk);
            Check.that(loaded instanceof ServiceJourney l && "SJ:1".equals(l.getId())
                            && "test journey".equals(Mls.text(l.getName())),
                    "the fragment blob decodes back to the journey it was built from");
        }
    }

    /// dbNames is the set of class maps that exist, and each of them holds its one row.
    @Test
    void testEveryInsertedClassHasItsOwnMap(TestStore ts) throws Exception {
        Path dir = seeded(ts, "maps.lmdb");
        try (Store store = Stores.open(dir, true); Txn txn = store.roTxn()) {
            Check.that(store.dbNames(txn).containsAll(
                            List.of(ServiceJourney.class, Operator.class, TrainNumber.class)),
                    "dbNames sees all three class maps, got " + store.dbNames(txn));
            Check.equals(1, count(store.iterObjects(txn, ServiceJourney.class)), "one journey row");
            Check.equals(1, count(store.scanObjects(txn, Operator.class)),
                    "and the raw-blob reader agrees for the Operator");
        }
    }

    /// cloneFrom into a fresh, empty, same-format store: the edges, the blobs, the id index and the
    /// local-key sequence all come across.
    @Test
    void testACloneCarriesTheEdgesTheBlobsAndTheSequence(TestStore ts) throws Exception {
        Path srcDir = seeded(ts, "clone-src.lmdb");
        Path dstDir = ts.tempPath("clone-dst.lmdb");
        try (Store src = Stores.open(srcDir, true); Store dst = Stores.create(dstDir, ts.format)) {
            long srcSeq;
            try (Txn stx = src.roTxn()) {
                srcSeq = src.sequence(stx, 0);
            }
            try (Txn stx = src.roTxn(); Txn dtx = dst.rwTxn()) {
                Check.that(dst.cloneFrom(src, stx, dtx, null, 0) >= 0,
                        "cloneFrom returns the largest local key it wrote");
                dtx.commit();
            }
            try (Txn txn = dst.roTxn()) {
                Check.equals(2, count(dst.iterEdges(txn)), "the clone carries both edges");
                long fk = dst.lookupFullKey(txn, "SJ:1", "1", ServiceJourney.class);
                Check.that(fk != -1 && dst.loadObjectByFullKey(txn, fk) instanceof ServiceJourney,
                        "…and the blobs and the id index with them");
                Check.that(dst.sequence(txn, 0) >= srcSeq - 1,
                        "the clone's local-key sequence carries the source's high-water mark ("
                                + dst.sequence(txn, 0) + " vs " + srcSeq + ")");
            }
        }
    }

    /// An Ensemble streaming pass re-inserting the objects it mutated. The overwrite must replace
    /// the row rather than add one, and re-extracting the references must leave the edge count
    /// where it was — which is what makes an in-place phase re-runnable.
    @Test
    void testAnEnsembleOverwriteKeepsOneRowAndRewiresIt(TestStore ts) throws Exception {
        Path dir = seeded(ts, "ensemble.lmdb");
        try (Store store = Stores.open(dir, false)) {
            try (Txn txn = store.rwTxn()) {
                Map<Class<?>, List<Ensemble.Operation>> ops = Map.of(
                        ServiceJourney.class, List.of(obj -> {
                            Mls.setText(((ServiceJourney) obj).getName(), "renamed");
                            return true;
                        }));
                store.insertAnyObjects(txn, Ensemble.applyOperations(store, txn, ops));
                txn.commit();
            }
            store.resolve(); // the overwrite re-extracted the refs; wire them again
            try (Txn txn = store.roTxn()) {
                long fk = store.lookupFullKey(txn, "SJ:1", "1", ServiceJourney.class);
                ServiceJourney l = (ServiceJourney) store.loadObjectByFullKey(txn, fk);
                Check.equals("renamed", Mls.text(l.getName()), "the operation's edit was committed");
                Check.equals(1, count(store.iterObjects(txn, ServiceJourney.class)),
                        "the overwrite replaced the row rather than adding one");
                Check.equals(2, count(store.iterEdges(txn)), "and both edges are wired again");
            }
        }
    }

    /// A Txn addresses one store's environment, so another store's txn is refused by identity. The
    /// alternative is reading this store's request against that store's maps, which returns rows
    /// rather than an error.
    @Test
    void testAForeignTransactionIsRefused(TestStore ts) throws Exception {
        try (Store store = Stores.create(ts.tempPath("guard-a.lmdb"), ts.format);
             Store other = Stores.create(ts.tempPath("guard-b.lmdb"), ts.format)) {
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(operator()));
                txn.commit();
            }
            try (Txn otherTxn = other.roTxn()) {
                try {
                    store.iterOnlyObjects(otherTxn, Operator.class).iterator().hasNext();
                    throw new AssertionError("a foreign transaction was accepted");
                } catch (IllegalArgumentException expected) {
                }
            }
        }
    }

    private static int count(Iterable<?> it) {
        int n = 0;
        for (Object ignored : it) n++;
        return n;
    }
}
