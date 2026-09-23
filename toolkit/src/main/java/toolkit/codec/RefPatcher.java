package toolkit.codec;

import org.codehaus.stax2.LocationInfo;
import org.codehaus.stax2.XMLStreamReader2;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
/// passes every other byte through verbatim: the parser only locates element boundaries, via
/// [LocationInfo] per-event input byte offsets, and nothing outside a patched start tag is
/// rewritten, so text, CDATA, comments, entity references and attribute order survive byte for
/// byte. The surgery is done on the raw bytes: every character it keys on is ASCII, and a UTF-8
/// multi-byte sequence contains no ASCII byte.
///
/// Matching is by the triple (`@ref`, `@version`, `@nameOfRefClass`) as it appears in the fragment,
/// on any element carrying an unqualified `ref` attribute, in any namespace and under any element
/// name — NeTEx spreads `*Ref` elements over several namespaces and the resolve code never looks at
/// the element name either. Every element matching a patched triple is patched, as the bean-side
/// repair does.
///
/// [#patch] returns `null` — meaning "fall back to the re-marshal" — when the fragment is not
/// well-formed, has a located start tag that does not scan as one, or when any requested patch
/// failed to land on at least one element.
public final class RefPatcher {

    /// A reference as it appears in the stored fragment. `null` = attribute absent.
    public record Key(String ref, String version, String nameOfRefClass) {}

    /// What resolve() decided the reference's attributes should become. `null` = remove.
    public record Val(String version, String nameOfRefClass) {}

    private static final String A_REF = "ref";
    private static final String A_VERSION = "version";
    private static final String A_NAME = "nameOfRefClass";

    /// `FragmentCodec.decode`'s reader, shared by [#scan] and [#patch].
    private final XMLInputFactory xif = Stax.factory();

    /// Elements that matched a patch but are not named `*Ref` — expected to stay 0.
    private long oddlyNamed;

    /// Why patches were refused: {not-well-formed, bad offsets, unlanded, tag scan}.
    private final long[] refusals = new long[4];

    public long[] refusals() {
        return refusals;
    }

    public RefPatcher() {
    }

    private static final boolean DEBUG = Conf.on(Conf.REF_PATCH_DEBUG);
    private static int debugged;

