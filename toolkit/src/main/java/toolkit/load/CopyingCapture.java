package toolkit.load;

import com.ctc.wstx.stax.WstxOutputFactory;
import org.codehaus.stax2.XMLStreamReader2;
import org.codehaus.stax2.XMLStreamWriter2;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.util.StreamReaderDelegate;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/// Fragment capture: a [StreamReaderDelegate] that tees every StAX event of an interesting element
/// into a Woodstox [XMLStreamWriter2] while JAXB unmarshals through it. The fragment is built from
/// source parse events, not from a post-bind re-marshal.
///
/// Attribute denormalisation lives here (PROV-CAP: denorm-depth) because this is the only
/// component that sees every event of the document. Attributes are injected into the reader, so
/// the bound bean and the captured fragment agree by construction.
///
/// ## Normalization set — the only ways a fragment may differ from the source bytes
///
/// 1. **Namespace-context hoisting.** In-scope bindings not re-declared by the root are added to
///    it, after its own attributes. Needed for self-containment, including prefixes that occur
///    only inside QName-valued content. The reserved `xml` prefix and an in-scope empty default
///    namespace are not hoisted.
/// 2. **Start-tag whitespace.** Attributes keep source order; one space between, double quotes.
///    Source newlines and `'` quoting are lost.
/// 3. **Entity re-escaping.** The parser resolves references and the writer re-escapes
///    canonically, so `&#65;` becomes `A`. CDATA stays CDATA. Attribute-value whitespace
///    normalization is the parser's, per spec.
/// 4. **Empty-element form.** Always self-closing.
/// 5. **Attribute synthesis, at any depth.** `id`, `version`, `dataSourceRef`,
///    `responsibilitySetRef` appended to every element lacking them for which a value can be
///    computed — see [#startElement()].
/// 6. **Encoding.** Always UTF-8; a non-UTF-8 source is transcoded.
/// 7. **srsName denormalisation** (PROV-CAP: srsname-depth). Under a frame
///    `DefaultLocationSystem`, `srsName` is synthesized
///    onto the `srs_name.txt` set (matched on local name, any namespace) and onto every `Location`
///    without one. Inside a `Location` that declares `srsName`, that value wins over the frame
///    default. An srsName the source declares is stamped verbatim; feeds put non-CRS strings there.
///    The loader hands a frame default down only when it resolves to a CRS
///    (NetexLoader.ParseIterator#locationSystemOf), so a garbage default arrives here as null and
///    the geometries under it keep no srsName at all.
///
/// Comments and PIs copied verbatim. One instance per stream.
final class CopyingCapture extends StreamReaderDelegate {

    private static final String ID = "id";
    private static final String VERSION = "version";
    private static final String DATA_SOURCE_REF = "dataSourceRef";
    private static final String RESPONSIBILITY_SET_REF = "responsibilitySetRef";
    private static final String SRS_NAME = "srsName";
    private static final String LOCATION = "Location";
    private static final String ANY = "any";

    /// GML geometry classes are in the withId set because they carry `gml:id`, but arm 1 writes a
    /// no-namespace `id`, which binds to nothing there and would be a Java-only attribute in the
    /// fragment. So: derive and stack the id for descendants, do not inject it.
    private static final String GML_NS = "http://www.opengis.net/gml/3.2";

    /// Maximum attributes this class can synthesize onto one start tag (the arms of N5 + N7).
    private static final int MAX_INJECT = 5;

    private final XMLStreamReader2 raw;
    private final NetexLoader.NameSets sets;
    private final WstxOutputFactory wof = new WstxOutputFactory();
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(16 << 10);
    private XMLStreamWriter2 w;
    private int depth;
    private boolean copying;

    // Kept here, not in NetexLoader's loop: that loop never sees the inside of an interesting
    // element. Every event passes through next(), including the ones the unmarshaller pulls.
    private final ArrayDeque<String[]> lastIdStack = new ArrayDeque<>();   // (localName, id)
    private final ArrayDeque<String> lastVersionStack = new ArrayDeque<>();

    // Pushed by the loader at every FrameDefaults end and frame end. All null for a feed that
    // declares no FrameDefaults.
    private String dataSourceRef;
    private String responsibilitySetRef;
    private String locationSystem;
    private String locationSrsName;

