package toolkit.util;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// The drivers' logger: slf4j over logback, with a `String.format` surface rather than
/// slf4j's {@code {}} placeholders — the call sites build aligned tabular reports out of width and
/// precision specifiers (`%-8s`, `%,d`, `%.1f`) that {@code {}} cannot express.
/// Console layout is in `logback.xml`.
public final class Log {

    /// Must match logback.xml's console pattern so a --log-file transcript reads the same.
    private static final String PATTERN = "%d{ISO8601} %-5level %msg%n";

    private static final Logger LOG = LoggerFactory.getLogger("toolkit");

    private static FileAppender<ILoggingEvent> file;

    private Log() {}

    /// Also write every record to `path` (the `--log-file` option), truncating it. A `null` path
    /// detaches and closes the current file.
    public static synchronized void toFile(Path path) {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (file != null) {
            root.detachAppender(file);
            file.stop();                       // flushes; the reader may run immediately after
            file = null;
        }
        if (path == null) return;

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(ctx);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(ctx);
        appender.setName("log-file");
        appender.setFile(path.toString());
        appender.setAppend(false);
        appender.setImmediateFlush(true);
        appender.setEncoder(encoder);
        appender.start();
        if (!appender.isStarted()) throw new IllegalStateException("cannot open log file " + path);

        root.addAppender(appender);
        file = appender;
    }

    /// Elapsed seconds since a `System.nanoTime()` mark.
    public static double secs(long nanosStart) {
        return (System.nanoTime() - nanosStart) / 1e9;
    }

    /// Whole-process peak RSS in kB (VmHWM from /proc/self/status); -1 if unreadable.
    public static long peakRssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (IOException ignored) {
        }
        return -1;
    }

    /// A throwable with its full stack trace as one string.
    public static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static void info(String fmt, Object... args) {
        LOG.info(format(fmt, args));
    }

    public static void warn(String fmt, Object... args) {
        LOG.warn(format(fmt, args));
    }

    public static void error(String fmt, Object... args) {
        LOG.error(format(fmt, args));
    }

    /// No args means no formatting, so a message containing a literal `%` is safe. `Locale.ROOT`
    /// because a comma-decimal default locale would turn a `"%.1fs"` timing into `"3,2s"`.
    private static String format(String fmt, Object... args) {
        return args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args);
    }
}
