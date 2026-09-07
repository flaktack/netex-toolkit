package noi.netex.time;

import java.math.BigDecimal;
import java.math.BigInteger;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.Duration;

/// Carrier for `xs:duration`.
///
/// It wraps [javax.xml.datatype.Duration], which parses the whole `xs:duration` lexical space.
/// `java.time.Duration.parse` accepts only the `PnDTnHnMnS` form and throws on the
/// `PnYnMnDTnHnMnS` forms the corpus contains.
///
/// A serialization carrier: it retains the source lexical form so a re-marshal is byte-exact.
///
/// Normalise before comparing, and never put a carrier in a Set: `PT1H` and `PT60M` are the same
/// duration and different carriers. [#hashCode()] therefore throws; `equals` is left as identity.
///
/// Input that cannot be parsed is not an error: the carrier retains the raw text, reports
/// [#isUnparsed()] and returns `null` from [#toDuration()]. It is counted in [UnparsedTally].
public final class XmlDuration {

    private static final DatatypeFactory FACTORY = newFactory();

    private final Duration duration;
    private final String lexical;
    private final boolean unparsed;

    private XmlDuration(Duration duration, String lexical, boolean unparsed) {
        this.duration = duration;
        this.lexical = lexical;
        this.unparsed = unparsed;
    }

    // ------------------------------------------------------------------ factories

    /// Parses an `xs:duration` lexical form, retaining it verbatim (after whitespace trimming) so a
    /// re-marshal is byte-exact.
    ///
    /// @return `null` for `null` or blank input; an [unparsed][#isUnparsed()] carrier holding the
    ///         raw text if the value cannot be parsed; never throws.
    public static XmlDuration parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new XmlDuration(FACTORY.newDuration(s), s, false);
        } catch (RuntimeException e) {
            return unparsed(text);
        }
    }

    /// The one place an unparsed carrier is built, which is why the count is taken here: the
    /// carrier raises no `ValidationEvent` — the adapter returned an object, so JAXB saw a success
    /// — so `toolkit.codec.BindEvents` cannot see this value at all.
    private static XmlDuration unparsed(String raw) {
        UnparsedTally.record(UnparsedTally.Kind.DURATION, raw);
        return new XmlDuration(null, raw, true);
    }

    // ------------------------------------------------------------------ normalisation

    /// Returns a duration of days, hours, minutes and fractional seconds, signed.
    ///
    /// Years and months are ignored, there is no date to resolve them against. Use [#getYears()] /
    /// [#getMonths()] if a caller needs to detect the case.
    ///
    /// @return `null` if this carrier is [unparsed][#isUnparsed()].
    public java.time.Duration toDuration() {
        if (unparsed) {
            return null;
        }
        BigDecimal seconds = getSeconds();
        long wholeSeconds = seconds.longValue();
        long nanos = seconds.subtract(BigDecimal.valueOf(wholeSeconds)).movePointRight(9).longValue();
        java.time.Duration value = java.time.Duration.ofDays(getDays())
                .plusHours(getHours())
                .plusMinutes(getMinutes())
                .plusSeconds(wholeSeconds)
                .plusNanos(nanos);
        return getSign() < 0 ? value.negated() : value;
    }

    // ------------------------------------------------------------------ accessors

    /// `-1`, `0` or `1`; `0` for an unparsed carrier.
    public int getSign() {
        return unparsed ? 0 : duration.getSign();
    }

    /// Magnitude only — the sign lives in [#getSign()].
    public int getYears() {
        return field(DatatypeConstants.YEARS);
    }

    public int getMonths() {
        return field(DatatypeConstants.MONTHS);
    }

    public int getDays() {
        return field(DatatypeConstants.DAYS);
    }

    public int getHours() {
        return field(DatatypeConstants.HOURS);
    }

    public int getMinutes() {
        return field(DatatypeConstants.MINUTES);
    }

    /// Magnitude only, fractional; [BigDecimal#ZERO] when absent or unparsed.
    public BigDecimal getSeconds() {
        if (unparsed) {
            return BigDecimal.ZERO;
        }
        Number value = (Number) duration.getField(DatatypeConstants.SECONDS);
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
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
        return lexical != null ? lexical : duration.toString();
    }

    @Override
    public String toString() {
        return toXmlFormat();
    }

    /// @throws UnsupportedOperationException always — a carrier must never be hashed. Normalise
    ///         with [#toDuration()] before comparing.
    @Override
    public int hashCode() {
        throw new UnsupportedOperationException(
                "XmlDuration is a serialization carrier: normalise with toDuration() before "
                        + "comparing or hashing; never put a carrier in a Set");
    }

    // ------------------------------------------------------------------ helpers

    private int field(DatatypeConstants.Field name) {
        if (unparsed) {
            return 0;
        }
        BigInteger value = (BigInteger) duration.getField(name);
        return value == null ? 0 : value.intValue();
    }

    private static DatatypeFactory newFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
