package toolkit.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The one CLI parser for drivers and harnesses: known flags only, loud on anything else.
/// Spec entries: `"--name="` declares a value-taking flag, `"--name"` a boolean flag.
/// Unknown `--x`, a missing value, or a wrong positional count print the usage line and
/// exit 2.
public final class Args {

    private final Map<String, String> values = new HashMap<>();
    private final Set<String> flags = new HashSet<>();
    private final List<String> positional = new ArrayList<>();
    private final String usage;

    private Args(String usage) {
        this.usage = usage;
    }

    public static Args parse(String[] argv, String usage, String... spec) {
        Set<String> valueFlags = new HashSet<>();
        Set<String> boolFlags = new HashSet<>();
        for (String s : spec) {
            if (s.endsWith("=")) valueFlags.add(s.substring(0, s.length() - 1));
            else boolFlags.add(s);
        }
        Args a = new Args(usage);
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (valueFlags.contains(arg)) {
                if (i + 1 >= argv.length) a.die(arg + " needs a value");
                a.values.put(arg, argv[++i]);
            } else if (boolFlags.contains(arg)) {
                a.flags.add(arg);
            } else if (arg.startsWith("--")) {
                a.die("unknown option " + arg);
            } else {
                a.positional.add(arg);
            }
        }
        return a;
    }

    public String get(String flag, String defaultValue) {
        return values.getOrDefault(flag, defaultValue);
    }

    public boolean has(String flag) {
        return flags.contains(flag);
    }

    /// An integer-valued flag, or its default. A value that is not an integer prints the usage and
    /// exits 2: `--shards two` is a command-line error, not a silent fall back to the default.
    public int intFlag(String flag, int defaultValue) {
        String v = values.get(flag);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            die(flag + " must be an integer, got " + v);
            return defaultValue; // unreachable: die() exits
        }
    }

    public List<String> positional() {
        return positional;
    }

    /// Exactly n positionals, or usage + exit 2.
    public Args expectPositional(int n) {
        if (positional.size() != n) die("expected " + n + " positional argument(s), got " + positional.size());
        return this;
    }

    /// At least n positionals (N-source drivers: sources... target), or usage + exit 2.
    public Args expectAtLeast(int n) {
        if (positional.size() < n) {
            die("expected at least " + n + " positional argument(s), got " + positional.size());
        }
        return this;
    }

    private void die(String msg) {
        System.err.println(msg);
        System.err.println("usage: " + usage);
        System.exit(2);
    }
}
