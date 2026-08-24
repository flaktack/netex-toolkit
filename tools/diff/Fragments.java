// Logical, order-independent digest of every stored fragment of a db:
// "<class>\t<localKey hex>\t<sha256 of the decompressed fragment>", sorted. Two databases with an
// identical digest file store byte-identical fragments for byte-identical keys.
//
// Run: java -cp "<jar>:<deps>/*" tools/diff/Fragments.java <db> <out.txt>
import toolkit.codec.FragmentCodec;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

void main(String[] args) throws Exception {
    Path db = Path.of(args[0]);
    FragmentCodec codec = new FragmentCodec();
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    HexFormat hex = HexFormat.of();
    List<String> lines = new ArrayList<>();
    try (Store s = Stores.open(db, true); Txn t = s.roTxn()) {
        for (Class<?> c : s.dbNames(t)) {
            for (RawRow rr : s.iterRawObjects(t, c)) {
                md.reset();
                lines.add(c.getSimpleName() + "\t" + hex.formatHex(rr.localKey()) + "\t"
                        + hex.formatHex(md.digest(codec.decompress(rr.blob()))));
            }
        }
    }
    lines.sort(String::compareTo);
    try (BufferedWriter w = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
        for (String l : lines) {
            w.write(l);
            w.write('\n');
        }
    }
    System.out.println(lines.size() + " rows");
}
