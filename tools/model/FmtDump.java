// Read-only byte-level spot-check dumper for the DB_FORMAT.md appendix.
// Usage: java.sh -cp "<jar>:<deps>/*" FmtDump.java <db-dir> v1|v2
import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class FmtDump {

    static String hex(byte[] b, int max) {
        StringBuilder s = new StringBuilder();
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) s.append(String.format("%02x ", b[i]));
        if (b.length > max) s.append("… (").append(b.length).append(" B)");
        return s.toString().trim();
    }

    static String printable(byte[] b, int max) {
        StringBuilder s = new StringBuilder();
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) {
            char c = (char) (b[i] & 0xFF);
            s.append(c >= 32 && c < 127 ? c : '.');
        }
        return s.toString();
    }

    static long u64le(byte[] b) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[i] & 0xFFL) << (8 * i);
        return v;
    }

    static void dumpRows(Env<byte[]> env, Dbi<byte[]> d, String name, int limit, int maxBytes) {
        System.out.println("== map " + name + " ==");
        try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
            System.out.println("  entries=" + d.stat(tx).entries);
            int i = 0;
            try (CursorIterable<byte[]> ci = d.iterate(tx)) {
                for (CursorIterable.KeyVal<byte[]> kv : ci) {
                    if (i++ >= limit) break;
                    System.out.println("  K[" + kv.key().length + "] " + hex(kv.key(), maxBytes)
                            + "  |" + printable(kv.key(), maxBytes) + "|");
                    System.out.println("  V[" + kv.val().length + "] " + hex(kv.val(), maxBytes)
                            + "  |" + printable(kv.val(), maxBytes) + "|");
                }
            }
        }
    }

    public static void main(String[] args) {
        Path path = Path.of(args[0]);
        String fmt = args[1];
        Env<byte[]> env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(1L << 30).setMaxDbs(4096).setMaxReaders(126)
                .open(path.toFile(), EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS);
        System.out.println("=== " + path + " (" + fmt + ") ===");
        System.out.print("dbi names:");
        int named = 0;
        byte[] firstClassMap = null;
        for (byte[] n : env.getDbiNames()) {
            if (n.length <= 2) {
                named++;
                if (firstClassMap == null || compare(n, firstClassMap) < 0) firstClassMap = n;
            } else {
                System.out.print(" " + new String(n, StandardCharsets.UTF_8));
            }
        }
        System.out.println("  + " + named + " class maps (1-2 byte names); smallest name = "
                + hex(firstClassMap, 2));

        Dbi<byte[]> idIdx = env.openDbi("_id_idx".getBytes(StandardCharsets.UTF_8));
        dumpRows(env, idIdx, "_id_idx", 3, 64);

        Dbi<byte[]> cls = env.openDbi(firstClassMap);
        // decode first class-map row value: [4B LE len][lz4 block]
        try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
            try (CursorIterable<byte[]> ci = cls.iterate(tx)) {
                for (CursorIterable.KeyVal<byte[]> kv : ci) {
                    System.out.println("== class map name=" + hex(firstClassMap, 2) + " first row ==");
                    System.out.println("  K[" + kv.key().length + "] " + hex(kv.key(), 8));
                    byte[] v = kv.val();
                    System.out.println("  V[" + v.length + "] head: " + hex(v, 24));
                    int orig = (v[0] & 0xFF) | ((v[1] & 0xFF) << 8) | ((v[2] & 0xFF) << 16) | ((v[3] & 0xFF) << 24);
                    byte[] out = new byte[orig];
                    net.jpountz.lz4.LZ4Factory.fastestInstance().fastDecompressor()
                            .decompress(v, 4, out, 0, orig);
                    String xml = new String(out, StandardCharsets.UTF_8);
                    System.out.println("  origLen=" + orig + " decompressed head: "
                            + xml.substring(0, Math.min(120, xml.length())));
                    break;
                }
            }
        }

        if (fmt.equals("v1")) {
            Dbi<byte[]> classIdx = env.openDbi("_class_idx".getBytes(StandardCharsets.UTF_8));
            dumpRows(env, classIdx, "_class_idx (first rows)", 3, 40);
            try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
                for (int[] mk : new int[][] {{0xFF, 0xFF}, {0xFE, 0xFF}, {0xFD, 0xFF}}) {
                    byte[] k = {(byte) mk[0], (byte) mk[1]};
                    byte[] v = classIdx.get(tx, k);
                    System.out.println("  marker " + hex(k, 2) + " -> "
                            + (v == null ? "absent" : "|" + printable(v, 20) + "|"));
                }
            }
            Dbi<byte[]> outward = env.openDbi("_reference_outward".getBytes(StandardCharsets.UTF_8),
                    DbiFlags.MDB_DUPSORT, DbiFlags.MDB_DUPFIXED, DbiFlags.MDB_INTEGERKEY, DbiFlags.MDB_INTEGERDUP);
            System.out.println("== map _reference_outward (dup rows) ==");
            try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
                System.out.println("  entries=" + outward.stat(tx).entries);
                int i = 0;
                try (CursorIterable<byte[]> ci = outward.iterate(tx)) {
                    for (CursorIterable.KeyVal<byte[]> kv : ci) {
                        if (i++ >= 4) break;
                        long k = u64le(kv.key()), v = u64le(kv.val());
                        System.out.printf("  K %s (cls=%d loc=%d) -> V %s (cls=%d loc=%d)%n",
                                hex(kv.key(), 8), k >>> 32, (int) k, hex(kv.val(), 8), v >>> 32, (int) v);
                    }
                }
            }
            Dbi<byte[]> unres = env.openDbi("_unresolved".getBytes(StandardCharsets.UTF_8),
                    DbiFlags.MDB_DUPSORT, DbiFlags.MDB_INTEGERKEY);
            dumpRows(env, unres, "_unresolved", 2, 64);
            Dbi<byte[]> meta = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8));
            dumpRows(env, meta, "_meta", 5, 24);
        } else {
            Dbi<byte[]> meta = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8));
            dumpRows(env, meta, "_meta (first rows)", 6, 24);
            try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
                byte[] seq = meta.get(tx, "seq".getBytes(StandardCharsets.UTF_8));
                System.out.println("  seq -> " + hex(seq, 8) + " = " + u64le(seq));
                byte[] f = meta.get(tx, "format".getBytes(StandardCharsets.UTF_8));
                System.out.println("  format -> |" + printable(f, 20) + "|");
            }
            Dbi<byte[]> blocks = env.openDbi("_edge_blocks".getBytes(StandardCharsets.UTF_8));
            System.out.println("== map _edge_blocks ==");
            try (org.lmdbjava.Txn<byte[]> tx = env.txnRead()) {
                System.out.println("  entries=" + blocks.stat(tx).entries);
                try (CursorIterable<byte[]> ci = blocks.iterate(tx)) {
                    for (CursorIterable.KeyVal<byte[]> kv : ci) {
                        byte[] k = kv.key();
                        byte[] v = kv.val();
                        System.out.println("  key " + hex(k, 5) + " (kind=" + k[0]
                                + " block#=" + ((k[1] & 0xFF) | ((k[2] & 0xFF) << 8) | ((k[3] & 0xFF) << 16) | ((k[4] & 0xFF) << 24))
                                + ") valueLen=" + v.length + " (" + (v.length / 16) + " pairs)");
                        for (int off = 0; off < Math.min(48, v.length); off += 16) {
                            byte[] a = new byte[8], b2 = new byte[8];
                            System.arraycopy(v, off, a, 0, 8);
                            System.arraycopy(v, off + 8, b2, 0, 8);
                            long la = u64le(a), lb = u64le(b2);
                            System.out.printf("    pair[%d] %s | %s  (a: cls=%d loc=%d, b=%d)%n",
                                    off / 16, hex(a, 8), hex(b2, 8), la >>> 32, (int) la, lb);
                        }
                    }
                }
            }
            Dbi<byte[]> uk = env.openDbi("_unres_keys".getBytes(StandardCharsets.UTF_8));
            dumpRows(env, uk, "_unres_keys", 3, 64);
        }
        try {
            Dbi<byte[]> emb = env.openDbi("_embedding_idx".getBytes(StandardCharsets.UTF_8),
                    DbiFlags.MDB_DUPSORT, DbiFlags.MDB_DUPFIXED);
            dumpRows(env, emb, "_embedding_idx", 2, 64);
        } catch (Exception e) {
            System.out.println("== _embedding_idx absent ==");
        }
        env.close();
    }

    static int compare(byte[] a, byte[] b) {
        return java.util.Arrays.compareUnsigned(a, b);
    }
}
