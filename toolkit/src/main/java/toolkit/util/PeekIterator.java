package toolkit.util;

import java.util.Collections;
import java.util.Iterator;

/// Probe whether a lazy stream has ANY element without losing it, then hand the full stream —
/// peeked head included — to a consumer. The EPIP builders decide empty-vs-container with it.
///
/// SINGLE-USE: probe with [#hasAny()], then call [#iterator()] once. Iterating without probing
/// returns the underlying stream unchanged.
public final class PeekIterator<T> implements Iterable<T> {

    private final Iterator<T> inner;
    private Boolean hasValue; // null = not probed yet
    private T first;

    public PeekIterator(Iterator<T> inner) {
        this.inner = inner;
    }

    public PeekIterator(Iterable<T> inner) {
        this(inner.iterator());
    }

    /// Probe one element; cached, and the element is kept rather than lost.
    public boolean hasAny() {
        if (hasValue == null) {
            if (inner.hasNext()) {
                first = inner.next();
                hasValue = true;
            } else {
                hasValue = false;
            }
        }
        return hasValue;
    }

    /// The full remaining stream, peeked head first. Empty after a false probe; the untouched
    /// underlying stream when never probed. Single use.
    @Override
    public Iterator<T> iterator() {
        if (hasValue == null) return inner;
        if (!hasValue) return Collections.emptyIterator();
        return new Iterator<T>() {
            private boolean headDone;

            @Override
            public boolean hasNext() {
                return !headDone || inner.hasNext();
            }

            @Override
            public T next() {
                if (!headDone) {
                    headDone = true;
                    T head = first;
                    first = null;
                    return head;
                }
                return inner.next();
            }
        };
    }
}
