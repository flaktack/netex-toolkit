package toolkit.test;

import java.util.Objects;

/// Minimal assertions for toolkit tests (no JUnit: tests stay source-launched scripts).
public final class Check {

    private Check() {}

    public static void that(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    /// JUnit-style `(expected, actual)` — the REVERSE of `Xml.assertXmlEqual`.
    public static void equals(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(msg + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void isNull(Object actual, String msg) {
        if (actual != null) throw new AssertionError(msg + ": expected null but was <" + actual + ">");
    }
}
