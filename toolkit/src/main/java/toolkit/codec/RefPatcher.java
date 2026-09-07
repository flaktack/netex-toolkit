package toolkit.codec;

import com.ctc.wstx.stax.WstxInputFactory;
import org.codehaus.stax2.LocationInfo;
import org.codehaus.stax2.XMLStreamReader2;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import toolkit.util.Conf;
import toolkit.util.Log;

/// In-place reference-attribute patcher for a stored NeTEx fragment
/// (PROV-CAP: no-remarshal-resolve).
///
/// Resolve repairs a reference by setting `version` and/or `nameOfRefClass` and then persisting
/// the object. Persisting through a full re-marshal, to change one start-tag attribute, is where
/// stored-fragment loss comes from. [#patch] instead rewrites exactly the affected start tags and
/// passes every other character through verbatim: the parser only locates element boundaries, via
/// Woodstox [LocationInfo] character offsets, and nothing outside a patched start tag is rewritten,
/// so text, CDATA, comments, entity references and attribute order survive byte for byte.
///
/// Matching is by the triple (`@ref`, `@version`, `@nameOfRefClass`) as it appears in the fragment,
/// on any element carrying an unqualified `ref` attribute, in any namespace and under any element
/// name — NeTEx spreads `*Ref` elements over several namespaces and the resolve code never looks at
/// the element name either. Every element matching a patched triple is patched, as the bean-side
/// repair does.
///
/// [#patch] returns `null` — meaning "fall back to the re-marshal" — when the fragment is not
/// well-formed, contains a CR (whose line-ending normalisation moves the parser's offsets off the
/// raw text), has a located start tag that does not scan as one, or when any requested patch failed
/// to land on at least one element.
public final class RefPatcher {

    /// A reference as it appears in the stored fragment. `null` = attribute absent.
    public record Key(String ref, String version, String nameOfRefClass) {}

    /// What resolve() decided the reference's attributes should become. `null` = remove.
    public record Val(String version, String nameOfRefClass) {}

    private static final String A_REF = "ref";
    private static final String A_VERSION = "version";
    private static final String A_NAME = "nameOfRefClass";

    /// Woodstox: the only StAX implementation here that reports per-event input offsets.
    private final WstxInputFactory wif = Stax.offsetFactory();

    /// Aalto — `FragmentCodec.decode`'s reader, used by [#scan] (no offsets needed).
    private final XMLInputFactory scanFactory = Stax.decodeFactory();

    /// Elements that matched a patch but are not named `*Ref` — expected to stay 0.
    private long oddlyNamed;

    /// Why patches were refused: {CR, not-well-formed, bad offsets, unlanded, tag scan}.
    private final long[] refusals = new long[5];

    public long[] refusals() {
        return refusals;
    }

    public RefPatcher() {
    }

    private static final boolean DEBUG = Conf.on(Conf.REF_PATCH_DEBUG);
    private static int debugged;

    private static synchronized void debug(String s, Map<Key, Val> patches, Set<Key> landed) {
        if (debugged++ > 3) return;
        Log.warn("[refpatch] UNLANDED wanted=%s landed=%s", patches.keySet(), landed);
        Log.warn("[refpatch] fragment head: %s", s.substring(0, Math.min(1500, s.length())));
    }

    public long oddlyNamedMatches() {
        return oddlyNamed;
    }

    /// One reference element as the fragment carries it: the three attributes [#patch] keys on,
    /// plus where it sits (`element` local name, `depth` 0 = fragment root).
    public record Occurrence(String ref, String version, String nameOfRefClass, String element,
                             int depth) {}

