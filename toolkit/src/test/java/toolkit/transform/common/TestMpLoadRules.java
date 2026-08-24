package toolkit.transform.common;

// Coverage for MpLoadRules.mergeStopPlaceQuays — the quay-union dedup — and its fragment-fidelity
// contract on the Store.setMergePolicy insert path.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.LocationStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import org.junit.jupiter.api.Test;
import toolkit.codec.FragmentCodec;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.store.WithFragment;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TestMpLoadRules {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// Pure-function branches: union + id-sorted append + file-order independence.
    @Test
    void testQuayUnionAppendsSortedAndOrderIndependent() {
        StopPlace stored = sp("SP:1", quay("Q:C", null), quay("Q:A", null));
        StopPlace incoming = sp("SP:1", quay("Q:B", null));
        StopPlace merged = (StopPlace) MpLoadRules.mergeStopPlaceQuays(stored, incoming);
        Check.that(merged != null, "stored copy adds quays -> non-null merge");
        Check.equals(List.of("Q:B", "Q:A", "Q:C"), quayIds(merged),
                "incoming quays first, stored-only quays appended sorted by id");

        // order independence: swapping which copy arrives first yields the same quay SET
        StopPlace merged2 = (StopPlace) MpLoadRules.mergeStopPlaceQuays(
                sp("SP:1", quay("Q:B", null)), sp("SP:1", quay("Q:C", null), quay("Q:A", null)));
        Check.that(merged2 != null, "reverse arrival also merges");
        Check.equals(List.of("Q:C", "Q:A", "Q:B"), quayIds(merged2),
                "reverse arrival: same union, incoming-first layout");
    }

    /// Centroid backfill: an incoming quay without a located centroid adopts the stored one.
    @Test
    void testCentroidBackfill() {
        StopPlace stored = sp("SP:1", quay("Q:A", new BigDecimal("46.5")));
        StopPlace incoming = sp("SP:1", quay("Q:A", null));
        StopPlace merged = (StopPlace) MpLoadRules.mergeStopPlaceQuays(stored, incoming);
        Check.that(merged != null, "centroid adoption alone is a change");
        Quay q = (Quay) merged.getQuays().getQuayRefOrQuay().get(0).getValue();
        Check.equals(new BigDecimal("46.5"), q.getCentroid().getLocation().getLatitude(),
                "incoming quay adopted the stored centroid");
    }

    /// The null-return "stored adds nothing" branch.
    @Test
    void testNoOpMergeReturnsNull() {
        StopPlace stored = sp("SP:1", quay("Q:A", new BigDecimal("46.5")));
        StopPlace incoming = sp("SP:1", quay("Q:A", new BigDecimal("46.5")), quay("Q:B", null));
        Check.isNull(MpLoadRules.mergeStopPlaceQuays(stored, incoming),
                "stored copy adds nothing -> null (plain replace)");
    }

    /// Fragment-fidelity contract on the insert path: a null merge keeps the incoming
    /// WithFragment's verbatim bytes; a non-null merge re-marshals (fragment lost).
    @Test
    void testMergePolicyFragmentFidelity(TestStore db) throws Exception {
        Store store = db.store;
        FragmentCodec codec = new FragmentCodec();
        store.setMergePolicy(StopPlace.class, MpLoadRules::mergeStopPlaceQuays);
        try {
            StopPlace first = sp("SP:1", quay("Q:A", null));
            byte[] frag1 = codec.marshalFragment(first);
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(new WithFragment(first, frag1)));
                txn.commit();
            }
            // identical copy -> merge returns null -> the INCOMING verbatim fragment is stored
            StopPlace same = sp("SP:1", quay("Q:A", null));
            byte[] frag2 = ("<!-- verbatim marker -->" + new String(codec.marshalFragment(same),
                    StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(new WithFragment(same, frag2)));
                txn.commit();
            }
            Check.equals(new String(frag2, StandardCharsets.UTF_8), rawFragment(store, codec),
                    "null merge keeps the incoming verbatim fragment");
            // copy with a NEW quay -> non-null merge -> re-marshal (verbatim marker gone)
            StopPlace more = sp("SP:1", quay("Q:B", null));
            byte[] frag3 = ("<!-- another marker -->" + new String(codec.marshalFragment(more),
                    StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            try (Txn txn = store.rwTxn()) {
                store.insertAnyObjects(txn, List.of(new WithFragment(more, frag3)));
                txn.commit();
            }
            String stored = rawFragment(store, codec);
            Check.that(!stored.contains("another marker"), "non-null merge re-marshals (fragment lost)");
            try (Txn tx = store.roTxn()) {
                StopPlace merged = (StopPlace) store.loadObjectByFullKey(tx,
                        store.lookupFullKey(tx, "SP:1", "1", StopPlace.class));
                Check.equals(List.of("Q:B", "Q:A"), quayIds(merged), "merged blob has the quay union");
            }
        } finally {
            store.setMergePolicy(StopPlace.class, null);
        }
    }

    /// The blob and the reference index must describe the SAME object after a merge.
    ///
    /// They did not. `insertOne` cleared the stored copy's ref rows, ran the extraction walk on the
    /// INCOMING copy, and only then merged — so the blob became the quay union while the
    /// edge/unresolved index described the incoming copy alone. Every quay contributed by the
    /// stored copy was in the blob with its outgoing references missing from the index, and
    /// unreachable to `resolve()`.
    ///
    /// This is the load shape of the drivers that register this policy and see the same StopPlace
    /// arrive in a stop-offer member and again in a line file.
    @Test
    void testMergedObjectIsWhatGetsIndexed(TestStore db) throws Exception {
        Store store = db.store;
        store.setMergePolicy(StopPlace.class, MpLoadRules::mergeStopPlaceQuays);
        try {
            insert(store, sp("SP:1", quayWithLevel("Q:A", "L:A")));
            insert(store, sp("SP:1", quayWithLevel("Q:B", "L:B")));

            try (Txn tx = store.roTxn()) {
                long fk = store.lookupFullKey(tx, "SP:1", "1", StopPlace.class);
                StopPlace merged = (StopPlace) store.loadObjectByFullKey(tx, fk);
                Check.equals(List.of("Q:B", "Q:A"), quayIds(merged), "precondition: the blob is the union");

                List<String> targets = new ArrayList<>();
                for (toolkit.store.UnresolvedRow row : store.iterUnresolved(tx)) {
                    if (row.referrerFullKey() != fk) continue;
                    targets.add(toolkit.keycodec.NulKeyCodec.idPart(row.encodedTarget()));
                }
                java.util.Collections.sort(targets);
                Check.equals(List.of("L:A", "L:B"), targets,
                        "the index must carry BOTH copies' quay references, because the blob "
                                + "carries both quays — the stored copy's L:A used to be dropped "
                                + "by clearRefRows and never re-extracted");
            }
        } finally {
            store.setMergePolicy(StopPlace.class, null);
        }
    }

    /// The two halves of the policy's LIFECYCLE, neither of which the tests above can see: they
    /// both register the policy for their whole duration and assert only on the merged blob.
    ///
    /// A merge is an OVERWRITE of the existing (id, version, class) row. If it appended instead,
    /// every assertion above would still hold — lookupFullKey would find one of the two rows and it
    /// would be the merged one — while the store grew a duplicate per re-arrival of the same
    /// StopPlace, which for the two drivers carrying 10 of the 12 national feeds is every stop in
    /// every line file.
    ///
    /// And setMergePolicy(clazz, null) has to put the store back to plain last-wins replacement.
    /// The clearing call appears in this file only in `finally` blocks, where nothing follows it,
    /// so a store that kept merging after it was cleared would have gone unnoticed here and would
    /// silently union across the boundary a driver draws between two loads. Ported from the retired
    /// toolkit.smoke.Smoke.
    @Test
    void testTheMergeIsAnOverwriteAndClearingThePolicyResumesLastWins(TestStore db)
            throws Exception {
        Store store = db.store;
        insert(store, sp("SP:1", quay("Q:A", null)));
        store.setMergePolicy(StopPlace.class, MpLoadRules::mergeStopPlaceQuays);
        try {
            insert(store, sp("SP:1", quay("Q:B", null)));
            try (Txn tx = store.roTxn()) {
                Check.equals(List.of("Q:B", "Q:A"), quayIds(loaded(store, tx)),
                        "precondition: the policy unioned the two copies");
                Check.equals(1, rowCount(store, tx),
                        "the merge REPLACED the row — a duplicate here is a store that grows per "
                                + "re-arrival of the same stop");
            }
        } finally {
            store.setMergePolicy(StopPlace.class, null);
        }

        insert(store, sp("SP:1", quay("Q:C", null)));
        try (Txn tx = store.roTxn()) {
            Check.equals(List.of("Q:C"), quayIds(loaded(store, tx)),
                    "with the policy cleared the insert is a plain last-wins replace, not a union");
            Check.equals(1, rowCount(store, tx), "and still one row");
        }
    }

    // ---------------------------------------------------------------- helpers

    static StopPlace loaded(Store store, Txn tx) {
        return (StopPlace) store.loadObjectByFullKey(tx,
                store.lookupFullKey(tx, "SP:1", "1", StopPlace.class));
    }

    static int rowCount(Store store, Txn tx) {
        int n = 0;
        for (Object ignored : store.iterObjects(tx, StopPlace.class)) n++;
        return n;
    }

    static void insert(Store store, StopPlace sp) {
        try (Txn txn = store.rwTxn()) {
            store.insertAnyObjects(txn, List.of(sp));
            txn.commit();
        }
    }

    /// A quay carrying one outward reference, so the extraction walk has something to record.
    static Quay quayWithLevel(String id, String levelRef) {
        Quay q = quay(id, null);
        noi.netex.model.LevelRefStructure ref = new noi.netex.model.LevelRefStructure();
        ref.setRef(levelRef);
        ref.setVersion("1");
        q.setLevelRef(ref);
        return q;
    }

    static String rawFragment(Store store, FragmentCodec codec) {
        try (Txn tx = store.roTxn()) {
            long fk = store.lookupFullKey(tx, "SP:1", "1", StopPlace.class);
            byte[] blob = store.loadRawObject(tx, StopPlace.class,
                    toolkit.keycodec.NulKeyCodec.u32le(toolkit.keycodec.NulKeyCodec.fullKeyLocal(fk)));
            return new String(codec.decompress(blob), StandardCharsets.UTF_8);
        }
    }

    static List<String> quayIds(StopPlace sp) {
        List<String> ids = new ArrayList<>();
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            if (el.getValue() instanceof Quay q) ids.add(q.getId());
        }
        return ids;
    }

    static StopPlace sp(String id, Quay... quays) {
        StopPlace sp = new StopPlace();
        sp.setId(id);
        sp.setVersion("1");
        Quays_RelStructure rel = new Quays_RelStructure();
        for (Quay q : quays) rel.getQuayRefOrQuay().add(FACTORY.createQuay(q));
        sp.setQuays(rel);
        return sp;
    }

    static Quay quay(String id, BigDecimal lat) {
        Quay q = new Quay();
        q.setId(id);
        q.setVersion("1");
        if (lat != null) {
            LocationStructure loc = new LocationStructure();
            loc.setLatitude(lat);
            loc.setLongitude(new BigDecimal("11.3"));
            SimplePoint_VersionStructure centroid = new SimplePoint_VersionStructure();
            centroid.setLocation(loc);
            q.setCentroid(centroid);
        }
        return q;
    }
}
