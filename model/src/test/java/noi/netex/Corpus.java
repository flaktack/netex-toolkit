package noi.netex;

/// Real lexical forms, lifted verbatim from source feeds: every shape a binding has to survive.
public final class Corpus {

    private Corpus() {
    }

    /// `xs:time`. The first four are after-midnight values a LocalTime binding destroys.
    public static final String[] TIMES = {
            "24:00:00",
            "24:09:00",
            "25:15:00",
            "30:05:00",
            "18:51:00+01:00",
            "07:30:00",
            "00:05:00",
            "23:25:00",
            "12:00:00",
    };

    /// `xs:dateTime`.
    public static final String[] DATE_TIMES = {
            "2024-12-13T00:37:41.000+01:00",
            "2026-02-10T09:29:40.961807061Z",
            "2026-07-14T01:46:16.8044987+02:00",
            "2025-12-18T23:59:59",
            "1978-10-01T00:00:00",
            "2026-02-10T00:00:00",
            "2024-06-09T00:00:00",
    };

    /// `xs:date`.
    public static final String[] DATES = {
            "2025-12-14",
            "2026-02-10",
    };

    /// `xs:duration`. The first two are the forms `java.time.Duration` rejects.
    public static final String[] DURATIONS = {
            "P0Y0M0DT0H36M0.000S",
            "-P0Y0M0DT0H10M0.000S",
            "PT16M",
            "PT53M",
            "PT0S",
            "PT1M12S",
            "PT11H22M",
    };
}
