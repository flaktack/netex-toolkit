package toolkit.harness;

import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/// Apply several per-object operations in ONE streaming pass. An operation mutates the object in
/// place and returns whether it changed it; the driver scans every class exactly once, applies that
/// class's operations in order, and yields the object iff any operation changed it. Feed the
/// returned iterator to `dst.insertAnyObjects`.
public final class Ensemble {

    /// `Callable[[obj], bool]`: mutate in place, return whether the object changed.
    @FunctionalInterface
    public interface Operation {
        boolean apply(Object obj);
    }

    private Ensemble() {}

    public static Iterator<Object> applyOperations(Store db, Txn txn, Map<Class<?>, List<Operation>> operations) {
        Iterator<Class<?>> classes = db.dbNames(txn).stream()
                .filter(c -> {
                    List<Operation> ops = operations.get(c);
                    return ops != null && !ops.isEmpty();
                })
                .iterator();
        return new Iterator<>() {
            Iterator<Object> current;
            List<Operation> currentOps;
            Object pending;

            @Override
            public boolean hasNext() {
                while (pending == null) {
                    if (current == null || !current.hasNext()) {
                        if (!classes.hasNext()) return false;
                        Class<?> clazz = classes.next();
                        currentOps = operations.get(clazz);
                        current = db.iterOnlyObjects(txn, clazz).iterator();
                        continue;
                    }
                    Object obj = current.next();
                    boolean changed = false;
                    for (Operation op : currentOps) {
                        changed = op.apply(obj) || changed;
                    }
                    if (changed) pending = obj;
                }
                return true;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object o = pending;
                pending = null;
                return o;
            }
        };
    }
}
