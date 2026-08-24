package toolkit.test;

import noi.netex.model.EntityStructure;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/// The generator-drive helpers for transformer tests: [#run] drains a generator under a ro txn,
/// [#apply] feeds one through `insertAnyObjects` on a single rw txn — the in-place fix shape —
/// and [#byId] indexes a class by id.
///
/// [#apply] does NOT resolve: it is commit-only, so a test needing re-wired references calls
/// `resolve()` afterwards, outside the txn.
public final class Gen {

    private Gen() {}

    /// Drain a streaming generator under a ro txn and return the list.
    public static <T> List<T> run(TestStore ts, BiFunction<Store, Txn, Iterator<T>> gen) {
        try (Txn txn = ts.store.roTxn()) {
            List<T> out = new ArrayList<>();
            Iterator<T> it = gen.apply(ts.store, txn);
            while (it.hasNext()) out.add(it.next());
            return out;
        }
    }

    /// Run the generator and feed its output to `insertAnyObjects` on ONE rw txn, then commit. The
    /// generator may lazily read from the same txn it writes through — the read cursor survives the
    /// write, including a same-key overwrite with a larger value.
    public static void apply(TestStore ts, BiFunction<Store, Txn, Iterator<?>> gen) {
        try (Txn txn = ts.store.rwTxn()) {
            ts.store.insertAnyObjects(txn, gen.apply(ts.store, txn));
            txn.commit();
        }
    }

    /// id -&gt; object over `iterOnlyObjects`, in class-map (local-key) order — a LinkedHashMap, so
    /// iteration order is deterministic.
    public static <T> Map<String, T> byId(TestStore ts, Class<T> clazz) {
        try (Txn txn = ts.store.roTxn()) {
            Map<String, T> out = new LinkedHashMap<>();
            for (Object o : ts.store.iterOnlyObjects(txn, clazz)) {
                out.put(((EntityStructure) o).getId(), clazz.cast(o));
            }
            return out;
        }
    }
}
