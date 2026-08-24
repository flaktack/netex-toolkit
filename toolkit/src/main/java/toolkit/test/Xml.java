package toolkit.test;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import toolkit.codec.FragmentCodec;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// The XML side of the test harness: serialise objects for readable
/// assertions, and compare XML canonically so an expected block can be indented to fit the test
/// file. [#canonical] normalises namespace prefixes away entirely (elements/attributes
/// compare by {namespace}localname), sorts attributes, and drops formatting-only whitespace —
/// significant text inside nodes is preserved, so a transform that mangles text is still caught.
public final class Xml {

    private static final FragmentCodec CODEC = new FragmentCodec();

    private Xml() {}

    /// One NeTEx object as an XML string; element name = class simple name.
    public static String toXml(Object obj) {
        return new String(CODEC.marshalFragment(obj), StandardCharsets.UTF_8);
    }

    /// Serialise a sequence and join with newlines; compare via [#assertXmlEqual].
    public static String toXmlAll(Iterable<?> objs) {
        StringBuilder sb = new StringBuilder();
        for (Object o : objs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(toXml(o).strip());
        }
        return sb.toString();
    }

    /// Canonical form; `xml` may hold several concatenated top-level objects.
    public static String canonical(String xml) {
        xml = xml.replaceFirst("^\\s*<\\?xml[^?]*\\?>", ""); // declaration can't live inside the wrapper
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(
                    ("<_harness>" + xml + "</_harness>").getBytes(StandardCharsets.UTF_8)));
            StringBuilder sb = new StringBuilder();
            write(doc.getDocumentElement(), sb);
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError("canonical(): unparseable XML: " + e, e);
        }
    }

    /// Equality that ignores formatting, attribute order and prefix choice. NOTE the argument order
    /// is `(actual, expected)`, the REVERSE of [Object, String)][Check#equals(Object,]'s.
    /// Elements with significant text must be written on ONE line in the expected block: only
    /// inter-element whitespace is ignored, text-internal whitespace is preserved verbatim.
    public static void assertXmlEqual(String actual, String expected, String msg) {
        String a = canonical(actual);
        String b = canonical(expected);
        if (!a.equals(b)) {
            throw new AssertionError((msg != null ? msg + "\n" : "")
                    + "XML mismatch\n--- actual (canonical):\n" + a + "\n--- expected (canonical):\n" + b);
        }
    }

    public static void assertXmlEqual(String actual, String expected) {
        assertXmlEqual(actual, expected, null);
    }

    private static void write(Node node, StringBuilder sb) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE -> {
                sb.append('<').append(qname(node.getNamespaceURI(), node.getLocalName()));
                NamedNodeMap attrs = node.getAttributes();
                List<Attr> sorted = new ArrayList<>();
                for (int i = 0; i < attrs.getLength(); i++) {
                    Attr a = (Attr) attrs.item(i);
                    if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) continue;
                    sorted.add(a);
                }
                sorted.sort(Comparator.comparing(a -> qname(a.getNamespaceURI(), a.getLocalName())));
                for (Attr a : sorted) {
                    sb.append(' ').append(qname(a.getNamespaceURI(), a.getLocalName()))
                            .append("=\"").append(escape(a.getValue())).append('"');
                }
                sb.append('>');
                NodeList children = node.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) write(children.item(i), sb);
                sb.append("</").append(qname(node.getNamespaceURI(), node.getLocalName())).append(">\n");
            }
            case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                String text = node.getNodeValue();
                if (!text.isBlank()) sb.append(escape(text)); // whitespace-only text is dropped
            }
            default -> { /* comments / PIs ignored, as with C14N-for-comparison */ }
        }
    }

    private static String qname(String ns, String local) {
        return (ns == null || ns.isEmpty()) ? local : "{" + ns + "}" + local;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
