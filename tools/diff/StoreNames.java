// StoreNames — the name-keyed store A/B gate, for two stores whose class numbering is not the same.
//
// A v2 class map's LMDB dbi NAME *is* its 2-byte class index, and the byte-level store comparators
// key on that name, so a renumbered class table makes them compare one class's map with another's:
// differing and missing maps over identical row counts, where nothing was lost.
//
// This reads each side's OWN class table out of its own `_meta` and compares map by map UNDER THE
// CLASS NAME, rewriting every stored class index — in `_id_idx`, `_embedding_idx`, `_edge_blocks`
// and `_unres_keys` — into that store's own class NAME before hashing. If the two sides then
// agree, the whole difference was a pure renumbering.
//
// Pure LMDB: no model class is referenced below, so one source runs against either model jar.
//
// WHAT IT CHECKS, AND WHAT IT CANNOT SEE
//
//   - It compares STORED BYTES under a name. It does not unmarshal, so `<Description/>` and
//     `<Description></Description>` are one infoset and two byte strings, called different here;
//     equally, a semantic change with no byte change is called identical.
//   - `_meta`'s CLASS TABLE is reported and never fails the run: the table enumerates the MODEL,
//     not the data, so two versions legitimately hold different numbers of classes. Names present
//     on only one side are PRINTED — a class that lost its objects looks the same here as one that
//     never had any.
//   - LOCAL KEYS AND INTERN IDS ARE COMPARED AS NUMBERS. Both are assigned in insertion order, so
//     two stores holding the same objects inserted in a different order DIFFER here. The graph
//     dump answers the content-only question.
//   - It does not look at free pages or file size: it walks logical rows, so it is immune to page
//     layout and equally cannot see a store that is bloated but logically identical.
//   - `_edge_blocks` TOMBSTONES are compared verbatim, as slots. A compacted store and an
//     uncompacted one can hold the same live edges in different slots and will DIFFER.
//   - It is O(store bytes): every value in every map is read and hashed.
//
// Both packed formats are decoded, not just the two keyed maps: `_edge_blocks` kind 0 is
// (referrer full_key, target full_key), kind 1 is (referrer full_key, `_unres_keys` intern id),
// and an intern id is followed into `_unres_keys` so its target key's class trailer is re-keyed
// too.
//
// REFUSALS. "CLASS MAPS BY NAME: identical=0 differ=0" reads exactly like a pass, so the gate
// refuses rather than reports whenever it cannot prove it had something to look at:
//
//   - a path that is not a store directory holding data.mdb;
//   - a store with no `_meta`, or a `_meta` with no class-table rows: with no class table there is
//     no name to key by;
//   - a store with zero class maps, or two stores with NO class map in common by name;
//   - fewer than SNC_MIN_ROWS rows compared in total;
//   - AN UNLABELLED CLASS MAP: a dbi whose index is absent from that store's class table;
//   - a stored class index, anywhere, that the class table cannot name.
//
// COST: linear in stored bytes, so this is an on-demand gate. Its subject does not exist in CI —
// a runner has no LMDB store. `--self-test` builds its own synthetic stores, needs no data, and is
// what CI runs.
//
// USAGE
//
//   on-demand A/B     StoreNames <a.lmdb> <b.lmdb>
//   negative test     StoreNames --self-test        (it bites AND it refuses)
//
//   exit 0  every class map and every re-keyed index agrees under the class name
//   exit 1  they do not — each difference is printed
//   exit 2  GATE INOPERATIVE: it could not do the thing it reports on. Never a skip.
//
//   SNC_MIN_ROWS      floor on the total rows compared. Default 1.
//
import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.lmdbjava.Txn;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import toolkit.util.Conf;

public class StoreNames {

    /// GATE INOPERATIVE. Never a skip, never a pass: exit 2.
    static class Inoperative extends RuntimeException {
        Inoperative(String m) { super(m); }
    }

    static void refuse(String msg) { throw new Inoperative(msg); }

    record Side(String path, Map<Integer, String> table, Map<String, long[]> rows,
                Map<String, String> sha, Map<String, String> idxMaps, Map<String, String> meta,
                long totalRows) {}

