package toolkit;

// The repository's source tree, as a list of .java files a test can scan.
//
// TestProvenance pins the PROV-CAP markers against toolkit.prov.Capabilities and TestConf pins
// property reads against toolkit.util.Conf; both hold the tree to a declared table and both walk
// it through here, so a scan cannot silently stop finding things in one of them alone.

import toolkit.test.Check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Sources {

    private Sources() {}

    /// The trees a fix -- or a knob -- can land in.
    ///
    /// The suite is one of them, and has to be: a knob read only by a test is still a knob.
    public static final List<String> ROOTS = List.of(
            "toolkit/src/main/java", "toolkit/src/test/java", "scripts",
            "model/src/main/java", "tools");

    /// The repository root: the property the pom passes, then the launched file (a source-launched
    /// caller under `tools/`), then cwd.
    ///
    /// `toolkit.repo.dir` and not a relative path off cwd, because surefire runs with the MODULE as
    /// its working directory while three of these roots are the module's siblings.
    public static Path root() {
        String p = System.getProperty("toolkit.repo.dir");
        if (p != null) return Path.of(p).toAbsolutePath().normalize();
        String cmd = System.getProperty("jdk.launcher.sourcefile");
        if (cmd != null) return Path.of(cmd).toAbsolutePath().getParent().getParent().getParent();
        return Path.of("").toAbsolutePath();
    }

    /// Every `.java` file under [#ROOTS], minus the ones `skip` accepts. Refuses a vacuous scan:
    /// every root must be a real directory, and the walk must find a plausible number of files.
    public static List<Path> all(java.util.function.Predicate<Path> skip) throws IOException {
        Path repo = root();
        List<String> missing = new ArrayList<>();
        for (String r : ROOTS) if (!Files.isDirectory(repo.resolve(r))) missing.add(r);
        Check.equals(List.of(), missing,
                "source roots exist under " + repo + " (a scan is vacuous without them)");

        List<Path> out = new ArrayList<>();
        for (String r : ROOTS) {
            try (var w = Files.walk(repo.resolve(r))) {
                w.filter(p -> p.toString().endsWith(".java")).filter(p -> !skip.test(p)).forEach(out::add);
            }
        }
        Check.that(out.size() >= 100,
                "the source scan found " + out.size() + " .java files under " + repo
                        + " (expect >= 100); a scan that found nothing cannot have checked anything");
        return out;
    }

    public static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
