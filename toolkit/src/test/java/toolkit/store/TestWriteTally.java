package toolkit.store;

// One tally, two harnesses. InPlace prints it as "changed N"; DbToDb prints it as "wrote N" beside
// a separate "cloned N", because cloneFrom copies a whole source verbatim without reaching
// insertOne, and summing the two would let a clone of millions hide a derivation of three.
//
// An in-place fix re-emits mutated objects under their existing (id, version), so the store's row
// counts are identical whether it rewrote every object or none of them. That is why InPlace carries
// a per-class insert summary rather than the emptyOutput post-condition FeedToDb and DbToDb use: a
// fix that changes nothing is a legitimate outcome, so refusing would make the idempotent case an
// error.

import noi.netex.model.Line;
import noi.netex.model.Operator;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.List;
import java.util.Map;

public class TestWriteTally {

    private static Line line(String id) {
        Line l = new Line();
        l.setId(id);
        l.setVersion("1");
        return l;
    }

    private static Operator operator(String id) {
        Operator o = new Operator();
        o.setId(id);
        o.setVersion("1");
        return o;
    }

    /// The tally counts what was re-inserted, per class, and survives a commit.
    @Test
    void testAFixThatRewritesReportsWhatItTouched(TestStore ts) {
        AbstractLmdbStore db = (AbstractLmdbStore) ts.store;
        try (Txn txn = db.rwTxn()) {
            db.insertAnyObjects(txn, List.of(line("L:1"), line("L:2"), operator("O:1")));
            txn.commit();
        }
        Map<String, Long> byClass = db.insertedByClass();
        Check.equals(3L, db.insertedTotal(), "three objects were inserted: " + byClass);
        Check.equals(2L, byClass.get("Line"), "two Lines: " + byClass);
        Check.equals(1L, byClass.get("Operator"), "one Operator: " + byClass);

        // Highest first: on a real fix the interesting class is the one that moved most.
        Check.equals("Line", byClass.keySet().iterator().next(),
                "ordered by count, not by name: " + byClass);
    }

    /// Rewriting the same object is a change that does not move the store size, which is what makes
    /// row counts useless as an answer.
    @Test
    void testRewritingInPlaceCountsThoughTheStoreDoesNotGrow(TestStore ts) {
        AbstractLmdbStore db = (AbstractLmdbStore) ts.store;
        try (Txn txn = db.rwTxn()) {
            db.insertAnyObjects(txn, List.of(line("L:1")));
            txn.commit();
        }
        long rowsAfterFirst = countRows(db);

        try (Txn txn = db.rwTxn()) {
            Line same = line("L:1");
            same.setName(noi.netex.text.Mls.of("renamed"));
            db.insertAnyObjects(txn, List.of(same));
            txn.commit();
        }
        Check.equals(rowsAfterFirst, countRows(db),
                "the store did not grow — same (id, version), so this is the flat case");
        Check.equals(2L, db.insertedTotal(),
                "…but two inserts happened, and only the tally can say so");
    }

    /// A fix with no subject: the tally is empty, and that has to read as a valid outcome.
    @Test
    void testAFixThatChangesNothingHasAnEmptyTally(TestStore ts) {
        AbstractLmdbStore db = (AbstractLmdbStore) ts.store;
        Check.equals(0L, db.insertedTotal(),
                "nothing inserted yet, so nothing to report — InPlace prints 'changed nothing' "
                + "rather than refusing, because an idempotent fix must not be an error");
        Check.that(db.insertedByClass().isEmpty(), "and no per-class rows: " + db.insertedByClass());
    }

    /// cloneFrom writes straight to the class dbi and never reaches insertOne, so it is counted
    /// apart. DbToDb's emptyOutput check must not be switched to the insert tally: a pure-clone
    /// transform has a zero insert tally and a completely full target.
    @Test
    void testCloningIsCountedApartFromInserting(TestStore ts) throws Exception {
        AbstractLmdbStore src = (AbstractLmdbStore) ts.store;
        try (Txn txn = src.rwTxn()) {
            src.insertAnyObjects(txn, List.of(line("L:1"), line("L:2"), operator("O:1")));
            txn.commit();
        }
        try (TestStore dstTs = TestStore.create(ts.format)) {
            AbstractLmdbStore dst = (AbstractLmdbStore) dstTs.store;
            try (Txn stx = src.roTxn(); Txn dtx = dst.rwTxn()) {
                dst.cloneFrom(src, stx, dtx, null, 0);
                dtx.commit();
            }
            Check.equals(3L, dst.clonedTotal(), "three rows copied: " + dst.clonedByClass());
            Check.equals(2L, dst.clonedByClass().get("Line"), "…split by class: " + dst.clonedByClass());
            Check.equals(0L, dst.insertedTotal(),
                    "…and NOTHING counted as inserted. A clone is not the transform's own output, "
                    + "and summing the two would let a full target vouch for a generator that "
                    + "produced nothing.");
            Check.that(countRows(dst) > 0,
                    "the target is genuinely full, which is why emptyOutput must ask the TARGET "
                    + "and not the insert tally");
        }
    }

    private static long countRows(AbstractLmdbStore db) {
        long n = 0;
        try (Txn t = db.roTxn()) {
            for (Class<?> c : db.dbNames(t)) {
                for (@SuppressWarnings("unused") byte[] ignored : db.scanObjects(t, c)) n++;
            }
        }
        return n;
    }

    private TestWriteTally() {}
}
