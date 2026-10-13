package toolkit.util;

/// Whether a detected degradation is tolerated and counted, or refused.
///
/// The rule: fail by default, degrade only on explicit opt-in, count either way. A default that is
/// not fatal is one where failing would refuse data the loader accepts today; that is the only
/// admissible reason, and it is recorded per concern below.
///
/// Enforced at the harness boundary: the counters reach the driver, which decides once per run
/// with the totals in hand. A refusal is an [IllegalStateException] out of the harness — a
/// non-zero exit, and the pipeline renames into place only on success, so a refused run promotes
/// nothing.
///
/// The flags are system properties, declared in [Conf] and read once into a `static final` at
/// class-init. [#report()] prints the effective policy into the run's transcript.
public final class Strictness {

    private Strictness() {}

    /// Master switch: `-Dtoolkit.strict=true` makes every concern fatal.
    private static final boolean ALL = Conf.on(Conf.STRICT);

    /// A fragment could not be captured, so the object was stored as a re-marshal of the bean
    /// instead of its source bytes.
    ///
    /// Fatal by default. Opt out: `-Dtoolkit.strict.remarshal=false`.
    public static final boolean REMARSHAL = flag(Conf.STRICT_REMARSHAL);

    /// A stage produced nothing: a load that inserted zero objects, a transform that wrote zero.
    ///
    /// Fatal by default. Opt out: `-Dtoolkit.strict.emptyOutput=false`.
    public static final boolean EMPTY_OUTPUT = flag(Conf.STRICT_EMPTY_OUTPUT);

    /// JAXB raised a ValidationEvent: a lexical value did not convert into its bound Java type, and
    /// the field was left at its zero value.
    ///
    /// Not fatal by default: a handler returning `false` throws `UnmarshalException` on fragments
    /// the loader tolerates today, so failing would refuse feeds that currently load. On with
    /// `-Dtoolkit.strict.bindEvents=true`.
    public static final boolean BIND_EVENTS = flag(Conf.STRICT_BIND_EVENTS);

    /// References that matched no resolve tier and stayed in `_unresolved`.
    ///
    /// Not fatal by default: cross-border feeds genuinely reference objects published by another
    /// feed. On with `-Dtoolkit.strict.unresolvedRefs=true` for a single-feed load.
    public static final boolean UNRESOLVED_REFS = flag(Conf.STRICT_UNRESOLVED_REFS);

    /// A reference did not resolve on its exact `(id, version, class)` key and was answered by a
    /// prefix scan that ignores the requested class and version — the smallest matching id-index
    /// row wins, deterministically and possibly on the wrong version or class.
    ///
    /// Not fatal by default: it happens at scale on real feeds. On with
    /// `-Dtoolkit.strict.refFallback=true`.
    public static final boolean REF_FALLBACK = flag(Conf.STRICT_REF_FALLBACK);

    /// A date, time or duration in the feed did not parse, so its carrier holds the raw lexeme and
    /// every `toLocalTime()` / `toLocalDateTime()` / `toDuration()` on it returns null.
    ///
    /// Not fatal by default: the carrier re-emits the source lexeme verbatim, so an export is
    /// unaffected and only arithmetic sees the null. A separate concern because these adapters
    /// raise no `ValidationEvent`, so [#BIND_EVENTS] cannot see them. On with
    /// `-Dtoolkit.strict.unparsedTime=true`.
    public static final boolean UNPARSED_TIME = flag(Conf.STRICT_UNPARSED_TIME);

    /// Objects the pipeline deleted from its own output — a journey dropped for a dangling
    /// reference, a passing time dropped for an order mismatch, an interesting subtree skipped
    /// because it had no id or no model class.
    ///
    /// Not fatal by default: several fire on the national corpus. What matters is the aggregate;
    /// `-Dtoolkit.strict.droppedOutput=true` makes the number refusable.
    public static final boolean DROPPED_OUTPUT = flag(Conf.STRICT_DROPPED_OUTPUT);

    /// A generated object's id was composed from the configured codespace because the object it was
    /// generated from carried no class token to derive from, so it does not share the grammar of
    /// the feed objects around it.
    ///
    /// Counts only a derivation that was attempted and failed — the two generators with no source
    /// object compose from the codespace directly and are not counted, so a non-zero value is
    /// always a feed whose ids the derivation could not read.
    ///
    /// Not fatal by default: a feed is free to publish ids this cannot parse, and the id it gets
    /// is still unique. `-Dtoolkit.strict.idFallback=true` makes the number refusable.
    public static final boolean ID_FALLBACK = flag(Conf.STRICT_ID_FALLBACK);

    /// Two live containers claim one embedded id, so the reference to it resolves to whichever
    /// `resolveEmbeddings` reaches first.
    ///
    /// Counted off the embedding index, which sees the classes in the embedding scope -- where the
    /// referenceable ones are. A nested value outside that scope is not counted and nothing
    /// references one by id.
    ///
    /// Not fatal by default: publishers ship the shape, and a merge that qualifies colliding ids
    /// cannot see an embedded one to qualify. On with
    /// `-Dtoolkit.strict.embeddedDuplicates=true`.
    public static final boolean EMBEDDED_DUPLICATES = flag(Conf.STRICT_EMBEDDED_DUPLICATES);

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
            throw new IllegalStateException(String.format(
                    "[%s] REFUSED: %,d %s. Fix the input, or pass -Dtoolkit.strict.%s=false to accept it.",
                    tag, count, what, prop));
        }
        Log.warn("[%s] %,d %s (tolerated; -Dtoolkit.strict.%s=true to refuse)", tag, count, what, prop);
    }

    /// Boolean counterpart of [#require], for a state that is not a count.
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
        if (!REPORTED.compareAndSet(false, true)) return;
        Log.info("[strict] remarshal=%b emptyOutput=%b bindEvents=%b unresolvedRefs=%b "
                 + "refFallback=%b unparsedTime=%b droppedOutput=%b idFallback=%b "
                 + "embeddedDuplicates=%b%s",
                REMARSHAL, EMPTY_OUTPUT, BIND_EVENTS, UNRESOLVED_REFS,
                REF_FALLBACK, UNPARSED_TIME, DROPPED_OUTPUT, ID_FALLBACK, EMBEDDED_DUPLICATES,
                ALL ? " (-Dtoolkit.strict=true)" : "");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();
}
