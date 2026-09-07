package toolkit.util;

// The toolkit.util.Conf knob table, held to the code from both ends by a Sources scan of the tree:
// every declared knob has a call site, and no property read outside Conf names a knob. Either scan
// alone leaves the other direction open.

import org.junit.jupiter.api.Test;
import toolkit.Sources;
import toolkit.prov.Capabilities;
import toolkit.test.Check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestConf {

    /// Conf itself declares every name; its own text is the table, not a call site.
    private static boolean isMechanism(Path p) {
        String s = p.toString();
        return s.endsWith("toolkit/util/Conf.java") || s.endsWith("toolkit/util/TestConf.java");
    }

    private static List<Path> sources() throws IOException {
        return Sources.all(TestConf::isMechanism);
    }

    // ================================================================ the table, from both ends

    /// Knobs whose only call sites are downstream, named rather than pattern-matched. [Conf]
    /// refuses a name it does not declare, so deleting one of these turns
    /// `-Dtoolkit.xb.pass2Filter=false`, which the downstream build passes, from a knob into a
    /// build failure. A list and not a `toolkit.xb.*` prefix rule, which would absorb a genuinely
    /// dead knob added later; [#testDownstreamKnobsAreStillDeclared] keeps the list from rotting.
    private static final java.util.Set<String> READ_DOWNSTREAM = java.util.Set.of(
            "toolkit.xb.pass2Filter",
            "toolkit.xb.count",
            "toolkit.xb.count.gc",
            "toolkit.xb.count.histogram",
            "toolkit.xb.count.every",
            "toolkit.xb.count.stopAfterPass2",
            "toolkit.xb.count.dropSj");

    /// Every declared knob is read somewhere. A knob in the table with no call site is a documented
    /// setting that does nothing.
    @Test
    void testEveryKnobHasACallSite() throws Exception {
        StringBuilder tree = new StringBuilder();
        for (Path p : sources()) tree.append(Sources.read(p)).append('\n');
        String all = tree.toString();

        List<String> orphans = new ArrayList<>();
        for (Conf.Flag f : Conf.ALL) {
            if (f.bootstrap()) continue;          // read by the bootstrap itself, not through Conf
            if (READ_DOWNSTREAM.contains(f.property())) continue;
            // The field name, as a call site spells it: Conf.REF_PATCH.
            if (!all.contains("Conf." + fieldName(f))) orphans.add(f.property());
        }
        Check.equals(List.of(), orphans, "every declared knob is read by some call site");
    }

    /// The exemption list cannot outlive what it exempts: a knob renamed or deleted from [Conf]
    /// leaves a name here that matches nothing, and the next orphan with that spelling would be
    /// waved through. It does not check that the downstream call site exists; this repository
    /// cannot see that tree.
    @Test
    void testDownstreamKnobsAreStillDeclared() {
        TreeSet<String> declared = new TreeSet<>();
        for (Conf.Flag f : Conf.ALL) declared.add(f.property());
        TreeSet<String> stale = new TreeSet<>(READ_DOWNSTREAM);
        stale.removeAll(declared);
        Check.equals(new TreeSet<String>(), stale,
                "every downstream-exempt name is still a declared knob");
    }

    /// No property read outside [Conf] may name a `toolkit.*` key, or any key the table declares
    /// under a legacy spelling.
    ///
    /// `java.io.tmpdir`, `sun.java.command` and `jdk.launcher.sourcefile` are exempt: they are
    /// things the JVM tells us, not knobs anybody sets. `toolkit.repo.dir` is exempt because it
    /// locates the tree this scan reads, and reading it through [Conf] would make the scan depend
    /// on the table it is checking.
    @Test
    void testNoUndeclaredPropertyReadSurvives() throws Exception {
        TreeSet<String> bad = new TreeSet<>();
        for (Path p : sources()) {
            Matcher m = READ.matcher(Sources.read(p));
            while (m.find()) {
                String key = m.group(2);
                if (EXEMPT.contains(key)) continue;
                if (!key.startsWith(Conf.PREFIX) && !isLegacyOrEnv(key)) continue;
                bad.add(key + " @ " + p.getFileName() + " (" + m.group(1) + ")");
            }
        }
        Check.equals(new TreeSet<String>(), bad,
                "every knob is read through toolkit.util.Conf, so the table is the whole set");
    }

    /// `System.getProperty("x")`, `Boolean.getBoolean("x")`, and the numeric pair.
    private static final Pattern READ = Pattern.compile(
            "(System\\.getProperty|System\\.getenv|Boolean\\.getBoolean"
            + "|Integer\\.getInteger|Long\\.getLong)\\(\"([^\"]*)\"");

    private static final java.util.Set<String> EXEMPT = java.util.Set.of(
            "java.io.tmpdir", "sun.java.command", "jdk.launcher.sourcefile", "toolkit.repo.dir");

    private static boolean isLegacyOrEnv(String key) {
        for (Conf.Flag f : Conf.ALL) {
            if (key.equals(f.legacyProperty()) || key.equals(f.env())) return true;
        }
        return false;
    }

    /// `REF_PATCH` for `refPatch`, `STRICT_EMPTY_OUTPUT` for `strict.emptyOutput`.
    private static String fieldName(Conf.Flag f) {
        for (java.lang.reflect.Field fl : Conf.class.getFields()) {
            try {
                if (fl.get(null) == f) return fl.getName();
            } catch (IllegalAccessException ignored) {
                // a public field of a public class; cannot happen
            }
        }
        throw new IllegalStateException(f.property() + " is in ALL but is not a field of Conf");
    }

    /// Every knob is documented, and the table has no duplicate names.
    @Test
    void testEveryKnobIsDocumented() {
        List<String> undocumented = new ArrayList<>();
        for (Conf.Flag f : Conf.ALL) {
            if (f.doc() == null || f.doc().isBlank()) undocumented.add(f.property());
        }
        Check.equals(List.of(), undocumented, "every knob carries a one-line doc");
        Check.equals(Conf.ALL.size(), new TreeSet<>(Conf.ALL.stream().map(Conf.Flag::name).toList()).size(),
                "no two knobs share a name");
    }

    // ================================================================ parsing

    /// Every accepted on/off spelling, and nothing outside the set.
    @Test
    void testBooleanSpellings() {
        for (String on : new String[] {"true", "TRUE", "on", "On", "yes", "1", ""}) {
            Conf.override(Conf.XB_COUNT, on);
            Check.that(Conf.on(Conf.XB_COUNT), "\"" + on + "\" must read as ON");
        }
        for (String off : new String[] {"false", "FALSE", "off", "Off", "no", "0"}) {
            Conf.override(Conf.XB_COUNT, off);
            Check.that(!Conf.on(Conf.XB_COUNT), "\"" + off + "\" must read as OFF");
        }
        Conf.clearOverride(Conf.XB_COUNT);
    }

    /// A malformed boolean refuses rather than reading as one side or the other.
    @Test
    void testMalformedBooleanIsRefused() {
        IllegalStateException e = null;
        try {
            Conf.override(Conf.XB_COUNT, "flase");
        } catch (IllegalStateException ex) {
            e = ex;
        } finally {
            Conf.clearOverride(Conf.XB_COUNT);
        }
        Check.that(e != null, "a malformed boolean must refuse");
        Check.that(e.getMessage().contains("true|false|on|off|yes|no|1|0"),
                "the refusal must say what is accepted, got: " + e.getMessage());
    }

    /// A malformed number refuses too, naming the knob it came from.
    @Test
    void testMalformedNumberIsRefused() {
        IllegalStateException e = null;
        try {
            Conf.override(Conf.LOAD_ZIP_WORKERS, "many");
        } catch (IllegalStateException ex) {
            e = ex;
        } finally {
            Conf.clearOverride(Conf.LOAD_ZIP_WORKERS);
        }
        Check.that(e != null, "a malformed int must refuse");
        Check.that(e.getMessage().contains("toolkit.load.zipWorkers"),
                "the refusal must name the knob, got: " + e.getMessage());
    }

    /// Reading a knob as the wrong type is a programming error, not a parse failure.
    @Test
    void testWrongKindIsRefused() {
        IllegalArgumentException e = null;
        try {
            Conf.intOf(Conf.REF_PATCH);
        } catch (IllegalArgumentException ex) {
            e = ex;
        }
        Check.that(e != null, "reading a BOOL as an INT must refuse");
    }

    // ================================================================ the file

    /// The file supplies a value, and `-D` still beats it. That order is what keeps the
    /// pinned Makefile and pom command lines working unchanged.
    @Test
    void testFilePrecedence() throws Exception {
        Path dir = Files.createTempDirectory("conf-precedence");
        Path file = dir.resolve("toolkit.conf");
        Files.writeString(file, "load.zipWorkers = 4\n");

        Check.equals("4", run(file, null, "load.zipWorkers"), "the file supplies the value");
        Check.equals("8", run(file, "-Dtoolkit.load.zipWorkers=8", "load.zipWorkers"),
                "-D outranks the file");
        Check.equals("0", run(null, null, "load.zipWorkers"), "no file means the declared default");
    }

    /// A key naming no knob is refused, and the message guesses what was meant.
    @Test
    void testUnknownFileKeyIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("conf-unknown");
        Path file = dir.resolve("toolkit.conf");
        Files.writeString(file, "load.zipWorkkers = 4\n");
        String out = runExpectingFailure(file, null);
        Check.that(out.contains("no such knob"), "the refusal must say so, got: " + out);
        Check.that(out.contains("did you mean load.zipWorkers"),
                "the refusal must guess the intended knob, got: " + out);
    }

    /// A named file that is not there is a typo; running past it would run under a configuration
    /// nobody chose.
    @Test
    void testMissingNamedFileIsRefused() throws Exception {
        String out = runExpectingFailure(Path.of("/nonexistent/toolkit.conf"), null);
        Check.that(out.contains("names no readable file"),
                "the refusal must name the missing file, got: " + out);
    }

    /// A malformed value in the file is found when the file is read, not an hour into the run.
    @Test
    void testMalformedFileValueIsRefusedAtLoad() throws Exception {
        Path dir = Files.createTempDirectory("conf-malformed");
        Path file = dir.resolve("toolkit.conf");
        // A knob no probe below reads, so only load-time parsing can catch it.
        Files.writeString(file, "bind.siteCap = lots\n");
        String out = runExpectingFailure(file, null);
        Check.that(out.contains("toolkit.bind.siteCap"),
                "the refusal must name the knob, got: " + out);
    }

    /// The bootstrap knobs cannot come from the file: one is circular, one locates the suite.
    @Test
    void testBootstrapKeysAreRefusedInTheFile() throws Exception {
        Path dir = Files.createTempDirectory("conf-bootstrap");
        Path file = dir.resolve("toolkit.conf");
        Files.writeString(file, "conf = /somewhere/else.conf\n");
        String out = runExpectingFailure(file, null);
        Check.that(out.contains("read before the file is"),
                "the refusal must say why, got: " + out);
    }

    // ================================================================ capability gating

    /// `no-invent-nameofrefclass` is not claimed by a default run. The capability reads "RefPatcher
    /// never adds a nameOfRefClass attribute to a reference whose source fragment did not carry
    /// one", and `RefPatcher.wanted` adds the attribute exactly when
    /// `-Dtoolkit.inventNameOfRefClass` is on, which is the default.
    @Test
    void testDefaultRunDoesNotClaimInventNameOfRefClass() {
        Check.that(Capabilities.declared().contains("no-invent-nameofrefclass"),
                "the capability is still declared -- the opt-in arm has it");
        Check.that(!Capabilities.current().contains("no-invent-nameofrefclass"),
                "a default run must NOT claim it: inventNameOfRefClass defaults ON");
        Check.that(Conf.withheldCaps().contains("no-invent-nameofrefclass"),
                "and it must be withheld for the stated reason");
    }

    /// A capability granted by an on flag is present, and absent when the flag is off.
    @Test
    void testRefPatchGatesNoRemarshalResolve() {
        Check.that(Capabilities.current().contains("no-remarshal-resolve"),
                "refPatch defaults ON, so the capability holds");
        try {
            Conf.override(Conf.REF_PATCH, false);
            Check.that(!Capabilities.current().contains("no-remarshal-resolve"),
                    "-Dtoolkit.refPatch=false takes the re-marshal path, so the store does NOT have it");
        } finally {
            Conf.clearOverride(Conf.REF_PATCH);
        }
        Check.that(Capabilities.current().contains("no-remarshal-resolve"), "and it comes back");
    }

    /// Every gated capability names one that exists. A gate on a typo would silently never fire.
    @Test
    void testEveryCapabilityGateNamesADeclaredCapability() {
        List<String> unknown = new ArrayList<>();
        for (Conf.Flag f : Conf.ALL) {
            if (f.capability() != null && !Capabilities.declared().contains(f.capability())) {
                unknown.add(f.property() + " -> " + f.capability());
            }
        }
        Check.equals(List.of(), unknown, "every capability gate names a declared capability");
    }

    // ================================================================ a probe in its own JVM
    //
    // The file is read once, in Conf's class initialiser, so the file cases cannot be exercised by
    // setting a property inside this JVM. Each one launches a source-file probe instead.

    private static final String PROBE = """
            public class ConfProbe {
                public static void main(String[] a) {
                    toolkit.util.Conf.Flag f = toolkit.util.Conf.byName(a[0]);
                    System.out.println("VALUE=" + toolkit.util.Conf.intOf(f));
                }
            }
            """;

    /// The probe's stdout value for `knob`, with an optional extra `-D`.
    private static String run(Path confFile, String extraD, String knob) throws Exception {
        String out = launch(confFile, extraD, knob);
        Matcher m = Pattern.compile("VALUE=(\\S+)").matcher(out);
        Check.that(m.find(), "the probe must report a value, got: " + out);
        return m.group(1);
    }

    /// The probe's combined output when it is expected to die.
    private static String runExpectingFailure(Path confFile, String extraD) throws Exception {
        String out = launch(confFile, extraD, "load.zipWorkers");
        Check.that(!out.contains("VALUE="), "the probe must NOT have produced a value: " + out);
        return out;
    }

    private static String launch(Path confFile, String extraD, String knob) throws Exception {
        Path dir = Files.createTempDirectory("conf-probe");
        Path src = dir.resolve("ConfProbe.java");
        Files.writeString(src, PROBE);

        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath()));
        if (confFile != null) cmd.add("-Dtoolkit.conf=" + confFile);
        if (extraD != null) cmd.add(extraD);
        cmd.add(src.toString());
        cmd.add(knob);

        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())        // so ./toolkit.conf cannot be picked up by accident
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        Check.that(!out.contains("error: compilation failed"),
                "the probe must compile -- otherwise every refusal case below is vacuous:\n" + out);
        return out;
    }

    /// This JVM's classpath, absolutised. The probe runs from a temp working directory and the
    /// suite is launched with a relative `-cp`, so the two together would hand the child a
    /// classpath pointing at nothing.
    private static String classpath() {
        String[] entries = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        List<String> abs = new ArrayList<>();
        for (String e : entries) abs.add(Path.of(e).toAbsolutePath().toString());
        return String.join(java.io.File.pathSeparator, abs);
    }
}
