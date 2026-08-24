package noi.netex.text;

import jakarta.xml.bind.JAXBElement;
import java.io.Serializable;
import java.util.List;
import noi.netex.model.MultilingualString;
import noi.netex.model.TextType;

/// THE `MultilingualString` ACCESSOR SEAM.
///
/// It exists because 2.0 redefines `MultilingualString` from simple content to a MIXED complex
/// type, so `getValue()`/`setValue()` cease to exist.
public final class Mls {

    private Mls() {}

    private static String part(Serializable o) {
        if (o instanceof String s) return s;
        if (o instanceof JAXBElement<?> e && e.getValue() instanceof TextType t) return t.getValue();
        return null;
    }

    public static String text(MultilingualString m) {
        if (m == null) return null;
        List<Serializable> content = m.getContent();
        if (content.isEmpty()) return null;
        if (content.size() == 1) {
            String only = part(content.get(0));
            return only == null ? "" : only;
        }
        StringBuilder sb = new StringBuilder();
        for (Serializable o : content) {
            String p = part(o);
            if (p != null) sb.append(p);
        }
        return sb.toString();
    }

    public static String textOrEmpty(MultilingualString m) {
        String v = text(m);
        return v == null ? "" : v;
    }

    public static boolean hasText(MultilingualString m) {
        String v = text(m);
        return v != null && !v.isEmpty();
    }

    public static void setText(MultilingualString m, String s) {
        List<Serializable> content = m.getContent();
        content.clear();
        if (s != null) content.add(s);
    }

    public static MultilingualString of(String s) {
        MultilingualString m = new MultilingualString();
        if (s != null) m.getContent().add(s);
        return m;
    }
}
