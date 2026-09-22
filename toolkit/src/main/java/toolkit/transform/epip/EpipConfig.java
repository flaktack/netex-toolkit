package toolkit.transform.epip;

// The configuration defaults the EPIP drivers and exporters read. These are compile-time
// constants; there is no overlay file.

/// The configuration values consumed by the EPIP pipeline.

public final class EpipConfig {

    private EpipConfig() {}

    /// When a Line carries both an operatorRef and an authorityRef, true moves the OPERATOR into
    /// additionalOperators (the Authority stays primary); false moves the AUTHORITY instead.
    public static final boolean AUTHORITY_REFERENCE = true;

    /// The xmlns of the generator-defaults Codespace ([GeneratorDefaults#PRODUCTION]).
    public static final String CODESPACE = "OPENOV";

    /// The generated-object version, kept as an int here and stringified where the model needs it
    /// (see [GeneratorDefaults]).
    public static final int VERSION = 1;
}
