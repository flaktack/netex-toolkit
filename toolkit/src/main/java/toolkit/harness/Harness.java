package toolkit.harness;

import toolkit.store.AbstractLmdbStore;
import toolkit.store.Store;
import toolkit.util.Conf;
import toolkit.util.Log;
import toolkit.util.Strictness;

import java.util.Map;

/// The tail every driver sandwich runs, in one place.
///
/// Report the reference fallbacks, report the bind events, decide the `bindEvents` concern, print
/// the effective policy.
///
/// NOT a base class: the sandwiches are static entry points with genuinely different middles and no
/// shared state. What they share is a suffix, and a suffix is a call.
final class Harness {

    private Harness() {}

    /// The common close-out: what the run read loosely, what failed to bind, and the policy it ran
    /// under. Called after the store is closed, so it reports on the whole stage.
    static void finish(String tag) {
        AbstractLmdbStore.reportRefFallbacks(tag);
        toolkit.codec.BindEvents.report(tag);
        Strictness.require(Strictness.BIND_EVENTS, "bindEvents", toolkit.codec.BindEvents.total(),
                tag, "source values did not parse into their bound type and were left empty");
        // The half BindEvents CANNOT see: an unparseable date/time becomes a carrier, the adapter
        // returns an object, no ValidationEvent is raised and the line above counts zero.
        Log.info("[%s] unparsed date/time values: %s", tag, noi.netex.time.UnparsedTally.summary());
        Strictness.require(Strictness.UNPARSED_TIME, "unparsedTime",
                noi.netex.time.UnparsedTally.total(), tag,
                // Deliberately does not claim the bytes changed: an unparsed carrier re-marshals
                // verbatim. What is lost is the VALUE, at every arithmetic site, silently.
                "date/time values did not parse and are held as raw text, so every "
                + "toLocalTime()/toLocalDateTime()/toDuration() on them returns null");
        Strictness.report();
        // What the run was configured with, plus any undeclared -Dtoolkit.*. Here rather than at
        // startup because every driver passes through this method.
        Conf.report();
        Conf.checkUnknown();
    }

    /// "`<verb> N objects: Class n, Class n, …`", highest count first, or a stated no-op.
    ///
    /// Row counts cannot answer this for a stage that rewrites in place: the store's size is
    /// identical whether it rewrote everything or nothing.
    ///
    /// @param zeroNote what to say when nothing was written; null to stay silent
    static void reportInserted(String tag, Store store, String verb, String zeroNote) {
        if (!(store instanceof AbstractLmdbStore lmdb)) return;
        long total = lmdb.insertedTotal();
        if (total == 0) {
            if (zeroNote != null) Log.info("[%s] %s", tag, zeroNote);
            return;
        }
        Log.info("[%s] %s %,d objects: %s", tag, verb, total, oneLine(lmdb.insertedByClass()));
    }

    /// The clone half, counted apart — see [DbToDb] for why summing them would mislead.
    static void reportCloned(String tag, Store store) {
        if (!(store instanceof AbstractLmdbStore lmdb)) return;
        long cloned = lmdb.clonedTotal();
        if (cloned > 0) {
            Log.info("[%s] cloned %,d objects: %s", tag, cloned, oneLine(lmdb.clonedByClass()));
        }
    }

    private static String oneLine(Map<String, Long> byClass) {
        StringBuilder sb = new StringBuilder();
        byClass.forEach((name, n) ->
                sb.append(sb.isEmpty() ? "" : ", ").append(name).append(' ')
                  .append(String.format("%,d", n)));
        return sb.toString();
    }
}