    // Synthesized onto the current start element only: injectCount is 0 on every other event,
    // which is what makes the attribute accessors below inert.
    private final String[] injectName = new String[MAX_INJECT];
    private final String[] injectValue = new String[MAX_INJECT];
    private int injectCount;

    // Scratch for the per-start-element attribute scan (see scanAttrs).
    private String ownId, ownVersion, ownDataSourceRef, ownResponsibilitySetRef, ownSrsName;

    CopyingCapture(XMLStreamReader2 raw, NetexLoader.NameSets sets) {
        super(raw);
        this.raw = raw;
        this.sets = sets;
    }

    /// All-null disables the three arms entirely — one null check per start element.
    void setFrameDefaults(String dataSourceRef, String responsibilitySetRef, String locationSystem) {
        this.dataSourceRef = dataSourceRef;
        this.responsibilitySetRef = responsibilitySetRef;
        this.locationSystem = locationSystem;
    }

    /// Starts at the current event, which must be the fragment root START_ELEMENT. Order on the
    /// root tag: source attributes, hoisted bindings, then synthesized attributes — the same path
    /// every nested element takes, so the root is not a special case.
    void beginCapture(Map<String, String> inScopeNs) throws XMLStreamException {
        if (getEventType() != XMLStreamConstants.START_ELEMENT) {
            throw new XMLStreamException("beginCapture: not on START_ELEMENT");
        }
        buf.reset();
        w = (XMLStreamWriter2) wof.createXMLStreamWriter(buf, "UTF-8");
        w.copyEventFromReader(raw, true); // root start tag: element + own ns decls + attributes
        // N1: hoist ancestor-declared bindings the root does not re-declare
        Set<String> own = new HashSet<>();
        for (int i = 0; i < raw.getNamespaceCount(); i++) {
            String p = raw.getNamespacePrefix(i);
            own.add(p == null ? "" : p);
        }
        for (Map.Entry<String, String> e : inScopeNs.entrySet()) {
            String prefix = e.getKey();
            String uri = e.getValue();
            if (own.contains(prefix) || "xml".equals(prefix) || uri == null || uri.isEmpty()) continue;
            if (prefix.isEmpty()) {
                w.writeDefaultNamespace(uri);
            } else {
                w.writeNamespace(prefix, uri);
            }
        }
        writeInjected();
        depth = 1;
        copying = true;
    }

    /// The captured fragment bytes; call after the unmarshaller consumed the whole subtree.
    byte[] finishCapture() throws XMLStreamException {
        // Both conditions: depth is 0 only when the fragment's own end tag was copied.
        if (copying || depth != 0) {
            abortCapture();
            throw new XMLStreamException("capture did not reach the fragment end tag (depth " + depth + ")");
        }
        w.close();
        w = null;
        return buf.toByteArray();
    }

    /// Drop any in-flight capture state (error path); the delegate keeps working uncaptured.
    void abortCapture() {
        copying = false;
        if (w != null) {
            try {
                w.close();
            } catch (XMLStreamException ignored) {
                // writer targets a byte buffer; close cannot meaningfully fail
            }
            w = null;
        }
    }

    // ------------------------------------------------------------ event funnel

    @Override
    public int next() throws XMLStreamException {
        int ev = super.next();
        injectCount = 0; // attribute accessors are only legal on START_ELEMENT
        if (ev == XMLStreamConstants.START_ELEMENT) {
            startElement();
        }
        if (copying) copy(ev);
        if (ev == XMLStreamConstants.END_ELEMENT) {
            endElement();
        }
        return ev;
    }

