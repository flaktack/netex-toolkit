package toolkit.prov;

import toolkit.util.Conf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/// The provenance sidecar: `<store-dir>/.provenance.json`.
///
/// OUTSIDE LMDB, deliberately: verdicts here are byte comparisons of the store itself, and the
/// on-disk format may not move a byte to accommodate a debugging aid. A sidecar file next to
/// `data.mdb` is invisible to every such comparison — LMDB ignores unknown files in the directory —
/// and travels with the directory through `mv`, `rm -rf` and `Stores.destroy`.
///
/// MINIMUM, not union: a merged store is only as capable as its least capable input, so
/// [#mergeFrom] intersects. A union would call a store current because two of its three inputs were.
///
/// **Absence means UNKNOWN, never FINE.** A store directory with no sidecar, or one whose lineage
/// includes a store with no sidecar, is marked `caps_known:false` and every check refuses it.
///
/// Disabled entirely by `-Dtoolkit.prov=off`.
public final class Provenance {

    /// The sidecar file name.
    public static final String FILE = ".provenance.json";

    /// Sidecar schema version — bumped only if the shape below changes incompatibly.
    public static final int SCHEMA = 1;

    /// `-Dtoolkit.prov=off` disables all stamping and merging. Checks still read what exists.
    public static final boolean ENABLED = Conf.on(Conf.PROV);

    private Provenance() {}

    /// One line of lineage. `kind` is `observed` (a real rw open) or `attested`.
    public record Writer(String kind, String tool, String args, String at,
                         String jarSha256, String why, Set<String> caps) {}

    /// A parsed sidecar.
    public record Prov(int schema, String created, boolean capsKnown,
                       Set<String> capsEffective, List<Writer> writers, int writersElided) {}

    // ------------------------------------------------------------------ read

    /// The sidecar of `dir`, or null when there is none.
    public static Prov read(Path dir) {
        Path f = dir.resolve(FILE);
        if (!Files.isRegularFile(f)) return null;
        try {
            Object o = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            Map<String, Object> m = Json.obj(o);
            List<Writer> ws = new ArrayList<>();
            for (Object w : Json.arr(m.get("writers"))) {
                Map<String, Object> wm = Json.obj(w);
                ws.add(new Writer(Json.str(wm.get("kind")), Json.str(wm.get("tool")),
                        Json.str(wm.get("args")), Json.str(wm.get("at")),
                        Json.str(wm.get("jar_sha256")), Json.str(wm.get("why")),
                        strSet(wm.get("caps"))));
            }
            return new Prov((int) Json.num(m.get("schema")), Json.str(m.get("created")),
                    Boolean.TRUE.equals(m.get("caps_known")), strSet(m.get("caps_effective")), ws,
                    (int) Json.num(m.get("writers_elided")));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + f, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("corrupt provenance sidecar " + f + ": " + e, e);
        }
    }

    // ------------------------------------------------------------------ write (production hooks)

    /// Call site 1 + 2, from [toolkit.store.Stores]: a store has just been opened READ-WRITE.
    ///
    /// @param freshDb true when `data.mdb` did not exist before this open — i.e. the running
    ///                code wrote every byte in it, so its capability set is the running set. False
    ///                for a pre-existing store being modified, whose content is a mix and whose
    ///                effective set is therefore the INTERSECTION with whatever was there before —
    ///                or UNKNOWN if there was no sidecar to intersect with.
    public static void stampOpen(Path dir, boolean freshDb) {
        if (!ENABLED) return;
        Prov p = read(dir);
        Set<String> running = Capabilities.current();
        boolean known;
        Set<String> eff;
        List<Writer> ws;
        String created;
        int el;
        if (p == null) {
            created = now();
            ws = new ArrayList<>();
            el = 0;
            if (freshDb) {
                known = true;
                eff = new LinkedHashSet<>(running);
            } else {
                // Pre-existing bytes, no sidecar: what wrote them is unknown, and
                // `nx prov stamp --caps ... --why ...` is the only way to assert otherwise.
                known = false;
                eff = new LinkedHashSet<>();
            }
        } else {
            created = p.created();
            ws = new ArrayList<>(p.writers());
            known = p.capsKnown();
            eff = intersect(p.capsEffective(), running);
            el = p.writersElided();
        }
        ws.add(new Writer("observed", tool(), args(), now(), jarSha256(), null, running));
        write(dir, cap(new Prov(SCHEMA, created, known, eff, ws, el)));
    }