    static Env<byte[]> open(String p) {
        File f = new File(p);
        if (!f.isDirectory()) {
            refuse("not a store directory: " + p + "\n"
                    + "  A v2/v1 store is a DIRECTORY holding data.mdb. Comparing a path that is not\n"
                    + "  one cannot fail in an informative way, so it is refused before it is opened.");
        }
        if (!new File(f, "data.mdb").isFile()) {
            refuse("no data.mdb in " + p + " — this is not an LMDB environment");
        }
        try {
            return Env.create(ByteArrayProxy.PROXY_BA).setMapSize(1L << 40).setMaxDbs(4096)
                    .open(f, EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS);
        } catch (RuntimeException e) {
            refuse("cannot open " + p + " read-only: " + e);
            throw e; // unreachable
        }
    }

    /// LMDB dbi names are C strings, so a class idx whose high byte is 0 arrives 1 byte long.
    static int idxOfMapName(byte[] n) {
        if (n.length == 1) return n[0] & 0xFF;
        return (n[0] & 0xFF) | ((n[1] & 0xFF) << 8);
    }

    static int u16(byte lo, byte hi) { return (lo & 0xFF) | ((hi & 0xFF) << 8); }

    static long u64le(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xFFL);
        return v;
    }

    static int u32le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 3] & 0xFF) << 24);
    }

    /// idx -&gt; class name, or REFUSE: rendering an unknown idx as "?549" would put the number back
    /// into the hash.
    static String nm(Side ctx, Map<Integer, String> t, int idx, String where) {
        String s = t.get(idx);
        if (s == null) {
            refuse("class index " + idx + " appears in " + where + " of " + ctx.path()
                    + " but that store's class table cannot name it.\n"
                    + "  A name-keyed comparison that falls back to the NUMBER for some rows is not a\n"
                    + "  name-keyed comparison. Either the class table is truncated or the store was\n"
                    + "  written by something that did not register its classes.");
        }
        return s;
    }

    /// The 2-byte class trailer of a NulKeyCodec key: id NUL version NUL classIdx(2 LE).
    static void hashEncodedTargetKey(MessageDigest md, Side ctx, byte[] key, String where) {
        if (key.length < 2) {
            refuse("a " + where + " value in " + ctx.path() + " is " + key.length
                    + " bytes — too short to carry a 2-byte class trailer");
        }
        md.update(key, 0, key.length - 2);
        md.update(nm(ctx, ctx.table(), u16(key[key.length - 2], key[key.length - 1]), where)
                .getBytes(StandardCharsets.UTF_8));
    }

    /// A full_key is classIdx in the high 32 bits, local key in the low 32.
    static void hashFullKey(MessageDigest md, Side ctx, long fk, String where) {
        md.update(nm(ctx, ctx.table(), (int) (fk >>> 32), where).getBytes(StandardCharsets.UTF_8));
        md.update(String.valueOf(fk & 0xFFFFFFFFL).getBytes(StandardCharsets.UTF_8));
    }

    static final long TOMBSTONE = -1L;

    static Side read(String path) {
        Map<Integer, String> table = new TreeMap<>();
        Map<String, long[]> rows = new TreeMap<>();
        Map<String, String> sha = new TreeMap<>();
        Map<String, String> idxMaps = new TreeMap<>();
        Map<String, String> meta = new TreeMap<>();
        long total = 0;
        Side ctx = new Side(path, table, rows, sha, idxMaps, meta, 0);
        try (Env<byte[]> env = open(path)) {
            List<byte[]> raw = new ArrayList<>(env.getDbiNames());
            if (raw.isEmpty()) refuse(path + " holds no sub-databases at all — it is an empty environment");

            // ---- pass 1: the class table. Everything below is keyed through it.
            boolean sawMeta = false;
            for (byte[] nb : raw) {
                if (!"_meta".equals(new String(nb, StandardCharsets.UTF_8))) continue;
                sawMeta = true;
                Dbi<byte[]> d = env.openDbi(nb);
                try (Txn<byte[]> tx = env.txnRead(); CursorIterable<byte[]> it = d.iterate(tx)) {
                    for (CursorIterable.KeyVal<byte[]> kv : it) {
                        byte[] k = kv.key();
                        if (k.length == 3 && k[0] == 'c') {
                            table.put(u16(k[1], k[2]), new String(kv.val(), StandardCharsets.UTF_8));
                        } else {
                            meta.put(new String(k, StandardCharsets.UTF_8),
                                    HexFormat.of().formatHex(kv.val()));
                        }
                    }
                }
            }
            if (!sawMeta) {
                refuse(path + " has no `_meta` sub-database, so it carries no class table.\n"
                        + "  Without one there is no name to key by and every comparison this tool makes\n"
                        + "  would silently degrade to the class NUMBERING it exists to see past.");
            }
            if (table.isEmpty()) {
                refuse(path + "'s `_meta` holds no class-table rows ('c' + 2-byte idx).\n"
                        + "  Same consequence as a missing `_meta`: nothing can be compared by name.");
            }

            // ---- pass 2: every sub-database.
            int classMaps = 0;
            for (byte[] nb : raw) {
                String literal = new String(nb, StandardCharsets.UTF_8);
                boolean classMap = nb.length <= 2;
                if (classMap && !table.containsKey(idxOfMapName(nb))) {
                    refuse("UNLABELLED CLASS MAP in " + path + ": sub-database "
                            + idxOfMapName(nb) + " has no row in that store's class table.\n"
                            + "  The probe version of this tool skipped such a map SILENTLY, which is the\n"
                            + "  exact shape AbstractLmdbStore.dbNames() was flagged for in #101 job 2 — a\n"
                            + "  class map whose stored name no longer resolves is dropped without a word.\n"
                            + "  A dropped map must never be able to make this run greener.");
                }
                if (classMap) classMaps++;
                Dbi<byte[]> d = env.openDbi(nb);
                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA-256");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                long n = 0;
                long bytes = 0;
                try (Txn<byte[]> tx = env.txnRead(); CursorIterable<byte[]> it = d.iterate(tx)) {
                    for (CursorIterable.KeyVal<byte[]> kv : it) {
                        byte[] k = kv.key();
                        byte[] v = kv.val();
                        n++;
                        bytes += v.length;
                        switch (classMap ? "" : literal) {
                            case "_id_idx", "_embedding_idx" -> {
                                // key: id NUL version NUL classIdx(2 LE)   value: full_key u64 LE
                                hashEncodedTargetKey(md, ctx, k, literal + " key");
                                if (v.length < 8) refuse(literal + " value is " + v.length + " bytes in " + path);
                                hashFullKey(md, ctx, u64le(v, 0), literal + " value");
                                md.update((byte) 1);
                            }
                            case "_edge_blocks" -> {
                                // key: [kind][4-byte LE block#]   value: 16-byte (first, second) pairs
                                if (k.length != 5) refuse("_edge_blocks key is " + k.length + " bytes in " + path);
                                if (v.length % 16 != 0) refuse("_edge_blocks value is " + v.length
                                        + " bytes in " + path + " — not a whole number of 16-byte pairs");
                                int kind = k[0] & 0xFF;
                                md.update(k);
                                md.update((byte) 0);
                                for (int off = 0; off < v.length; off += 16) {
                                    long a = u64le(v, off);
                                    long b = u64le(v, off + 8);
                                    if (a == TOMBSTONE) {           // a dead slot, kept in place
                                        md.update("TOMB".getBytes(StandardCharsets.UTF_8));
                                    } else {
                                        hashFullKey(md, ctx, a, "_edge_blocks referrer");
                                        if (kind == 0) {
                                            hashFullKey(md, ctx, b, "_edge_blocks target");
                                        } else {
                                            // kind 1's second column is an intern id into
                                            // _unres_keys, not a full key. Re-keying it happens
                                            // in the _unres_keys map below; the id itself is
                                            // insertion order and is compared as a number.
                                            md.update(("i" + b).getBytes(StandardCharsets.UTF_8));
                                        }
                                    }
                                    md.update((byte) 2);
                                }
                                md.update((byte) 1);
                            }
                            case "_unres_keys" -> {
                                // key: 4-byte LE intern id   value: encoded target key
                                if (k.length != 4) refuse("_unres_keys key is " + k.length + " bytes in " + path);
                                md.update(String.valueOf(u32le(k, 0)).getBytes(StandardCharsets.UTF_8));
                                md.update((byte) 0);
                                hashEncodedTargetKey(md, ctx, v, "_unres_keys value");
                                md.update((byte) 1);
                            }
                            case "_meta" -> {
                                if (k.length == 3 && k[0] == 'c') {
                                    md.update(kv.val());     // NAME only; the idx key is what moves
                                } else {
                                    md.update(k);
                                    md.update((byte) 0);
                                    md.update(v);
                                }
                                md.update((byte) 1);
                            }
                            default -> {
                                md.update(k);
                                md.update((byte) 0);
                                md.update(v);
                                md.update((byte) 1);
                            }
                        }
                    }
                }
                total += n;
                String h = HexFormat.of().formatHex(md.digest());
                if (classMap) {
                    rows.put(table.get(idxOfMapName(nb)), new long[] {n, bytes});
                    sha.put(table.get(idxOfMapName(nb)), h);
                } else {
                    idxMaps.put(literal, n + " " + h);
                }
            }
            if (classMaps == 0) {
                refuse(path + " has a class table but not one class map. There are no stored objects\n"
                        + "  to compare, and 'identical' over nothing is the false green this gate refuses.");
            }
        }
        return new Side(path, table, rows, sha, idxMaps, meta, total);
    }

    // ------------------------------------------------------------------------------- the run

    static int compare(String pa, String pb) {
        long minRows = Conf.longOf(Conf.DIFF_MIN_ROWS);
        Side a = read(pa);
        Side b = read(pb);
        System.out.println("A " + pa);
        System.out.println("B " + pb);
        System.out.println("classTable rows: A=" + a.table().size() + " B=" + b.table().size()
                + "   (the MODEL's registry, not the data — see the header)");

        boolean bad = false;

        // ---- class maps, by name
        TreeSet<String> all = new TreeSet<>(a.rows().keySet());
        all.addAll(b.rows().keySet());
        int identical = 0;
        int differ = 0;
        int only = 0;
        long ba = 0;
        long bb = 0;
        long comparedRows = 0;
        for (String n : all) {
            long[] ra = a.rows().get(n);
            long[] rb = b.rows().get(n);
            if (ra == null || rb == null) {
                only++;
                System.out.println("  ONLY-" + (ra == null ? "B" : "A") + "  " + n
                        + "  rows=" + (ra == null ? rb[0] : ra[0]));
                continue;
            }
            ba += ra[1];
            bb += rb[1];
            comparedRows += ra[0];
            if (ra[0] == rb[0] && a.sha().get(n).equals(b.sha().get(n))) identical++;
            else {
                differ++;
                System.out.printf("  DIFFER %-44s rows %d/%d bytes %d/%d (%+d)%n",
                        n, ra[0], rb[0], ra[1], rb[1], rb[1] - ra[1]);
            }
        }
        System.out.println("CLASS MAPS BY NAME: identical=" + identical + " differ=" + differ
                + " onlyOneSide=" + only + " blobBytes " + ba + "/" + bb + " (" + (bb - ba) + ")");
        if (identical + differ == 0) {
            refuse("the two stores share NO class map by name (A has " + a.rows().size()
                    + ", B has " + b.rows().size() + ").\n"
                    + "  'identical=0 differ=0' reads exactly like a pass and is not one. Either these are\n"
                    + "  two unrelated stores or one side's class table does not label what it holds.");
        }
        if (comparedRows < minRows) {
            refuse(comparedRows + " rows compared across the class maps in common, floor is "
                    + minRows + " (SNC_MIN_ROWS).\n"
                    + "  A comparison over an empty subject is not a green.");
        }
        if (differ > 0 || only > 0) bad = true;

        // ---- the four index maps, re-keyed by name
        TreeSet<String> sp = new TreeSet<>(a.idxMaps().keySet());
        sp.addAll(b.idxMaps().keySet());
        for (String n : sp) {
            String va = a.idxMaps().get(n);
            String vb = b.idxMaps().get(n);
            boolean same = Objects.equals(va, vb);
            if ("_meta".equals(n)) continue;   // handled below; the class table is a model fact
            System.out.println("  " + (same ? "IDENTICAL-BY-NAME " : "STILL-DIFFERS     ")
                    + n + "  A=" + va + "  B=" + vb);
            if (!same) bad = true;
        }

        // ---- _meta: the non-class-table rows are data; the class table is the model's registry
        TreeSet<String> mk = new TreeSet<>(a.meta().keySet());
        mk.addAll(b.meta().keySet());
        for (String k : mk) {
            String va = a.meta().get(k);
            String vb = b.meta().get(k);
            boolean same = Objects.equals(va, vb);
            System.out.println("  " + (same ? "META-SAME  " : "META-DIFFERS ") + k
                    + "  A=" + va + "  B=" + vb);
            if (!same) bad = true;
        }
        TreeSet<String> na = new TreeSet<>(a.table().values());
        TreeSet<String> nb2 = new TreeSet<>(b.table().values());
        TreeSet<String> onlyA = new TreeSet<>(na);
        onlyA.removeAll(nb2);
        TreeSet<String> onlyB = new TreeSet<>(nb2);
        onlyB.removeAll(na);
        System.out.println("  CLASS TABLE (informational, never fails): " + na.size() + "/" + nb2.size()
                + " names, " + onlyA.size() + " only in A, " + onlyB.size() + " only in B");
        if (!onlyA.isEmpty()) System.out.println("     only in A: " + head(onlyA));
        if (!onlyB.isEmpty()) System.out.println("     only in B: " + head(onlyB));

        System.out.println();
        System.out.println("  rows read: A=" + a.totalRows() + " B=" + b.totalRows()
                + "; class maps compared by name: " + (identical + differ));
        System.out.println("  BLIND SPOTS, every run: this compares STORED BYTES under a class name.");
        System.out.println("    It does not unmarshal, local keys and intern ids are compared as numbers");
        System.out.println("    (so it sees insertion ORDER), _edge_blocks tombstones are compared as");
        System.out.println("    slots, and the class table is reported rather than enforced. Use");
        System.out.println("    tools/diff/Graph for the content-only question.");

        if (bad) {
            System.err.println("StoreNames: REFUSED — the two stores differ under the class name.");
            System.err.println("  That is NOT a renumbering. Read the DIFFER/STILL-DIFFERS lines above.");
            return 1;
        }
        System.out.println("[gate] the two stores are identical under the class name ("
                + identical + " class maps, " + comparedRows + " rows)");
        return 0;
    }

    static String head(TreeSet<String> s) {
        List<String> l = new ArrayList<>(s);
        if (l.size() <= 12) return String.join(", ", l);
        return String.join(", ", l.subList(0, 12)) + ", … (" + (l.size() - 12) + " more)";
    }

    // ------------------------------------------------------------------------------ self-test
    //
    // Both halves are demonstrated: a comparator that always says "differ" would pass a bites-test
    // and be useless.
    //
    //   1  PURE RENUMBERING -> exit 0, the positive control.
    //   2  ONE BYTE OF ONE BLOB -> exit 1, naming the class.
    //   3  AN EDGE RETARGETED -> exit 1. Only `_edge_blocks` differs.
    //   4  NO CLASS TABLE -> exit 2.
    //   5  NO CLASS MAP IN COMMON BY NAME -> exit 2, not the "identical=0 differ=0" that reads
    //      like a pass.
    //   6  AN UNLABELLED CLASS MAP -> exit 2, never a silent skip.
    //   7  A PATH THAT IS NOT A STORE -> exit 2.
    //
    // The stores are synthetic and tiny: no feed data, no model jar. This is the part of the gate
    // that runs in CI.

    static void put(Env<byte[]> env, String name, byte[]... kv) {
        put(env, name.getBytes(StandardCharsets.UTF_8), kv);
    }

    static void put(Env<byte[]> env, byte[] name, byte[]... kv) {
        Dbi<byte[]> d = env.openDbi(name, DbiFlags.MDB_CREATE);
        try (Txn<byte[]> tx = env.txnWrite()) {
            for (int i = 0; i < kv.length; i += 2) d.put(tx, kv[i], kv[i + 1]);
            tx.commit();
        }
    }

    /// The dbi name of a class map: the 2-byte LE class idx, truncated to 1 byte when the high
    /// byte is 0 because an LMDB dbi name is a C string. (A class idx whose LOW byte is 0 would
    /// therefore be unnameable; the real store never assigns one, and the fixtures avoid it.)
    static byte[] mapName(int idx) {
        return idx < 256 ? new byte[] {(byte) idx} : new byte[] {(byte) idx, (byte) (idx >> 8)};
    }

    static byte[] cKey(int idx) { return new byte[] {'c', (byte) idx, (byte) (idx >> 8)}; }

    static byte[] u32(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    static byte[] u64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    static byte[] idKey(String id, String version, int idx) {
        return cat(id.getBytes(StandardCharsets.UTF_8), new byte[] {0},
                version.getBytes(StandardCharsets.UTF_8), new byte[] {0},
                new byte[] {(byte) idx, (byte) (idx >> 8)});
    }

    static long fullKey(int idx, int local) { return ((long) idx << 32) | (local & 0xFFFFFFFFL); }

    /// A minimal but structurally real v2 store: `_meta` class table, two class maps, `_id_idx`,
    /// `_embedding_idx`, `_edge_blocks` (both kinds, including a tombstone) and `_unres_keys`.
    ///
    /// @param lineIdx  class index the store gives `Line`
    /// @param sjIdx    class index the store gives `ServiceJourney`
    /// @param blob     the `Line` fragment bytes (arm 2 perturbs one byte of this)
    /// @param edgeDst  local key the resolved edge points at (arm 3 retargets this)
    /// @param labelSj  false = leave `ServiceJourney`'s map UNLABELLED (arm 6)
    /// @param names    false = give the two stores disjoint class names (arm 5)
    static Path mkStore(Path dir, String name, int lineIdx, int sjIdx, String blob, int edgeDst,
                        boolean labelSj, boolean names, boolean classTable) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p);
        Env<byte[]> env = Env.create(ByteArrayProxy.PROXY_BA).setMapSize(1L << 26).setMaxDbs(16)
                .open(p.toFile());
        try {
            String lineName = names ? "Line" : "Corridor";
            String sjName = names ? "ServiceJourney" : "Passage";
            List<byte[]> meta = new ArrayList<>();
            meta.add("format".getBytes(StandardCharsets.UTF_8));
            meta.add("java-v2:1".getBytes(StandardCharsets.UTF_8));
            if (classTable) {
                meta.add(cKey(lineIdx));
                meta.add(lineName.getBytes(StandardCharsets.UTF_8));
                if (labelSj) {
                    meta.add(cKey(sjIdx));
                    meta.add(sjName.getBytes(StandardCharsets.UTF_8));
                }
            }
            put(env, "_meta", meta.toArray(new byte[0][]));
            put(env, mapName(lineIdx), u32(0), blob.getBytes(StandardCharsets.UTF_8));
            put(env, mapName(sjIdx), u32(0),
                    "<ServiceJourney id=\"sj1\"/>".getBytes(StandardCharsets.UTF_8));
            put(env, "_id_idx",
                    idKey("line1", "1", lineIdx), u64(fullKey(lineIdx, 0)),
                    idKey("sj1", "1", sjIdx), u64(fullKey(sjIdx, 0)));
            put(env, "_embedding_idx",
                    idKey("emb1", "1", sjIdx), u64(fullKey(lineIdx, 0)));
            // kind 0: one live edge sj -> line(edgeDst), one tombstone.
            put(env, "_edge_blocks",
                    cat(new byte[] {0}, u32(0)),
                    cat(u64(fullKey(sjIdx, 0)), u64(fullKey(lineIdx, edgeDst)),
                            u64(TOMBSTONE), u64(TOMBSTONE)),
                    // kind 1: one unresolved (referrer sj, intern id 0).
                    cat(new byte[] {1}, u32(0)),
                    cat(u64(fullKey(sjIdx, 0)), u64(0)));
            put(env, "_unres_keys", u32(0), idKey("missing1", "1", lineIdx));
        } finally {
            env.close();
        }
        return p;
    }

    record ArmResult(int rc, String out) {}

    static ArmResult runArm(String a, String b) {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream cap = new java.io.PrintStream(buf, true, StandardCharsets.UTF_8);
        java.io.PrintStream so = System.out;
        java.io.PrintStream se = System.err;
        System.setOut(cap);
        System.setErr(cap);
        int rc;
        try {
            rc = compare(a, b);
        } catch (Inoperative e) {
            System.out.println("GATE INOPERATIVE: " + e.getMessage());
            rc = 2;
        } finally {
            System.setOut(so);
            System.setErr(se);
        }
        return new ArmResult(rc, buf.toString(StandardCharsets.UTF_8));
    }

    static boolean arm(String label, String what, int wantRc, String wantText, String a, String b) {
        ArmResult r = runArm(a, b);
        boolean ok = r.rc() == wantRc && (wantText == null || r.out().contains(wantText));
        System.out.println("--- arm " + label + ": " + what + " -> expect exit " + wantRc
                + (wantText == null ? "" : " naming \"" + wantText + "\""));
        for (String l : r.out().split("\n", -1)) {
            if (!l.isEmpty()) System.out.println("    " + l);
        }
        System.out.println("    arm " + label + " " + (ok ? "OK" : "FAILED (exit " + r.rc() + ")"));
        return ok;
    }

    static int selfTest() throws Exception {
        Path dir = Files.createTempDirectory("storenamecmp-selftest");
        System.out.println("=== StoreNames --self-test: seven arms in " + dir);
        String blob = "<Line id=\"line1\" version=\"1\"/>";
        // A and B hold the same data under DIFFERENT class numbering.
        Path a = mkStore(dir, "a", 7, 9, blob, 0, true, true, true);
        Path b = mkStore(dir, "b", 300, 301, blob, 0, true, true, true);
        Path blobDiff = mkStore(dir, "blobdiff", 300, 301,
                "<Line id=\"line1\" version=\"2\"/>", 0, true, true, true);
        Path edgeDiff = mkStore(dir, "edgediff", 300, 301, blob, 1, true, true, true);
        Path noTable = mkStore(dir, "notable", 300, 301, blob, 0, true, true, false);
        Path disjoint = mkStore(dir, "disjoint", 300, 301, blob, 0, true, false, true);
        Path unlabelled = mkStore(dir, "unlabelled", 300, 301, blob, 0, false, true, true);

        boolean ok = true;
        ok &= arm("1", "the SAME data under two different class numberings", 0,
                "[gate] the two stores are identical under the class name", a.toString(), b.toString());
        ok &= arm("2", "one byte of one Line fragment changed", 1, "DIFFER Line",
                a.toString(), blobDiff.toString());
        ok &= arm("3", "one _edge_blocks edge retargeted (class maps untouched)", 1,
                "STILL-DIFFERS     _edge_blocks", a.toString(), edgeDiff.toString());
        ok &= arm("4", "B has no class table", 2, "no class-table rows",
                a.toString(), noTable.toString());
        ok &= arm("5", "no class map in common by NAME", 2, "share NO class map by name",
                a.toString(), disjoint.toString());
        ok &= arm("6", "B has an UNLABELLED class map", 2, "UNLABELLED CLASS MAP",
                a.toString(), unlabelled.toString());
        ok &= arm("7", "B does not exist", 2, "not a store directory",
                a.toString(), dir.resolve("no-such-store").toString());

        System.out.println();
        if (!ok) {
            System.err.println("StoreNames: SELF-TEST FAILED. Until every arm passes, this gate's");
            System.err.println("  green means nothing — and a store comparator that cannot fail is the");
            System.err.println("  §7.8 shape it was written to escape.");
            return 2;
        }
        System.out.println("StoreNames: self-test OK — it agrees on a pure renumbering, bites on a");
        System.out.println("  blob byte and on an edge, and refuses four subjects it cannot compare.");
        rmTree(dir);
        return 0;
    }

    /// The self-test's stores are throwaway; a failing run keeps them.
    static void rmTree(Path p) throws Exception {
        try (var s = Files.walk(p)) {
            for (Path q : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                System.exit(selfTest());
            }
            if (args.length != 2) {
                refuse("usage: StoreNames <a.lmdb> <b.lmdb>   |   StoreNames --self-test");
            }
            System.exit(compare(args[0], args[1]));
        } catch (Inoperative e) {
            System.err.println("GATE INOPERATIVE: " + e.getMessage());
            System.err.println("  This is exit 2, not a skip and never a pass.");
            System.exit(2);
        }
    }
}
