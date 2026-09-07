package toolkit.store;

// The `v1` store format was removed; v2 is the one format, and a store-taking test runs once.
// Three consequences are pinned here: the class and its source directory are absent and no source
// file names them, `--format v1` and a v1 database on disk are refused by name, and the loader's
// and `Stores.open`'s defaults are v2. The two on-disk fixtures are built here from the raw LMDB
// signature, so the test carries its own ground truth.

import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.junit.jupiter.api.Test;
import toolkit.Sources;
import toolkit.harness.FeedToDb;
import toolkit.load.ZipLoad;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TestStoreFormat {

    /// The source root this scan reads. Derived, never hard-coded: a constant path would scan a
    /// tree other than the one under test.
    private static final Path TREE = sourceRoot();

    /// The same locator [toolkit.Sources#root] uses, kept separate from it on purpose.
    private static Path sourceRoot() {
        String p = System.getProperty("toolkit.repo.dir");
        if (p != null) return Path.of(p).toAbsolutePath().normalize();
        String cmd = System.getProperty("jdk.launcher.sourcefile");
        if (cmd != null) return Path.of(cmd).toAbsolutePath().getParent().getParent().getParent();
        return Path.of("").toAbsolutePath();
    }

    private static Path tmp() throws IOException {
        Path d = Files.createTempDirectory("toolkit-fmt-");
        d.toFile().deleteOnExit();
        return d;
    }

    // ================================================================ 1. the class is gone

    /// The implementation class is not on the classpath at all.
    @Test
    void testRetiredStoreClassIsAbsent() {
        try {
            Class.forName("toolkit.store.v1.V1CompatStore");
            throw new AssertionError("toolkit.store.v1.V1CompatStore is back on the classpath");
        } catch (ClassNotFoundException expected) {
            // the point
        }
        Check.that(!Files.isDirectory(TREE.resolve("toolkit/src/main/java/toolkit/store/v1")),
                "toolkit/store/v1/ does not exist as a source directory");
    }

    /// No source file under the scanned roots names `V1CompatStore` or `toolkit.store.v1`.
    @Test
    void testTreeDoesNotMentionTheRetiredStore() throws Exception {
        // Sources.ROOTS minus tools/, kept separate so a root list edited for another scan cannot
        // silently change this one's subject.
        List<String> roots = List.of("toolkit/src/main/java", "toolkit/src/test/java",
                "scripts", "model/src/main/java");
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        List<String> missing = new ArrayList<>();
        for (String r : roots) if (!Files.isDirectory(TREE.resolve(r))) missing.add(r);
        // An absent root must fail the test, not shrink its subject to nothing.
        Check.equals(List.of(), missing,
                "source roots exist under " + TREE + " (this scan is vacuous without them)");
        for (String r : roots) {
            Path root = TREE.resolve(r);
            if (!Files.isDirectory(root)) continue;
            try (var w = Files.walk(root)) {
                for (Path p : w.filter(x -> x.toString().endsWith(".java")).toList()) {
                    scanned++;
                    // These two quote the name in their own prose rather than using it.
                    if (p.endsWith("TestStoreFormat.java") || p.endsWith("store/Store.java")) continue;
                    String t = Files.readString(p, StandardCharsets.UTF_8);
                    if (t.contains("V1CompatStore") || t.contains("toolkit.store.v1")) {
                        hits.add(TREE.relativize(p).toString());
                    }
                }
            }
        }
        Check.that(scanned >= 100,
                "the scan read " + scanned + " .java files under " + TREE + " (expect >= 100); "
                        + "a scan that read nothing cannot have found nothing");
        Check.equals(List.of(), hits, "no source file references the retired v1 store");
    }

    /// [toolkit.test.Runner]'s format list holds one format, so a store-taking test runs once.
    /// Runner's list and not this suite's: the suite runs on `TestStoreExtension`, while Runner is
    /// what ships in the jar for somebody testing their own driver.
    @Test
    void testEveryStoreTakingTestRunsExactlyOnce() throws Exception {
        Field f = Runner.class.getDeclaredField("FORMATS");
        f.setAccessible(true);
        String[] formats = (String[]) f.get(null);
        Check.equals(1, formats.length,
                "Runner runs each store-taking test once (formats = " + String.join(",", formats) + ")");
        Check.equals(Stores.V2, formats[0], "the one format is v2");
    }

    // ================================================================ 2. the refusals

    /// `--format v1` is refused by name and with the reason; an unknown format gets its own
    /// message.
    @Test
    void testCreateRefusesTheRetiredFormat() throws Exception {
        Path d = tmp().resolve("nope.lmdb");
        try {
            Stores.create(d, Stores.RETIRED_V1);
            throw new AssertionError("Stores.create accepted the retired v1 format");
        } catch (IllegalArgumentException e) {
            Check.that(e.getMessage().contains("retired"),
                    "the refusal says the format was retired — got: " + e.getMessage());
        }
        Check.that(!Files.exists(d.resolve("data.mdb")),
                "a refused create leaves no half-made store behind");
        try {
            Stores.create(tmp().resolve("v9.lmdb"), "v9");
            throw new AssertionError("Stores.create accepted format v9");
        } catch (IllegalArgumentException e) {
            Check.that(e.getMessage().contains("unknown store format"),
                    "an unknown format still says so — got: " + e.getMessage());
        }
    }

    /// A real v1 database on disk. Fixture = the exact signature v1 carried: a `_class_idx` map
    /// whose `FF FF` row holds the key-format guard `raw-utf8-nul:1`, and no `_meta` map.
    @Test
    void testSniffRefusesARetiredV1Database() throws Exception {
        Path d = tmp().resolve("v1.lmdb");
        Files.createDirectories(d);
        writeLmdb(d, "_class_idx", new byte[] {(byte) 0xFF, (byte) 0xFF}, "raw-utf8-nul:1");
        try {
            Stores.sniffFormat(d);
            throw new AssertionError("sniffFormat accepted a v1 database");
        } catch (IllegalStateException e) {
            Check.that(e.getMessage().contains("v1") && e.getMessage().contains("retired"),
                    "the refusal identifies it as a retired v1 db — got: " + e.getMessage());
        }
        // Stores.open must not find another way in.
        try {
            Stores.open(d, true);
            throw new AssertionError("Stores.open opened a v1 database");
        } catch (IllegalStateException expected) {
            // the point
        }
    }

    /// Bytes that are an LMDB environment but not a toolkit store get the other message.
    @Test
    void testSniffDistinguishesNonToolkitBytes() throws Exception {
        Path d = tmp().resolve("alien.lmdb");
        Files.createDirectories(d);
        writeLmdb(d, "something_else", "k".getBytes(StandardCharsets.UTF_8), "v");
        try {
            Stores.sniffFormat(d);
            throw new AssertionError("sniffFormat accepted a non-toolkit database");
        } catch (IllegalStateException e) {
            Check.that(e.getMessage().contains("not a toolkit store"),
                    "a non-toolkit db is diagnosed as such — got: " + e.getMessage());
            Check.that(!e.getMessage().contains("retired"),
                    "and is NOT blamed on the retired format — got: " + e.getMessage());
        }
    }

    // ================================================================ 3. the defaults

    /// The loader run with no --format, with the format marker read back off the store it wrote.
    ///
    /// `FeedToDb.driverMain(...)` and not `scripts/NetexToDb.java`: a driver in the default package
    /// cannot be imported from a named one. The default under test is FeedToDb's either way — the
    /// driver never mentions a format.
    @Test
    void testNetexToDbDefaultsToV2() throws Exception {
        Path dir = tmp();
        Path feed = dir.resolve("feed.xml");
        Files.writeString(feed, TestStore.SKELETON.replace("__FRAMES__",
                "<ResourceFrame id=\"rf\" version=\"1\"><organisations>"
                        + "<Operator id=\"test:Operator:O1\" version=\"1\"><Name>O1</Name></Operator>"
                        + "</organisations></ResourceFrame>"), StandardCharsets.UTF_8);
        Path db = dir.resolve("out.lmdb");
        FeedToDb.driverMain("netex-to-db", new String[] {feed.toString(), db.toString()},
                ZipLoad.Options.DEFAULTS);
        try (Store s = Stores.open(db, true)) {
            Check.equals(Stores.V2, s.formatId(),
                    "NetexToDb with no --format writes v2 (this is THE chain-wide default)");
            Check.equals("java-v2:1", s.formatMarker(), "and the on-disk marker says so");
        }
        Check.equals(Stores.V2, Stores.sniffFormat(db), "and the bytes sniff as v2");
        wipe(dir);
    }

    /// The 2-arg `Stores.open` overload — the other place a default format is set.
    @Test
    void testStoresOpenDefaultsToV2() throws Exception {
        Path dir = tmp();
        Path db = dir.resolve("fresh.lmdb");
        try (Store s = Stores.open(db, false)) {
            Check.equals(Stores.V2, s.formatId(), "Stores.open(path, rw) creates v2");
        }
        wipe(dir);
    }

    /// A one-row LMDB environment at `dir`, with one named map.
    private static void writeLmdb(Path dir, String map, byte[] key, String value) {
        try (Env<byte[]> env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(1L << 20).setMaxDbs(4).open(dir.toFile(), EnvFlags.MDB_NOTLS)) {
            Dbi<byte[]> db = env.openDbi(map.getBytes(StandardCharsets.UTF_8), DbiFlags.MDB_CREATE);
            db.put(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void wipe(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }
}
