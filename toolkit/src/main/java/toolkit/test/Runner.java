package toolkit.test;

import toolkit.store.Stores;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Zero-infra test runner for source-launched test scripts. A test class exposes
/// `public static void test*(TestStore)` methods (or no-arg `test*()` for pure tests).
///
/// One format, one run per test. The `FORMATS` array is the seam a second format would re-enter
/// through, and the format column stays in the output so transcripts line up row for row.
///
/// Script usage: {@code void main(String[] args) { Runner.run(MyTest.class); }} — or an aggregate
/// script passing several classes. Exits non-zero on any failure.
///
/// [#run] is the script entry point and EXITS the JVM. [#runReporting] is the same run without the
/// exit, for a host running several test classes in one JVM that must still see the ones after a
/// failure. The transcript is identical either way.
public final class Runner {

    private Runner() {}

    /// The on-disk formats every store-taking test is run against.
    private static final String[] FORMATS = {Stores.V2};

    /// What a run counted: `tests` test RUNS attempted, `failures` of them failed.
    public record Tally(int tests, int failures) {}

    public static void run(Class<?>... testClasses) {
        if (runReporting(testClasses).failures() > 0) System.exit(1);
    }

    /// Same transcript as [#run], but RETURNS the counts instead of exiting. The only per-JVM state
    /// in play is the JAXB context and the reflection caches.
    ///
    /// Returning the counts means a batched host never has to redirect `System.out`, which would
    /// put the runner in the way of every driver's logging.
    public static Tally runReporting(Class<?>... testClasses) {
        // Small v2 edge blocks, so the dirty-block machinery crosses block boundaries at test scale
        // (production default is 65,536 pairs). Suite-wide, because a test that never reaches a
        // second block cannot tell a correct block reader from a cursor-ordered one.
        toolkit.util.Conf.override(toolkit.util.Conf.V2_BLOCK_PAIRS, 128);
        record Result(String name, String format, Throwable failure) {}
        List<Result> results = new ArrayList<>();

        for (Class<?> testClass : testClasses) {
            List<Method> tests = new ArrayList<>();
            for (Method m : testClass.getDeclaredMethods()) {
                if (!m.getName().startsWith("test")) continue;
                if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())) {
                    tests.add(m);
                } else {
                    System.out.printf("WARN  %s.%s looks like a test but is not public static — skipped%n",
                            testClass.getSimpleName(), m.getName());
                }
            }
            if (tests.isEmpty()) {
                // A silent zero-test class would print "0 tests, 0 failures" and exit 0.
                throw new IllegalStateException(testClass.getName() + ": no public static test* methods found");
            }
            tests.sort(Comparator.comparing(Method::getName));
            for (Method m : tests) {
                String name = testClass.getSimpleName() + "." + m.getName();
                boolean takesStore = m.getParameterCount() == 1 && m.getParameterTypes()[0] == TestStore.class;
                String[] formats = takesStore ? FORMATS : new String[] {"-"};
                for (String fmt : formats) {
                    Throwable failure = null;
                    try {
                        if (takesStore) {
                            try (TestStore ts = TestStore.create(fmt)) {
                                m.invoke(null, ts);
                            }
                        } else {
                            m.invoke(null);
                        }
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        failure = e.getCause();
                    } catch (Throwable t) {
                        failure = t;
                    }
                    results.add(new Result(name, fmt, failure));
                }
            }
        }

        int failed = 0;
        for (Result r : results) {
            String status = r.failure() == null ? "ok  " : "FAIL";
            System.out.printf("%s  %-6s %s%n", status, r.format(), r.name());
            if (r.failure() != null) {
                failed++;
                r.failure().printStackTrace(System.out);
            }
        }
        System.out.printf("%d tests, %d failures%n", results.size(), failed);
        return new Tally(results.size(), failed);
    }
}
