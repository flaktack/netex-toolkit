package toolkit.harness;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import toolkit.codec.FragmentCodec;
import toolkit.keycodec.NulKeyCodec;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/// Turns stored rows into emitted fragment bytes, into a [ZipWriter].
///
/// Stored values are `[4B LE origLen][LZ4 block]`, so emission is decompress + byte copy — no JAXB
/// on the hot path. Every method here is that copy under a different way of choosing the rows:
/// all of a class, the first of a class, a list of full_keys, or a container-element plan.
///
/// Decompression is the store's on-disk format and no export can opt out of it. Anything done to the
/// fragment after that is profile, and this class does none of it — it calls [Fragments] and emits
/// whatever comes back, which by default is the stored bytes, verbatim.
///
/// Not `AutoCloseable`: it owns neither the store, the transaction nor the zip. The four it is
/// handed outlive it.
public final class FrameWriter {

    /// The profile hook: a decompressed fragment on its way out, and what should actually be
    /// written. Called once per emitted object, never for a row that is skipped.
    ///
    /// The default is identity — stored bytes, verbatim. An EPIP-shaped export passes
    /// `FragmentOrder::normalise`, which matches this signature as a method reference.
    ///
    /// @see toolkit.codec.FragmentOrder#normalise
    @FunctionalInterface
    public interface Fragments {
        byte[] of(Class<?> clazz, byte[] fragment);
    }

    private static final Fragments VERBATIM = (clazz, fragment) -> fragment;

    private final Store db;
    private final Txn tx;
    private final ZipWriter out;
    private final NetexEnvelope env;
    private final Fragments hook;
    private final FragmentCodec codec = new FragmentCodec();

    /// The verbatim writer: stored bytes out, unchanged.
    public FrameWriter(Store db, Txn tx, ZipWriter out, NetexEnvelope env) {
        this(db, tx, out, env, VERBATIM);
    }

    public FrameWriter(Store db, Txn tx, ZipWriter out, NetexEnvelope env, Fragments hook) {
        this.db = db;
        this.tx = tx;
        this.out = out;
        this.env = env;
        this.hook = hook;
    }

    /// An entry: the zip entry, then the envelope preamble every entry repeats. Paired with [#end];
    /// an entry opened without the preamble is not a document.
    public void entry(String name) throws IOException {
        out.openEntry(name);
        env.open(out);
    }

    /// The mirror of [#entry]: the envelope's closing tags, then the entry (which logs its totals).
    public void end() throws IOException {
        env.close(out);
        out.closeEntry();
    }

    // ------------------------------------------------------------ emission

    /// Every object of the class, in store scan (key) order. Returns the count emitted.
    public long all(Class<?> clazz) throws IOException {
        long n = 0;
        for (RawRow r : db.iterRawObjects(tx, clazz)) {
            write(clazz, r.blob());
            n++;
        }
        return n;
    }

    /// [#all] by NeTEx class name; 0 when this store has no such class map.
    public long all(String className) throws IOException {
        Class<?> c = db.classForName(className);
        return c == null ? 0 : all(c);
    }

    /// Only the first row of the class in store scan (key) order — insertion order for the toolkit's
    /// local keys; any further rows are dropped. Returns 0 or 1. For the frames that take exactly one
    /// of something as a direct child with no container element.
    public long first(Class<?> clazz) throws IOException {
        Iterator<RawRow> it = db.iterRawObjects(tx, clazz).iterator();
        try {
            if (!it.hasNext()) return 0;
            write(clazz, it.next().blob());
            return 1;
        } finally {
            closeCursor(it);
        }
    }

    /// [#first] by NeTEx class name; 0 when this store has no such class map.
    public long first(String className) throws IOException {
        Class<?> c = db.classForName(className);
        return c == null ? 0 : first(c);
    }

    /// The rows named by a full_key list, in the order given — for a driver that decided its own
    /// order from the stored indexes rather than from a scan.
    public void byFullKey(LongArrayList fullKeys, Class<?> clazz) throws IOException {
        for (long fk : fullKeys) {
            write(clazz, db.loadRawObject(tx, clazz, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fk))));
        }
    }

    /// [#byFullKey] over a list that spans several classes, each row's class read off its own key.
    ///
    /// A full_key carries its class index, so a caller that partitioned a mixed set — the two
    /// journey classes of one container, say — does not have to re-group it by class to emit it.
    public void byFullKey(LongArrayList fullKeys) throws IOException {
        for (long fk : fullKeys) {
            Class<?> c = db.classForIdx(NulKeyCodec.fullKeyClassIdx(fk));
            if (c == null) continue; // a key whose class map this store lost: nothing to load
            write(c, db.loadRawObject(tx, c, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fk))));
        }
    }

    /// A container-element plan: `containerTag -> the class names that go in it`, in iteration
    /// order (pass a `LinkedHashMap` — the XSD sequence is an order, not a set).
    ///
    /// A container tag is written only when something lands in it. An empty `<lines/>` is not the
    /// same document as no `<lines>` at all, and for most NeTEx containers it is not even valid.
    public void containers(Map<String, List<String>> plan) throws IOException {
        containers(plan, fk -> true);
    }

    /// [#containers] with a row filter, consulted once per row on its full_key. The writer does not
    /// ask what the filter decides on: which rows a profile considers payload is the profile's to
    /// say, and a container left empty by it writes no tag, exactly as one with no rows does.
    public void containers(Map<String, List<String>> plan, LongPredicate keep) throws IOException {
        for (Map.Entry<String, List<String>> e : plan.entrySet()) {
            boolean any = false;
            for (String className : e.getValue()) {
                Class<?> c = db.classForName(className);
                if (c == null) continue;
                for (RawRow r : db.iterRawObjects(tx, c)) {
                    if (!keep.test(db.fullKeyOf(c, r.localKey()))) continue;
                    if (!any) {
                        out.write("<" + e.getKey() + ">");
                        any = true;
                    }
                    write(c, r.blob());
                }
            }
            if (any) out.write("</" + e.getKey() + ">");
        }
    }

    /// Whether the class has any row — the decode-free existence probe a driver asks before opening
    /// an entry it would otherwise have to un-write. No blob is read.
    public boolean hasRows(Class<?> clazz) {
        Iterator<byte[]> it = db.scanObjects(tx, clazz).iterator();
        try {
            return it.hasNext();
        } finally {
            closeCursor(it);
        }
    }

    /// [#hasRows] by NeTEx class name; false when this store has no such class map.
    public boolean hasRows(String className) {
        Class<?> c = db.classForName(className);
        return c != null && hasRows(c);
    }

    /// Decompress, hand to the profile, emit, count — the one path every method above ends in, so
    /// the hook cannot be applied to some emitted fragments and not others.
    private void write(Class<?> clazz, byte[] blob) throws IOException {
        out.write(hook.of(clazz, codec.decompress(blob)));
        out.countObject();
    }

    /// An iteration abandoned before its end leaves the cursor open; the store's iterators are
    /// `AutoCloseable` when they hold one.
    private static void closeCursor(Iterator<?> it) {
        if (it instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {
            }
        }
    }
}
