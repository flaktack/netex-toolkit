package toolkit.harness;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/// The `PublicationDelivery` / `CompositeFrame` wrapper every entry of a split NeTEx
/// export repeats, written straight into a [ZipWriter].
///
/// Each entry is an INDEPENDENT delivery — a consumer that reads one file must get a valid document
/// — so the preamble is written once per entry, with the codespaces and frame defaults repeated
/// verbatim in each. This object is therefore built once and written many times.
///
/// NOT reproducible, deliberately: `<PublicationTimestamp>` is a wall clock, in every entry. It is
/// the one field that makes two exports of one store differ, and why [ZipWriter]'s pinned entry
/// timestamps buy comparable METADATA rather than a comparable archive.
///
/// Not parameterised: the NeTEx and gml namespaces, the NeTEx version, the default location system
/// and units, and the `versionRef` on every `TypeOfFrameRef`.
public final class NetexEnvelope {

    /// The two strings a profile actually chooses, plus the two labels around them.
    ///
    /// @param participantRef  `<ParticipantRef>` — the EPIP export writes `NDOV`
    /// @param description     the `<Description><Text>` body
    /// @param compositeFrameId  the composite frame's `id`
    /// @param typeOfFrameRef  the composite frame's type, e.g. `epip:EU_PI_NETWORK_OFFER`
    public record Options(String participantRef, String description,
                          String compositeFrameId, String typeOfFrameRef) {}

    private final Options opts;
    private final String version;
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
        // Today, as yyyyMMdd, read once: an export that straddles midnight must not emit two
        // versions for one run.
        this.version = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
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
                + "<PublicationTimestamp>" + LocalDateTime.now() + "</PublicationTimestamp>"
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