    /// Fragments have just been copied verbatim out of `sourceDir`, so the target is only as
    /// capable as the source it inherited bytes from: take the MINIMUM, and inherit "unknown" when
    /// the source has no sidecar.
    public static void mergeFrom(Path targetDir, Path sourceDir) {
        if (!ENABLED) return;
        Prov t = read(targetDir);
        if (t == null) {
            // cloneFrom always runs on a store opened rw, so stampOpen ran first; if it did not,
            // something bypassed Stores and the answer is "unknown".
            t = new Prov(SCHEMA, now(), false, new LinkedHashSet<>(), new ArrayList<>(), 0);
        }
        Prov s = read(sourceDir);
        boolean known = t.capsKnown();
        Set<String> eff = new LinkedHashSet<>(t.capsEffective());
        List<Writer> ws = new ArrayList<>(t.writers());
        int el = t.writersElided();
        if (s == null) {
            known = false;
            eff = new LinkedHashSet<>();
            ws.add(new Writer("cloned-from", sourceDir.toString(), null, now(), null,
                    "source has no provenance sidecar — capability set is UNKNOWN", Set.of()));
        } else {
            known = known && s.capsKnown();
            eff = intersect(eff, s.capsEffective());
            if (!s.capsKnown()) eff = new LinkedHashSet<>();
            ws.add(new Writer("cloned-from", sourceDir.toString(), null, now(), null,
                    null, s.capsEffective()));
            for (Writer w : s.writers()) if (!ws.contains(w)) ws.add(w);
            el += s.writersElided();
        }
        write(targetDir, cap(new Prov(SCHEMA, t.created(), known, eff, ws, el)));
    }

    // ------------------------------------------------------------------ attest (operator)

    /// Operator assertion for a store built before this tool existed: "I know what jar wrote this;
    /// here is its capability set." Recorded as `kind:"attested"` with a mandatory reason and
    /// printed loudly by `nx prov check`, so it does not look like a measurement.
    ///
    /// Intersects with anything already recorded — attesting can only ever LOWER a set, so a
    /// mis-attestation cannot launder a store the machinery already knows is stale.
    public static void attest(Path dir, Set<String> caps, String why) {
        if (why == null || why.isBlank()) throw new IllegalArgumentException("attest requires --why");
        for (String c : caps) {
            if (!Capabilities.current().contains(c)) {
                throw new IllegalArgumentException("unknown capability: " + c
                        + " (known: " + Capabilities.sorted(Capabilities.current()) + ")");
            }
        }
        Prov p = read(dir);
        Set<String> eff;
        List<Writer> ws;
        String created;
        int el;
        if (p == null) {
            created = now();
            ws = new ArrayList<>();
            eff = new LinkedHashSet<>(caps);
            el = 0;
        } else {
            created = p.created();
            ws = new ArrayList<>(p.writers());
            eff = p.capsKnown() ? intersect(p.capsEffective(), caps) : new LinkedHashSet<>(caps);
            el = p.writersElided();
        }
        ws.add(new Writer("attested", null, null, now(), null, why, new LinkedHashSet<>(caps)));
        write(dir, cap(new Prov(SCHEMA, created, true, eff, ws, el)));
    }

    // ------------------------------------------------------------------ check

    /// What a chain preflight needs to know about one input.
    public record Verdict(Path dir, boolean present, boolean capsKnown,
                          Set<String> effective, Set<String> missing, boolean attested) {
        public boolean ok() { return present && capsKnown && missing.isEmpty(); }
    }

    /// Compare one store against the running jar's capability set.
    public static Verdict check(Path dir) {
        Prov p = read(dir);
        Set<String> running = Capabilities.current();
        if (p == null) {
            return new Verdict(dir, false, false, Set.of(), new TreeSet<>(running), false);
        }
        Set<String> eff = p.capsKnown() ? p.capsEffective() : Set.of();
        Set<String> missing = new TreeSet<>(running);
        missing.removeAll(eff);
        boolean attested = p.writers().stream().anyMatch(w -> "attested".equals(w.kind()));
        return new Verdict(dir, true, p.capsKnown(), new TreeSet<>(eff), missing, attested);
    }

    // ------------------------------------------------------------------ internals