    /// Runs for every element of the document, at any depth.
    private void startElement() {
        final String ln = raw.getLocalName();
        final boolean withId = sets.withId.contains(ln);
        final boolean withVersion = sets.withVersion.contains(ln);
        final boolean dmo = (dataSourceRef != null || responsibilitySetRef != null)
                && sets.dataManagedObjects.contains(ln);
        final boolean srs = locationSystem != null
                && (sets.srsName.contains(ln) || LOCATION.equals(ln));
        if (!withId && !withVersion && !dmo && !srs) return;
        scanAttrs(withId, withVersion, dmo, srs);

        // ---- arm 1: id. Any depth, absent-only, no FrameDefaults gate. Derived from the nearest
        // id-bearing ancestor with its local name swapped for ours:
        // Operator "at:stv:Operator:29:" -> Address "at:stv:Address:29:".
        if (withId) {
            String id = ownId;
            if (id == null) {
                String[] top = lastIdStack.peek();
                if (top != null && top[1] != null) {
                    id = top[1].replace(top[0], ln);
                    if (!GML_NS.equals(raw.getNamespaceURI())) inject(ID, id); // see GML_NS
                }
            }
            lastIdStack.push(new String[] {ln, id});
        }

        // ---- arm 2: version. Any depth, absent-only, no FrameDefaults gate. An id-bearing element
        // inherits the nearest enclosing version; everything else (a *Ref) takes the literal "any".
        if (withVersion) {
            String version = ownVersion;
            if (version == null) {
                version = withId && !lastVersionStack.isEmpty() ? lastVersionStack.peek() : ANY;
                inject(VERSION, version);
            }
            lastVersionStack.push(version);
        }

        // ---- arms 3 and 4: dataSourceRef / responsibilitySetRef. Applied on START, not END — the
        // value moves only at a FrameDefaults or frame end, neither of which can occur strictly
        // inside a non-frame element, and by END the writer has already emitted the start tag.
        //
        // Both arms use the DataManagedObject set, though the schema puts dataSourceRef on
        // EntityInVersion: injecting on the wider set would put something in the fragment that the
        // bound object drops.
        if (dmo && !sets.frames.contains(ln)) {
            if (dataSourceRef != null && ownDataSourceRef == null) inject(DATA_SOURCE_REF, dataSourceRef);
            if (responsibilitySetRef != null && ownResponsibilitySetRef == null) {
                inject(RESPONSIBILITY_SET_REF, responsibilitySetRef);
            }
        }

        // ---- arm 5 (N7): srsName.
        if (srs) {
            if (LOCATION.equals(ln) && ownSrsName != null) locationSrsName = ownSrsName;
            if (ownSrsName == null) {
                // The srs_name arm prefers the enclosing Location's srsName; the Location arm
                // always takes the frame default. The two sets are disjoint — Location is not in
                // srs_name.txt, its model class is LocationStructure.
                inject(SRS_NAME, sets.srsName.contains(ln) && locationSrsName != null
                        ? locationSrsName : locationSystem);
            }
        }
    }

    private void endElement() {
        String ln = raw.getLocalName();
        if (sets.withId.contains(ln) && !lastIdStack.isEmpty()) lastIdStack.pop();
        if (sets.withVersion.contains(ln) && !lastVersionStack.isEmpty()) lastVersionStack.pop();
        if (LOCATION.equals(ln)) locationSrsName = null;
    }

    /// One pass, no-namespace attributes only. `getAttributeValue(null, name)` is not usable here:
    /// per the StAX contract a null URI matches any namespace, so it finds `gml:id` for `id`.
    private void scanAttrs(boolean withId, boolean withVersion, boolean dmo, boolean srs) {
        ownId = ownVersion = ownDataSourceRef = ownResponsibilitySetRef = ownSrsName = null;
        for (int i = 0, n = raw.getAttributeCount(); i < n; i++) {
            String ns = raw.getAttributeNamespace(i);
            if (ns != null && !ns.isEmpty()) continue;
            String name = raw.getAttributeLocalName(i);
            if (withId && ID.equals(name)) ownId = raw.getAttributeValue(i);
            else if (withVersion && VERSION.equals(name)) ownVersion = raw.getAttributeValue(i);
            else if (dmo && DATA_SOURCE_REF.equals(name)) ownDataSourceRef = raw.getAttributeValue(i);
            else if (dmo && RESPONSIBILITY_SET_REF.equals(name)) ownResponsibilitySetRef = raw.getAttributeValue(i);
            else if (srs && SRS_NAME.equals(name)) ownSrsName = raw.getAttributeValue(i);
        }
    }

    private void inject(String name, String value) {
        injectName[injectCount] = name;
        injectValue[injectCount] = value;
        injectCount++;
    }

