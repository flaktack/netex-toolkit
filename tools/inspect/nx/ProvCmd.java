package inspect.nx;

import toolkit.prov.Capabilities;
import toolkit.prov.Provenance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/// `nx prov` — the provenance reader.
///
/// `check` is the chain preflight: it prints the effective capability set of every input and EXITS
/// NON-ZERO when any input's set is a proper subset of the running jar's, or when an input has no
/// sidecar at all — absence means "unknown, refuse", never "fine".
final class ProvCmd {

    static int run(String[] args) throws Exception {
        if (args.length == 0) return usage();
        String sub = args[0];
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));
        return switch (sub) {
            case "check" -> check(rest);
            case "show" -> show(rest);
            case "caps" -> caps();
            case "stamp" -> stamp(rest);
            default -> usage();
        };
    }

    private static int usage() {
        System.err.println("""
                nx prov check <db>...                     staleness gate; exit 1 on any stale input
                nx prov show  <db>...                     print sidecars
                nx prov caps                              print the running capability vector
                nx prov stamp <db> --caps a,b,c --why "..."   operator attestation (can only LOWER a set)
                          --caps all                      attest the full running vector""");
        return 2;
    }

    // ---------------------------------------------------------------- caps

    // Name and description only: a capability carries no task list.
    private static int caps() {
        System.out.println("running capability vector (" + Capabilities.ALL.size() + "):");
        for (Capabilities.Cap c : Capabilities.ALL) {
            System.out.printf("  %-28s %s%n", c.name(), c.what());
        }
        return 0;
    }

    // ---------------------------------------------------------------- check

    private static int check(List<String> dbs) {
        if (dbs.isEmpty()) return usage();
        Set<String> running = Capabilities.current();
        System.out.println("nx prov check — running jar declares " + running.size() + " capabilities:");
        System.out.println("  " + String.join(" ", Capabilities.sorted(running)));
        System.out.println();
        int bad = 0;
        for (String d : dbs) {
            Path p = Path.of(d);
            Provenance.Verdict v = Provenance.check(p);
            if (!v.present()) {
                System.out.printf("REFUSE  %s%n", p);
                System.out.printf("        no %s — provenance UNKNOWN, and unknown is not fine.%n",
                        Provenance.FILE);
                System.out.printf("        (missing all %d: %s)%n", v.missing().size(),
                        String.join(" ", v.missing()));
                bad++;
            } else if (!v.capsKnown()) {
                System.out.printf("REFUSE  %s%n", p);
                System.out.printf("        caps_known=false — this store, or something it cloned "
                        + "from, has no provenance.%n");
                bad++;
            } else if (!v.missing().isEmpty()) {
                System.out.printf("REFUSE  %s%n", p);
                System.out.printf("        effective (%d): %s%n", v.effective().size(),
                        String.join(" ", Capabilities.sorted(v.effective())));
                System.out.printf("        MISSING   (%d): %s%n", v.missing().size(),
                        String.join(" ", v.missing()));
                for (String m : v.missing()) {
                    String w = Capabilities.describe(m);
                    if (w != null) System.out.printf("          %-26s %s%n", m, w);
                }
                bad++;
            } else {
                System.out.printf("ok      %s%n", p);
                System.out.printf("        effective (%d): complete%s%n", v.effective().size(),
                        v.attested() ? "  [contains an OPERATOR ATTESTATION — see `nx prov show`]" : "");
            }
            if (v.attested() && !v.ok()) {
                System.out.println("        note: this sidecar contains an operator attestation.");
            }
        }
        System.out.println();
        if (bad > 0) {
            System.out.printf("nx prov check: %d of %d inputs REFUSED — chain not started.%n", bad, dbs.size());
            return 1;
        }
        System.out.printf("nx prov check: %d inputs, all current.%n", dbs.size());
        return 0;
    }

    // ---------------------------------------------------------------- show

    private static int show(List<String> dbs) throws Exception {
        if (dbs.isEmpty()) return usage();
        for (String d : dbs) {
            Path f = Path.of(d).resolve(Provenance.FILE);
            System.out.println("===== " + d);
            if (!Files.isRegularFile(f)) {
                System.out.println("(no sidecar)");
                continue;
            }
            System.out.println(Files.readString(f));
        }
        return 0;
    }

    // ---------------------------------------------------------------- stamp

    /// Operator attestation for a store built before this tool existed: the only way to make such a
    /// store pass. Requires a written reason, records itself as `attested` rather than `observed`,
    /// and can only ever LOWER an already-recorded set.
    private static int stamp(List<String> args) {
        Path db = null;
        String capsArg = null;
        String why = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--caps" -> capsArg = args.get(++i);
                case "--why" -> why = args.get(++i);
                default -> {
                    if (db != null) return usage();
                    db = Path.of(args.get(i));
                }
            }
        }
        if (db == null || capsArg == null || why == null) return usage();
        Set<String> caps = new LinkedHashSet<>();
        if ("all".equals(capsArg)) {
            caps.addAll(Capabilities.current());
        } else if (!capsArg.isBlank()) {
            for (String c : capsArg.split(",")) if (!c.isBlank()) caps.add(c.trim());
        }
        if (capsArg.startsWith("all-")) {   // "all-enum-collapse" = everything except that one
            caps.clear();
            caps.addAll(Capabilities.current());
            for (String c : capsArg.substring(4).split(",")) caps.remove(c.trim());
        }
        Provenance.attest(db, caps, why);
        Provenance.Verdict v = Provenance.check(db);
        System.out.printf("attested %s%n  caps_effective (%d): %s%n  missing (%d): %s%n  why: %s%n",
                db, v.effective().size(), String.join(" ", Capabilities.sorted(v.effective())),
                v.missing().size(), String.join(" ", new TreeSet<>(v.missing())), why);
        return 0;
    }

    private ProvCmd() {}
}
