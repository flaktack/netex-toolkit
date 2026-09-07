package toolkit;

// Runs a driver from `scripts/` the way production runs it: source-launched, in its own JVM.
//
// WHY A SUBPROCESS. The drivers are in the DEFAULT PACKAGE and outside every Maven module, so no
// compiled test can import one. What a test CAN do is call what the driver calls --
// `SomeHarness.driverMain(tag, args, fn)` -- which is what TestMergeDbToDb and TestStoreFormat do,
// and what a test of `EpipDbToXml` could do too now that its profile is `toolkit.harness.EpipDbToZip`
// rather than 1140 lines of the script itself. That is exactly what makes this different: calling
// the harness tests the PROFILE, and launching the file tests the FILE.

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
    /// the pom's surefire comment says what each one silences.
    private static final List<String> JVM_FLAGS = List.of(
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow");

    /// `scripts/<driver>.java args...`, and it must exit 0.
    ///
    /// Returns the driver's combined output: a driver reports through its log tail.
    public static String run(String driver, List<String> args) throws Exception {
        Path src = Sources.root().resolve("scripts").resolve(driver + ".java");
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
