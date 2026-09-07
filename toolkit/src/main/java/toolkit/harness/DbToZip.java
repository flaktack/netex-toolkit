package toolkit.harness;

import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/// The EXPORT sandwich: one store out to a zip. It opens read-only and writes no object, so it has
/// no resolve pair, no target store, no `--format`, no insert tally and no empty-output
/// post-condition.
///
/// What is owned here: the common flags, the input-exists guard, the read-only store and its read
/// transaction, the atomic `.tmp` + rename (a half-written zip must never appear under the final
/// name, and a failed run must leave no `.tmp` either), the timing/throughput line, and
/// `Harness.finish`. What the driver supplies is the middle: what goes in the zip.
///
/// Script usage (uncompiled, JEP 458):
///
/// ```
/// public static void main(String[] args) throws Exception {
///     DbToZip.driverMain("epip-db-to-xml", args, USAGE, new String[] {"--shards="}, a -> {
///         int shards = a.intFlag("--shards", 1);
///         return (db, tx, out) -> new MyExport(db, tx, out).run(shards);
///     });
/// }
/// ```
public final class DbToZip {

    /// The 3-arg export contract: a read-only store, its read transaction, the zip being written.
    @FunctionalInterface
    public interface ExportFn {
        void export(Store db, Txn tx, ZipWriter out) throws Exception;
    }

    /// Two stages, because an export's own options must be read before its store is opened.
    /// [ExportFn] takes no options: a driver's own flags are read here off the parsed [Args] and
    /// captured by the returned function, which keeps every flag error ahead of the first byte.
    @FunctionalInterface
    public interface Setup {
        ExportFn configure(Args a) throws Exception;
    }

    private DbToZip() {}

    /// The exports' shared CLI: `<db> <out.zip> [--level N] [--log-file F]`, plus whatever
    /// `extraFlags` the driver declares ([Args] spec entries — `"--name="` for a
    /// value-taking flag). `usage` is the driver's own line, and nothing here rewrites it.
    public static void driverMain(String tag, String[] args, String usage, String[] extraFlags,
                                  Setup setup) throws Exception {
        List<String> spec = new ArrayList<>(List.of(extraFlags));
        spec.add("--level=");
        spec.add("--log-file=");
        Args a = Args.parse(args, usage, spec.toArray(new String[0])).expectPositional(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        int level = a.intFlag("--level", 6);
        if (level < 0 || level > 9) {
            System.err.println("usage: " + usage);
            System.exit(2);
        }
        ExportFn exportFn = setup.configure(a);

        long t0 = System.nanoTime();
        Path dbPath = Path.of(a.positional().get(0));
        Path zipPath = Path.of(a.positional().get(1));
        if (!Stores.exists(dbPath)) {
            // exit 1 for a missing input, exit 2 for a malformed command line; the Makefile
            // distinguishes them.
            Log.error("%s does not exist.", dbPath);
            System.exit(1);
        }
        // Written beside the target so the rename stays within one filesystem, hence atomic.
        Path tmp = zipPath.resolveSibling(zipPath.getFileName() + ".tmp");
        try (Store db = Stores.open(dbPath, true); Txn tx = db.roTxn()) {
            long objects;
            long bytes;
            try {
                try (ZipWriter out = new ZipWriter(tmp, level, tag)) {
                    exportFn.export(db, tx, out);
                    objects = out.totalObjects();
                    bytes = out.totalBytes();
                }
                Files.move(tmp, zipPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                Files.deleteIfExists(tmp); // no stale .tmp left behind
                throw t;
            }
            double wall = Log.secs(t0);
            Log.info("[%s] done: %s — %d objects, %.1f MB XML emitted, %.1f MB zip, %.2f s, "
                            + "%.1f MB/s (uncompressed), peak RSS %,d kB",
                    tag, zipPath, objects, bytes / 1e6, Files.size(zipPath) / 1e6, wall,
                    bytes / 1e6 / wall, Log.peakRssKb());
        }
        Harness.finish(tag);
    }
}
