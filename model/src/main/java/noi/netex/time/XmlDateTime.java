package noi.netex.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Carrier for `xs:dateTime` and `xs:date` — one type for both.
///
/// The single carrier is load-bearing: `EpipCalendar` mixes `DayTypeAssignment.getDate()` (an
/// `xs:date`) with `AvailabilityCondition.getFromDate()` (an `xs:dateTime`) in one collection, and
/// two types would make that collection untypeable.
///
/// A serialization carrier: it retains the source lexical form so a re-marshal is byte-exact, and
/// keeps the timezone offset a plain LocalDateTime binding parses and discards.
///
/// Normalise before comparing, and never put a carrier in a Set — in particular not in the EPIP
/// calendar flattener, whose set membership decides the valid-day bits. A naive and an
/// offset-bearing carrier naming the same wall clock are not interchangeable, and two carriers
/// naming the same instant may differ lexically. [#hashCode()] therefore throws, turning a silent
/// cardinality change into a failure; `equals` is left as identity.
///
/// Unparseable input is not an error: the carrier keeps the raw text, reports [#isUnparsed()] and
/// returns null from [#toLocalDateTime()]. It is counted in [UnparsedTally].
public final class XmlDateTime {

    private static final Pattern DATE_TIME_LEXICAL = Pattern.compile(
            "(-?\\d{4,})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?"
                    + "(Z|[+-]\\d{2}:\\d{2})?");

    private static final Pattern DATE_LEXICAL = Pattern.compile(
            "(-?\\d{4,})-(\\d{2})-(\\d{2})(Z|[+-]\\d{2}:\\d{2})?");

    private final LocalDateTime local;
    private final ZoneOffset offset;
    private final boolean dateOnly;
    private final String lexical;
    private final boolean unparsed;

    private XmlDateTime(LocalDateTime local, ZoneOffset offset, boolean dateOnly,
                        String lexical, boolean unparsed) {
        this.local = local;
        this.offset = offset;
        this.dateOnly = dateOnly;
        this.lexical = lexical;
        this.unparsed = unparsed;
    }

    // ------------------------------------------------------------------ factories

