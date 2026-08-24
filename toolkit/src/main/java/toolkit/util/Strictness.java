package toolkit.util;

/// Whether a detected degradation is tolerated and counted, or refused.
///
/// The rule: fail by default, degrade only on explicit opt-in, count either way. A default that is
/// NOT fatal is one where failing would refuse data the loader accepts today — recorded per
/// concern below, and the only admissible reason.
///
/// Enforced at the harness boundary, not in the hot path: the counters reach the driver, which
/// decides once per run with the totals in hand. A refusal is an [IllegalStateException] out of the
/// harness — a non-zero exit, and the pipeline renames into place only on success, so a refused
/// run promotes nothing.
///
/// Flags are system properties, not CLI flags, so a driver gains one without touching its argument
/// parser. Each is read once into a `static final`, so a disabled arm folds away. All are declared
/// in [Conf], which refuses an undeclared `toolkit.*` property rather than ignoring it, and
/// [#report()] prints the effective policy into the run's transcript.
public final class Strictness {

    private Strictness() {}

    /// Master switch: `-Dtoolkit.strict=true` makes every concern below fatal.
    private static final boolean ALL = Conf.on(Conf.STRICT);

    /// A fragment could not be captured, so the object was stored as a re-marshal of the bean
    /// instead of its source bytes.
    ///
    /// FATAL BY DEFAULT — the one degradation that silently breaks the store's central claim, that
    /// no round-trip loss is baked into storage. Opt out: `-Dtoolkit.strict.remarshal=false`.
    public static final boolean REMARSHAL = flag(Conf.STRICT_REMARSHAL);

    /// A stage produced nothing: a load that inserted zero objects, a transform that wrote zero.
    ///
    /// FATAL BY DEFAULT. No feed has zero as its right answer, and drivers do not check counts.
    /// Opt out: `-Dtoolkit.strict.emptyOutput=false`.
    public static final boolean EMPTY_OUTPUT = flag(Conf.STRICT_EMPTY_OUTPUT);

    /// JAXB raised a ValidationEvent: a lexical value did not convert into its bound Java type, and
    /// the field was left at its zero value.
    ///
    /// NOT fatal by default: a handler returning `false` throws `UnmarshalException` on fragments
    /// the loader tolerates today, so failing would refuse feeds that currently load — a behaviour
    /// change, not a fix. On with `-Dtoolkit.strict.bindEvents=true`.
    public static final boolean BIND_EVENTS = flag(Conf.STRICT_BIND_EVENTS);

    /// References that matched no resolve tier and stayed in `_unresolved`.
    ///
    /// NOT fatal by default: cross-border feeds genuinely reference objects published by another
    /// feed. On with `-Dtoolkit.strict.unresolvedRefs=true` for a single-feed load, where a
    /// dangling reference is a defect rather than a fact about the corpus.
    public static final boolean UNRESOLVED_REFS = flag(Conf.STRICT_UNRESOLVED_REFS);

    /// A reference did not resolve on its exact `(id, version, class)` key and was answered by
    /// a prefix scan that ignores the requested class AND version — the smallest matching id-index
    /// row wins, deterministically.
    ///
    /// NOT fatal by default: it happens at scale on real feeds. Its own concern, not part of
    /// [#UNRESOLVED_REFS], because the failure mode is the opposite: an unresolved reference is a
    /// countable hole, while this one RESOLVED, possibly to the wrong version or class. On with
    /// `-Dtoolkit.strict.refFallback=true`.
    public static final boolean REF_FALLBACK = flag(Conf.STRICT_REF_FALLBACK);

    /// A date, time or duration in the feed did not parse, so its carrier holds the raw lexeme and
    /// every `toLocalTime()` / `toLocalDateTime()` / `toDuration()` on it returns
    /// null.
    ///
    /// NOT fatal by default: the carrier keeps the source lexeme and re-emits it verbatim, so an
    /// export is unaffected and only arithmetic sees the null.
    ///
    /// Declared because these adapters raise no `ValidationEvent`, so `BindEvents` cannot see them.
    /// On with `-Dtoolkit.strict.unparsedTime=true`.
    public static final boolean UNPARSED_TIME = flag(Conf.STRICT_UNPARSED_TIME);

    /// Objects the pipeline deleted from its own output — a journey dropped for a dangling
    /// reference, a passing time dropped for an order mismatch, an interesting subtree skipped
    /// because it had no id or no model class.
    ///
    /// NOT fatal by default: several fire on the national corpus. What matters is the AGGREGATE;
    /// `-Dtoolkit.strict.droppedOutput=true` makes the number refusable.
    public static final boolean DROPPED_OUTPUT = flag(Conf.STRICT_DROPPED_OUTPUT);

    /// The master switch, then the concern's own flag. Defaults live in [Conf].
    private static boolean flag(Conf.Flag f) {
        return ALL || Conf.on(f);
    }

    /// Refuse when `count` is non-zero and `fatal`; otherwise warn and carry on. One call shape for
    /// every concern, so the message always names its opt-out.
    ///
    /// @param fatal   the concern's flag, e.g. [#REMARSHAL]
    /// @param prop    the property that switches it, without the `toolkit.strict.` prefix
    /// @param count   how many times it happened; zero is silent
    /// @param tag     the driver tag, so a chained run says which stage refused
    /// @param what    what happened, as a sentence fragment: "objects were stored as a JAXB
    ///                re-marshal instead of their source bytes"
    public static void require(boolean fatal, String prop, long count, String tag, String what) {
        if (count == 0) return;
        if (fatal) {
            // The consequence belongs in `what`, at the call site: it differs per concern — bytes
            // for REMARSHAL, an edge for UNRESOLVED_REFS, arithmetic for UNPARSED_TIME.
            throw new IllegalStateException(String.format(
                    "[%s] REFUSED: %,d %s. Fix the input, or pass -Dtoolkit.strict.%s=false to accept it.",
                    tag, count, what, prop));
        }
        Log.warn("[%s] %,d %s (tolerated; -Dtoolkit.strict.%s=true to refuse)", tag, count, what, prop);
    }

    /// Boolean counterpart of [#require]: "this stage produced nothing" is not a count, and
    /// formatting it as one reads as "1 objects were inserted".
    ///
    /// @param what a complete sentence describing the state, e.g. "this load inserted no objects"
    public static void refuseIf(boolean fatal, String prop, boolean condition, String tag, String what) {
        if (!condition) return;
        if (fatal) {
            throw new IllegalStateException(String.format(
                    "[%s] REFUSED: %s. Fix the input, or pass -Dtoolkit.strict.%s=false to accept it.",
                    tag, what, prop));
        }
        Log.warn("[%s] %s (tolerated; -Dtoolkit.strict.%s=true to refuse)", tag, what, prop);
    }

    /// Log the effective policy once, so the transcript records what the run enforced.
    public static void report() {
        // Once per JVM, not per driver: the values are class-init static finals.
        if (!REPORTED.compareAndSet(false, true)) return;
        Log.info("[strict] remarshal=%b emptyOutput=%b bindEvents=%b unresolvedRefs=%b "
                 + "refFallback=%b unparsedTime=%b droppedOutput=%b%s",
                REMARSHAL, EMPTY_OUTPUT, BIND_EVENTS, UNRESOLVED_REFS,
                REF_FALLBACK, UNPARSED_TIME, DROPPED_OUTPUT,
                ALL ? " (-Dtoolkit.strict=true)" : "");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();
}
