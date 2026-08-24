// Byte-compare two LMDB stores map-by-map, key-by-key.
// Usage: java -cp "<jar>:<deps>/*" tools/diff/StoreBytes.java <a.lmdb> <b.lmdb>
// Prints per-map row counts + a SHA-256 over (map, key, value) for each side, then any
// first differing rows. Exit 0 iff every map, every key and every value is identical.
import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.lmdbjava.Txn;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class StoreBytes {

    record MapDump(long rows, String sha, List<byte[]> keys, List<byte[]> vals) {}

    /// Printable label for a raw dbi name (binary names are shown as hex).
    static String label(byte[] n) {
        boolean printable = n.length > 0;
        for (byte b : n) if (b < 32 || b >= 127) { printable = false; break; }
        return printable ? new String(n, StandardCharsets.UTF_8) : "0x" + HexFormat.of().formatHex(n);
    }

    static Env<byte[]> open(String p) {
        return Env.create(ByteArrayProxy.PROXY_BA).setMapSize(1L << 40).setMaxDbs(4096)
                .open(new File(p), EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS);
    }

    static Map<String, MapDump> dump(String path, boolean keep) throws Exception {
        Map<String, MapDump> out = new LinkedHashMap<>();
        try (Env<byte[]> env = open(path)) {
            // Dbi names are RAW BYTES here — the v2 store names maps with 2-byte binary
            // class-idx keys, so round-tripping them through String corrupts them.
            List<byte[]> raw = new ArrayList<>(env.getDbiNames());
            raw.sort(java.util.Arrays::compareUnsigned);
            for (byte[] nb : raw) {
                String n = label(nb);
                Dbi<byte[]> d = env.openDbi(nb);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long rows = 0;
                List<byte[]> ks = keep ? new ArrayList<>() : null;
                List<byte[]> vs = keep ? new ArrayList<>() : null;
                try (Txn<byte[]> tx = env.txnRead(); CursorIterable<byte[]> it = d.iterate(tx)) {
                    for (CursorIterable.KeyVal<byte[]> kv : it) {
                        byte[] k = kv.key(), v = kv.val();
                        md.update(k); md.update((byte) 0); md.update(v); md.update((byte) 1);
                        rows++;
                        if (keep) { ks.add(k); vs.add(v); }
                    }
                }
                out.put(n, new MapDump(rows, HexFormat.of().formatHex(md.digest()), ks, vs));
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        Map<String, MapDump> A = dump(args[0], true);
        Map<String, MapDump> B = dump(args[1], true);
        TreeSet<String> all = new TreeSet<>(A.keySet()); all.addAll(B.keySet());
        int bad = 0;
        for (String n : all) {
            MapDump a = A.get(n), b = B.get(n);
            if (a == null || b == null) {
                System.out.printf("%-28s MISSING on %s%n", n, a == null ? "A" : "B"); bad++; continue;
            }
            boolean ok = a.rows == b.rows && a.sha.equals(b.sha);
            System.out.printf("%-28s rows %-9d %s %s%n", n, a.rows, a.sha.substring(0, 16),
                    ok ? "IDENTICAL" : "DIFFERS (rows " + a.rows + " vs " + b.rows + ")");
            if (!ok) {
                bad++;
                int shown = 0;
                for (int i = 0; i < Math.min(a.keys.size(), b.keys.size()) && shown < 5; i++) {
                    if (!java.util.Arrays.equals(a.keys.get(i), b.keys.get(i))
                            || !java.util.Arrays.equals(a.vals.get(i), b.vals.get(i))) {
                        System.out.println("   row " + i + " A.key=" + HexFormat.of().formatHex(a.keys.get(i))
                                + " B.key=" + HexFormat.of().formatHex(b.keys.get(i)));
                        System.out.println("      A.val=" + new String(a.vals.get(i), StandardCharsets.UTF_8));
                        System.out.println("      B.val=" + new String(b.vals.get(i), StandardCharsets.UTF_8));
                        shown++;
                    }
                }
            }
        }
        System.out.println(bad == 0 ? "DBCMP: IDENTICAL (" + all.size() + " maps)" : "DBCMP: " + bad + " DIFFERING MAPS");
        System.exit(bad == 0 ? 0 : 1);
    }
}
