package toolkit.harness;

import toolkit.load.LoadStats;
import toolkit.load.NetexLoader;
import toolkit.load.ZipLoad;
import toolkit.prov.LoadReport;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.util.Log;
import toolkit.util.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// The LOADER sandwich: a feed into a fresh store. A loader driver is a [ZipLoad.Options]
/// configuration plus one `driverMain` call; the harness owns the CLI, the validate-before-destroy
/// guard, store creation, the load, the mandatory resolve pair, and the post-conditions.
///
/// It takes `.xml`, `.xml.gz` and `.zip`, because the loader does.
///
/// The target is deleted and recreated. Appending into a pre-populated store is out of scope.
///
/// Script usage (uncompiled, JEP 458):
///
/// ```
/// public static void main(String[] args) throws Exception {
///     FeedToDb.driverMain("netex-to-db", args, ZipLoad.Options.DEFAULTS);
/// }
/// ```
///
/// That is `scripts/NetexToDb.java`, verbatim — the worked example for this sandwich. A driver
/// with feed-specific needs passes its own [ZipLoad.Options] in place of the defaults.
public final class FeedToDb {

    private FeedToDb() {}

    /// The extensions `NetexLoader.load` dispatches on. Anything else is refused up front.
    private static boolean loadable(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".xml") || lower.endsWith(".xml.gz");
    }

    /// Two ways in, one sequence out. [ZipLoad#run] reads a zip's members, [NetexLoader#load] a
    /// single file. Everything after the bytes are in — the post pass, then the `resolve()` +
    /// `resolveEmbeddings()` pair — happens here, once, for both.
    ///
    /// Merge policies apply on both paths, being dedup rules over inserted objects and nothing to do
    /// with zip membership. Genuinely zip-only options refuse rather than being dropped quietly.
    private static LoadStats load(Path feed, Store store, ZipLoad.Options opts) throws Exception {
        if (opts == null) opts = ZipLoad.Options.DEFAULTS;
        LoadStats stats;
        if (feed.toString().toLowerCase().endsWith(".zip")) {
            stats = ZipLoad.run(feed, store, new NetexLoader(), opts);
            return finishLoad(store, opts, stats);
        }
        if (opts.memberFilter() != null || opts.memberOrder() != null) {
            throw new IllegalArgumentException(feed + " is a single-file feed, but this driver set "
                    + "memberFilter/memberOrder, which only mean something for a zip. Refusing "
                    + "rather than ignoring them: a filter that silently does not apply is how a "
                    + "load quietly takes in more than it was configured to.");
        }
        Map<Class<?>, Store.MergeFn> policies =
                opts.mergePolicies() == null ? Map.of() : opts.mergePolicies();
        policies.forEach(store::setMergePolicy);
        try {
            stats = new NetexLoader().load(feed, store);
        } finally {
            for (Class<?> clazz : policies.keySet()) store.setMergePolicy(clazz, null);
        }
        return finishLoad(store, opts, stats);
    }

    /// The tail both arms share, in the order the zip arm established.
    private static LoadStats finishLoad(Store store, ZipLoad.Options opts, LoadStats stats)
            throws Exception {
        if (opts.postLoad() != null) opts.postLoad().accept(store);
        store.resolve();
        store.resolveEmbeddings();
        return stats;
    }

    public static void driverMain(String tag, String[] args, ZipLoad.Options opts) throws Exception {
        Args a = Args.parse(args,
                "<driver> <feed.xml[.gz|.zip]> <target_db> [--format v2] [--log-file F]",
                "--format=", "--log-file=").expectPositional(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        Path feed = Path.of(a.positional().get(0));
        Path db = Path.of(a.positional().get(1));
        String format = a.get("--format", Stores.V2);

        // Everything below the line is destructive, so every input check happens above it.
        if (!Stores.V2.equals(format)) {
            Log.error(Stores.RETIRED_V1.equals(format)
                    ? "--format v1: that store format was retired; v2 is the only format."
                    : "--format %s: unknown store format; v2 is the only format.", format);
            System.exit(2);
        }
        if (!loadable(feed.toString()) || !Files.exists(feed)) {
            Log.error("%s is not an existing .xml, .xml.gz or .zip feed.", feed);
            System.exit(1);
        }

        long t0 = System.nanoTime();
        Stores.destroy(db);
        LoadStats stats;
        try (Store store = Stores.create(db, format)) {
            stats = load(feed, store, opts);
            Log.info("[%s] loaded %,d objects (%.1f s)", tag, stats.objects(), Log.secs(t0));
            // The post-conditions, decided here once with the totals in hand: a load that inserted
            // nothing, and a load whose fragment capture failed and stored re-marshals instead of
            // source bytes. Both otherwise exit 0 with only a warning.
            Strictness.refuseIf(Strictness.EMPTY_OUTPUT, "emptyOutput", stats.objects() == 0,
                    tag, "this load inserted no objects — the feed matched nothing loadable");
            Strictness.require(Strictness.REMARSHAL, "remarshal", stats.captureFallbacks(),
                    tag, "objects were stored as a JAXB re-marshal instead of their source bytes");
            // What came in, by class. No zero note; emptyOutput has already refused.
            Harness.reportInserted(tag, store, "loaded", null);
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw e;
        }
        Log.info("[%s] done: %s (%.1f s, peak RSS %,d kB)", tag, db, Log.secs(t0), Log.peakRssKb());
        Harness.finish(tag);
        // Persist what this load tolerated: the store itself holds no record, so a downstream stage
        // cannot otherwise tell a clean build from one that swallowed its bind losses.
        LoadReport.write(db, tag, Map.of(
                "remarshal", stats.captureFallbacks(),
                "skippedNoId", stats.skippedNoId(),
                "unmapped", stats.unmapped(),
                "bindEvents", toolkit.codec.BindEvents.total(),
                "unparsedTime", noi.netex.time.UnparsedTally.total()));
    }
}
