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

/// The FIX sandwich: one existing store opened read-write, the apply function run inside one write
/// transaction (reading and writing back on the same txn), commit, then the mandatory `resolve()` +
/// `resolveEmbeddings()` pair. The db's format is whatever it already is — there is no `--format`
/// option.
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
            // an in-place fix must only ever run on an existing database.
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
        Harness.finish(tag);
    }

    /// The resolve pair alone, on an existing store: open rw, `resolve()`, `resolveEmbeddings()`,
    /// close. Returns how many references the two wired between them.
    ///
    /// Separate from [#runInPlace] because both passes open and commit their own write txn (see
    /// [toolkit.store.StoreResolve]), so neither can run under one.
    public static long resolveOnly(Path database, String tag) throws Exception {
        long t0 = System.nanoTime();
        long wired;
        try (Store db = Stores.open(database, false)) {
            wired = db.resolve() + db.resolveEmbeddings();
        }
        Log.info("[%s] wired %,d references (%.1f s)", tag, wired, secs(t0));
        Harness.finish(tag);
        return wired;
    }

    private static void reportChanged(String tag, Store db) {
        Harness.reportInserted(tag, db, "changed",
                "changed nothing — no object was re-inserted (the fix found no subject; this is a "
                + "valid outcome for a fix, and is why there is no emptyOutput check here)");
    }

    private static double secs(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }
}
