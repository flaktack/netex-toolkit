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

/// The TRANSFORM sandwich, around one 4-arg apply function: open source read-only + target
/// read-write, run the apply inside one read / one write transaction, commit, then the mandatory
/// `resolve()` + `resolveEmbeddings()` pair (a transform that re-inserts objects lands every forward
/// reference in the unresolved map; resolve wires them back), plus the shared CLI (positional source
/// target, `--log-file`).
///
/// Extra option `--format v2` selects the target store format, defaulting to the source's. Kept
/// because scripts pass it and [toolkit.harness.Args] refuses an unknown option.
///
/// Script usage (uncompiled, JEP 458):
///
/// ```
/// public static void main(String[] args) {
///     DbToDb.driverMain("db-to-db-copy", args, (src, stx, dst, dtx) -> {
///         for (Class<?> clazz : src.dbNames(stx)) src.copyMap(stx, dst, dtx, clazz);
///     });
/// }
/// ```
///
/// That is `scripts/DbToDbCopy.java`, verbatim — the worked example for this sandwich, and what
/// `toolkit/src/test/java/toolkit/harness/TestHarnessExamples.java` checks still exists.
public final class DbToDb {

    /// The 4-arg transform contract: `apply_fn(source_db, source_txn, target_db, target_txn)`.
    @FunctionalInterface
    public interface ApplyFn {
        void apply(Store source, Txn sourceTxn, Store target, Txn targetTxn) throws Exception;
    }

    private DbToDb() {}

    /// The drivers' shared CLI: source/target/--log-file/--format, existence check, log-and-reraise.
    public static void driverMain(String tag, String[] args, ApplyFn applyFn) {
        Args a = Args.parse(args, "<driver> <source_db> <target_db> [--log-file F] [--format v2]",
                "--log-file=", "--format=").expectPositional(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        Path source = Path.of(a.positional().get(0));
        Path target = Path.of(a.positional().get(1));
        if (!Files.exists(source)) {
            Log.error("%s does not exist.", source);
            System.exit(1); // the Makefile treats exit 0 as success
        }
        try {
            runDbToDb(applyFn, source, target, tag, a.get("--format", null));
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }

    /// Apply inside one ro + one rw txn, commit, then the mandatory resolve() + resolveEmbeddings()
    /// pair. For a v2 target both are in-memory operations persisted at their commit.
    public static void runDbToDb(ApplyFn applyFn, Path source, Path target, String tag, String targetFormat)
            throws Exception {
        long t0 = System.nanoTime();
        String fmt = targetFormat != null ? targetFormat : Stores.sniffFormat(source);
        try (Store src = Stores.open(source, true);
             Store dst = Stores.create(target, fmt)) {
            try (Txn stx = src.roTxn(); Txn dtx = dst.rwTxn()) {
                applyFn.apply(src, stx, dst, dtx);
                dtx.commit();
            }
            Log.info("[%s] apply done (%.1f s), resolving", tag, secs(t0));
            dst.resolve();
            dst.resolveEmbeddings();
            try (Txn dtx = dst.roTxn()) {
                // Stops at the first row: "did it write anything" is an existence question, and the
                // insert tally cannot answer it — cloneFrom writes straight to the class dbi and
                // never reaches insertOne, so a pure-clone transform has a zero tally and a full
                // target.
                boolean any = false;
                for (Class<?> c : dst.dbNames(dtx)) {
                    if (dst.scanObjects(dtx, c).iterator().hasNext()) { any = true; break; }
                }
                Strictness.refuseIf(Strictness.EMPTY_OUTPUT, "emptyOutput", !any,
                        tag, "this transform wrote no objects to the target");
            }
            reportWritten(tag, dst);
        }
        Log.info("[%s] done (%.1f s)", tag, secs(t0));
        // The transforms are where loadObjectByReference is called, so finish()'s
        // reference-fallback count reports on this sandwich in particular.
        Harness.finish(tag);
    }

    /// What the transform put in the target, split into what it built and what it copied. Counted
    /// apart because `cloneFrom` copies a whole source verbatim without reaching `insertOne`, so one
    /// total would let a large copy hide a transform whose own output was three objects.
    private static void reportWritten(String tag, Store dst) {
        Harness.reportCloned(tag, dst);
        Harness.reportInserted(tag, dst, "wrote",
                "wrote no objects of its own — the target is the clone, unchanged");
    }

    private static double secs(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }
}
