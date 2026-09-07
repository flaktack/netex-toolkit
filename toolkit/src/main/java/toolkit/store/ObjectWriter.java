package toolkit.store;

import java.util.Iterator;
import java.util.function.LongPredicate;

/// Role: everything that mutates a store. Object insert (with its reference/id-index maintenance),
/// the duplicate-merge policy, the local-key sequence, and the raw structural clone.
public interface ObjectWriter {

    /// Insert a stream of objects of any class, with outward / unresolved reference extraction (the
    /// RecursiveAttributes walk) on every insert, id-index maintenance and overwrite semantics (an
    /// existing (id, version, class) row is replaced and its old reference rows cleared). Items may
    /// be model objects or [WithFragment].
    void insertAnyObjects(Txn txn, Iterator<?> objects);

    default void insertAnyObjects(Txn txn, Iterable<?> objects) {
        insertAnyObjects(txn, objects.iterator());
    }

    /// Single-class insert; `empty` drops the map + side rows first.
    void insertObjects(Txn txn, Class<?> clazz, Iterator<?> objects, boolean empty);

    /// How a duplicate-insert of `clazz` combines the stored copy with the incoming one
    /// (see [#setMergePolicy]). Return the object to store — typically the incoming copy, mutated —
    /// or `null` to signal the incoming copy stands unchanged (plain last-wins replace, exactly as
    /// if no policy were registered).
    ///
    /// Member types are inherited through subinterfaces, so the `Store.MergeFn` spelling also
    /// resolves.
    @FunctionalInterface
    interface MergeFn {
        Object merge(Object existing, Object incoming);
    }

    /// Class-scoped duplicate-merge policy: while a policy is registered for `clazz`,
    /// inserting an object whose (id, version, class) already exists decodes the stored blob,
    /// calls `fn.merge(existing, incoming)` and stores the result, instead of the default
    /// last-wins replacement. Reference/embedding extraction still runs against the incoming copy
    /// only (the parallel workers extract per copy).
    ///
    /// Session state, not persisted: policies live on this open Store handle only and are gone
    /// on reopen; `fn == null` clears the class's policy. Register for the exact concrete
    /// model class (no subtype dispatch).
    ///
    /// Fidelity caveat: a non-null merge result is re-marshalled — the row's source-verbatim
    /// captured fragment is lost for that row. A null result keeps the plain-replace path,
    /// verbatim fragment included.
    void setMergePolicy(Class<?> clazz, MergeFn fn);

    /// The current local-key sequence value, advanced by `advance` afterwards.
    long sequence(Txn txn, long advance);

    /// Raw structural copy of `source` into this (empty) store — class maps,
    /// id index, reference indexes — with `skipFullKeys` filtering (the db-to-db "delete")
    /// and `keyOffset` shifting of local keys (multi-source merges). Same-format sources
    /// only. Returns the largest local key written (offset applied). The copy runs below the object
    /// level, reading raw maps and the format marker.
    long cloneFrom(Store source, Txn sourceTxn, Txn targetTxn, LongPredicate skipFullKeys, long keyOffset);
}
