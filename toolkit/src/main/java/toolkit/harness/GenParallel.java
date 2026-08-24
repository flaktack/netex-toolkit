package toolkit.harness;

import toolkit.codec.FragmentCodec;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.store.WithFragment;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/// ORDERED CPU OFF-LOAD for the EPIP journey generator. Default OFF.
///
/// ## What it is for
///
/// The generator stage is one LMDB **write** transaction, and a write txn is bound to the thread
/// that opened it. So the only parts that can move off that thread are the ones that are pure
/// functions of bytes or of a bean:
///
/// - `FragmentCodec.decode(blob, class)` — LZ4 + StAX + JAXB unmarshal of a stored row;
/// - `FragmentCodec.marshalFragment(bean)` — the marshal the insert path would run inline.
///
/// Together they are most of the per-object bean CPU.
///
/// ## Why the output cannot move
///
/// Both stages are order-preserving by construction. The consumer thread — the one holding the
/// write txn — is also the submitter: it pulls the source in its own order, hands each item to a
/// worker, and takes results back IN SUBMISSION ORDER from a bounded queue of futures. Workers
/// never touch the store, never see each other's items and decide nothing, so the sequence of
/// `insertOne` calls is identical to the serial arm's, item for item.
///
/// What is NOT preserved: reads keep their order relative to each other but NOT relative to the
/// inserts, because [#ordered] fills its in-flight window before the first result is written. A
/// generator that READS a class it also WRITES therefore sees a different store than in the serial
/// arm — read-your-own-writes inside one write transaction makes the lag observable.
///
/// The one real hazard: `insertOne` extracts references AND THEN marshals, and marshalling MUTATES
/// the bean, since `SchemaDefaults.LISTENER` nulls every field holding its declared default.
/// Marshalling ahead reverses that order, so normalisation removing or renaming a reference would
/// move edge rows. It cannot: the rule table holds only defaulted scalars and empty collections,
/// and an empty collection carries no reference. A probe re-extracts from the marshalled bean and
/// compares bytes on every object it samples.
///
/// ## The knob
///
/// `-Dtoolkit.epip.parallelJourneys=N`. Absent, `0` or negative gives the serial path, which is the
/// default. Both arms log the same row counts at INFO.
public final class GenParallel {

    /// `-Dtoolkit.epip.parallelJourneys` — worker count per stage; &le; 0 is the serial arm.
    public static final String PROPERTY = Conf.EPIP_PARALLEL_JOURNEYS.property();

    private GenParallel() {}

    /// Worker count; `0` is the serial path. Read from the property on every call — twice per
    /// generator run, never per object — so a test can run both arms in ONE JVM.
    ///
    /// A malformed value REFUSES rather than falling back to serial, whose only symptom would be a
    /// timing.
    public static int workers() {
        return Math.max(0, Conf.intOf(Conf.EPIP_PARALLEL_JOURNEYS));
    }

    // ---------------------------------------------------------------- the two off-loaded stages

    /// The generator's source scan. Serial arm: `iterOnlyObjects`. Parallel arm: the cursor
    /// walk stays on this thread and only `decode` moves, results delivered in cursor order.
    public static Iterator<Object> decodeAhead(Store db, Txn txn, Class<?> clazz, String tag) {
        int workers = workers();
        if (workers <= 0) {
            return counted(db.iterOnlyObjects(txn, clazz).iterator(), tag + " decoded", 0);
        }
        Iterator<RawRow> raw = db.iterRawObjects(txn, clazz).iterator();
        Iterator<Object> decoded = ordered(raw,
                () -> {
                    FragmentCodec codec = new FragmentCodec();
                    return (RawRow r) -> codec.decode(r.blob(), clazz);
                },
                workers, tag + "-decode");
        return counted(decoded, tag + " decoded", workers);
    }

    /// The generator's output. Serial arm: the objects as they are, marshalled inline by the insert
    /// path. Parallel arm: each object is marshalled on a worker and handed to the insert path as a
    /// [WithFragment], which the store already knows how to store without re-marshalling.
    public static Iterator<Object> marshalAhead(Iterator<Object> objects, String tag) {
        int workers = workers();
        if (workers <= 0) {
            return counted(objects, tag + " emitted", 0);
        }
        Iterator<Object> wrapped = ordered(objects,
                () -> {
                    FragmentCodec codec = new FragmentCodec();
                    return (Object o) -> (Object) new WithFragment(o, codec.marshalFragment(o));
                },
                workers, tag + "-marshal");
        return counted(wrapped, tag + " emitted", workers);
    }

    // ---------------------------------------------------------------- the machinery

    /// Counts what passes through and logs the total once, in BOTH arms.
    private static Iterator<Object> counted(Iterator<Object> src, String label, int workers) {
        return new Iterator<>() {
            long n;
            boolean reported;

            @Override
            public boolean hasNext() {
                boolean h = src.hasNext();
                if (!h && !reported) {
                    reported = true;
                    Log.info("[epip] %s %d rows (parallelJourneys=%d)", label, n, workers);
                }
                return h;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                n++;
                return src.next();
            }
        };
    }

    /// Map `src` through `fn` on `workers` threads and deliver the results **in source order**. The
    /// caller's thread does the pulling and the taking, so a caller holding an LMDB txn keeps every
    /// txn touch on its own thread.
    ///
    /// The queue of in-flight futures is bounded at `4 * workers`, which bounds peak memory.
    private static <I> Iterator<Object> ordered(Iterator<I> src,
                                                Supplier<Function<I, Object>> perThread,
                                                int workers, String name) {
        final int window = Math.max(8, 4 * workers);
        final ThreadLocal<Function<I, Object>> fn = ThreadLocal.withInitial(perThread);
        final AtomicInteger seq = new AtomicInteger();
        final ThreadFactory tf = r -> {
            Thread t = new Thread(r, "epip-" + name + "-" + seq.incrementAndGet());
            t.setDaemon(true);   // never hold the JVM open
            return t;
        };
        final ExecutorService pool = Executors.newFixedThreadPool(workers, tf);
        final ArrayBlockingQueue<Future<Object>> inFlight = new ArrayBlockingQueue<>(window);

        return new Iterator<>() {
            boolean done;

            private void fill() {
                while (inFlight.remainingCapacity() > 0 && src.hasNext()) {
                    I item = src.next();
                    inFlight.add(pool.submit(() -> fn.get().apply(item)));
                }
            }

            @Override
            public boolean hasNext() {
                if (done) return false;
                fill();
                if (inFlight.isEmpty()) {
                    done = true;
                    pool.shutdown();
                    return false;
                }
                return true;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Future<Object> f = inFlight.poll();
                try {
                    return f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow();
                    throw new IllegalStateException("interrupted in " + name, e);
                } catch (ExecutionException e) {
                    pool.shutdownNow();
                    Throwable c = e.getCause();
                    if (c instanceof RuntimeException re) throw re;
                    if (c instanceof Error err) throw err;
                    throw new IllegalStateException("failed in " + name, c);
                }
            }
        };
    }
}
