package toolkit.harness;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import toolkit.util.Strictness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The MERGE sandwich — N stores into one fresh store — beside [FeedToDb] (load),
/// [DbToDb] (transform) and [InPlace] (fix).
///
/// Raw-clone each source into the target with a cumulative key offset so per-source local keys
/// never collide, skipping rows whose `(id, version, class)` already arrived from an earlier source,
/// then run the mandatory `resolve()` + `resolveEmbeddings()` pair: cross-source references are
/// unresolved in every per-source store and can only be wired once every source is present.
///
/// "Combine several stores" is generic; what to do about the COLLISIONS is policy. This takes the
/// answer that needs no knowledge of the feeds — first source wins, the shadowed copy is dropped.
/// The other answer, qualifying colliding ids with a per-source tag so both survive, needs a tag per
/// source and lives in `../noi-pipeline`'s `transformers.common.ItMerge`.
///
/// Script usage (uncompiled, JEP 458):
///
/// ```
/// public static void main(String[] args) throws Exception {
///     MergeToDb.driverMain("merge-db", args);
/// }
/// ```
public final class MergeToDb {

    private MergeToDb() {}

    /// The drivers' shared CLI: N sources, one target, format sniffing, and two refusals that both
    /// happen BEFORE anything is written — a non-empty target would silently collide local keys,
    /// and `cloneFrom` is a raw byte copy so mixed-format sources would corrupt the target.
    public static void driverMain(String tag, String[] args) throws Exception {
        Args a = Args.parse(args,
                "<driver> <src1> [<src2> ...] <target> [--format v2] [--log-file F]",
                "--log-file=", "--format=").expectAtLeast(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        List<String> positional = a.positional();
        List<Path> sources = new ArrayList<>();
        String sourceFmt = null;
        for (String s : positional.subList(0, positional.size() - 1)) {
            Path p = Path.of(s);
            // Distinct messages for the two conditions.
            if (!java.nio.file.Files.exists(p)) {
                Log.error("%s does not exist.", p);
                System.exit(1);
            }
            if (!Stores.exists(p)) {
                Log.error("[%s] source %s exists but contains no database (data.mdb) -- "
                        + "refusing to merge from it", tag, p);
                System.exit(1);
            }
            String fmt = Stores.sniffFormat(p);
            if (sourceFmt == null) {
                sourceFmt = fmt;
            } else if (!sourceFmt.equals(fmt)) {
                Log.error("[%s] source %s is %s but earlier sources are %s -- "
                        + "refusing to merge mixed-format sources", tag, p, fmt, sourceFmt);
                System.exit(1);
            }
            sources.add(p);
        }
        Path target = Path.of(positional.get(positional.size() - 1));
        if (Stores.exists(target)) {
            Log.error("[%s] target %s already contains a database (data.mdb) -- "
                    + "refusing to merge into it", tag, target);
            System.exit(1);
        }
        String fmt = a.get("--format", null);
        try {
            merge(sources, target, fmt != null ? fmt : sourceFmt, tag);
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }

    /// The phase function, public so a test or a fused pipeline can drive it without the CLI.
    public static void merge(List<Path> sources, Path target, String format, String tag)
            throws Exception {
        long t0 = System.nanoTime();
        double cloneSecs;
        double resolveSecs;
        double embSecs;
        try (Store tdb = Stores.create(target, format)) {
            // One write transaction per source bounds peak dirty pages to a single source.
            long offset = 0;
            boolean allSwept = true;
            for (Path srcPath : sources) {
                try (Store sdb = Stores.open(srcPath, true); Txn rtx = sdb.roTxn()) {
                    allSwept &= sdb.blobRepairMarkerPresent(rtx);
                    try (Txn wtx = tdb.rwTxn()) {
                        LongOpenHashSet dups = null;
                        if (offset != 0) {
                            // Duplicate (id, version) across sources. A raw-cloned duplicate row
                            // would surface TWICE in every map-iterating consumer.
                            dups = CloneSupport.duplicateIdFullKeys(sdb, rtx, tdb, wtx);
                            if (!dups.isEmpty()) {
                                Log.warn("[%s] %d duplicate object ids in %s already cloned from an "
                                        + "earlier source -- keeping the first", tag, dups.size(), srcPath);
                            }
                        }
                        LongOpenHashSet skips = dups;
                        long maxLocal = tdb.cloneFrom(sdb, rtx, wtx,
                                (skips == null || skips.isEmpty()) ? null : skips::contains, offset);
                        wtx.commit();
                        // max() so an empty or fully-skipped source (returns 0) does not reset the
                        // offset and let the next source's keys collide with the first's.
                        offset = Math.max(offset, maxLocal + 1);
                    }
                }
                Log.info("[%s] cloned %s (keys shifted; next offset %d)", tag, srcPath, offset);
            }
            cloneSecs = Log.secs(t0);

            // Marker propagation: with EVERY source already blob-repaired the target's blobs are
            // internally consistent, so it inherits the marker before resolve() and the sweep is
            // skipped; cross-source rows are repaired at bind time anyway. Inheriting from a
            // PARTIALLY swept set is the unsound direction — hence the &=.
            if (allSwept) {
                Log.info("[%s] all sources blob-repaired -- target inherits the marker; "
                        + "resolve() skips the full sweep", tag);
                try (Txn wtx = tdb.rwTxn()) {
                    tdb.setBlobRepairMarker(wtx);
                    wtx.commit();
                }
            }

            long t1 = System.nanoTime();
            tdb.resolve();
            resolveSecs = Log.secs(t1);
            long t2 = System.nanoTime();
            tdb.resolveEmbeddings();
            embSecs = Log.secs(t2);

            // The post-conditions, as every other sandwich has them. A merge that produced nothing
            // means every source was empty or every row collided — never a right answer.
            try (Txn rtx = tdb.roTxn()) {
                boolean any = false;
                for (Class<?> c : tdb.dbNames(rtx)) {
                    if (tdb.scanObjects(rtx, c).iterator().hasNext()) { any = true; break; }
                }
                Strictness.refuseIf(Strictness.EMPTY_OUTPUT, "emptyOutput", !any,
                        tag, "this merge produced an empty target from " + sources.size() + " source(s)");
            }
            Harness.reportCloned(tag, tdb);
            Log.info("[%s] done: merged %d databases into %s", tag, sources.size(), target);
        }
        Log.info("[%s] phases: clone %.1f s, resolve %.1f s, resolveEmbeddings %.1f s, total %.1f s",
                tag, cloneSecs, resolveSecs, embSecs, Log.secs(t0));
        Log.info("[%s] jvm peak rss %d kB", tag, Log.peakRssKb());
        Harness.finish(tag);
    }
}
