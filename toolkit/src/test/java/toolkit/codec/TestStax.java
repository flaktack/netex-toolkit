package toolkit.codec;

// The toolkit's StAX reader, and what depends on there being exactly one.
//
// Four things are pinned: one reader across FragmentCodec, FragmentPeek, RefPatcher.scan and
// RefPatcher.patch; the byte offsets patch splices on, over inputs where a byte offset is not a
// character offset; the enum collapse firing; and malformed input refused rather than read as
// absent.

import jakarta.xml.bind.Unmarshaller;
import noi.netex.model.Line;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public class TestStax {

    private static final String NS = "http://www.netex.org.uk/netex";

    /// Fragment shapes drawn from the corpus: references with and without nameOfRefClass, a padded
    /// enum, GML with a namespaced lookalike attribute, entities, CDATA, a comment, mixed
    /// whitespace, and an empty element.
    private static final String[][] FRAGMENTS = {
        {"noi.netex.model.Line",
            "<Line xmlns=\"" + NS + "\" id=\"x:Line:1\" version=\"1\">"
                + "<Name>L1</Name><TransportMode>bus </TransportMode>"
                + "<RepresentedByGroupRef ref=\"x:Network:1\" version=\"2\"/></Line>"},
        {"noi.netex.model.Line",
            "<Line xmlns=\"" + NS + "\" id=\"x:Line:2\" version=\"1\">"
                + "<Name>A &amp; B &lt;C&gt; &#233;</Name>"
                + "<Description><![CDATA[raw < text & more]]></Description>"
                + "<TransportMode>rail</TransportMode></Line>"},
        {"noi.netex.model.ServiceJourney",
            "<ServiceJourney xmlns=\"" + NS + "\" id=\"x:SJ:1\" version=\"1\">\n"
                + "  <!-- a comment between children -->\n"
                + "  <ServiceJourneyPatternRef ref=\"x:SJP:1\" version=\"3\""
                + " nameOfRefClass=\"ServiceJourneyPattern\"/>\n"
                + "  <passingTimes>\n"
                + "    <TimetabledPassingTime id=\"x:TPT:1\" version=\"1\">"
                + "<DepartureTime>23:50:00</DepartureTime></TimetabledPassingTime>\n"
                + "  </passingTimes>\n"
                + "</ServiceJourney>"},
        {"noi.netex.model.StopPlace",
            "<StopPlace xmlns=\"" + NS + "\" xmlns:gml=\"http://www.opengis.net/gml/3.2\""
                + " id=\"x:SP:1\" version=\"1\"><Name>S</Name>"
                + "<Centroid><Location><gml:pos srsName=\"EPSG:4326\">45.0 7.0</gml:pos>"
                + "</Location></Centroid>"
                + "<ParentSiteRef ref=\"x:SP:0\"/></StopPlace>"},
        {"noi.netex.model.StopPlace",
            "<StopPlace xmlns=\"" + NS + "\" id=\"x:SP:2\" version=\"1\">"
                + "<Name></Name><ShortName xml:lang=\"it\">Sp</ShortName>"
                + "<keyList/></StopPlace>"},
    };

    private static Class<?> factoryClass(Object owner, String field) throws Exception {
        Field f = owner.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(owner).getClass();
    }

    /// (1) One reader for every consumer of a stored fragment. The invariant is about the factory
    /// objects the three classes hold, which is why it is read reflectively off private fields.
    @Test
    void testEveryFragmentReaderSharesOneStaxImplementation() throws Exception {
        Class<?> chosen = Stax.factory().getClass();
        Check.equals(com.fasterxml.aalto.stax.InputFactoryImpl.class, chosen,
                "Stax.factory is Aalto; if this is deliberately changed, change Stax's javadoc "
                        + "with it");
        Check.equals(chosen, factoryClass(new FragmentCodec(), "xif"),
                "FragmentCodec.decode reads with the chosen factory");
        Check.equals(chosen, factoryClass(new FragmentPeek(), "xif"),
                "FragmentPeek reads with the SAME factory FragmentCodec.decode uses");
        Check.equals(chosen, factoryClass(new RefPatcher(), "xif"),
                "RefPatcher reads with the SAME factory FragmentCodec.decode uses");
    }

    /// (2) `patch` splices on per-event input byte offsets. Each case puts something ahead of the
    /// reference that moves a character offset away from the byte offset, or that a line-ending
    /// normalisation would move: a splice landing one position out rewrites the wrong bytes.
    @Test
    void testPatchLandsTheSpliceOnByteOffsets() {
        String head = "<ServiceJourney xmlns=\"" + NS + "\" id=\"x:SJ:9\" version=\"1\">";
        check(head + "<ServiceJourneyPatternRef ref=\"x:SJP:9\"/></ServiceJourney>",
                new RefPatcher.Key("x:SJP:9", null, null), new RefPatcher.Val("4", null),
                head + "<ServiceJourneyPatternRef ref=\"x:SJP:9\" version=\"4\"/></ServiceJourney>",
                "an ASCII fragment");

        String sp = "<StopPlace xmlns=\"" + NS + "\" id=\"x:SP:4\" version=\"1\">"
                + "<Name>Bozen/Bolzano Bahnhofsplatz — città 🚌</Name>";
        check(sp + "<ParentSiteRef ref=\"x:SP:0\"/></StopPlace>",
                new RefPatcher.Key("x:SP:0", null, null), new RefPatcher.Val("2", null),
                sp + "<ParentSiteRef ref=\"x:SP:0\" version=\"2\"/></StopPlace>",
                "multi-byte text ahead of the reference");

        String cr = "<ServiceJourney xmlns=\"" + NS + "\" id=\"x:SJ:2\" version=\"1\">\r\n  ";
        check(cr + "<ServiceJourneyPatternRef ref=\"x:SJP:2\" version=\"3\"/>\r\n</ServiceJourney>",
                new RefPatcher.Key("x:SJP:2", "3", null), new RefPatcher.Val("9", null),
                cr + "<ServiceJourneyPatternRef ref=\"x:SJP:2\" version=\"9\"/>\r\n</ServiceJourney>",
                "CRLF, whose line-ending normalisation does not reach a byte offset");
    }

    private static void check(String src, RefPatcher.Key k, RefPatcher.Val v, String want,
                              String what) {
        byte[] out = new RefPatcher().patch(src.getBytes(StandardCharsets.UTF_8), Map.of(k, v));
        Check.that(out != null, "patch returns a fragment for " + what);
        Check.equals(want, new String(out, StandardCharsets.UTF_8), what);
    }

    /// ... and every byte outside a patched start tag survives, so the reference-carrying shapes
    /// the toolkit stores come back unchanged when no patch lands on them.
    @Test
    void testPatchLeavesUnmatchedFragmentsAlone() {
        for (String[] f : FRAGMENTS) {
            byte[] xml = f[1].getBytes(StandardCharsets.UTF_8);
            Check.isNull(new RefPatcher().patch(xml,
                            Map.of(new RefPatcher.Key("no:such:ref", null, null),
                                    new RefPatcher.Val("1", null))),
                    "a patch that lands on nothing refuses rather than rewriting: "
                            + f[1].substring(0, Math.min(60, f[1].length())));
        }
    }

    /// `scan` reports every unqualified `ref`, and only those: `gml:pos`'s namespaced attributes
    /// and the fragment's own `id` are not references.
    @Test
    void testScanReadsTheUnqualifiedReferencesOnly() {
        var occ = new RefPatcher().scan(FRAGMENTS[3][1].getBytes(StandardCharsets.UTF_8));
        Check.equals(1, occ.size(), "one reference in the GML fragment");
        Check.equals("ParentSiteRef", occ.get(0).element(), "and it is the ParentSiteRef");
        Check.equals("x:SP:0", occ.get(0).ref(), "with its ref read unqualified");

        FragmentPeek fp = new FragmentPeek();
        Set<String> names = Set.of("ServiceJourneyPatternRef", "RepresentedByGroupRef",
                "ParentSiteRef");
        Check.equals("x:SP:0", fp.firstChildRef(FRAGMENTS[3][1]
                .getBytes(StandardCharsets.UTF_8), names).ref(),
                "FragmentPeek reads the same reference off the same bytes");
    }

    /// (3) EnumWhitespace's pre-filter reads the first and last character of one CHARACTERS event,
    /// so every padding position must still collapse.
    @Test
    void testEnumCollapseFires() throws Exception {
        JaxbPin.pin();
        for (String raw : new String[] {"bus", "bus ", " bus", " bus ", "bus\t", "bus\n",
                "b&#117;s "}) {
            String xml = "<Line xmlns=\"" + NS + "\" id=\"x\"><TransportMode>" + raw
                    + "</TransportMode></Line>";
            Unmarshaller u = FragmentCodec.packageContext().createUnmarshaller();
            Line l = (Line) decode(u, xml.getBytes(StandardCharsets.UTF_8), Line.class);
            Check.that(l.getTransportMode() != null,
                    "TransportMode binds for [" + raw + "] — a null here is the silent "
                            + "460-Route loss");
            Check.equals("bus", l.getTransportMode().value(), "collapsed value for [" + raw + "]");
        }
    }

    /// A long text node may arrive as several CHARACTERS events. What is asserted is that the
    /// accumulated text is whole, not the chunk count, which is a third-party implementation
    /// detail free to move.
    @Test
    void testLongTextArrivesWhole() throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20000; i++) b.append((char) ('a' + i % 26));
        String text = b.toString();
        String xml = "<Line xmlns=\"" + NS + "\" id=\"x\"><Name>" + text + "</Name></Line>";
        XMLStreamReader r = Stax.factory().createXMLStreamReader(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        StringBuilder acc = new StringBuilder();
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.CHARACTERS) acc.append(r.getText());
        }
        r.close();
        Check.equals(text, acc.toString(), "the text that was written arrives whole");
    }

    /// (4) Malformed input is refused. "Absent" and "unreadable" must not become one answer.
    @Test
    void testMalformedInputIsRefused() throws Exception {
        String[] bad = {
            "<Line xmlns=\"" + NS + "\" id=\"x\"><Name>n</Line>",                       // malformed
            "<!DOCTYPE Line [<!ENTITY e \"E\">]><Line xmlns=\"" + NS + "\"><Name>&e;</Name></Line>",
            "<Line xmlns=\"" + NS + "\" id=\"x\"><ns:Name>n</ns:Name></Line>",          // bad prefix
        };
        JaxbPin.pin();
        for (String x : bad) {
            boolean threw = false;
            try {
                Unmarshaller u = FragmentCodec.packageContext().createUnmarshaller();
                decode(u, x.getBytes(StandardCharsets.UTF_8), Line.class);
            } catch (Exception e) {
                threw = true;
            }
            Check.that(threw, "refused: " + x.substring(0, Math.min(45, x.length())));
        }
        // ... and RefPatcher.scan's contract on the same input: null, not an empty list.
        Check.isNull(new RefPatcher().scan(bad[0].getBytes(StandardCharsets.UTF_8)),
                "scan returns null (fall back to the decode), not an empty result");
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decode(Unmarshaller u, byte[] xml, Class<?> c) throws Exception {
        XMLStreamReader r = EnumWhitespace.wrap(
                Stax.factory().createXMLStreamReader(new ByteArrayInputStream(xml)));
        r.nextTag();
        jakarta.xml.bind.JAXBElement el = u.unmarshal(r, c);
        r.close();
        return el.getValue();
    }
}
