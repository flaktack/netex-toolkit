package toolkit.codec;

// Agreement between the toolkit's two StAX implementations: Aalto on the decode path, Woodstox on
// the patch path.
//
// Four things are pinned: one decode reader across FragmentCodec, FragmentPeek and
// RefPatcher.scan; the same bean out of either parser, on the fragment shapes the toolkit stores;
// the enum collapse firing under both; and the same inputs (malformed, an internal DTD subset with
// SUPPORT_DTD=false) refused by both.

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import noi.netex.model.Line;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPlace;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.test.Check;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TestStaxAgreement {

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

    private static XMLInputFactory woodstox() {
        XMLInputFactory f = new com.ctc.wstx.stax.WstxInputFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }

    private static Class<?> factoryClass(Object owner, String field) throws Exception {
        Field f = owner.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(owner).getClass();
    }

    /// (1) One reader for every consumer of a stored fragment. The invariant is about the factory
    /// objects the three classes hold, which is why it is read reflectively off private fields.
    @Test
    void testTheDecodePathUsesOneStaxImplementation() throws Exception {
        Class<?> chosen = Stax.decodeFactory().getClass();
        Check.equals(com.fasterxml.aalto.stax.InputFactoryImpl.class, chosen,
                "Stax.decodeFactory is Aalto — measured 1.18x in situ against Woodstox and "
                        + "kept it; if this is deliberately changed, change Stax's javadoc with it");
        Check.equals(chosen, factoryClass(new FragmentCodec(), "xif"),
                "FragmentCodec.decode reads with the chosen decode factory");
        Check.equals(chosen, factoryClass(new FragmentPeek(), "xif"),
                "FragmentPeek reads with the SAME factory FragmentCodec.decode uses");
        Check.equals(chosen, factoryClass(new RefPatcher(), "scanFactory"),
                "RefPatcher.scan reads with the SAME factory FragmentCodec.decode uses");
    }

    /// ... and the offset-reporting one is still Woodstox: `RefPatcher.patch` needs `LocationInfo`.
    @Test
    void testThePatchPathStillHasItsOffsetReader() throws Exception {
        Check.equals(com.ctc.wstx.stax.WstxInputFactory.class,
                factoryClass(new RefPatcher(), "wif"),
                "RefPatcher.patch keeps the Woodstox factory: byte-splicing needs per-event "
                        + "input character offsets, which is the mechanism behind in-place patching");
        RefPatcher rp = new RefPatcher();
        byte[] src = ("<ServiceJourney xmlns=\"" + NS + "\" id=\"x:SJ:9\" version=\"1\">"
                + "<ServiceJourneyPatternRef ref=\"x:SJP:9\"/></ServiceJourney>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] patched = rp.patch(src, java.util.Map.of(
                new RefPatcher.Key("x:SJP:9", null, null), new RefPatcher.Val("4", null)));
        Check.equals("<ServiceJourney xmlns=\"" + NS + "\" id=\"x:SJ:9\" version=\"1\">"
                        + "<ServiceJourneyPatternRef ref=\"x:SJP:9\" version=\"4\"/></ServiceJourney>",
                new String(patched, StandardCharsets.UTF_8),
                "the offset reader still lands the splice on the right start tag");
    }

    /// (2) The two parsers produce the same bean, compared by re-marshal, which covers every bound
    /// field at every depth.
    @Test
    void testBothParsersDecodeToTheSameBean() throws Exception {
        JaxbPin.pin();
        Unmarshaller ua = FragmentCodec.packageContext().createUnmarshaller();
        Unmarshaller uw = FragmentCodec.packageContext().createUnmarshaller();
        Marshaller m = FragmentCodec.packageContext().createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
        m.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
        m.setListener(SchemaDefaults.LISTENER);
        XMLInputFactory fa = Stax.decodeFactory(), fw = woodstox();
        for (String[] f : FRAGMENTS) {
            Class<?> c = Class.forName(f[0]);
            byte[] xml = f[1].getBytes(StandardCharsets.UTF_8);
            String a = new String(marshal(m, decode(fa, ua, xml, c)), StandardCharsets.UTF_8);
            String w = new String(marshal(m, decode(fw, uw, xml, c)), StandardCharsets.UTF_8);
            Check.equals(a, w, "Aalto and Woodstox re-marshal identically: " + c.getSimpleName()
                    + " " + f[1].substring(0, Math.min(60, f[1].length())));
        }
    }

    /// ... and so do the two byte-readers on top of the decoder's reader.
    @Test
    void testBothParsersScanAndPeekTheSame() throws Exception {
        RefPatcher rp = new RefPatcher();
        Field sf = RefPatcher.class.getDeclaredField("scanFactory");
        sf.setAccessible(true);
        FragmentPeek fp = new FragmentPeek();
        Field pf = FragmentPeek.class.getDeclaredField("xif");
        pf.setAccessible(true);
        Set<String> names = Set.of("ServiceJourneyPatternRef", "RepresentedByGroupRef",
                "ParentSiteRef");
        XMLInputFactory fw = woodstox();
        for (String[] f : FRAGMENTS) {
            byte[] xml = f[1].getBytes(StandardCharsets.UTF_8);
            sf.set(rp, Stax.decodeFactory());
            List<RefPatcher.Occurrence> a = rp.scan(xml);
            sf.set(rp, fw);
            List<RefPatcher.Occurrence> w = rp.scan(xml);
            Check.equals(a, w, "RefPatcher.scan agrees under both parsers");

            pf.set(fp, Stax.decodeFactory());
            FragmentPeek.ChildRef pa = fp.firstChildRef(xml, names);
            pf.set(fp, fw);
            FragmentPeek.ChildRef pw = fp.firstChildRef(xml, names);
            Check.equals(pa, pw, "FragmentPeek agrees under both parsers");
        }
    }

    /// (3) EnumWhitespace's pre-filter reads the first and last character of one CHARACTERS event,
    /// so a parser that chunks text differently changes what it sees. Every padding position must
    /// still collapse under either parser.
    @Test
    void testEnumCollapseFiresUnderBothParsers() throws Exception {
        JaxbPin.pin();
        for (String raw : new String[] {"bus", "bus ", " bus", " bus ", "bus\t", "bus\n",
                "b&#117;s "}) {
            String xml = "<Line xmlns=\"" + NS + "\" id=\"x\"><TransportMode>" + raw
                    + "</TransportMode></Line>";
            for (Object[] arm : new Object[][] {{"aalto", Stax.decodeFactory()},
                                                {"woodstox", woodstox()}}) {
                Unmarshaller u = FragmentCodec.packageContext().createUnmarshaller();
                Line l = (Line) decode((XMLInputFactory) arm[1], u,
                        xml.getBytes(StandardCharsets.UTF_8), Line.class);
                Check.that(l.getTransportMode() != null,
                        "TransportMode binds for [" + raw + "] under " + arm[0]
                                + " — a null here is the silent 460-Route loss");
                Check.equals("bus", l.getTransportMode().value(),
                        "collapsed value for [" + raw + "] under " + arm[0]);
            }
        }
    }

    /// Woodstox splits a long text node, Aalto does not. What is asserted is that the accumulated
    /// text is identical either way, not the chunk size, which is a third-party implementation
    /// detail free to move.
    @Test
    void testTextIsWholeEvenWhenChunkedDifferently() throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20000; i++) b.append((char) ('a' + i % 26));
        String text = b.toString();
        String xml = "<Line xmlns=\"" + NS + "\" id=\"x\"><Name>" + text + "</Name></Line>";
        int[] chunks = new int[2];
        String[] joined = new String[2];
        XMLInputFactory[] arms = {Stax.decodeFactory(), woodstox()};
        for (int i = 0; i < 2; i++) {
            XMLStreamReader r = arms[i].createXMLStreamReader(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            StringBuilder acc = new StringBuilder();
            while (r.hasNext()) {
                if (r.next() == javax.xml.stream.XMLStreamConstants.CHARACTERS) {
                    chunks[i]++;
                    acc.append(r.getText());
                }
            }
            r.close();
            joined[i] = acc.toString();
        }
        Check.equals(joined[0], joined[1],
                "the same text arrives either way (aalto " + chunks[0] + " event(s), woodstox "
                        + chunks[1] + ")");
        Check.equals(text, joined[0], "and it is the text that was written");
    }

    /// (4) Both refuse the same inputs. "Absent" and "unreadable" must not become one answer.
    @Test
    void testBothParsersRefuseTheSameInputs() throws Exception {
        String[] bad = {
            "<Line xmlns=\"" + NS + "\" id=\"x\"><Name>n</Line>",                       // malformed
            "<!DOCTYPE Line [<!ENTITY e \"E\">]><Line xmlns=\"" + NS + "\"><Name>&e;</Name></Line>",
            "<Line xmlns=\"" + NS + "\" id=\"x\"><ns:Name>n</ns:Name></Line>",          // bad prefix
        };
        JaxbPin.pin();
        for (String x : bad) {
            for (Object[] arm : new Object[][] {{"aalto", Stax.decodeFactory()},
                                                {"woodstox", woodstox()}}) {
                boolean threw = false;
                try {
                    Unmarshaller u = FragmentCodec.packageContext().createUnmarshaller();
                    decode((XMLInputFactory) arm[1], u, x.getBytes(StandardCharsets.UTF_8),
                            Line.class);
                } catch (Exception e) {
                    threw = true;
                }
                Check.that(threw, arm[0] + " refuses: " + x.substring(0, Math.min(45, x.length())));
            }
        }
        // ... and RefPatcher.scan's contract on the same input: null, not an empty list.
        RefPatcher rp = new RefPatcher();
        Check.isNull(rp.scan(bad[0].getBytes(StandardCharsets.UTF_8)),
                "scan returns null (fall back to the decode), not an empty result");
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decode(XMLInputFactory xif, Unmarshaller u, byte[] xml, Class<?> c)
            throws Exception {
        XMLStreamReader r = EnumWhitespace.wrap(
                xif.createXMLStreamReader(new ByteArrayInputStream(xml)));
        r.nextTag();
        JAXBElement el = u.unmarshal(r, c);
        r.close();
        return el.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static byte[] marshal(Marshaller m, Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
        m.marshal(new JAXBElement(new QName(ModelRegistry.NETEX_NS, o.getClass().getSimpleName()),
                o.getClass(), o), bos);
        return bos.toByteArray();
    }
}
