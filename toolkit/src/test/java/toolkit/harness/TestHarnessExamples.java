package toolkit.harness;

// The example set held to the harness set, from both ends (OPEN.md item 12c).
//
// WHY BOTH. toolkit/harness/package-info.java lists the sandwiches and README.md calls the drivers
// in scripts/ "the worked examples for writing your own". Two hand-maintained claims, and both had
// drifted before this test existed. The failure mode recurs every time a sandwich is added, because
// the two lists that should grow are prose in two other files.
//
// DISCOVERED, NOT ENUMERATED. The subject is read off the filesystem and confirmed by reflection; a
// hand-written list here would be a third list to forget.
//
// The companions (Args, ZipWriter, NetexEnvelope, FrameWriter) and the compositions (EpipToDb) are
// excluded by having no driverMain rather than by being named, so adding one never edits this file.

import org.junit.jupiter.api.Test;
import toolkit.Sources;
import toolkit.test.Check;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class TestHarnessExamples {

    private static final String HARNESS_DIR = "toolkit/src/main/java/toolkit/harness";
    private static final String PACKAGE_INFO = HARNESS_DIR + "/package-info.java";

    /// The tree a driver may live in. It was `scripts/conv` + `scripts/fix` until the drivers
    /// flattened into one directory; the suite is deliberately not a root — a test calling
    /// driverMain is not an example, and two of them do (TestMergeDbToDb, TestStoreFormat).
    private static final List<String> DRIVER_ROOTS = List.of("scripts");

    /// A vacuity floor, NOT the count. There are five sandwiches today and this asserts four on
    /// purpose: pinning it to the exact number would recreate the hand-maintained count that
    /// OPEN.md 12c is about. What it defends against is the scan finding nothing and reporting
    /// green, which is the one way a discovery test lies.
    private static final int MIN_HARNESSES = 4;

    /// Every public toolkit.harness type that exposes a public static driverMain.
    private static List<Class<?>> sandwiches() throws IOException {
        Path dir = Sources.root().resolve(HARNESS_DIR);
        Check.that(Files.isDirectory(dir),
                "the harness package exists at " + dir + " (this scan is vacuous without it)");

        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> w = Files.list(dir)) {
            for (Path p : w.sorted().toList()) {
                String file = p.getFileName().toString();
                if (!file.endsWith(".java") || file.equals("package-info.java")) continue;
                Class<?> c;
                try {
                    c = Class.forName("toolkit.harness." + file.substring(0, file.length() - 5));
                } catch (ClassNotFoundException e) {
                    // A source file whose class is not on the classpath. This USED to be OPEN.md
                    // 14's stale-jar trap, when the suite was source-launched against the built
                    // jar; it now compiles against target/classes, so this arm can only fire on a
                    // genuinely non-public or non-existent class. Kept because a scan that skips
                    // what it cannot resolve is a scan that reports green over a shrinking subject.
                    throw new AssertionError(p + " has no class on the classpath — "
                            + "the jar is behind toolkit/src (mvn clean install)", e);
                }
                if (!Modifier.isPublic(c.getModifiers())) continue; // Harness itself
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("driverMain")
                            && Modifier.isPublic(m.getModifiers())
                            && Modifier.isStatic(m.getModifiers())) {
                        found.add(c);
                        break;
                    }
                }
            }
        }
        Check.that(found.size() >= MIN_HARNESSES,
                "the harness scan found " + found.size() + " types with a driverMain under " + dir
                        + " (expect >= " + MIN_HARNESSES + ", a vacuity floor and not the count); "
                        + "a scan that found nothing cannot have checked anything");
        return found;
    }

    /// OPEN.md 12c: every sandwich is named in the package doc that claims to list them.
    @Test
    void testEverySandwichIsNamedInThePackageDoc() throws IOException {
        String doc = Sources.read(Sources.root().resolve(PACKAGE_INFO));
        List<String> unnamed = new ArrayList<>();
        for (Class<?> c : sandwiches()) {
            if (!doc.contains(c.getSimpleName())) unnamed.add(c.getSimpleName());
        }
        Check.equals(List.of(), unnamed,
                "every toolkit.harness type with a driverMain is named in " + PACKAGE_INFO
                        + " — these are not: " + unnamed);
    }

    /// README.md calls scripts/ "the worked examples for writing your own". This is that claim.
    @Test
    void testEverySandwichHasADriverInScripts() throws IOException {
        List<String> drivers = new ArrayList<>();
        for (String root : DRIVER_ROOTS) {
            Path dir = Sources.root().resolve(root);
            Check.that(Files.isDirectory(dir),
                    "driver root " + dir + " exists (this scan is vacuous without it)");
            try (Stream<Path> w = Files.walk(dir)) {
                for (Path p : w.filter(f -> f.toString().endsWith(".java")).toList()) {
                    drivers.add(Sources.read(p));
                }
            }
        }
        Check.that(!drivers.isEmpty(), "the driver scan found .java files under " + DRIVER_ROOTS);

        List<String> uncovered = new ArrayList<>();
        for (Class<?> c : sandwiches()) {
            String call = c.getSimpleName() + ".driverMain(";
            if (drivers.stream().noneMatch(src -> src.contains(call))) {
                uncovered.add(c.getSimpleName());
            }
        }
        // Every uncovered sandwich at once: told one at a time, this takes as many runs to clear
        // as there are gaps, and each run is a full build.
        Check.equals(List.of(), uncovered,
                "every sandwich has at least one example driver under " + DRIVER_ROOTS
                        + " calling its driverMain — these have none: " + uncovered);
    }
}
