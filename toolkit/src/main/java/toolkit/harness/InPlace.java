package toolkit.harness;

import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import toolkit.util.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The same-database counterpart of [DbToDb]: a "fix" opens ONE existing store read-write,
/// runs the apply function inside one write transaction (reading and writing back on the same txn,
/// the fix idiom), commits, then runs the mandatory `resolve()` +
/// `resolveEmbeddings()` pair. The db's format is whatever it already is — there is no
/// `--format` option.
///
/// `--no-resolve` skips the resolve pair. For A/B comparison only; production fixes must not use it.
///
/// Script usage (uncompiled, JEP 458):
///
/// ```
/// void main(String[] args) {
///     InPlace.driverMain("add-stop", args, (db, txn) ->
///         db.insertAnyObjects(txn, fixedJourneys(db, txn)));
/// }
/// ```
public final class InPlace {

    /// The 2-arg fix contract: one store, one read-write transaction for both reads and writes.
    @FunctionalInterface
    public interface FixFn {
        void apply(Store db, Txn txn) throws Exception;
    }

    private InPlace() {}

    /// The fixes' shared CLI: database/--log-file/--no-resolve, existence check, log-and-reraise.
    public static void driverMain(String tag, String[] args, FixFn fixFn) {
        Args a = Args.parse(args, "<fix> <database> [--log-file F] [--no-resolve]",
                "--log-file=", "--no-resolve").expectPositional(1);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        Path database = Path.of(a.positional().get(0));
        if (!toolkit.store.Stores.exists(database)) {
            // A bare directory would silently become a fresh empty store via Stores.open(rw);
            // an in-place fix must only ever run on an EXISTING database.
            Log.error("%s is not an existing database.", database);
            System.exit(1);
        }
        try {
            runInPlace(fixFn, database, tag, !a.has("--no-resolve"));
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }

    /// Fix sandwich: open rw, apply inside one rw txn, commit, then resolve() + resolveEmbeddings().
    public static void runInPlace(FixFn fixFn, Path database, String tag, boolean resolve) throws Exception {
        long t0 = System.nanoTime();
        try (Store db = Stores.open(database, false)) {
            try (Txn txn = db.rwTxn()) {
                fixFn.apply(db, txn);
                txn.commit();
            }
            if (resolve) {
                Log.info("[%s] apply done (%.1f s), resolving", tag, secs(t0));
                db.resolve();
                db.resolveEmbeddings();
            }
            reportChanged(tag, db);
        }
        Log.info("[%s] done (%.1f s)", tag, secs(t0));
        // A fix reads its own store through the same reference path, so it needs the same reporting.
        Harness.finish(tag);
    }

    /// What the fix rewrote, by class.
    ///
    /// Deliberately NOT an `emptyOutput` post-condition, unlike the other sandwiches: a fix that
    /// changes nothing is legitimate — the defect it repairs is absent — so refusing would make
    /// the idempotent second run an error.
    ///
    /// Row counts cannot answer it either: a fix re-emits objects under their existing
    /// (id, version), so the store's size is identical whether it rewrote nothing or everything.
    private static void reportChanged(String tag, Store db) {
        Harness.reportInserted(tag, db, "changed",
                "changed nothing — no object was re-inserted (the fix found no subject; this is a "
                + "valid outcome for a fix, and is why there is no emptyOutput check here)");
    }

    private static double secs(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }
}