    private static synchronized void debug(byte[] xml, Map<Key, Val> patches, Set<Key> landed) {
        if (debugged++ > 3) return;
        Log.warn("[refpatch] UNLANDED wanted=%s landed=%s", patches.keySet(), landed);
        Log.warn("[refpatch] fragment head: %s",
                new String(xml, 0, Math.min(1500, xml.length), StandardCharsets.UTF_8));
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
        List<int[]> spans = new ArrayList<>();   // {start, end} of each start tag to rewrite
        List<Val> vals = new ArrayList<>();
        Set<Key> landed = new HashSet<>();
        try {
            XMLStreamReader2 r =
                    (XMLStreamReader2) xif.createXMLStreamReader(new ByteArrayInputStream(xml));
            LocationInfo li = r.getLocationInfo();
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String[] t = triple(r);
                if (t == null) continue;
                Key key = new Key(t[0], t[1], t[2]);
                Val v = patches.get(key);
                if (v == null) continue;
                long from = li.getStartingByteOffset();
                long to = li.getEndingByteOffset();
                if (from < 0 || to <= from || to > xml.length) { refusals[1]++; return null; }
                if (xml[(int) from] != '<') { refusals[1]++; return null; }
                int end = tagEnd(xml, (int) from, (int) to);
                if (end < 0) { refusals[1]++; return null; }
                if (!r.getLocalName().endsWith("Ref")) oddlyNamed++;
                spans.add(new int[] {(int) from, end});
                vals.add(v);
                landed.add(key);
            }
            r.close();
        } catch (Exception e) {
            refusals[0]++;
            return null; // not well-formed: the re-marshal is the safe answer
        }
        if (landed.size() != patches.size()) {
            refusals[2]++;
            if (DEBUG) debug(xml, patches, landed);
            return null; // a requested repair found no element
        }
        if (spans.isEmpty()) return null;
        // spans/vals are positionally paired and already in document order; the ascending check in
        // the splice loop below enforces that, so never sort them.
        ByteArrayOutputStream out = new ByteArrayOutputStream(xml.length + 64 * spans.size());
        int copied = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] sp = spans.get(i);
            if (sp[0] < copied) return null; // nested/overlapping start tags cannot happen
            byte[] tag = rewriteTag(xml, sp[0], sp[1], vals.get(i));
            if (tag == null) { refusals[3]++; return null; }
            out.write(xml, copied, sp[0] - copied);
            out.write(tag, 0, tag.length);
            copied = sp[1];
        }
        out.write(xml, copied, xml.length - copied);
        return out.toByteArray();
    }

    /// End (exclusive) of the start tag that begins at `from`: the first `'>'` outside an attribute
    /// value. `limit` is the parser-reported end of the event, which is an upper bound on the tag.
    private static int tagEnd(byte[] b, int from, int limit) {
        boolean inQuote = false;
        byte q = 0;
        for (int i = from + 1; i < limit; i++) {
            byte c = b[i];
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

    /// One attribute of a start tag, as byte ranges.
    private record Attr(int preStart, int nameStart, int nameEnd, int valueStart, int valueEnd,
                        byte quote, int end) {}

    /// Rewrite the start tag `b[from,to)` so that `version`/`nameOfRefClass` carry `v`.
    /// Present-and-different is replaced in place (attribute order preserved); present-and-equal is
    /// left untouched; absent-and-wanted is appended after the last existing attribute;
    /// present-and-wanted-null is removed together with its leading whitespace.
    ///
    /// @return the new tag bytes, or `null` if the tag does not scan as a start tag
    private static byte[] rewriteTag(byte[] b, int from, int to, Val v) {
        if (b[from] != '<' || b[to - 1] != '>') return null;
        int limit = to - 1;
        int p = from + 1;
        while (p < limit && !isSpace(b[p]) && b[p] != '/') p++; // element qname
        int afterLast = p;
        List<Attr> attrs = new ArrayList<>();
        while (true) {
            int pre = p;
            while (p < limit && isSpace(b[p])) p++;
            if (p >= limit || b[p] == '/') break;
            int nameStart = p;
            while (p < limit && !isSpace(b[p]) && b[p] != '=') p++;
            int nameEnd = p;
            if (nameEnd == nameStart) return null;
            while (p < limit && isSpace(b[p])) p++;
            if (p >= limit || b[p] != '=') return null;
            p++;
            while (p < limit && isSpace(b[p])) p++;
            if (p >= limit || (b[p] != '"' && b[p] != '\'')) return null;
            byte q = b[p];
            int valueStart = ++p;
            while (p < limit && b[p] != q) p++;
            if (p >= limit) return null;
            int valueEnd = p++;
            attrs.add(new Attr(pre, nameStart, nameEnd, valueStart, valueEnd, q, p));
            afterLast = p;
        }
        List<Object[]> edits = new ArrayList<>(); // {start, end, replacement}
        if (!planAttr(b, attrs, A_VERSION, v.version(), afterLast, edits)) return null;
        if (!planAttr(b, attrs, A_NAME, v.nameOfRefClass(), afterLast, edits)) return null;
        if (edits.isEmpty()) return Arrays.copyOfRange(b, from, to);
        edits.sort((x, y) -> Integer.compare((Integer) x[0], (Integer) y[0]));
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from + 64);
        int copied = from;
        for (Object[] e : edits) {
            int es = (Integer) e[0];
            int ee = (Integer) e[1];
            if (es < copied) return null;
            out.write(b, copied, es - copied);
            byte[] rep = (byte[]) e[2];
            out.write(rep, 0, rep.length);
            copied = ee;
        }
        out.write(b, copied, to - copied);
        return out.toByteArray();
    }

    /// Queue the edit for one attribute; false = the tag is not patchable.
    private static boolean planAttr(byte[] b, List<Attr> attrs, String name, String wanted,
                                    int afterLast, List<Object[]> edits) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        Attr found = null;
        for (Attr a : attrs) {
            if (a.nameEnd() - a.nameStart() == nameBytes.length
                    && Arrays.equals(b, a.nameStart(), a.nameEnd(), nameBytes, 0,
                            nameBytes.length)) {
                if (found != null) return false; // duplicate attribute: not well-formed anyway
                found = a;
            }
        }
        if (found == null) {
            if (wanted == null) return true; // absent, and wanted absent
            edits.add(new Object[] {afterLast, afterLast,
                    (" " + name + "=\"" + escape(wanted, '"') + "\"")
                            .getBytes(StandardCharsets.UTF_8)});
            return true;
        }
        if (wanted == null) { // present, wanted absent: drop it, leading whitespace included
            edits.add(new Object[] {found.preStart(), found.end(), new byte[0]});
            return true;
        }
        byte[] next = escape(wanted, (char) found.quote()).getBytes(StandardCharsets.UTF_8);
        if (found.valueEnd() - found.valueStart() == next.length
                && Arrays.equals(b, found.valueStart(), found.valueEnd(), next, 0, next.length)) {
            return true; // present and already correct: leave the bytes alone
        }
        edits.add(new Object[] {found.valueStart(), found.valueEnd(), next});
        return true;
    }

    private static boolean isSpace(byte c) {
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
