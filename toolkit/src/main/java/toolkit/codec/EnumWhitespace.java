package toolkit.codec;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlEnumValue;
import toolkit.model.ModelRegistry;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The whitespace facet JAXB forgets on enums (PROV-CAP: enum-collapse).
///
/// Every NeTEx enumeration is a restriction of `xs:NMTOKEN`, whose whitespace facet is `collapse`:
/// `<TransportMode>bus </TransportMode>` and `bus` are the same value. XJC binds such a type to an
/// `@XmlEnum` with the default base `String.class`, and the RI parses an enum by handing the raw
/// lexical to the `String` transducer — facet `preserve` — and looking the result up in a map keyed
/// on the exact `@XmlEnumValue` text. A padded value misses the map and the RI returns `null`
/// silently: no ValidationEvent, no exception. The element ceases to exist on the bean, and on the
/// next marshal it ceases to exist in the XML. (jakarta's `CollapsedStringAdapter` is already
/// installed on the plain NMTOKEN *string* fields; it is not reachable from `@XmlEnum`.)
///
/// Downstream, a Line whose `transportMode` binds to null is exported with no mode, and a consumer
/// that rejects an unsupported mode discards the whole Route.
///
/// The substitution fires only when the collapsed text differs from the raw text and the collapsed
/// text is a declared lexical value of an enum the model binds to an element of this local name.
/// The name-keyed table stays sound while that second condition holds, even though some element
/// names bind to an enum in one class and to a String elsewhere.
///
/// Not applied to enum-valued attributes, which have the same hole and the same silent null.
public final class EnumWhitespace {

    /// Element local name -> the declared lexical values of every enum bound to that name.
    private static volatile Map<String, Set<String>> values;

    private EnumWhitespace() {}

    /// The name table, built once per JVM by reflection over the model package.
    public static Map<String, Set<String>> table() {
        Map<String, Set<String>> v = values;
        if (v == null) {
            synchronized (EnumWhitespace.class) {
                v = values;
                if (v == null) {
                    v = build();
                    values = v;
                }
            }
        }
        return v;
    }

