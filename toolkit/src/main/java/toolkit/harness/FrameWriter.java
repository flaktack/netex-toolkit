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

/// The third companion of the export sandwich, beside [ZipWriter] (entries, byte and object
/// totals) and [NetexEnvelope] (the per-entry preamble). What it owns is the step between them:
/// turning STORED ROWS into EMITTED FRAGMENT BYTES.
///
/// Stored values are `[4B LE origLen][LZ4 block]`, so emission is decompress + byte copy — no JAXB
/// on the hot path. Every method here is that copy under a different way of choosing the rows:
/// all of a class, the first of a class, a list of full_keys, or a container-element plan.
///
/// WHAT IS MECHANISM AND WHAT IS PROFILE. Decompression is mechanism: it is the store's on-disk
/// format, every reader does it, and no export can opt out of it. Anything done to the fragment
/// AFTER that is profile, and this class does none of it — it calls [Fragments] and emits whatever
/// comes back. The default is the stored bytes, verbatim.
///
/// Running every emitted fragment through [toolkit.codec.FragmentOrder#normalise] is one profile's
/// requirement, not the harness's: an export whose consumer wants the stored bytes verbatim is a
/// legitimate export. So it is a parameter, and the parameter defaults to off.
///
/// Not `AutoCloseable`: it owns neither the store, the transaction nor the zip. The four it is
/// handed outlive it.
public final class FrameWriter {

    /// The PROFILE hook: a decompressed fragment on its way out, and what should actually be
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

    // ------------------------------------------------------------ entries

    /// An entry: the zip entry, then the envelope preamble every entry repeats.
    ///
    /// Paired with [#end], and paired here rather than at each call site: a driver that opened the
    /// entry and forgot the preamble would emit a zip entry that is not a document.
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

    /// Only the FIRST row of the class in store scan (key) order — insertion order for the
    /// toolkit's local keys; any further rows are dropped. Returns 0 or 1.
    ///
    /// For the frames that take exactly one of something as a direct child with no container
    /// element.
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

    /// The rows named by a full_key list, in the order given — the emission half of any driver
    /// that decided its own order from the stored indexes rather than from a scan.
    public void byFullKey(LongArrayList fullKeys, Class<?> clazz) throws IOException {
        for (long fk : fullKeys) {
            write(clazz, db.loadRawObject(tx, clazz, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fk))));
        }
    }

    /// A container-element plan: `containerTag -> the class names that go in it`, in iteration
    /// order (pass a `LinkedHashMap` — the XSD sequence is an order, not a set).
    ///
    /// A container tag is written ONLY when something lands in it. An empty `<lines/>` is not the
    /// same document as no `<lines>` at all, and for most NeTEx containers it is not even valid.
    public void containers(Map<String, List<String>> plan) throws IOException {
        for (Map.Entry<String, List<String>> e : plan.entrySet()) {
            boolean any = false;
            for (String className : e.getValue()) {
                Class<?> c = db.classForName(className);
                if (c == null) continue;
                for (RawRow r : db.iterRawObjects(tx, c)) {
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

    // ------------------------------------------------------------ probes

    /// Whether the class has any row — the decode-free existence probe a driver asks before
    /// opening an entry it would otherwise have to un-write.
    ///
    /// `scanObjects` (local keys only) rather than `iterRawObjects`: the question is existence, and
    /// this way the first row's blob is never even read.
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

    // ------------------------------------------------------------ internals

    /// Decompress, hand to the profile, emit, count. The one path every method above ends in, so
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
                // a cursor that will not close has nothing left to tell the caller
            }
        }
    }
}
