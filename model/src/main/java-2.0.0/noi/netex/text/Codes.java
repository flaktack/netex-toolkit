package noi.netex.text;

import noi.netex.model.PublicCodeStructure;
import noi.netex.model.Quay;

/// THE `PublicCodeStructure` ACCESSOR SEAM.
///
/// The type carries the code as an `@XmlValue` beside an optional `type` attribute, so a code is
/// reached through a wrapper rather than off the holder directly.
public final class Codes {

    private Codes() {}

    /// The code `c` carries, or null when `c` is absent or carries no value. A feed may publish the
    /// element empty — campania/AIR writes `&lt;PublicCode /&gt;` on every Line — which binds to a
    /// present structure holding null.
    public static String text(PublicCodeStructure c) {
        return c == null ? null : c.getValue();
    }

    /// Whether `c` carries a code. Blank counts as absent, which is what admits the empty element
    /// above.
    public static boolean hasText(PublicCodeStructure c) {
        String v = text(c);
        return v != null && !v.isBlank();
    }

    /// A structure holding `value`, with no `type`.
    public static PublicCodeStructure of(String value) {
        PublicCodeStructure c = new PublicCodeStructure();
        c.setValue(value);
        return c;
    }

    public static String publicCode(Quay q) {
        return text(q.getPublicCode());
    }
}
