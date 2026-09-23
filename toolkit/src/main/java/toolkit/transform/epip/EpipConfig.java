package toolkit.transform.epip;

// The configuration defaults the EPIP drivers and exporters read. These are compile-time
// constants; there is no overlay file.

/// The configuration values consumed by the EPIP pipeline.

public final class EpipConfig {

    private EpipConfig() {}

    /// When a Line carries both an operatorRef and an authorityRef, true moves the OPERATOR into
    /// additionalOperators (the Authority stays primary); false moves the AUTHORITY instead.
    public static final boolean AUTHORITY_REFERENCE = true;

    /// The EPIP data codespace, which the profile reserves for exactly this: ids a producer mints
    /// with no identifier scheme of its own to put them in.
    ///
    /// The FALLBACK prefix, not the universal one. A generated id takes the grammar of the object
    /// it came from; this is what is left when there is no source id to read.
    public static final String CODESPACE = "epd";

    /// The `id` and `XmlnsUrl` of the Codespace [#CODESPACE] is the xmlns of. Declared in the
    /// export so the ids that carry the prefix name a codespace the document defines.
    public static final String CODESPACE_ID = "epip_data";
    public static final String CODESPACE_URL = "http://netex-cen.eu/epip_data";

    /// The generated-object version, kept as an int here and stringified where the model needs it
    /// (see [GeneratorDefaults]).
    public static final int VERSION = 1;
}
