package toolkit.load;

import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Zip feed loader: `.xml` and `.xml.gz` members (case-insensitive, decompressed by [#wrap],
/// which [NetexLoader]'s own zip arm shares), filtered and ordered per [Options], one
/// `loadStream` each under a `zipname!member` label.
///
/// It reads members and stops there. The post pass and the `resolve()`/`resolveEmbeddings()` pair
/// belong to `toolkit.harness.FeedToDb` — properties of a finished LOAD, not of a zip.
public final class ZipLoad {

    private ZipLoad() {}

    /// Per-feed knobs, each nullable (null = default):
    ///
    /// - `memberFilter` — keep only matching member names; default: every .xml member.
    /// - `memberOrder` — load order. With last-wins duplicate-id precedence, ORDER IS SEMANTICS.
    ///    Default is ZIP ARCHIVE ORDER; do NOT default to a name sort, it changes which duplicate
    ///    wins.
    /// - `mergePolicies` — [Store#setMergePolicy] registrations held for the duration of the LOAD
    ///    only; default: none, i.e. last-wins.
    /// - `postLoad` — run after the policies are cleared and BEFORE resolve. May open its own rw
    ///    txn: none is held here.
    public record Options(Predicate<String> memberFilter, Comparator<String> memberOrder,
                          Map<Class<?>, Store.MergeFn> mergePolicies, Consumer<Store> postLoad) {

        /// All defaults: no filter, archive order, no merge policies, no post pass.
        public static final Options DEFAULTS = new Options(null, null, null, null);
    }

    /// Load `zip` into `store` per `opts` (null = [Options#DEFAULTS]).
    public static LoadStats run(Path zip, Store store, NetexLoader loader, Options opts) throws Exception {
        if (opts == null) opts = Options.DEFAULTS;
        LoadStats total = LoadStats.ZERO;
        Map<Class<?>, Store.MergeFn> policies = opts.mergePolicies() == null ? Map.of() : opts.mergePolicies();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<ZipEntry> members = new ArrayList<>(); // ZipEntry identity: duplicate NAMES each load once
            int filtered = 0;
            int nonXml = 0;
            for (Iterator<? extends ZipEntry> en = zf.entries().asIterator(); en.hasNext(); ) {
                ZipEntry e = en.next();
                if (e.isDirectory()) continue;
                String lower = e.getName().toLowerCase();
                if (!lower.endsWith(".xml") && !lower.endsWith(".xml.gz")) {
                    nonXml++;
                    continue;
                }
                if (opts.memberFilter() != null && !opts.memberFilter().test(e.getName())) {
                    filtered++;
                    continue;
                }
                members.add(e);
            }
            if (opts.memberOrder() != null) {
                members.sort(Comparator.comparing(ZipEntry::getName, opts.memberOrder()));
            } // else: zip archive order (see Options javadoc — order is dedup precedence)
            if (filtered > 0 || nonXml > 0) {
                Log.info("[zipload] %d members skipped by filter, %d non-XML members ignored", filtered, nonXml);
            }
            if (members.isEmpty()) {
                throw new IllegalStateException(zip + ": no loadable XML members"
                        + (filtered + nonXml > 0 ? " (" + (filtered + nonXml) + " skipped/ignored)" : ""));
            }
            policies.forEach(store::setMergePolicy);
            try {
                total = WORKERS > 0 && members.size() > 1
                        ? runPipelined(zip, store, loader, members)
                        : runSerial(zip, store, loader, members, zf);
            } finally {
                for (Class<?> clazz : policies.keySet()) store.setMergePolicy(clazz, null);
            }
        }
        // The member loop's own profile, before the post pass adds writes of its own.
        if (store instanceof toolkit.store.AbstractLmdbStore als) als.logLoadProfile("members");
        // Decided here, not in FeedToDb: this is the feed boundary both loader entry points share,
        // and it precedes the post pass and resolve.
        NetexLoader.reportDropped(zip.getFileName().toString(), total);
        // run() does NOT resolve. Its one caller, FeedToDb, owns that sequence.
        return total;
    }

    /// Parse and insert on the calling thread, in order, one write txn per member — unless
    /// [#TXN_BATCH] says otherwise.
    private static LoadStats runSerial(Path zip, Store store, NetexLoader loader,
                                       List<ZipEntry> members, ZipFile zf) throws Exception {
        LoadStats total = LoadStats.ZERO;
        Batcher batch = new Batcher(store);
        try {
            for (ZipEntry e : members) {
                InputStream raw = zf.getInputStream(e);
                try (InputStream in = wrap(raw, e.getName())) {
                    NetexLoader.Parse p = loader.open(in, zip.getFileName() + "!" + e.getName());
                    try {
                        store.insertAnyObjects(batch.txn(), p.objects());
                    } finally {
                        p.closeReader();
                    }
                    batch.memberDone();
                    total = total.plus(p.finish());
                }
            }
            batch.commitPending();
        } finally {
            batch.close();
        }
        logTxns(members.size(), batch);
        return total;
    }

    /// The count any speed claim about this loader has to quote. Logged in both arms.
    private static void logTxns(int members, Batcher batch) {
        Log.info("[zipload] %d members, %d write txns, commit %.1f s (%.0f ms/txn)",
                members, batch.txns, batch.commitNanos / 1e9,
                batch.txns == 0 ? 0 : batch.commitNanos / 1e6 / batch.txns);
    }

    /// `-Dtoolkit.load.zipTxnBatch=N` — commit every N members instead of every one.
    ///
    /// Store content does not depend on txn boundaries: insert order is unchanged, the full-key
    /// sequence is read and written inside the txn, and LMDB reads see the same txn's uncommitted
    /// writes. What a batch changes is durability granularity — a crash loses the batch — and
    /// dirty-page residency: LMDB holds a write txn's dirty pages in memory, so N must keep a batch
    /// inside the container.
    private static final int TXN_BATCH = Math.max(1, Conf.intOf(Conf.LOAD_ZIP_TXN_BATCH));

    /// Opens a write txn, keeps it for up to [#TXN_BATCH] members, commits, repeats.
    private static final class Batcher implements AutoCloseable {
        private final Store store;
        private Txn open;
        private int inBatch;
        long commitNanos;
        int txns;

        Batcher(Store store) {
            this.store = store;
        }

        Txn txn() {
            if (open == null) open = store.rwTxn();
            return open;
        }

        /// One member has been fully inserted; commit if the batch is full.
        void memberDone() {
            if (++inBatch >= TXN_BATCH) commitPending();
        }

        /// Commit whatever is open (the tail batch).
        void commitPending() {
            if (open == null) return;
            long t0 = System.nanoTime();
            open.commit();
            open.close();
            commitNanos += System.nanoTime() - t0;
            txns++;
            open = null;
            inBatch = 0;
        }

        /// Abort whatever is still open — a no-op after `commitPending`. On the failure path an
        /// unfinished txn is DISCARDED, never silently committed.
        @Override
        public void close() {
            if (open == null) return;
            open.close();
            open = null;
            inBatch = 0;
        }
    }

    /// Package-visible so [NetexLoader]'s own zip arm decompresses members identically; the two
    /// arms disagreeing is how a zip of gzipped members loads zero objects and exits 0.
    static InputStream wrap(InputStream raw, String name) throws IOException {
        return name.toLowerCase().endsWith(".gz")
                ? new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 20)
                : new BufferedInputStream(raw, 1 << 20);
    }

    // ------------------------------------------------------- within-zip pipeline

    /// `-Dtoolkit.load.zipWorkers=N` — parse N members concurrently, hand their objects to ONE
    /// writer thread (the caller's). `0`, the default, is the serial loop above.
    ///
    /// One writer, because store CONTENT is a function of insert ORDER: `insertOne` takes the next
    /// value of a monotonic sequence, resolves each reference against the id index as it stands,
    /// and lets duplicate ids go last-wins over the driver's member order. Parallel writers would
    /// move all three.
    ///
    /// Byte identity is structural: each member is still parsed by one iterator over one stream,
    /// and the writer drains members in order, each queue to its end, so `insertOne` sees the same
    /// call sequence it sees serially.
    ///
    /// Size against [#TXN_BATCH] first — this pays off on few large members, not on many small
    /// ones, where commits dominate.
    private static final int WORKERS = Conf.intOf(Conf.LOAD_ZIP_WORKERS);
    /// Objects buffered per in-flight member; the pipeline's whole memory bound is WORKERS × this.
    private static final int QUEUE_CAP = Conf.intOf(Conf.LOAD_ZIP_QUEUE);
    /// Queue sentinel: this member's parse is over (identity comparison — never a model object).
    private static final Object END = new Object();

    /// One member in flight: the worker fills `q`, the writer drains it.
    private static final class Pipe {
        final ZipEntry entry;
        final String label;
        final ArrayBlockingQueue<Object> q = new ArrayBlockingQueue<>(QUEUE_CAP);
        volatile NetexLoader.Parse parse;
        volatile Throwable error;

        Pipe(ZipEntry entry, String label) {
            this.entry = entry;
            this.label = label;
        }
    }

    private static LoadStats runPipelined(Path zip, Store store, NetexLoader loader,
                                          List<ZipEntry> members) throws Exception {
        int workers = Math.min(WORKERS, members.size());
        Log.info("[zipload] pipelined: %d parse workers, 1 writer, %d members, queue %d",
                workers, members.size(), QUEUE_CAP);
        List<Pipe> pipes = new ArrayList<>(members.size());
        for (ZipEntry e : members) pipes.add(new Pipe(e, zip.getFileName() + "!" + e.getName()));

        // One ZipFile per worker: a private handle costs one central-directory parse each.
        List<ZipFile> handles = new ArrayList<>(workers);
        ThreadLocal<ZipFile> handle = new ThreadLocal<>();
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "zipload-parse");
            t.setDaemon(true);
            return t;
        });
        // A finished member holds its objects until the writer drains it, so the thread count does
        // not bound memory — this does. FAIR, or a late worker can starve the member the writer
        // needs next.
        java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(workers + 1, true);
        LoadStats total = LoadStats.ZERO;
        Batcher batch = new Batcher(store);
        try {
            // Submission order is member order and the pool queue is FIFO, so the first worker to
            // dequeue is on the member the writer needs first.
            for (Pipe p : pipes) {
                pool.execute(() -> {
                    try {
                        inFlight.acquire();
                        ZipFile zf = handle.get();
                        if (zf == null) {
                            zf = new ZipFile(zip.toFile());
                            handle.set(zf);
                            synchronized (handles) { handles.add(zf); }
                        }
                        try (InputStream in = wrap(zf.getInputStream(p.entry), p.entry.getName())) {
                            NetexLoader.Parse parse = loader.open(in, p.label);
                            p.parse = parse;
                            try {
                                for (Iterator<Object> it = parse.objects(); it.hasNext(); ) {
                                    p.q.put(it.next());
                                }
                            } finally {
                                parse.closeReader();
                            }
                        }
                    } catch (Throwable t) {
                        p.error = t;
                    } finally {
                        try {
                            p.q.put(END);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
            for (Pipe p : pipes) {
                try {
                    store.insertAnyObjects(batch.txn(), new PipeIterator(p));
                    batch.memberDone();
                } finally {
                    inFlight.release();
                }
                if (p.error != null) throw asException(p.error);
                total = total.plus(p.parse.finish());
            }
            batch.commitPending();
        } finally {
            batch.close();
            pool.shutdownNow();
            for (ZipFile zf : handles) {
                try { zf.close(); } catch (IOException ignored) { }
            }
        }
        logTxns(members.size(), batch);
        return total;
    }

    private static Exception asException(Throwable t) {
        return t instanceof Exception e ? e : new IllegalStateException("parse worker failed", t);
    }

    /// Blocking view of one member's queue, ending at [#END] (or at the worker's failure).
    private static final class PipeIterator implements Iterator<Object> {
        private final Pipe pipe;
        private Object pending;
        private boolean done;

        PipeIterator(Pipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public boolean hasNext() {
            if (done) return false;
            if (pending != null) return true;
            try {
                Object o = pipe.q.take();
                if (o == END) {
                    done = true;
                    return false;
                }
                pending = o;
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted draining " + pipe.label, e);
            }
        }

        @Override
        public Object next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            Object o = pending;
            pending = null;
            return o;
        }
    }
}
