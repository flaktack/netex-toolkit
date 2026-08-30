package toolkit;

// Runs a driver from `scripts/` the way production runs it: source-launched, in its own JVM.
//
// WHY A SUBPROCESS. The drivers are in the DEFAULT PACKAGE and outside every Maven module, so no
// compiled test can import one. For the thin ones that costs nothing, because the driver IS
// `SomeHarness.driverMain(tag, args, fn)` and a test calls that instead (TestMergeDbToDb and
// TestStoreFormat both do). `EpipDbToXml` is the exception: it is EPIP export PROFILE that exists
// nowhere else, so "call what the driver calls" has nothing to call.
//
// Same idiom as TestConf's config probe, for the same reason.
//
// WHAT IT COSTS: one JVM start, one JAXB context init and a javac per call -- several seconds
// against milliseconds. Two callers, deliberately.
//
// WHAT IT BUYS BACK: this is the only test that runs a `scripts/` driver at all, flags and source
// launcher included. Everything else about the drivers is checked by reading them
// (TestHarnessExamples) or by compiling them (tools/gate/compile.sh).

import toolkit.test.Check;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Drivers {

    private Drivers() {}

    /// Warning suppression, not correctness — on JDK 25 the store runs with no flags at all. The
    /// child opens a store, so it gets the set the pom gives this JVM and its stderr stays clean;
    /// a driver reports through its log tail, and eight JVM warnings on top of it are noise a
    /// caller would have to filter. See the pom's surefire comment for what each one silences and
    /// for the four --add-opens that were measured dead and removed.
    private static final List<String> JVM_FLAGS = List.of(
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow");

    /// `scripts/<driver>.java args...`, and it must exit 0.
    ///
    /// Returns the driver's combined output, because a driver reports through its log tail and a
    /// caller may want to assert on it.
    public static String run(String driver, List<String> args) throws Exception {
        Path src = Sources.root().resolve("scripts").resolve(driver + ".java");
        // Operative-first: a driver that was renamed or moved must fail here, naming the path, and
        // not further down as an empty output or a missing file.
        Check.that(Files.isRegularFile(src), "the driver source exists at " + src);

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx2g");
        cmd.addAll(JVM_FLAGS);
        cmd.add("-cp");
        cmd.add(classpath());
        cmd.add(src.toString());
        cmd.addAll(args);

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        // A compile failure exits non-zero, so the exit check covers it -- but it reads as "the
        // driver refused my input" unless the output is in the message, and it never is otherwise.
        Check.equals(0, exit, "scripts/" + driver + ".java exited " + exit + ":\n" + out);
        return out;
    }

    /// This JVM's classpath, absolutised: the child runs with an inherited working directory, and
    /// surefire's `-cp` entries are the module's, so a relative entry would point at nothing.
    private static String classpath() {
        List<String> abs = new ArrayList<>();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            abs.add(Path.of(e).toAbsolutePath().toString());
        }
        return String.join(File.pathSeparator, abs);
    }
}