    private static Map<String, Set<String>> build() {
        Map<String, Set<String>> out = new HashMap<>();
        for (Class<?> c : ModelRegistry.allModelClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = valueType(f);
                if (t == null || !t.isEnum()) continue;
                Set<String> set = out.computeIfAbsent(elementName(f), k -> new HashSet<>());
                for (Object constant : t.getEnumConstants()) set.add(lexical(t, (Enum<?>) constant));
            }
        }
        return out;
    }

    /// The XML element name a field binds to, mirroring JAXB's own `##default` rule.
    private static String elementName(Field f) {
        XmlElement e = f.getAnnotation(XmlElement.class);
        if (e != null) return "##default".equals(e.name()) ? f.getName() : e.name();
        XmlElementRef r = f.getAnnotation(XmlElementRef.class);
        if (r != null) return "##default".equals(r.name()) ? f.getName() : r.name();
        return f.getName();
    }

    /// The bound value type behind `List<..>` and `JAXBElement<..>` wrappers.
    private static Class<?> valueType(Field f) {
        Class<?> t = f.getType();
        Type g = f.getGenericType();
        if (List.class.isAssignableFrom(t) || t == JAXBElement.class) {
            return g instanceof ParameterizedType p ? erase(p.getActualTypeArguments()[0]) : null;
        }
        return t;
    }

    private static Class<?> erase(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof WildcardType w) return erase(w.getUpperBounds()[0]);
        if (t instanceof ParameterizedType p) {
            return p.getRawType() == JAXBElement.class
                    ? erase(p.getActualTypeArguments()[0]) : erase(p.getRawType());
        }
        return null;
    }

    /// The constant's lexical form: `@XmlEnumValue` when present, else the Java name.
    private static String lexical(Class<?> enumType, Enum<?> constant) {
        try {
            XmlEnumValue v = enumType.getField(constant.name()).getAnnotation(XmlEnumValue.class);
            return v != null ? v.value() : constant.name();
        } catch (NoSuchFieldException e) {
            return constant.name();
        }
    }

    /// `xs:collapse`: trim, then squeeze every internal whitespace run to one space.
    public static String collapse(String s) {
        int n = s.length();
        int i = 0;
        while (i < n && isSpace(s.charAt(i))) i++;
        int j = n;
        while (j > i && isSpace(s.charAt(j - 1))) j--;
        StringBuilder sb = new StringBuilder(j - i);
        boolean pending = false;
        for (int k = i; k < j; k++) {
            char ch = s.charAt(k);
            if (isSpace(ch)) {
                pending = true;
            } else {
                if (pending) sb.append(' ');
                pending = false;
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /// Wrap a reader so the unmarshaller sees collapsed text for enum-valued elements.
    ///
    /// The wrapper reports a different value; it never changes which events occur, so a
    /// `CopyingCapture` underneath still tees the raw source events into the stored fragment. The
    /// stored fragment therefore keeps `"bus "` while the bound object carries `"bus"`.
    public static Reader wrap(XMLStreamReader r) {
        return new Reader(r);
    }

    /// The [EnumWhitespace#wrap] delegate; public only so callers can [#reset].
    public static final class Reader extends StreamReaderDelegate {
        private final Map<String, Set<String>> table = table();
        /// Local name of the element the cursor is inside, or null on any other event.
        private String elementName;
        /// The collapsed text to report for the CURRENT text event, or null to report the raw one.
        private String replacement;
        private char[] chars;

        Reader(XMLStreamReader r) {
            super(r);
            observe(r.getEventType());
        }

        /// Forget the element context. Needed when the caller advances the underlying reader behind
        /// this wrapper's back, which the loader does: its outer cursor loop positions the fragment
        /// root itself and only then hands the wrapper to the unmarshaller.
        public void reset() {
            elementName = null;
            replacement = null;
        }

        @Override
        public int next() throws XMLStreamException {
            int ev = super.next();
            observe(ev);
            return ev;
        }

        @Override
        public int nextTag() throws XMLStreamException {
            int ev = super.nextTag();
            observe(ev);
            return ev;
        }

        private void observe(int ev) {
            replacement = null;
            switch (ev) {
                case XMLStreamConstants.START_ELEMENT -> elementName = getLocalName();
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                        XMLStreamConstants.SPACE -> maybeCollapse();
                default -> elementName = null;
            }
        }

        /// Runs for every text event of every fragment ever decoded, so the steady state must not
        /// hash, allocate or copy.
        ///
        /// The pre-filter — does the text start or end with whitespace — is read straight off the
        /// parser's buffer. It holds only while every enum lexical in the model is an NMTOKEN
        /// containing no space: a text whose first and last characters are non-space then differs
        /// from its collapse only by an internal run, which puts a space in the collapsed form and
        /// matches no constant.
        private void maybeCollapse() {
            if (elementName == null) return;
            char[] buf = super.getTextCharacters();
            int start = super.getTextStart();
            int len = super.getTextLength();
            if (len == 0 || (!isSpace(buf[start]) && !isSpace(buf[start + len - 1]))) return;
            Set<String> constants = table.get(elementName);
            if (constants == null) return;
            String raw = new String(buf, start, len);
            String collapsed = collapse(raw);
            if (!collapsed.equals(raw) && constants.contains(collapsed)) {
                replacement = collapsed;
                chars = null;
            }
        }

        private char[] chars() {
            char[] c = chars;
            if (c == null) {
                c = replacement.toCharArray();
                chars = c;
            }
            return c;
        }

        @Override
        public String getText() {
            return replacement != null ? replacement : super.getText();
        }

        @Override
        public char[] getTextCharacters() {
            return replacement != null ? chars() : super.getTextCharacters();
        }

        @Override
        public int getTextStart() {
            return replacement != null ? 0 : super.getTextStart();
        }

        @Override
        public int getTextLength() {
            return replacement != null ? replacement.length() : super.getTextLength();
        }

        @Override
        public int getTextCharacters(int sourceStart, char[] target, int targetStart, int length)
                throws XMLStreamException {
            if (replacement == null) {
                return super.getTextCharacters(sourceStart, target, targetStart, length);
            }
            int n = Math.min(length, replacement.length() - sourceStart);
            if (n < 0) n = 0;
            replacement.getChars(sourceStart, sourceStart + n, target, targetStart);
            return n;
        }

        @Override
        public boolean isWhiteSpace() {
            return replacement == null && super.isWhiteSpace();
        }

        /// Funnelled through [#next()] so the collapsed text is what accumulates; the inherited
        /// delegate would advance the wrapped reader and bypass this wrapper entirely (and, in the
        /// loader, `CopyingCapture`'s fragment tee with it).
        @Override
        public String getElementText() throws XMLStreamException {
            if (getEventType() != XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("getElementText: not on START_ELEMENT");
            }
            StringBuilder sb = new StringBuilder();
            int ev = next();
            while (ev != XMLStreamConstants.END_ELEMENT) {
                switch (ev) {
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA,
                            XMLStreamConstants.SPACE, XMLStreamConstants.ENTITY_REFERENCE ->
                            sb.append(getText());
                    case XMLStreamConstants.PROCESSING_INSTRUCTION, XMLStreamConstants.COMMENT -> { }
                    case XMLStreamConstants.END_DOCUMENT ->
                            throw new XMLStreamException("getElementText: unexpected end of document");
                    default -> throw new XMLStreamException("getElementText: element has mixed content");
                }
                ev = next();
            }
            return sb.toString();
        }
    }
}
