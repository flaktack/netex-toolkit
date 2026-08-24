package inspect.nx;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A parsed XML fragment, in **document order**: element name, the attributes as they were written,
/// the text, the children in the order they occurred.
///
/// Attributes are never sorted and siblings are never reordered — every other instrument in the
/// stack canonicalises order away, so a schema-invalid sibling order is visible only here. Text is
/// kept verbatim, with no trim and no collapse, so a padded and an unpadded lexical stay different
/// values.
public final class XmlDoc {

    public static final class Node {
        public final String name;
        public final String ns;
        /// attribute (qualified-ish name, value) pairs, in the order the document wrote them.
        public final List<String[]> attrs = new ArrayList<>();
        public final List<Node> kids = new ArrayList<>();
        /// concatenated character data directly inside this element (verbatim).
        public String text = "";
        Node(String ns, String name) { this.ns = ns; this.name = name; }
        public boolean isLeaf() { return kids.isEmpty(); }
    }

    public final Node root;
    public final byte[] bytes;

    private XmlDoc(Node root, byte[] bytes) { this.root = root; this.bytes = bytes; }

    // ------------------------------------------------------------------ parse

    public static XmlDoc parse(byte[] xml) throws Exception {
        XMLInputFactory xif = XMLInputFactory.newInstance();
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xif.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        xif.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        XMLStreamReader r = xif.createXMLStreamReader(new ByteArrayInputStream(xml));
        Node root = null;
        Deque<Node> stack = new ArrayDeque<>();
        StringBuilder chars = new StringBuilder();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (!stack.isEmpty()) { stack.peek().text += chars; }
                chars.setLength(0);
                Node n = new Node(r.getNamespaceURI(), r.getLocalName());
                for (int i = 0; i < r.getAttributeCount(); i++) {
                    String p = r.getAttributePrefix(i);
                    String an = (p == null || p.isEmpty()) ? r.getAttributeLocalName(i)
                            : p + ":" + r.getAttributeLocalName(i);
                    n.attrs.add(new String[] {an, r.getAttributeValue(i)});
                }
                if (root == null) root = n; else stack.peek().kids.add(n);
                stack.push(n);
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA
                    || ev == XMLStreamConstants.SPACE) {
                chars.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                Node n = stack.pop();
                n.text += chars;
                chars.setLength(0);
            }
        }
        r.close();
        if (root == null) throw new IllegalArgumentException("no root element");
        return new XmlDoc(root, xml);
    }

    // ------------------------------------------------------------------ flatten

    /// Every element and attribute reachable from the root, as `path -> value`, in document
    /// order. Repeated siblings are indexed (`capacities/PassengerCapacity[1]`) so the map is
    /// total. An element with children contributes only its attributes and, if it has any, its own
    /// character data; a leaf contributes its text verbatim.
    public Map<String, String> flatten() {
        Map<String, String> out = new LinkedHashMap<>();
        flatten(root, "", out);
        return out;
    }

    private static void flatten(Node n, String prefix, Map<String, String> out) {
        String p = prefix.isEmpty() ? n.name : prefix;
        for (String[] a : n.attrs) out.put(p + "/@" + a[0], a[1]);
        if (n.isLeaf()) {
            out.put(p, n.text);
        } else {
            if (!n.text.isBlank()) out.put(p + "/text()", n.text);
            Map<String, Integer> seen = new LinkedHashMap<>();
            Map<String, Integer> total = new LinkedHashMap<>();
            for (Node k : n.kids) total.merge(k.name, 1, Integer::sum);
            for (Node k : n.kids) {
                int i = seen.merge(k.name, 1, Integer::sum) - 1;
                String kp = total.get(k.name) > 1 ? p + "/" + k.name + "[" + i + "]" : p + "/" + k.name;
                flatten(k, kp, out);
            }
        }
    }

    /// `containerPath -> [child element names in document order]`, for order comparison.
    public Map<String, List<String>> childOrder() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        childOrder(root, "", out);
        return out;
    }

    private static void childOrder(Node n, String prefix, Map<String, List<String>> out) {
        String p = prefix.isEmpty() ? n.name : prefix;
        if (!n.kids.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Node k : n.kids) names.add(k.name);
            out.put(p, names);
            Map<String, Integer> seen = new LinkedHashMap<>();
            Map<String, Integer> total = new LinkedHashMap<>();
            for (Node k : n.kids) total.merge(k.name, 1, Integer::sum);
            for (Node k : n.kids) {
                int i = seen.merge(k.name, 1, Integer::sum) - 1;
                String kp = total.get(k.name) > 1 ? p + "/" + k.name + "[" + i + "]" : p + "/" + k.name;
                childOrder(k, kp, out);
            }
        }
    }

    // ------------------------------------------------------------------ render

    /// Reindented, attributes and children still in document order.
    public String pretty() {
        StringBuilder sb = new StringBuilder();
        pretty(root, 0, sb);
        return sb.toString();
    }

    private static void pretty(Node n, int depth, StringBuilder sb) {
        String pad = "  ".repeat(depth);
        sb.append(pad).append('<').append(n.name);
        for (String[] a : n.attrs) {
            sb.append(' ').append(a[0]).append("=\"").append(esc(a[1])).append('"');
        }
        if (n.kids.isEmpty()) {
            if (n.text.isEmpty()) { sb.append("/>\n"); return; }
            sb.append('>').append(esc(n.text)).append("</").append(n.name).append(">\n");
            return;
        }
        sb.append(">\n");
        for (Node k : n.kids) pretty(k, depth + 1, sb);
        sb.append(pad).append("</").append(n.name).append(">\n");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public String verbatim() { return new String(bytes, StandardCharsets.UTF_8); }
}