    private void writeInjected() throws XMLStreamException {
        for (int i = 0; i < injectCount; i++) {
            w.writeAttribute(injectName[i], injectValue[i]);
        }
    }

    private void copy(int ev) throws XMLStreamException {
        switch (ev) {
            case XMLStreamConstants.START_ELEMENT -> {
                depth++;
                w.copyEventFromReader(raw, true);
                writeInjected(); // N5/N7 — after the source attributes, as they are appended
            }
            case XMLStreamConstants.END_ELEMENT -> {
                w.copyEventFromReader(raw, true);
                if (--depth == 0) copying = false;
            }
            // Document ends with a capture still open. Deliberately a no-op: clearing `copying`
            // here is what finishCapture() reads as success, so it would store a partial fragment
            // as a whole one.
            case XMLStreamConstants.END_DOCUMENT -> { }
            default -> w.copyEventFromReader(raw, true);
        }
    }

    /// Funnelled through [#next()] so a capture is never bypassed — the inherited delegate would
    /// advance the wrapped reader directly.
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
                        XMLStreamConstants.SPACE, XMLStreamConstants.ENTITY_REFERENCE -> sb.append(getText());
                case XMLStreamConstants.PROCESSING_INSTRUCTION, XMLStreamConstants.COMMENT -> { }
                case XMLStreamConstants.END_DOCUMENT ->
                        throw new XMLStreamException("getElementText: unexpected end of document");
                default -> throw new XMLStreamException("getElementText: element has mixed content");
            }
            ev = next();
        }
        return sb.toString();
    }

    /// Same funnelling rationale as [#getElementText].
    @Override
    public int nextTag() throws XMLStreamException {
        int ev = next();
        while (ev == XMLStreamConstants.CHARACTERS && isWhiteSpace()
                || ev == XMLStreamConstants.CDATA && isWhiteSpace()
                || ev == XMLStreamConstants.SPACE
                || ev == XMLStreamConstants.PROCESSING_INSTRUCTION
                || ev == XMLStreamConstants.COMMENT) {
            ev = next();
        }
        if (ev != XMLStreamConstants.START_ELEMENT && ev != XMLStreamConstants.END_ELEMENT) {
            throw new XMLStreamException("nextTag: expected start or end tag", getLocation());
        }
        return ev;
    }

    // ----------------------------------------------- N5/N7 attribute injection (reader side)

    // JAXB must see the synthesized attributes too, or the bean and the fragment disagree. They
    // are appended after the source attributes, in no namespace.

    private int injectedIndex(int index) {
        int base = super.getAttributeCount();
        return index >= base && index < base + injectCount ? index - base : -1;
    }

    @Override
    public int getAttributeCount() {
        return super.getAttributeCount() + injectCount;
    }

    @Override
    public String getAttributeValue(int index) {
        int i = injectedIndex(index);
        return i < 0 ? super.getAttributeValue(index) : injectValue[i];
    }

    @Override
    public String getAttributeLocalName(int index) {
        int i = injectedIndex(index);
        return i < 0 ? super.getAttributeLocalName(index) : injectName[i];
    }

    @Override
    public String getAttributeNamespace(int index) {
        return injectedIndex(index) < 0 ? super.getAttributeNamespace(index) : null;
    }

    @Override
    public String getAttributePrefix(int index) {
        return injectedIndex(index) < 0 ? super.getAttributePrefix(index) : "";
    }

    @Override
    public QName getAttributeName(int index) {
        int i = injectedIndex(index);
        return i < 0 ? super.getAttributeName(index) : new QName(injectName[i]);
    }

    @Override
    public String getAttributeType(int index) {
        return injectedIndex(index) < 0 ? super.getAttributeType(index) : "CDATA";
    }

    @Override
    public boolean isAttributeSpecified(int index) {
        return injectedIndex(index) >= 0 || super.isAttributeSpecified(index);
    }

    @Override
    public String getAttributeValue(String namespaceURI, String localName) {
        if (injectCount > 0 && (namespaceURI == null || namespaceURI.isEmpty())) {
            for (int i = 0; i < injectCount; i++) {
                if (injectName[i].equals(localName)) return injectValue[i];
            }
        }
        return super.getAttributeValue(namespaceURI, localName);
    }
}
