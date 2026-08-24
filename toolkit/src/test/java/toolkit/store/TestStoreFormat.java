package toolkit.store;

// THE RETIREMENT GUARD. The `v1` store format was removed: one format, one test run per
// store test. Everything below exists so that removal cannot quietly come undone, and so the
// two things it broke on purpose — reading a v1 database, and `--format v1` — break LOUDLY and
// say why instead of failing somewhere downstream.
//
// Three separate failure modes are pinned here, because they have three different causes:
//
//   1. THE CLASS IS GONE and the tree does not mention it. A future edit that reintroduces
//      toolkit.store.v1 fails testRetiredStoreClassIsAbsent immediately.
//   2. THE ON-DISK REFUSAL IS SPECIFIC. Stores.sniffFormat used to answer "v1" for ANY database
//      without a _meta/format row — a silent fallback. It must now refuse, and it must distinguish
//      a retired v1 database (which the operator can rebuild) from bytes that are not a toolkit
//      store at all. Both fixtures below are BUILT HERE, from the raw LMDB signature, so the test
//      carries its own ground truth.
//   3. THE ENTRY-POINT DEFAULT IS OBSERVED, NOT ASSERTED. The loader driver decides what format
//      an entire downstream chain becomes, and it once defaulted to v1 while FeedToDb defaulted
//      to v2. testNetexToDbDefaultsToV2 runs the real driver with no --format and reads the
//      format marker back off the bytes it wrote.
//
// NEUTER PROOF (each test names the edit that must break it):
//   * testRetiredStoreClassIsAbsent            — restore toolkit/store/v1/V1CompatStore.java.
//   * testTreeDoesNotMentionTheRetiredStore    — put "V1CompatStore" back in any source file.
//   * testEveryStoreTakingTestRunsExactlyOnce  — add a second entry to Runner.FORMATS; the
//                                                suite silently doubles again and this fails.
//   * testCreateRefusesTheRetiredFormat        — make Stores.create fall through to V2 on "v1".
//   * testSniffRefusesARetiredV1Database       — restore sniffFormat's `return V1` fallback; it
//                                                stops throwing and this fails.
//   * testSniffDistinguishesNonToolkitBytes    — collapse the two refusals into one message.
//   * testNetexToDbDefaultsToV2                — set `String format = "v1"` back (or any other
//                                                non-v2 default) in conv/NetexToDb.java.
//   * testStoresOpenDefaultsToV2               — set the 2-arg Stores.open default back to v1.
// None of them can be satisfied by the removal alone: each asserts a POSITIVE replacement
// behaviour (a specific message, a specific format on disk, a specific array length), so
// deleting the guarded code makes them fail rather than pass vacuously.

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

    /// The source root this scan reads.
    ///
    /// DERIVED, never hard-coded: a constant absolute path would make the scan read a DIFFERENT
    /// tree from the one under test, and an `isDirectory` guard over a missing root is a green that
    /// cannot tell clean from empty. So the roots are ASSERTED, not guarded.
    private static final Path TREE = sourceRoot();

    /// The same locator [toolkit.Sources#root] uses, kept separate on purpose (see [#TREE]).
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
        // ...and so is its package, which would otherwise be an empty shell nobody notices.
        Check.that(!Files.isDirectory(TREE.resolve("toolkit/src/main/java/toolkit/store/v1")),
                "toolkit/store/v1/ does not exist as a source directory");
    }

    /// No production source names it. This is the one that catches a partial revert — a helper
    /// that still calls V1CompatStore, or a comment promising a format that is not there.
    @Test
    void testTreeDoesNotMentionTheRetiredStore() throws Exception {
        // Deliberately Sources.ROOTS minus tools/, and kept separate because it must not silently
        // inherit a root list edited for another scan. A root that no longer exists makes the
        // operative-first guard below refuse, which is what happens when a directory is emptied
        // (git does not track empty directories).
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
                    // This file quotes the name in its own prose: documentation OF the removal,
                    // not a use of it.
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

    /// The suite is not a dual-format matrix any more. The shipped runner's format list is read
    /// reflectively rather than counted from a transcript, so this fails the moment a second format
    /// is added back.
    ///
    /// It is [toolkit.test.Runner]'s list and not this suite's: the suite runs on
    /// `TestStoreExtension`, while Runner is what ships in the jar for somebody testing their own
    /// driver.
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

    /// `--format v1` is refused by name, with the reason, not with "unknown format".
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
        // An unknown format is still a DIFFERENT error: the v1 message must not have swallowed it.
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

    /// Bytes that are an LMDB environment but not a toolkit store get the OTHER message. Two
    /// distinct diagnoses, because the operator's next move differs: rebuild a v1 store from its
    /// feed; for anything else, they are looking at the wrong directory.
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

    /// Pinned by observation: run the loader with NO --format and read the format marker back off
    /// the store it wrote.
    ///
    /// `FeedToDb.driverMain(...)` and not `scripts/NetexToDb.java`, because a driver in the default
    /// package cannot be imported from a named one. The default under test is FeedToDb's either
    /// way — the driver never mentions a format.
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

    /// The 2-arg `Stores.open` overload — the other place a default format hid.
    @Test
    void testStoresOpenDefaultsToV2() throws Exception {
        Path dir = tmp();
        Path db = dir.resolve("fresh.lmdb");
        try (Store s = Stores.open(db, false)) {
            Check.equals(Stores.V2, s.formatId(), "Stores.open(path, rw) creates v2");
        }
        wipe(dir);
    }

    // ================================================================ fixtures

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
