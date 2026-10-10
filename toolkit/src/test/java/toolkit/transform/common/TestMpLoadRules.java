package toolkit.transform.common;

// Coverage for the MpLoadRules merge policies — mergeStopPlaceQuays, the quay-union dedup, and
// mergeValueSetValues, the code-list union — and the fragment-fidelity contract they share on the
// Store.setMergePolicy insert path.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.LocationStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.TypeOfProductCategory;
import noi.netex.model.TypeOfValue_VersionStructure;
import noi.netex.model.TypesOfValue_RelStructure;
import noi.netex.model.ValueSet;
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

    /// The two halves of the policy's LIFECYCLE. A merge is an OVERWRITE of the existing
    /// (id, version, class) row: appending instead would grow the store a duplicate per re-arrival
    /// of the same StopPlace, which for the two drivers carrying 10 of the 12 national feeds is
    /// every stop in every line file.
    ///
    /// And setMergePolicy(clazz, null) has to put the store back to plain last-wins replacement; a
    /// store that kept merging after the policy was cleared would silently union across the
    /// boundary a driver draws between two loads.
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

    // ------------------------------------------------- mergeValueSetValues

    /// Pure-function branches: union + id-sorted append + file-order independence.
    @Test
    void testValueSetUnionAppendsSortedAndOrderIndependent() {
        ValueSet stored = vs("VS:1", cat("C:REX"), cat("C:EC"));
        ValueSet incoming = vs("VS:1", cat("C:NJ"));
        ValueSet merged = (ValueSet) MpLoadRules.mergeValueSetValues(stored, incoming);
        Check.that(merged != null, "stored copy adds members -> non-null merge");
        Check.equals(List.of("C:NJ", "C:EC", "C:REX"), memberIds(merged),
                "incoming members first, stored-only members appended sorted by id");

        ValueSet merged2 = (ValueSet) MpLoadRules.mergeValueSetValues(
                vs("VS:1", cat("C:NJ")), vs("VS:1", cat("C:REX"), cat("C:EC")));
        Check.that(merged2 != null, "reverse arrival also merges");
        Check.equals(List.of("C:REX", "C:EC", "C:NJ"), memberIds(merged2),
                "reverse arrival: same union, incoming-first layout");
    }

    /// A ValueSet whose members the incoming copy already carries adds nothing.
    @Test
    void testValueSetNoOpMergeReturnsNull() {
        ValueSet stored = vs("VS:1", cat("C:EC"));
        ValueSet incoming = vs("VS:1", cat("C:EC"), cat("C:NJ"));
        Check.isNull(MpLoadRules.mergeValueSetValues(stored, incoming),
                "stored copy adds nothing -> null (plain replace)");
    }

    /// An incoming copy that declares no members at all still collects the stored copy's.
    @Test
    void testValueSetWithNoValuesContainerCollectsTheStoredMembers() {
        ValueSet stored = vs("VS:1", cat("C:EC"));
        ValueSet incoming = vs("VS:1");
        incoming.setValues(null);
        ValueSet merged = (ValueSet) MpLoadRules.mergeValueSetValues(stored, incoming);
        Check.that(merged != null, "an empty incoming copy merges");
        Check.equals(List.of("C:EC"), memberIds(merged), "the container is created and filled");
    }

    /// The union across a third arrival, which is the shape of a Mentz archive: one id in every
    /// member file, each file declaring the subset it uses.
    @Test
    void testValueSetUnionAccumulatesAcrossArrivals(TestStore db) throws Exception {
        Store store = db.store;
        store.setMergePolicy(ValueSet.class, MpLoadRules::mergeValueSetValues);
        try {
            insertAny(store, vs("VS:1", cat("C:REX")));
            insertAny(store, vs("VS:1", cat("C:EC")));
            insertAny(store, vs("VS:1", cat("C:NJ")));
            try (Txn tx = store.roTxn()) {
                ValueSet merged = (ValueSet) store.loadObjectByFullKey(tx,
                        store.lookupFullKey(tx, "VS:1", "1", ValueSet.class));
                List<String> ids = new ArrayList<>(memberIds(merged));
                java.util.Collections.sort(ids);
                Check.equals(List.of("C:EC", "C:NJ", "C:REX"), ids,
                        "every arrival's members survive -- a reference to any of them resolves");
            }
        } finally {
            store.setMergePolicy(ValueSet.class, null);
        }
    }

    // ---------------------------------------------------------------- helpers

    static void insertAny(Store store, Object o) {
        try (Txn txn = store.rwTxn()) {
            store.insertAnyObjects(txn, List.of(o));
            txn.commit();
        }
    }

    static List<String> memberIds(ValueSet vs) {
        List<String> ids = new ArrayList<>();
        for (JAXBElement<? extends TypeOfValue_VersionStructure> el : vs.getValues().getTypeOfValue()) {
            ids.add(el.getValue().getId());
        }
        return ids;
    }

    static ValueSet vs(String id, TypeOfProductCategory... members) {
        ValueSet vs = new ValueSet();
        vs.setId(id);
        vs.setVersion("1");
        TypesOfValue_RelStructure rel = new TypesOfValue_RelStructure();
        for (TypeOfProductCategory m : members) rel.getTypeOfValue().add(FACTORY.createTypeOfProductCategory(m));
        vs.setValues(rel);
        return vs;
    }

    static TypeOfProductCategory cat(String id) {
        TypeOfProductCategory c = new TypeOfProductCategory();
        c.setId(id);
        c.setVersion("1");
        return c;
    }

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
