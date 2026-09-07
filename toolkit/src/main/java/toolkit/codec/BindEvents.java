package toolkit.codec;

import jakarta.xml.bind.ValidationEvent;
import jakarta.xml.bind.ValidationEventHandler;
import jakarta.xml.bind.ValidationEventLocator;
import toolkit.util.Conf;
import toolkit.util.Log;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/// The [ValidationEventHandler] that counts what the JAXB RI swallows.
///
/// A lexical value that fails to parse into its bound type raises a `ValidationEvent`; the RI's
/// default handler returns true for `WARNING` and `ERROR`, unmarshalling continues, and the field
/// is left at its zero value. This counts those events and changes nothing else: the verdict is
/// delegated to whatever handler was installed before it, which is the RI's own default:
///
/// ```
/// unmarshal default:  WARNING -> true   ERROR -> true    FATAL_ERROR -> false
/// marshal   default:  WARNING -> false  ERROR -> false   FATAL_ERROR -> false
/// ```
///
/// The marshal side is already loud — any event becomes an exception — so installing this there is
/// for attribution.
///
/// The tally is bounded: at most [#SITE_CAP] distinct (side, scope, class, element, cause) sites
/// with {@value #SAMPLE_CAP} sample lexicals each. The event object itself is dropped, as the RI
/// does.
///
/// Not thread-safe per instance: one handler belongs to one `Unmarshaller`, which belongs to one
/// [FragmentCodec] or one loader stream, each single-threaded by contract. Only the static tally
/// is shared.
public final class BindEvents implements ValidationEventHandler {

    /// Distinct (side, scope, class, element, cause) sites retained before further events are
    /// counted only in `DROPPED`. `-Dtoolkit.bind.siteCap=N` raises it.
    public static final int SITE_CAP = Conf.intOf(Conf.BIND_SITE_CAP);
    /// Sample lexical values kept per site.
    public static final int SAMPLE_CAP = 3;

    /// One counted binding failure, as it can be identified at event time.
    public record Site(String side, String scope, String clazz, String element, String cause) {}

    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder DROPPED = new LongAdder();
    private static final Map<Site, Tally> SITES = new ConcurrentHashMap<>();
    private static final AtomicBoolean HOOKED = new AtomicBoolean();
    /// Total at the last [#report], so the safety net stays quiet about what was reported.
    private static final LongAdder REPORTED = new LongAdder();

    private static final class Tally {
        final LongAdder count = new LongAdder();
        final Set<String> samples = new LinkedHashSet<>(4);

        synchronized void sample(String v) {
            if (v != null && samples.size() < SAMPLE_CAP) samples.add(v);
        }

        synchronized List<String> samplesCopy() {
            return new ArrayList<>(samples);
        }
    }

    // ------------------------------------------------------------------ instance

    private final String side;
    private final ValidationEventHandler delegate;
    private String scope = "-";
    private Class<?> clazz;
    private XMLStreamReader reader;

    private BindEvents(String side, ValidationEventHandler delegate) {
        this.side = side;
        this.delegate = delegate;
    }

    /// Wrap whatever handler `current` is — on a fresh RI unmarshaller or marshaller that is the
    /// implementation object itself, i.e. the default policy — so the verdict never changes.
    public static BindEvents unmarshalling(ValidationEventHandler current) {
        return new BindEvents("unmarshal", current);
    }

    /// As [#unmarshalling], for the marshal side.
    public static BindEvents marshalling(ValidationEventHandler current) {
        return new BindEvents("marshal", current);
    }

    /// The feed, zip member or store this handler's owner is currently reading — the "per feed"
    /// axis of the report. Unset reads as `-`.
    public void scope(String s) {
        this.scope = (s == null || s.isEmpty()) ? "-" : s;
    }

    /// The declared root type and the reader about to be consumed. There is no matching `clear()`.
    public void context(Class<?> c, XMLStreamReader r) {
        this.clazz = c;
        this.reader = r;
    }

    /// The loader's reader is per stream, not per element, so it is set once and only the declared
    /// type moves.
    public void reader(XMLStreamReader r) {
        this.reader = r;
    }

    /// The declared type of the object about to be bound — the loader's per-object half of
    /// [#context], paired with a [#reader] set once for the stream.
    public void binding(Class<?> c) {
        this.clazz = c;
    }

    @Override
    public boolean handleEvent(ValidationEvent e) {
        try {
            record(e);
        } catch (RuntimeException ignored) {
            // Counting is best-effort.
            DROPPED.increment();
        }
        return delegate.handleEvent(e);
    }

