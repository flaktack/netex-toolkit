package noi.netex.time;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/// How many date/time lexicals did not parse, and what they looked like.
///
/// [XmlTime], [XmlDateTime] and [XmlDuration] answer an unparseable lexical with an unparsed
/// carrier — raw text kept, re-marshal still byte-exact, normalisation returning null. From JAXB's
/// point of view the adapter succeeded and returned an object, so no `ValidationEvent` is raised
/// and `toolkit.codec.BindEvents` cannot see one of them; this tally is the only place they are
/// visible. An unparseable time becomes a null at every arithmetic site downstream.
///
/// This class counts and changes nothing. The harness reads [#total()] at the end of a load and
/// hands it to the `unparsedTime` concern, where refuse-or-tolerate is decided.
public final class UnparsedTally {

    private UnparsedTally() {}

    /// Which carrier refused the lexeme — the same four bindings `00-global.xjb` installs.
    public enum Kind {
        TIME("time"),
        DATE_TIME("dateTime"),
        DATE("date"),
        DURATION("duration");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /// The XSD type name, for a report line.
        public String label() {
            return label;
        }
    }

    /// Distinct sample lexicals kept per kind.
    public static final int SAMPLE_CAP = 3;

    /// Sample lexicals are truncated to this many characters — a lexical is untrusted input.
    private static final int SAMPLE_LEN = 60;

    private static final Kind[] KINDS = Kind.values();
    private static final Bucket[] BUCKETS = new Bucket[KINDS.length];

    static {
        for (int i = 0; i < BUCKETS.length; i++) BUCKETS[i] = new Bucket();
    }

    private static final class Bucket {
        final LongAdder count = new LongAdder();
        final Set<String> samples = new LinkedHashSet<>(4);
        /// Read before the lock; set once the samples are taken. Never un-set except by reset.
        volatile boolean full;

        void sample(String v) {
            if (full || v == null) return;
            synchronized (this) {
                if (samples.size() < SAMPLE_CAP) {
                    samples.add(v.length() > SAMPLE_LEN ? v.substring(0, SAMPLE_LEN) + "…" : v);
                }
                full = samples.size() >= SAMPLE_CAP;
            }
        }

        synchronized List<String> samplesCopy() {
            return new ArrayList<>(samples);
        }

        synchronized void reset() {
            count.reset();
            samples.clear();
            full = false;
        }
    }

    /// Count one unparsed carrier. Package-private: the only legitimate caller is the factory that
    /// builds a carrier the parse refused, so the count cannot drift from the population.
    ///
    /// @param lexical the raw source text the carrier retains, kept as a bounded sample
    static void record(Kind kind, String lexical) {
        Bucket b = BUCKETS[kind.ordinal()];
        b.count.increment();
        b.sample(lexical);
    }

    /// Every unparsed carrier constructed in this JVM, across all four kinds.
    public static long total() {
        long n = 0;
        for (Bucket b : BUCKETS) n += b.count.sum();
        return n;
    }

    public static long count(Kind kind) {
        return BUCKETS[kind.ordinal()].count.sum();
    }

    /// At most {@value #SAMPLE_CAP} distinct raw lexicals seen for this kind, in arrival order.
    public static List<String> samples(Kind kind) {
        return BUCKETS[kind.ordinal()].samplesCopy();
    }

    /// One line for a harness to log, with the samples in it.
    ///
    /// @return `"0"` when nothing failed to parse, so the caller can print the clean case too.
    public static String summary() {
        long total = total();
        if (total == 0) return "0";
        StringBuilder sb = new StringBuilder(96);
        sb.append(String.format("%,d", total));
        for (Kind k : KINDS) {
            long n = count(k);
            if (n == 0) continue;
            sb.append(String.format("  %s ×%,d", k.label(), n));
            List<String> ex = samples(k);
            if (!ex.isEmpty()) sb.append(" e.g. ").append(ex);
        }
        return sb.toString();
    }

    /// Drop every counter and sample. For tests, and for a tool that measures one sweep at a time.
    public static void reset() {
        for (Bucket b : BUCKETS) b.reset();
    }
}
