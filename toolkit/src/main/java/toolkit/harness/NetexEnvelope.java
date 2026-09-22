package toolkit.harness;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/// The `PublicationDelivery` / `CompositeFrame` wrapper every entry of a split NeTEx export repeats,
/// written straight into a [ZipWriter].
///
/// Each entry is an independent delivery — a consumer that reads one file must get a valid document
/// — so the preamble is written once per entry, with the codespaces and frame defaults repeated
/// verbatim in each. This object is therefore built once and written many times.
///
/// `<PublicationTimestamp>` and the frame `version` are read ONCE, in the constructor, and repeated
/// in every entry. Supply both through [Options] and two exports of one store are byte-identical;
/// leave them null and they come off the clock, which is what makes two runs differ.
///
/// Not parameterised: the NeTEx and gml namespaces, the NeTEx version, the default location system
/// and units, and the `versionRef` on every `TypeOfFrameRef`.
public final class NetexEnvelope {

    /// The strings a profile chooses, plus the two stamps that decide reproducibility.
    ///
    /// @param participantRef  `<ParticipantRef>`
    /// @param description     the `<Description><Text>` body
    /// @param compositeFrameId  the composite frame's `id`
    /// @param typeOfFrameRef  the composite frame's type, e.g. `epip:EU_PI_NETWORK_OFFER`
    /// @param publicationTimestamp  `<PublicationTimestamp>`, or null for the clock
    /// @param frameVersion   the `version` on the composite frame and every frame, or null for
    ///                       today as `yyyyMMdd`
    public record Options(String participantRef, String description,
                          String compositeFrameId, String typeOfFrameRef,
                          LocalDateTime publicationTimestamp, String frameVersion) {

        /// Both stamps off the clock — the shape every caller used before either was configurable.
        public Options(String participantRef, String description,
                       String compositeFrameId, String typeOfFrameRef) {
            this(participantRef, description, compositeFrameId, typeOfFrameRef, null, null);
        }
    }

    private final Options opts;
    private final String version;
    private final String publicationTimestamp;
    private final List<byte[]> codespaces;
    private final byte[] defaultLocale;

    /// @param codespaces    the `<Codespace>` fragments to repeat in every entry, in emission
    ///                      order; empty writes no `<codespaces>` element at all
    /// @param defaultLocale the `<DefaultLocale>` block, already serialised, or null — deriving it
    ///                      is profile business and stays with the driver
    public NetexEnvelope(Options opts, List<byte[]> codespaces, byte[] defaultLocale) {
        this.opts = opts;
        this.codespaces = List.copyOf(codespaces);
        this.defaultLocale = defaultLocale;
        // Both read once: an export that straddles midnight must not emit two versions for one
        // run, and open() runs per ENTRY, so reading the clock there gave every entry of one
        // export a different timestamp.
        this.version = opts.frameVersion() != null ? opts.frameVersion()
                : LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        this.publicationTimestamp = (opts.publicationTimestamp() != null
                ? opts.publicationTimestamp() : LocalDateTime.now()).toString();
    }

    /// The version string every frame of this export carries.
    public String version() {
        return version;
    }

    /// The preamble, down to and including the opening `<frames>`.
    public void open(ZipWriter out) throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<PublicationDelivery xmlns=\"http://www.netex.org.uk/netex\""
                + " xmlns:gml=\"http://www.opengis.net/gml/3.2\" version=\"ntx:1.1\">"
                + "<PublicationTimestamp>" + publicationTimestamp + "</PublicationTimestamp>"
                + "<ParticipantRef>" + opts.participantRef() + "</ParticipantRef>"
                + "<Description><Text>" + opts.description() + "</Text></Description>"
                + "<dataObjects><CompositeFrame id=\"" + opts.compositeFrameId()
                + "\" version=\"" + version + "\">"
                + "<TypeOfFrameRef ref=\"" + opts.typeOfFrameRef() + "\" versionRef=\"1.0\"/>");
        if (!codespaces.isEmpty()) {
            out.write("<codespaces>");
            for (byte[] c : codespaces) out.write(c);
            out.write("</codespaces>");
        }
        out.write("<FrameDefaults>");
        // DefaultLocale precedes DefaultLocationSystem — VersionFrameDefaultsStructure's field order.
        if (defaultLocale != null) out.write(defaultLocale);
        out.write("<DefaultLocationSystem>urn:ogc:def:crs:EPSG::4326</DefaultLocationSystem>"
                + "<DefaultSystemOfUnits>SiMetres</DefaultSystemOfUnits></FrameDefaults><frames>");
    }

    /// The closing tags matching [#open].
    public void close(ZipWriter out) throws IOException {
        out.write("</frames></CompositeFrame></dataObjects></PublicationDelivery>");
    }

    /// One frame's start tag and its `TypeOfFrameRef`, at this envelope's version.
    public void frameOpen(ZipWriter out, String frame, String id, String typeRef) throws IOException {
        out.write("<" + frame + " id=\"" + id + "\" version=\"" + version + "\">"
                + "<TypeOfFrameRef ref=\"" + typeRef + "\" versionRef=\"1.0\"/>");
    }
}