    private void record(ValidationEvent e) {
        TOTAL.increment();
        Site site = new Site(side, scope,
                clazz == null ? "-" : clazz.getSimpleName(),
                elementAt(reader),
                cause(e));
        Tally t = SITES.get(site);
        if (t == null) {
            if (SITES.size() >= SITE_CAP) {
                DROPPED.increment();
                return;
            }
            t = SITES.computeIfAbsent(site, k -> new Tally());
        }
        t.count.increment();
        t.sample(e.getMessage());
        arm();
    }

    /// The offending element, from the reader the RI is standing on: when a lexical conversion
    /// fails the StAX reader is on that element's `END_ELEMENT`.
    private static String elementAt(XMLStreamReader r) {
        if (r == null) return "-";
        try {
            int ev = r.getEventType();
            if (ev == XMLStreamConstants.START_ELEMENT || ev == XMLStreamConstants.END_ELEMENT) {
                return r.getName().toString();
            }
        } catch (RuntimeException ignored) {
            // no location: the count still stands
        }
        return "-";
    }

    /// The linked exception's type, or the severity — never the value, which is unbounded.
    private static String cause(ValidationEvent e) {
        Throwable t = e.getLinkedException();
        if (t != null) return t.getClass().getSimpleName();
        ValidationEventLocator l = e.getLocator();
        return "severity" + e.getSeverity() + (l == null ? "" : "@" + l.getLineNumber() + ":"
                + l.getColumnNumber());
    }

    // ------------------------------------------------------------------ the report

    /// Every event counted in this JVM, on every binding site.
    public static long total() {
        return TOTAL.sum();
    }

    /// Events counted but not attributed (site cap reached, or the tally itself threw).
    public static long dropped() {
        return DROPPED.sum();
    }

    /// The tally, highest count first.
    public static List<Map.Entry<Site, Long>> sites() {
        List<Map.Entry<Site, Long>> out = new ArrayList<>();
        for (Map.Entry<Site, Tally> e : SITES.entrySet()) {
            out.add(Map.entry(e.getKey(), e.getValue().count.sum()));
        }
        out.sort(Comparator.<Map.Entry<Site, Long>, Long>comparing(Map.Entry::getValue,
                Comparator.reverseOrder()).thenComparing(x -> x.getKey().toString()));
        return out;
    }

    /// Sample lexicals recorded for a site (at most {@value #SAMPLE_CAP}).
    public static List<String> samples(Site s) {
        Tally t = SITES.get(s);
        return t == null ? List.of() : t.samplesCopy();
    }

    /// The per-run summary, with no flag to switch it on. Clean data still prints one INFO line; a
    /// run that lost values prints one WARNING per site.
    ///
    /// The tally is per JVM, not per call, so a host running several stages in one JVM sees each
    /// line carry the running total; [#reset] scopes a count to one sweep.
    public static void report(String tag) {
        long total = TOTAL.sum();
        REPORTED.reset();
        REPORTED.add(total);
        if (total == 0) {
            Log.info("[%s] jaxb bind events: 0 (no value failed to parse into its bound type)", tag);
            return;
        }
        List<Map.Entry<Site, Long>> sites = sites();
        Log.warn("[%s] jaxb bind events: %,d over %d site(s) — a source value did not parse into "
                + "its bound type; JAXB raised, the field was left empty and the run continued "
                , tag, total, sites.size());
        for (Map.Entry<Site, Long> e : sites) {
            Site s = e.getKey();
            List<String> ex = samples(s);
            Log.warn("[%s]   %-9s %s · %s · %s · %s ×%,d%s", tag, s.side(), s.scope(), s.clazz(),
                    s.element(), s.cause(), e.getValue(),
                    ex.isEmpty() ? "" : "  e.g. " + ex);
        }
        long dropped = DROPPED.sum();
        if (dropped > 0) {
            Log.warn("[%s]   %,d event(s) counted but not attributed (site cap %d reached)",
                    tag, dropped, SITE_CAP);
        }
    }

    /// Arm the at-exit safety net once, on the first event, so clean data installs no hook. It
    /// covers a driver that never calls [#report], and `System.exit` paths.
    private static void arm() {
        if (HOOKED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (TOTAL.sum() != REPORTED.sum()) report("bind-events");
            }, "bind-events-report"));
        }
    }

    /// Drop every counter. For tests, and for a tool that measures one sweep at a time.
    public static void reset() {
        TOTAL.reset();
        DROPPED.reset();
        REPORTED.reset();
        SITES.clear();
    }
}
