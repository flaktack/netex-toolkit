package toolkit.codec;

import jakarta.xml.bind.ValidationEvent;
import jakarta.xml.bind.ValidationEventLocator;
import org.glassfish.jaxb.runtime.v2.runtime.Coordinator;
import toolkit.util.Conf;

/// Stops the JAXB runtime from re-creating a `ThreadLocalMap.Entry` on every XML event.
///
/// `Coordinator` — the shared base of `UnmarshallingContext` and `XMLSerializer` — brackets every
/// SAX-level callback with:
///
/// ```
/// pushCoordinator() { old = activeTable.get(); activeTable.set(this); }
/// popCoordinator()  { if (old != null) activeTable.set(old); else activeTable.remove(); }
/// ```
///
/// Those callbacks are not nested, so `old` is always null and every event ends with
/// `ThreadLocal.remove()`. The next event's `get()` misses, and `ThreadLocalMap.set` allocates a
/// fresh `Entry` — one per XML event, plus the stale-slot scan that follows it.
///
/// One sentinel `Coordinator` stays pushed on the thread for its whole life, so `old` is never
/// null, `popCoordinator` takes the `set(old)` branch, the entry is never deleted, `get()` always
/// hits and `set()` overwrites in place. The sentinel must never be popped and must never escape:
/// it is pushed only by itself, never handed to the library. Its one observable effect is what
/// `Coordinator._getInstance()` returns when no JAXB operation is on the stack — the sentinel
/// rather than null.
///
/// `-Dtoolkit.jaxbPin=false` disables it.
public final class JaxbPin extends Coordinator {

    private static final boolean ENABLED =
            Conf.on(Conf.JAXB_PIN);

    /// Non-null once this thread carries a pinned sentinel. Never read by the library.
    private static final ThreadLocal<JaxbPin> PINNED = new ThreadLocal<>();

    private JaxbPin() {}

    /// Idempotent, per thread: install the sentinel if this thread has none.
    public static void pin() {
        if (!ENABLED || PINNED.get() != null) return;
        JaxbPin p = new JaxbPin();
        p.pushCoordinator();
        PINNED.set(p);
    }

    /// True if this thread carries the sentinel (tests).
    public static boolean pinned() {
        return PINNED.get() != null;
    }

    @Override
    protected ValidationEventLocator getLocation() {
        throw new IllegalStateException("JaxbPin is a sentinel and must never be the active"
                + " coordinator during a JAXB operation");
    }

    @Override
    public boolean handleEvent(ValidationEvent event) {
        throw new IllegalStateException("JaxbPin is a sentinel and must never handle an event");
    }
}