    /// Parses an `xs:dateTime` lexical form, retaining it verbatim (after whitespace trimming) so a
    /// re-marshal is byte-exact.
    ///
    /// A date-only value in an `xs:dateTime` field yields an [unparsed][#isUnparsed()] carrier
    /// rather than being silently promoted. Use [#parseDate] for `xs:date` fields.
    ///
    /// @return `null` for `null` or blank input; never throws.
    public static XmlDateTime parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = DATE_TIME_LEXICAL.matcher(s);
        if (!m.matches()) {
            return unparsed(text, false);
        }
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            int second = Integer.parseInt(m.group(6));
            int nano = nanoOf(m.group(7));
            LocalDateTime value;
            if (hour == 24 && minute == 0 && second == 0 && nano == 0) {
                // xs:dateTime's end-of-day form.
                value = date.plusDays(1).atStartOfDay();
            } else if (hour > 23) {
                return unparsed(text, false);
            } else {
                value = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute, second, nano));
            }
            return new XmlDateTime(value, offsetOf(m.group(8)), false, s, false);
        } catch (RuntimeException e) {
            return unparsed(text, false);
        }
    }

    /// Parses an `xs:date` lexical form. The result reports [#isDateOnly()] and normalises to
    /// `atStartOfDay()`.
    ///
    /// @return `null` for `null` or blank input; never throws.
    public static XmlDateTime parseDate(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = DATE_LEXICAL.matcher(s);
        if (!m.matches()) {
            return unparsed(text, true);
        }
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            return new XmlDateTime(date.atStartOfDay(), offsetOf(m.group(4)), true, s, false);
        } catch (RuntimeException e) {
            return unparsed(text, true);
        }
    }

    public static XmlDateTime from(LocalDateTime value) {
        return new XmlDateTime(value, null, false, null, false);
    }

    /// The one place an unparsed carrier is built for either arm — counted here because the carrier
    /// raises no `ValidationEvent` (the adapter returned an object, so JAXB saw a success) and is
    /// therefore invisible to `toolkit.codec.BindEvents`. The two arms are counted apart: a
    /// date-only lexical in an `xs:dateTime` field is a different defect from unparseable text, and
    /// both land here.
    private static XmlDateTime unparsed(String raw, boolean dateOnly) {
        UnparsedTally.record(dateOnly ? UnparsedTally.Kind.DATE : UnparsedTally.Kind.DATE_TIME, raw);
        return new XmlDateTime(null, null, dateOnly, raw, true);
    }

    // ------------------------------------------------------------------ normalisation

    /// The local part with the offset discarded, or `atStartOfDay()` on the date arm.
    ///
    /// @return `null` if this carrier is [unparsed][#isUnparsed()].
    public LocalDateTime toLocalDateTime() {
        return local;
    }

    // ------------------------------------------------------------------ accessors

    /// The offset as written, or `null` when the source carried none.
    public ZoneOffset getOffset() {
        return offset;
    }

    /// True when the source was an `xs:date` (no time part).
    public boolean isDateOnly() {
        return dateOnly;
    }

    /// The retained source lexical form, or `null` for a synthesized value.
    public String getLexical() {
        return lexical;
    }

    public boolean isUnparsed() {
        return unparsed;
    }

    // ------------------------------------------------------------------ serialization

    /// The retained lexical form if there is one, else the canonical form.
    public String toXmlFormat() {
        return lexical != null ? lexical : canonical();
    }

    private String canonical() {
        StringBuilder sb = new StringBuilder(32);
        appendYear(sb, local.getYear());
        sb.append('-');
        appendAtLeast2(sb, local.getMonthValue());
        sb.append('-');
        appendAtLeast2(sb, local.getDayOfMonth());
        if (!dateOnly) {
            sb.append('T');
            appendAtLeast2(sb, local.getHour());
            sb.append(':');
            appendAtLeast2(sb, local.getMinute());
            sb.append(':');
            appendAtLeast2(sb, local.getSecond());
            appendFraction(sb, local.getNano());
        }
        if (offset != null) {
            sb.append(offset.getId());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toXmlFormat();
    }

    /// @throws UnsupportedOperationException always — a carrier must never be hashed. Normalise
    ///         with [#toLocalDateTime()] before putting values in a `Set`.
    @Override
    public int hashCode() {
        throw new UnsupportedOperationException(
                "XmlDateTime is a serialization carrier: normalise with toLocalDateTime() before "
                        + "comparing or hashing; never put a carrier in a Set");
    }

    // ------------------------------------------------------------------ helpers

    private static int nanoOf(String digits) {
        if (digits == null) {
            return 0;
        }
        String d = digits.length() > 9 ? digits.substring(0, 9) : digits;
        int nano = Integer.parseInt(d);
        for (int i = d.length(); i < 9; i++) {
            nano *= 10;
        }
        return nano;
    }

    private static ZoneOffset offsetOf(String text) {
        if (text == null) {
            return null;
        }
        return "Z".equals(text) ? ZoneOffset.UTC : ZoneOffset.of(text);
    }

    private static void appendYear(StringBuilder sb, int year) {
        int y = year;
        if (y < 0) {
            sb.append('-');
            y = -y;
        }
        if (y < 1000) {
            sb.append(String.format("%04d", y));
        } else {
            sb.append(y);
        }
    }

    private static void appendAtLeast2(StringBuilder sb, int value) {
        if (value < 10) {
            sb.append('0');
        }
        sb.append(value);
    }

    /// Fraction with the fewest of 3/6/9 digits that keeps the value; nothing when zero.
    private static void appendFraction(StringBuilder sb, int nano) {
        if (nano == 0) {
            return;
        }
        String digits = String.format("%09d", nano);
        if (digits.endsWith("000000")) {
            digits = digits.substring(0, 3);
        } else if (digits.endsWith("000")) {
            digits = digits.substring(0, 6);
        }
        sb.append('.').append(digits);
    }
}