    /// Cap on the lineage list: an uncapped union grows to thousands of rows and is rewritten once
    /// per source. The capability set — the only thing any gate reads — is a fold and does not
    /// depend on the list, so the narrative is capped and the elision recorded.
    static final int MAX_WRITERS = Conf.intOf(Conf.PROV_MAX_WRITERS);

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> r = new LinkedHashSet<>(a);
        r.retainAll(b);
        return r;
    }

    private static Set<String> strSet(Object o) {
        Set<String> s = new LinkedHashSet<>();
        if (o == null) return s;
        for (Object e : Json.arr(o)) s.add(String.valueOf(e));
        return s;
    }

    private static String now() {
        return Instant.now().toString();
    }

    /// The launched main class or script path. Every driver here is an UNCOMPILED `.java` script,
    /// for which `sun.java.command` reads `jdk.compiler/…SourceLauncher <script> <args…>`, so the
    /// launcher is stripped and the script path is reported.
    private static String tool() {
        String[] w = command();
        return w.length == 0 ? "" : w[0];
    }

    private static String args() {
        String[] w = command();
        return w.length < 2 ? "" : String.join(" ", java.util.Arrays.copyOfRange(w, 1, w.length));
    }

    private static String[] command() {
        String c = System.getProperty("sun.java.command", "").trim();
        if (c.isEmpty()) return new String[0];
        String[] w = c.split("\\s+");
        if (w[0].contains("SourceLauncher") && w.length > 1) {
            return java.util.Arrays.copyOfRange(w, 1, w.length);
        }
        return w;
    }

    private static volatile String jarSha;

    /// sha256 of the jar this class was loaded from ("source" when launched from .java sources).
    private static String jarSha256() {
        String s = jarSha;
        if (s != null) return s;
        try {
            var src = Capabilities.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return jarSha = "unknown";
            Path p = Path.of(src.getLocation().toURI());
            if (!Files.isRegularFile(p)) return jarSha = "source:" + p;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return jarSha = sb.toString();
        } catch (Exception e) {
            return jarSha = "unknown";
        }
    }

    /// Trim the lineage narrative to [#MAX_WRITERS], keeping the most recent entries and recording
    /// how many were dropped. The capability set is untouched.
    private static Prov cap(Prov p) {
        List<Writer> ws = p.writers();
        if (ws.size() <= MAX_WRITERS) return p;
        int drop = ws.size() - MAX_WRITERS;
        return new Prov(p.schema(), p.created(), p.capsKnown(), p.capsEffective(),
                new ArrayList<>(ws.subList(drop, ws.size())), p.writersElided() + drop);
    }

    private static void write(Path dir, Prov p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", p.schema());
        m.put("created", p.created());
        m.put("caps_known", p.capsKnown());
        m.put("caps_effective", Capabilities.sorted(p.capsEffective()));
        if (p.writersElided() > 0) m.put("writers_elided", p.writersElided());
        List<Object> ws = new ArrayList<>();
        for (Writer w : p.writers()) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("kind", w.kind());
            if (w.tool() != null) wm.put("tool", w.tool());
            if (w.args() != null && !w.args().isEmpty()) wm.put("args", w.args());
            wm.put("at", w.at());
            if (w.jarSha256() != null) wm.put("jar_sha256", w.jarSha256());
            if (w.why() != null) wm.put("why", w.why());
            wm.put("caps", Capabilities.sorted(w.caps()));
            ws.add(wm);
        }
        m.put("writers", ws);
        Path f = dir.resolve(FILE);
        Path tmp = dir.resolve(FILE + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, Json.write(m) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + f, e);
        }
    }

    // ------------------------------------------------------------------ minimal JSON

    /// A minimal JSON reader/writer for exactly the shape above; the toolkit has no JSON
    /// dependency. The parser refuses anything it does not understand.
    static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw new IllegalArgumentException("trailing input at " + j.i);
            return v;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
        @SuppressWarnings("unchecked")
        static List<Object> arr(Object o) { return o == null ? List.of() : (List<Object>) o; }
        static String str(Object o) { return o == null ? null : (String) o; }
        static double num(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, i)) throw new IllegalArgumentException("expected " + lit + " at " + i);
            i += lit.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') throw new IllegalArgumentException("expected : at " + (i - 1));
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i - 1));
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i - 1));
            }
        }

        private String string() {
            if (s.charAt(i++) != '"') throw new IllegalArgumentException("expected string at " + (i - 1));
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
        }

        private Number number() {
            int st = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw new IllegalArgumentException("bad value at " + st);
            return t.contains(".") || t.contains("e") || t.contains("E")
                    ? (Number) Double.valueOf(t) : (Number) Long.valueOf(t);
        }

        static String write(Object o) {
            StringBuilder b = new StringBuilder();
            write(o, b, 0);
            return b.toString();
        }

        private static void write(Object o, StringBuilder b, int ind) {
            switch (o) {
                case null -> b.append("null");
                case Map<?, ?> m -> {
                    if (m.isEmpty()) { b.append("{}"); return; }
                    b.append("{\n");
                    int n = 0;
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (n++ > 0) b.append(",\n");
                        pad(b, ind + 1);
                        quote(String.valueOf(e.getKey()), b);
                        b.append(": ");
                        write(e.getValue(), b, ind + 1);
                    }
                    b.append('\n');
                    pad(b, ind);
                    b.append('}');
                }
                case List<?> l -> {
                    if (l.isEmpty()) { b.append("[]"); return; }
                    boolean scalars = l.stream().allMatch(x -> x instanceof String || x instanceof Number);
                    if (scalars) {
                        b.append('[');
                        for (int k = 0; k < l.size(); k++) {
                            if (k > 0) b.append(", ");
                            write(l.get(k), b, ind);
                        }
                        b.append(']');
                        return;
                    }
                    b.append("[\n");
                    for (int k = 0; k < l.size(); k++) {
                        if (k > 0) b.append(",\n");
                        pad(b, ind + 1);
                        write(l.get(k), b, ind + 1);
                    }
                    b.append('\n');
                    pad(b, ind);
                    b.append(']');
                }
                case String s -> quote(s, b);
                case Boolean x -> b.append(x);
                case Number x -> b.append(x);
                default -> quote(String.valueOf(o), b);
            }
        }

        private static void pad(StringBuilder b, int n) { b.append("  ".repeat(n)); }

        private static void quote(String s, StringBuilder b) {
            b.append('"');
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        }
    }

    /// Unmodifiable view helper used by callers that render a verdict.
    public static Set<String> running() {
        return Collections.unmodifiableSet(Capabilities.current());
    }
}
