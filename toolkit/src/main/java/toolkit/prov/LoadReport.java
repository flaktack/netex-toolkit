package toolkit.prov;

import toolkit.util.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/// What a stage had to TOLERATE while building a store — written beside [Provenance]'s
/// sidecar, as `.loadreport.json`.
///
/// NOT part of the provenance sidecar: that records what the writing JAR was capable of — a
/// property of the CODE, intersected across a merge. What a load had to tolerate belongs to THIS
/// run over THIS input, does not intersect, and must not shift a capability check.
///
/// It exists because where [toolkit.util.Strictness] tolerates, the anomaly happened and the store
/// carries no record: a store built from a feed full of bind losses and re-marshalled fragments is
/// byte for byte a store, and a downstream stage cannot tell.
///
/// Absent file means "no report was written", NOT "nothing was tolerated". Do not read absence as
/// clean.
///
/// Disabled with `-Dtoolkit.prov=off`, alongside the sidecar.
public final class LoadReport {

    /// The report file name, beside [Provenance#FILE].
    public static final String FILE = ".loadreport.json";

    /// Bumped only if the shape below changes incompatibly.
    public static final int SCHEMA = 1;

    private LoadReport() {}

    /// Write the report for one stage. `counts` is concern name to occurrences; zero-valued entries
    /// are dropped. Writing is best-effort: a report that cannot be written must never be the reason
    /// a good load fails.
    ///
    /// @param dir   the store directory
    /// @param tag   the driver tag, so a chained build says which stage tolerated what
    /// @param counts concern name (as used by `Strictness`) to occurrence count
    public static void write(Path dir, String tag, Map<String, Long> counts) {
        if (!Provenance.ENABLED) return;
        Map<String, Long> nonZero = new TreeMap<>();
        counts.forEach((k, v) -> { if (v != null && v > 0) nonZero.put(k, v); });

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("tag", tag);
        m.put("at", java.time.Instant.now().toString());
        m.put("tolerated", new LinkedHashMap<String, Object>(nonZero));

        Path f = dir.resolve(FILE);
        Path tmp = dir.resolve(FILE + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, Provenance.Json.write(m) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Log.warn("[loadreport] could not write %s: %s", f, e);
        }
    }

    /// The tolerated counts recorded for `dir`, or null when there is no report. Null is
    /// UNKNOWN, not zero — see the class note.
    public static Map<String, Long> read(Path dir) {
        Path f = dir.resolve(FILE);
        if (!Files.exists(f)) return null;
        try {
            Map<String, Object> m = Provenance.Json.obj(
                    Provenance.Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
            Map<String, Object> t = Provenance.Json.obj(m.get("tolerated"));
            Map<String, Long> out = new TreeMap<>();
            t.forEach((k, v) -> out.put(k, (long) Provenance.Json.num(v)));
            return out;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("corrupt load report " + f, e);
        }
    }
}
