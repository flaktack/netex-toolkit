package toolkit.transform.epip;

// The two-entry defaults map every EPIP generator takes — a codespace and a version — as a record,
// so generator signatures read `f(Store db, Txn txn, GeneratorDefaults gd)`.

import noi.netex.model.Codespace;
import toolkit.model.NetexUtils;

/// The pair handed to every EPIP generator: the synthetic [Codespace] whose `xmlns` prefixes
/// generated ids (see [NetexUtils#getId]), and the version stamped on generated objects.
///
/// **The version is a STRING, not a number.** NeTEx types `@version` as a string, the beans type it
/// as {@link String}, and {@link EpipConfig#VERSION} is converted once here rather than at every
/// assignment.
public record GeneratorDefaults(Codespace codespace, String version) {

    /// The test defaults: Codespace(id="cs", xmlns="test"), version "1".
    public static final GeneratorDefaults TEST =
            new GeneratorDefaults(codespace("cs", "test"), "1");

    /// The production defaults, from [EpipConfig]: a Codespace with id `"codespace"` and the
    /// configured xmlns, plus the configured version as a string.
    public static final GeneratorDefaults PRODUCTION =
            new GeneratorDefaults(codespace("codespace", EpipConfig.CODESPACE),
                    String.valueOf(EpipConfig.VERSION));

    private static Codespace codespace(String id, String xmlns) {
        Codespace cs = new Codespace();
        cs.setId(id);
        cs.setXmlns(xmlns);
        return cs;
    }
}
