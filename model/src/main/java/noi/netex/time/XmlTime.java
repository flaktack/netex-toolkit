package noi.netex.time;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Carrier for `xs:time`.
///
/// PROV-CAP: adapters-lossless — this carrier and its [XmlDateTime] and [XmlDuration] siblings are
/// that capability.
///
/// A serialization carrier: it holds everything the source lexical form said, including the two
/// things [LocalTime] cannot — an hour of 24 or more, and a timezone offset — so a re-marshal is
/// byte-exact. The hour is stored raw: `24:09:00` reports [#getHour()] 24 and [#getDayOffset()] 1,
/// while [#toLocalTime()] normalises.
///
/// Normalise before comparing, and never put a carrier in a Set: two carriers holding the same
/// instant may differ in lexical form, offset presence and raw hour. [#hashCode()] therefore
/// throws, turning a silent wrong answer into a failure; `equals` is left as identity.
///
/// Unparseable input is not an error: the carrier keeps the raw text, reports [#isUnparsed()] and
/// returns null from [#toLocalTime()]. It is counted in [UnparsedTally].
public final class XmlTime {

    /// `hh:mm:ss[.fraction][Z|±hh:mm]`, with the hour deliberately unbounded.
    private static final Pattern LEXICAL = Pattern.compile(
            "(\\d{2,})" + ":(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})?");

    private final int hour;
    private final int minute;
    private final int second;
    private final int nano;
    private final ZoneOffset offset;
    private final String lexical;
    private final boolean unparsed;

    private XmlTime(int hour, int minute, int second, int nano,
                    ZoneOffset offset, String lexical, boolean unparsed) {
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.nano = nano;
        this.offset = offset;
        this.lexical = lexical;
        this.unparsed = unparsed;
    }

    // ------------------------------------------------------------------ factories

    /// Parses an `xs:time` lexical form, retaining it verbatim after whitespace trimming, so a
    /// re-marshal is byte-exact.
    ///
    /// @return `null` for `null` or blank input; an [unparsed][#isUnparsed()]
    ///         carrier holding the raw text if the value cannot be parsed; never throws.
    public static XmlTime parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = LEXICAL.matcher(s);
        if (!m.matches()) {
            return unparsed(text);
        }
        try {
            int h = Integer.parseInt(m.group(1));
            int mi = Integer.parseInt(m.group(2));
            int se = Integer.parseInt(m.group(3));
            if (mi > 59 || se > 59) {
                return unparsed(text);
            }
            return new XmlTime(h, mi, se, nanoOf(m.group(4)), offsetOf(m.group(5)), s, false);
        } catch (RuntimeException e) {
            return unparsed(text);
        }
    }

    /// Synthesized value, no offset, no retained lexical — re-emits canonically.
    public static XmlTime of(int hour, int minute, int second) {
        if (hour < 0 || minute < 0 || minute > 59 || second < 0 || second > 59) {
            throw new IllegalArgumentException("out of range: " + hour + ":" + minute + ":" + second);
        }
        return new XmlTime(hour, minute, second, 0, null, null, false);
    }

    /// Synthesized value from a wall clock. The day offset is lost by construction —
    /// [LocalTime] never carried one — so this can only ever produce hours 0..23.
    public static XmlTime from(LocalTime time) {
        return new XmlTime(time.getHour(), time.getMinute(), time.getSecond(), time.getNano(),
                null, null, false);
    }

    /// The one place an unparsed `xs:time` carrier is built, which is why the count is taken here:
    /// the carrier raises no `ValidationEvent` — the adapter returned an object, so JAXB saw a
    /// success — so `toolkit.codec.BindEvents` cannot see this value at all.
    private static XmlTime unparsed(String raw) {
        UnparsedTally.record(UnparsedTally.Kind.TIME, raw);
        return new XmlTime(0, 0, 0, 0, null, raw, true);
    }

    // ------------------------------------------------------------------ normalisation

    /// Wall-clock time, day offset and zone offset discarded.
    ///
    /// @return `null` if this carrier is [unparsed][#isUnparsed()].
    public LocalTime toLocalTime() {
        return unparsed ? null : LocalTime.of(hour % 24, minute, second, nano);
    }

    // ------------------------------------------------------------------ accessors

    /// The hour as written: 24, 25, 30 are returned unreduced.
    public int getHour() {
        return hour;
    }

    /// `hour / 24` — 1 for `24:09:00`, 0 for every ordinary time.
    public int getDayOffset() {
        return hour / 24;
    }

    /// Whether this value is a legal `xs:time`.
    ///
    /// XSD admits hour 24 only as the midnight-end-of-day form — zero minute, second and fraction
    /// — and no hour above 24. This carrier parses both, so a consumer that needs the distinction
    /// has to ask for it here.
    ///
    /// @return `false` for an [unparsed][#isUnparsed()] carrier, for any hour above 24, and for
    ///         hour 24 with a non-zero minute, second or fraction; `true` otherwise.
    public boolean isXsdValid() {
        if (unparsed || hour > 24) {
            return false;
        }
        return hour != 24 || (minute == 0 && second == 0 && nano == 0);
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }

    public int getNano() {
        return nano;
    }

    /// The offset as written, or `null` when the source carried none — which is the usual case.
    public ZoneOffset getOffset() {
        return offset;
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
        StringBuilder sb = new StringBuilder(18);
        appendAtLeast2(sb, hour);
        sb.append(':');
        appendAtLeast2(sb, minute);
        sb.append(':');
        appendAtLeast2(sb, second);
        appendFraction(sb, nano);
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
    ///         with [#toLocalTime()] (or compare [#toXmlFormat()]) instead.
    @Override
    public int hashCode() {
        throw new UnsupportedOperationException(
                "XmlTime is a serialization carrier: normalise with toLocalTime() before "
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
