package toolkit.transform.epip;

// The two-entry defaults map every EPIP generator takes -- a codespace and a version -- as a record,
// so generator signatures read `f(Store db, Txn txn, GeneratorDefaults gd)`.

import noi.netex.model.Codespace;
import toolkit.model.NetexUtils;

/// The pair handed to every EPIP generator: the [Codespace] a generated id falls back to, and the
/// version stamped on generated objects.
///
/// The codespace is a FALLBACK, not the prefix of every generated id. A generated object takes the
/// id grammar of the object it was generated from (see [NetexUtils#deriveId]); this is what a
/// generator uses when there is no source id, or when the source carries no class token to read.
///
/// It is also inserted into the store as a row, so the export declares it: an id carrying a
/// codespace the document never declares is what the profile forbids.
///
/// **The version is a STRING, not a number.** NeTEx types `@version` as a string, the beans type it
/// as {@link String}, and {@link EpipConfig#VERSION} is converted once here rather than at every
/// assignment.
public record GeneratorDefaults(Codespace codespace, String version) {

    /// The test defaults: Codespace(id="cs", xmlns="test"), version "1".
    public static final GeneratorDefaults TEST =
            new GeneratorDefaults(codespace("cs", "test", null), "1");

    /// The production defaults, from [EpipConfig]: the EPIP data codespace, plus the configured
    /// version as a string.
    public static final GeneratorDefaults PRODUCTION = new GeneratorDefaults(
            codespace(EpipConfig.CODESPACE_ID, EpipConfig.CODESPACE, EpipConfig.CODESPACE_URL),
            String.valueOf(EpipConfig.VERSION));

    /// The same defaults over a different codespace xmlns, for a publisher that does not want the
    /// EPIP one. The id and URL follow the xmlns, so a custom codespace is still self-describing.
    public static GeneratorDefaults withCodespace(String xmlns) {
        if (xmlns == null || xmlns.equals(EpipConfig.CODESPACE)) return PRODUCTION;
        return new GeneratorDefaults(codespace(xmlns, xmlns, null),
                String.valueOf(EpipConfig.VERSION));
    }

    private static Codespace codespace(String id, String xmlns, String url) {
        Codespace cs = new Codespace();
        cs.setId(id);
        cs.setXmlns(xmlns);
        cs.setXmlnsUrl(url);
        return cs;
    }
}
