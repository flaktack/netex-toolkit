package toolkit.harness;

// InPlace.runInPlace hands the fix ONE read-write transaction and the fix both reads and writes
// through it: a lazy iterator over a class map feeds insertAnyObjects, which writes back into THE
// SAME dbi, at THE SAME keys, while the read cursor is mid-iteration — and with a LARGER value, so
// LMDB has to relocate the page rather than patch it in place. A cursor that skipped or revisited a
// row would leave a store missing some of the fix, in a database that opens, exports and validates.
//
// NEUTER PROOF: shorten the replacement name to something no longer than the original ("old" ->
// "new") and the rewrite stops relocating pages; the test still passes and stops testing anything.
// The name written below is deliberately longer than the one it replaces, for every row.

import noi.netex.model.Operator;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class TestInPlaceCursorStability {

    /// Enough rows to span pages.
    private static final int N = 300;

    /// N operators followed by N journeys referencing them: targets first, so nothing is unresolved.
    private static Path seeded(TestStore ts) throws Exception {
        Path dir = ts.tempPath("inplace.lmdb");
        try (Store store = Stores.create(dir, ts.format)) {
            try (Txn txn = store.rwTxn()) {
                List<Object> objs = new ArrayList<>();
                for (int i = 0; i < N; i++) objs.add(operator(i));
                for (int i = 0; i < N; i++) objs.add(journey(i));
                store.insertAnyObjects(txn, objs);
                txn.commit();
            }
            store.resolve();
            try (Txn tx = store.roTxn()) {
                Check.equals(N, count(store.iterEdges(tx)), "the fixture wired one edge per journey");
            }
        }
        return dir;
    }

    private static Operator operator(int i) {
        Operator op = new Operator();
        op.setId("OF:" + i);
        op.setVersion("1");
        op.setName(Mls.of("op" + i));
        return op;
    }

    private static ServiceJourney journey(int i) {
        ServiceJourney sj = new ServiceJourney();
        sj.setId("SJF:" + i);
        sj.setVersion("1");
        sj.setName(Mls.of("old" + i));
        OperatorRefStructure op = new OperatorRefStructure();
        op.setRef("OF:" + i);
        op.setVersion("1");
        sj.setOperatorRef(op);
        return sj;
    }

    @Test
    void testTheReadCursorVisitsEveryRowExactlyOnceWhileWritingBack(TestStore ts)
            throws Exception {
        Path dir = seeded(ts);

        int[] seen = {0};
        Set<String> ids = new HashSet<>();
        InPlace.runInPlace((db, txn) -> {
            Iterator<Object> inner = db.iterOnlyObjects(txn, ServiceJourney.class).iterator();
            db.insertAnyObjects(txn, new Iterator<Object>() {
                @Override public boolean hasNext() {
                    return inner.hasNext();
                }

                @Override public Object next() {
                    ServiceJourney sj = (ServiceJourney) inner.next();
                    if (++seen[0] > N) {
                        throw new IllegalStateException("the cursor revisited a row: " + sj.getId());
                    }
                    ids.add(sj.getId());
                    // Longer than "old<i>": the write must relocate the value, not patch it.
                    Mls.setText(sj.getName(), "fixed " + sj.getId());
                    return sj;
                }
            });
        }, dir, "test-inplace-cursor-stability", true);

        Check.equals(N, seen[0], "the cursor produced exactly " + N + " rows");
        Check.equals(N, ids.size(), "…and they were " + N + " DISTINCT rows, so none was skipped");

        try (Store store = Stores.open(dir, true); Txn tx = store.roTxn()) {
            int rows = 0, fixed = 0;
            for (Object o : store.iterOnlyObjects(tx, ServiceJourney.class)) {
                rows++;
                ServiceJourney sj = (ServiceJourney) o;
                if (("fixed " + sj.getId()).equals(Mls.text(sj.getName()))) fixed++;
            }
            Check.equals(N, rows, "the rewrite kept exactly " + N + " rows");
            Check.equals(N, fixed, "and mutated every one of them");
            Check.equals(N, count(store.iterEdges(tx)), "the re-extracted references are all wired");
            Check.equals(0, count(store.iterUnresolved(tx)), "with nothing left unresolved");
        }
    }

    private static int count(Iterable<?> it) {
        int n = 0;
        for (Object ignored : it) n++;
        return n;
    }
}
