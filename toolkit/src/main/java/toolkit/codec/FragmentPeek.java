package toolkit.codec;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.Set;

/// Read ONE named value out of a stored NeTEx fragment without unmarshalling it.
///
/// Reading the attribute off the fragment is a StAX scan that STOPS at the element, so the cost is
/// bounded by where the value sits, not by the size of the object.
///
/// **Same reader as the decoder.** Aalto is the StAX implementation
/// `FragmentCodec.decode` feeds JAXB, so attribute-value normalisation, entity resolution and
/// namespace handling are the decoder's, not a second opinion about them.
///
/// **The namespace rule**: a NeTEx `ref` attribute is UNQUALIFIED, and
/// `getAttributeValue(null, name)` would match any namespace — reading, say, `gml:id` where `id`
/// was meant. Only no-namespace attributes count here.
public final class FragmentPeek {

    private final XMLInputFactory xif = Stax.decodeFactory();

    public FragmentPeek() {
    }

    /// A direct child of the fragment root: which element it was, and its `ref` attribute.
    public record ChildRef(String element, String ref) {}

    /// The fragment root's own `id` attribute and its first matching direct child, read in ONE
    /// scan — `id` is `null` when the root carries none (or there is no root at all), and
    /// `child` is `null` with exactly the meaning [#firstChildRef] gives it.
    public record RootChild(String id, ChildRef child) {}

    /// The FIRST direct child of the fragment root whose local name is in `elementNames`,
    /// with its unqualified `ref` attribute (`null` when the element carries none) —
    /// or `null` when the fragment has no such child, which is the reader's way of saying
    /// "the field JAXB would fill from this element is empty".
    ///
    /// The scan stops at the match. A well-formed NeTEx object has at most one child per field; a
    /// schema-invalid fragment carrying two would make JAXB keep the LAST and this the first.
    ///
    /// A fragment that does not parse throws, exactly as `FragmentCodec.decode` would have:
    /// "absent" and "unreadable" must not become the same answer.
    ///
    /// @return `null` if the fragment has no such direct child
    public ChildRef firstChildRef(byte[] xml, Set<String> elementNames) {
        return rootIdAndFirstChildRef(xml, elementNames).child();
    }

    /// [#firstChildRef], plus the root element's own unqualified `id` attribute, for the
    /// price of the same single scan.
    ///
    /// The root's attributes are on the very first START_ELEMENT, so `id` costs nothing the child
    /// scan was not already paying.
    ///
    /// **The namespace rule applies to `id` exactly as it does to `ref`**: NeTEx fragments carry
    /// `gml:id` on geometry children, and `Site`-family roots can carry both. Only a no-namespace
    /// `id` on the ROOT start tag is returned, which is the one JAXB maps to the model's `id` field.
    ///
    /// Never `null`: a fragment with no root element at all yields
    /// `RootChild(null, null)`, i.e. the same two "absent" answers the callers already handle.
    /// A fragment that does not parse throws, as above.
    public RootChild rootIdAndFirstChildRef(byte[] xml, Set<String> elementNames) {
        String id = null;
        try {
            XMLStreamReader r = xif.createXMLStreamReader(new ByteArrayInputStream(xml));
            int depth = -1;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    continue;
                }
                if (ev != XMLStreamConstants.START_ELEMENT) continue;
                depth++;
                if (depth == 0) {
                    id = unqualifiedAttribute(r, "id");
                    continue;
                }
                if (depth != 1 || !elementNames.contains(r.getLocalName())) continue;
                ChildRef out = new ChildRef(r.getLocalName(), unqualifiedAttribute(r, "ref"));
                r.close();
                return new RootChild(id, out);
            }
            r.close();
        } catch (Exception e) {
            throw new IllegalStateException("fragment scan failed", e);
        }
        return new RootChild(id, null);
    }

    /// The named attribute of the element the reader is on, ONLY in no namespace.
    private static String unqualifiedAttribute(XMLStreamReader r, String name) {
        for (int i = 0, n = r.getAttributeCount(); i < n; i++) {
            String ns = r.getAttributeNamespace(i);
            if ((ns == null || ns.isEmpty()) && name.equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    /// Does the fragment's ROOT start tag carry a `*:type` attribute — i.e. possibly an
    /// `xsi:type`?
    ///
    /// A stored row's class is its full key's class index, and
    /// `AbstractLmdbStore.loadObjectByFullKey` unmarshals with exactly that class as the declared
    /// type. The one thing that can break the equivalence is `xsi:type`, which JAXB honours and
    /// which can make the returned object a DIFFERENT class from the declared one. `true` means
    /// "cannot decide from the key, decode it".
    ///
    /// **Deliberately over-eager.** It matches any `prefix:type` attribute name in the root start
    /// tag, whatever the prefix is bound to, because the prefix for the XSI namespace is not fixed
    /// and resolving it would mean parsing. A false positive costs one decode; a false negative
    /// would be a wrong answer.
    ///
    /// Any doubt answers `true`: no root tag, a start tag that never closes, an empty fragment.
    public static boolean rootHasTypeAttribute(byte[] xml) {
        int i = 0;
        while (i < xml.length && xml[i] != '<') i++;
        // skip an XML declaration / comment / PI ahead of the root element, if any
        while (i + 1 < xml.length && (xml[i + 1] == '?' || xml[i + 1] == '!')) {
            while (i < xml.length && xml[i] != '>') i++;
            while (i < xml.length && xml[i] != '<') i++;
        }
        if (i >= xml.length) return true;
        boolean inQuote = false;
        byte q = 0;
        for (int p = i + 1; p < xml.length; p++) {
            byte c = xml[p];
            if (inQuote) {
                if (c == q) inQuote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                q = c;
            } else if (c == '>') {
                return false; // end of the root start tag, no *:type seen
            } else if (c == ':' && p + 5 < xml.length
                    && xml[p + 1] == 't' && xml[p + 2] == 'y' && xml[p + 3] == 'p'
                    && xml[p + 4] == 'e'
                    && (xml[p + 5] == '=' || xml[p + 5] == ' ' || xml[p + 5] == '\t'
                        || xml[p + 5] == '\n' || xml[p + 5] == '\r')) {
                return true;
            }
        }
        return true; // unterminated start tag: refuse
    }
}