    /// Read every reference element of `xml`, in document order, keying on the same attributes
    /// [#patch] does ([#triple]).
    ///
    /// Values are raw attribute strings, so a caller comparing against bean fields must apply the
    /// model's adapters — only `nameOfRefClass` is adapted; `ref` and `version` bind verbatim.
    ///
    /// The element set is a superset of what JAXB binds: anything with an unqualified `ref`
    /// attribute is reported, pruned packages and the fragment root included. It may over-report
    /// and must never miss a bound reference.
    ///
    /// @return one [Occurrence] per reference element (null attribute = absent), or
    ///     `null` if the fragment is not well-formed
    public List<Occurrence> scan(byte[] xml) {
        List<Occurrence> out = new ArrayList<>();
        try {
            XMLStreamReader r = scanFactory.createXMLStreamReader(new ByteArrayInputStream(xml));
            int depth = -1;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    continue;
                }
                if (ev != XMLStreamConstants.START_ELEMENT) continue;
                depth++;
                String[] t = triple(r);
                if (t != null) out.add(new Occurrence(t[0], t[1], t[2], r.getLocalName(), depth));
            }
            r.close();
        } catch (Exception e) {
            return null; // not well-formed: the caller must fall back to the decode
        }
        return out;
    }

    /// The reference triple of the START_ELEMENT the reader is positioned on, or `null` if the
    /// element carries no unqualified `ref` attribute. (Slot 3 is left for the caller's element
    /// name; [#patch] does not use it.)
    ///
    /// Only attributes in no namespace count: `getAttributeValue(null, name)` would match any
    /// namespace and would read, say, `gml:id` where `id` was meant. JAXB binds these attributes
    /// unqualified, so an unqualified-only scan is what mirrors it.
    private static String[] triple(XMLStreamReader r) {
        String ref = null;
        String version = null;
        String name = null;
        boolean hasRef = false;
        for (int i = 0, n = r.getAttributeCount(); i < n; i++) {
            String ns = r.getAttributeNamespace(i);
            if (ns != null && !ns.isEmpty()) continue;
            switch (r.getAttributeLocalName(i)) {
                case A_REF -> {
                    ref = r.getAttributeValue(i);
                    hasRef = true;
                }
                case A_VERSION -> version = r.getAttributeValue(i);
                case A_NAME -> name = r.getAttributeValue(i);
                default -> { }
            }
        }
        return hasRef ? new String[] {ref, version, name, null} : null;
    }

    /// Patch every reference element of `xml` whose triple is a key of `patches`.
    ///
    /// @return the patched fragment, or `null` if it could not be patched safely (the caller
    ///     must then fall back to the re-marshal)
    public byte[] patch(byte[] xml, Map<Key, Val> patches) {
        if (patches.isEmpty()) return null;
        for (byte b : xml) {
            if (b == '\r') { refusals[0]++; return null; } // CR moves the offsets off the raw text
        }
        String s = new String(xml, StandardCharsets.UTF_8);
        List<int[]> spans = new ArrayList<>();   // {start, end} of each start tag to rewrite
        List<Val> vals = new ArrayList<>();
        Set<Key> landed = new HashSet<>();
        try {
            XMLStreamReader2 r = (XMLStreamReader2) wif.createXMLStreamReader(new StringReader(s));
            LocationInfo li = r.getLocationInfo();
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String[] t = triple(r);
                if (t == null) continue;
                Key key = new Key(t[0], t[1], t[2]);
                Val v = patches.get(key);
                if (v == null) continue;
                long from = li.getStartingCharOffset();
                long to = li.getEndingCharOffset();
                if (from < 0 || to <= from || to > s.length()) { refusals[2]++; return null; }
                if (s.charAt((int) from) != '<') { refusals[2]++; return null; }
                int end = tagEnd(s, (int) from, (int) to);
                if (end < 0) { refusals[2]++; return null; }
                if (!r.getLocalName().endsWith("Ref")) oddlyNamed++;
                spans.add(new int[] {(int) from, end});
                vals.add(v);
                landed.add(key);
            }
            r.close();
        } catch (Exception e) {
            refusals[1]++;
            return null; // not well-formed: the re-marshal is the safe answer
        }
        if (landed.size() != patches.size()) {
            refusals[3]++;
            if (DEBUG) debug(s, patches, landed);
            return null; // a requested repair found no element
        }
        if (spans.isEmpty()) return null;
        // spans/vals are positionally paired and already in document order; the ascending check in
        // the splice loop below enforces that, so never sort them.
        StringBuilder out = new StringBuilder(s.length() + 64 * spans.size());
        int copied = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] sp = spans.get(i);
            if (sp[0] < copied) return null; // nested/overlapping start tags cannot happen
            String tag = rewriteTag(s, sp[0], sp[1], vals.get(i));
            if (tag == null) { refusals[4]++; return null; }
            out.append(s, copied, sp[0]).append(tag);
            copied = sp[1];
        }
        out.append(s, copied, s.length());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// End (exclusive) of the start tag that begins at `from`: the first `'>'` outside an attribute
    /// value. `limit` is the parser-reported end of the event, which Woodstox gives as the end of
    /// the tag for an empty element and as the end of the whole element for a non-empty one.
    private static int tagEnd(String s, int from, int limit) {
        boolean inQuote = false;
        char q = 0;
        for (int i = from + 1; i < limit; i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == q) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                q = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ start-tag surgery

    /// One attribute of a start tag, as character ranges.
    private record Attr(int preStart, int nameStart, int nameEnd, int valueStart, int valueEnd,
                        char quote, int end) {}

    /// Rewrite the start tag `s[from,to)` so that `version`/`nameOfRefClass` carry `v`.
    /// Present-and-different is replaced in place (attribute order preserved); present-and-equal is
    /// left untouched; absent-and-wanted is appended after the last existing attribute;
    /// present-and-wanted-null is removed together with its leading whitespace.
    ///
    /// @return the new tag text, or `null` if the tag does not scan as a start tag
    private static String rewriteTag(String s, int from, int to, Val v) {
        if (s.charAt(from) != '<' || s.charAt(to - 1) != '>') return null;
        int limit = to - 1;
        int p = from + 1;
        while (p < limit && !isSpace(s.charAt(p)) && s.charAt(p) != '/') p++; // element qname
        int afterLast = p;
        List<Attr> attrs = new ArrayList<>();
        while (true) {
            int pre = p;
            while (p < limit && isSpace(s.charAt(p))) p++;
            if (p >= limit || s.charAt(p) == '/') break;
            int nameStart = p;
            while (p < limit && !isSpace(s.charAt(p)) && s.charAt(p) != '=') p++;
            int nameEnd = p;
            if (nameEnd == nameStart) return null;
            while (p < limit && isSpace(s.charAt(p))) p++;
            if (p >= limit || s.charAt(p) != '=') return null;
            p++;
            while (p < limit && isSpace(s.charAt(p))) p++;
            if (p >= limit || (s.charAt(p) != '"' && s.charAt(p) != '\'')) return null;
            char q = s.charAt(p);
            int valueStart = ++p;
            while (p < limit && s.charAt(p) != q) p++;
            if (p >= limit) return null;
            int valueEnd = p++;
            attrs.add(new Attr(pre, nameStart, nameEnd, valueStart, valueEnd, q, p));
            afterLast = p;
        }
        List<Object[]> edits = new ArrayList<>(); // {start, end, replacement}
        if (!planAttr(s, attrs, A_VERSION, v.version(), afterLast, edits)) return null;
        if (!planAttr(s, attrs, A_NAME, v.nameOfRefClass(), afterLast, edits)) return null;
        if (edits.isEmpty()) return s.substring(from, to);
        edits.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
        StringBuilder out = new StringBuilder(to - from + 64);
        int copied = from;
        for (Object[] e : edits) {
            int es = (Integer) e[0];
            int ee = (Integer) e[1];
            if (es < copied) return null;
            out.append(s, copied, es).append((String) e[2]);
            copied = ee;
        }
        out.append(s, copied, to);
        return out.toString();
    }

    /// Queue the edit for one attribute; false = the tag is not patchable.
    private static boolean planAttr(String s, List<Attr> attrs, String name, String wanted,
                                    int afterLast, List<Object[]> edits) {
        Attr found = null;
        for (Attr a : attrs) {
            if (s.regionMatches(a.nameStart(), name, 0, name.length())
                    && a.nameEnd() - a.nameStart() == name.length()) {
                if (found != null) return false; // duplicate attribute: not well-formed anyway
                found = a;
            }
        }
        if (found == null) {
            if (wanted == null) return true; // absent, and wanted absent
            edits.add(new Object[] {afterLast, afterLast,
                    " " + name + "=\"" + escape(wanted, '"') + "\""});
            return true;
        }
        if (wanted == null) { // present, wanted absent: drop it, leading whitespace included
            edits.add(new Object[] {found.preStart(), found.end(), ""});
            return true;
        }
        String next = escape(wanted, found.quote());
        if (s.regionMatches(found.valueStart(), next, 0, next.length())
                && found.valueEnd() - found.valueStart() == next.length()) {
            return true; // present and already correct: leave the bytes alone
        }
        edits.add(new Object[] {found.valueStart(), found.valueEnd(), next});
        return true;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /// XML attribute-value escaping for the given delimiter.
    private static String escape(String s, char quote) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\n' -> sb.append("&#xA;");
                case '\r' -> sb.append("&#xD;");
                case '\t' -> sb.append("&#x9;");
                default -> {
                    if (c == quote) {
                        sb.append(c == '"' ? "&quot;" : "&apos;");
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /// Build the patch map from the before/after state of one object's reference list.
    ///
    /// @param before per-reference {@code {ref, version, nameOfRefClass}} snapshot taken before
    ///     resolve() mutated anything
    /// @param after the same three values read back after the mutation, positionally aligned
    /// @param inventNameOfRefClass false = do not ADD a `nameOfRefClass` attribute the source
    ///     fragment never had (PROV-CAP: no-invent-nameofrefclass); an attribute that
    ///     exists is still corrected
    /// @return the patch map, empty if nothing changed, or `null` on a conflict (two
    ///     references indistinguishable in the XML that resolve() sent to different values)
    public static Map<Key, Val> patchSet(String[][] before, String[][] after,
                                         boolean inventNameOfRefClass) {
        Map<Key, Val> out = new LinkedHashMap<>();
        for (int i = 0; i < before.length; i++) {
            if (!Objects.equals(before[i][0], after[i][0])) return null; // @ref must not move
            Val v = wanted(before[i], after[i], inventNameOfRefClass);
            if (!Objects.equals(before[i][1], v.version())
                    || !Objects.equals(before[i][2], v.nameOfRefClass())) {
                out.put(key(before[i]), v);
            }
        }
        // Every reference sharing a patched triple must want the same outcome — otherwise the XML
        // cannot tell them apart and only the re-marshal is faithful.
        for (int i = 0; i < before.length; i++) {
            Val v = out.get(key(before[i]));
            if (v != null && !v.equals(wanted(before[i], after[i], inventNameOfRefClass))) return null;
        }
        return out;
    }

    private static Key key(String[] b) {
        return new Key(b[0], b[1], b[2]);
    }

    private static Val wanted(String[] b, String[] a, boolean invent) {
        return new Val(a[1], !invent && b[2] == null ? null : a[2]);
    }
}
