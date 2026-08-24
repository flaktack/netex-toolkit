package inspect.nx;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAnyAttribute;
import jakarta.xml.bind.annotation.XmlAnyElement;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementRefs;
import jakarta.xml.bind.annotation.XmlElements;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.XmlValue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Layer **B**: the bound bean, rendered as `path = value` — **declared-but-null fields
/// included**.
///
/// ## Why the nulls
///
/// The defect shape this exists for is a byte-perfect fragment that binds to a null field:
///
/// ```
/// F:  <TransportMode>bus </TransportMode>        (byte-perfect from source)
/// B:  TransportMode  = null                      (no error, no ValidationEvent)
/// ```
///
/// Every fragment instrument is blind to it, and a renderer that skips nulls renders the absence as
/// nothing at all. So every declared field of every class in the inheritance chain is emitted,
/// populated or not; `--b-nonnull` turns that off and is off by default.
///
/// ## How the path is built
///
/// Paths use the XML name from the field's JAXB annotation, not the Java field name, so a B path
/// and an F path are the same string and join without a translation table. The Java field name and
/// declared type print alongside. Attributes print as `@id` and `@XmlValue` as `text()`, matching
/// [XmlDoc#flatten].
///
/// The generated model is `@XmlAccessorType(XmlAccessType.FIELD)` throughout, so field reflection
/// sees exactly what JAXB binds.
///
/// ## Null is a leaf
///
/// A null complex field cannot be walked into, so the recursion is bounded by what is populated;
/// the depth cap guards against cyclic object graphs, not against breadth.
public final class BeanRender {

    /// Where a row's XML name came from.
    ///
    /// Tagging every row with the annotation that named it turns "0 unmatched" into "0 unmatched,
    /// of which N went through a choice alternative or an `@XmlAnyElement` fallback".
    public enum Origin {
        /// `@XmlAttribute`.
        ATTR,
        /// `@XmlAnyAttribute`.
        ANY_ATTR,
        /// `@XmlValue`, i.e. `text()`.
        TEXT,
        /// `@XmlElement`.
        ELEMENT,
        /// `@XmlElementRef`.
        ELEMENT_REF,
        /// `@XmlElements` — name from the matching alternative.
        CHOICE,
        /// `@XmlElementRefs` — name from the matching alternative.
        CHOICE_REF,
        /// `@XmlAnyElement`, or a choice whose alternatives all declared `##default`: the name is
        /// the VALUE'S CLASS, or for a DOM node its own element name.
        ANY,
        /// `@XmlList` or a collection-valued `@XmlValue`: an `xs:list`, written as ONE element
        /// whose text is space-separated tokens, not as repeated siblings.
        LIST,
        /// No JAXB annotation; `XmlAccessType.FIELD` binds the field name.
        FIELD_NAME,
        /// A `JAXBElement` whose element name differs from the field's declared one.
        SUBSTITUTION,
        /// The same instance was already rendered above.
        CYCLE,
        /// The depth cap stopped the walk here.
        DEPTH_CAP,
        /// The field could not be read reflectively.
        UNREADABLE
    }

    /// One rendered line. `value` is null exactly when the field is null.
    public record Row(String path, String javaField, String declaredType, String value,
                      boolean isNull, boolean complex, Origin origin) {
        public String render() {
            String v = isNull ? "null" : value;
            return String.format("%-58s = %-34s   [%s %s]", path, v, javaField, declaredType);
        }
    }

    private final int maxDepth;
    private final boolean includeNulls;
    private final List<Row> rows = new ArrayList<>();
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    private BeanRender(int maxDepth, boolean includeNulls) {
        this.maxDepth = maxDepth;
        this.includeNulls = includeNulls;
    }

    public static List<Row> render(Object bean, int maxDepth, boolean includeNulls) {
        BeanRender r = new BeanRender(maxDepth, includeNulls);
        r.walk(bean, bean.getClass().getSimpleName(), 0);
        return r.rows;
    }

    /// `path -> value` for the non-null rows, for boundary joins against [XmlDoc#flatten].
    public static Map<String, Row> byPath(List<Row> rows) {
        Map<String, Row> m = new LinkedHashMap<>();
        for (Row r : rows) m.putIfAbsent(r.path(), r);
        return m;
    }

    // ------------------------------------------------------------------ walk

    private void walk(Object obj, String prefix, int depth) {
        if (obj == null) return;
        if (seen.put(obj, Boolean.TRUE) != null) {
            rows.add(new Row(prefix, "", obj.getClass().getSimpleName(),
                    "<already rendered above (cycle)>", false, true, Origin.CYCLE));
            return;
        }
        for (Class<?> c : chain(obj.getClass())) {
            for (Bind b : binds(c)) {
                Object v;
                try {
                    v = b.f.get(obj);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    rows.add(new Row(prefix + "/" + b.xmlName, b.f.getName(), b.type,
                            "<unreadable: " + e + ">", false, false, Origin.UNREADABLE));
                    continue;
                }
                emit(prefix + "/" + b.xmlName, b.f, b.f.getName(), b.type, b.origin, v, depth);
            }
        }
    }

    private void emit(String path, Field f, String javaField, String type, Origin origin,
                      Object v, int depth) {
        if (v == null) {
            if (includeNulls) rows.add(new Row(path, javaField, type, null, true, false, origin));
            return;
        }
        if (v instanceof JAXBElement<?> je) {
            // A substitution-group slot: the ELEMENT NAME lives on the value, not the field —
            // a ServiceJourneyPatternRef substituting for the declared JourneyPatternRef.
            String ln = je.getName() == null ? javaField : je.getName().getLocalPart();
            String parent = path.substring(0, path.lastIndexOf('/') + 1);
            String last = path.substring(path.lastIndexOf('/') + 1);
            // Keep any list index: it is part of the path XmlDoc.flatten produces, and dropping it
            // collapses every element of a substitution-group list onto one path.
            int br = last.indexOf('[');
            String declared = br < 0 ? last : last.substring(0, br);
            String idx = br < 0 ? "" : last.substring(br);
            String p = parent + ln + idx;
            if (!ln.equals(declared)) {
                rows.add(new Row(p, javaField, type,
                        "<JAXBElement substituting for declared <" + declared + ">>", false, true,
                        Origin.SUBSTITUTION));
                origin = Origin.SUBSTITUTION;
            }
            emit(p, null, javaField, type, origin, je.getValue(), depth);
            return;
        }
        if (v instanceof Collection<?> col) {
            // xs:list. @XmlList and a collection-valued @XmlValue both serialise as ONE element
            // carrying space-separated tokens, so indexing them as siblings would invent paths the
            // document never contains.
            if (origin == Origin.LIST || origin == Origin.TEXT) {
                StringBuilder sb = new StringBuilder();
                for (Object item : col) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(token(item));
                }
                rows.add(new Row(path, javaField, type, "\"" + sb + "\"", false, false, origin));
                return;
            }
            if (col.isEmpty()) {
                rows.add(new Row(path, javaField, type, "[] (empty)", false, false, origin));
                return;
            }
            // Index exactly as XmlDoc.flatten does — PER RESOLVED ELEMENT NAME among the siblings,
            // and only when that name occurs more than once — because a B path and an F path must
            // be the same string. Collection POSITION is not that index: a heterogeneous choice
            // list [Stop, Timing, Stop] is Stop[0]/Timing/Stop[1] in the document, not
            // Stop[0]/Timing[1]/Stop[2].
            List<String> names = new ArrayList<>(col.size());
            Map<String, Integer> total = new LinkedHashMap<>();
            String declaredLast = path.substring(path.lastIndexOf('/') + 1);
            for (Object item : col) {
                String n = itemName(f, item, declaredLast);
                names.add(n);
                total.merge(n, 1, Integer::sum);
            }
            Map<String, Integer> seen = new LinkedHashMap<>();
            int i = 0;
            for (Object item : col) {
                String n = names.get(i++);
                int k = seen.merge(n, 1, Integer::sum) - 1;
                // A JAXBElement item keeps the DECLARED name here: the substitution row below can
                // only be emitted while that name is still visible. The element name is applied one
                // level down.
                Choice ch = item instanceof JAXBElement ? null : choice(f, item);
                String base = retarget(path, ch == null ? null : ch.name());
                emit(total.get(n) > 1 ? base + "[" + k + "]" : base, f, javaField, type,
                        ch == null ? origin : ch.origin(), item, depth);
            }
            return;
        }
        Choice ch = choice(f, v);
        if (ch != null) { path = retarget(path, ch.name()); origin = ch.origin(); }
        if (v instanceof org.w3c.dom.Node dom) {
            // @XmlAnyElement content is a DOM subtree, not bound to fields but round-tripped
            // verbatim, so it renders as a reachable leaf: its descendants are unreachable by
            // design, not dropped.
            int kids = 0;
            for (org.w3c.dom.Node n = dom.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) kids++;
            }
            rows.add(new Row(path, javaField, "org.w3c.dom." + dom.getClass().getSimpleName(),
                    "<DOM subtree, " + kids + " child element(s), kept verbatim — not bound to"
                    + " bean fields>", false, true, Origin.ANY));
            return;
        }
        Class<?> vc = v.getClass();
        if (isComplex(vc)) {
            if (depth >= maxDepth) {
                rows.add(new Row(path, javaField, vc.getSimpleName(),
                        "<depth cap " + maxDepth + " reached>", false, true, Origin.DEPTH_CAP));
                return;
            }
            rows.add(new Row(path, javaField, vc.getSimpleName(), "<" + vc.getSimpleName() + ">",
                    false, true, origin));
            walk(v, path, depth + 1);
            return;
        }
        rows.add(new Row(path, javaField, type, scalar(v), false, false, origin));
    }

    /// The XML element name a **choice** field's value is written under.
    ///
    /// XJC names a choice field after ALL its alternatives at once, while the document writes one
    /// of them, so the name comes from the matching alternative, falling back to the value's class.
    record Choice(String name, Origin origin) {}

    static Choice choice(Field f, Object v) {
        if (f == null || v == null || v instanceof JAXBElement) return null;
        XmlElements xs = f.getAnnotation(XmlElements.class);
        if (xs != null) {
            for (XmlElement e : xs.value()) {
                if (e.type() != XmlElement.DEFAULT.class && e.type().isInstance(v)) {
                    return isDefault(e.name())
                            ? new Choice(v.getClass().getSimpleName(), Origin.ANY)
                            : new Choice(e.name(), Origin.CHOICE);
                }
            }
        }
        XmlElementRefs rs = f.getAnnotation(XmlElementRefs.class);
        if (rs != null) {
            for (XmlElementRef r : rs.value()) {
                if (r.type() != XmlElementRef.DEFAULT.class && r.type().isInstance(v)) {
                    return isDefault(r.name())
                            ? new Choice(v.getClass().getSimpleName(), Origin.ANY)
                            : new Choice(r.name(), Origin.CHOICE_REF);
                }
            }
        }
        if (xs != null || rs != null || f.isAnnotationPresent(XmlAnyElement.class)) {
            // A DOM node knows its own element name; its Java class does not.
            if (v instanceof org.w3c.dom.Node dom && dom.getLocalName() != null) {
                return new Choice(dom.getLocalName(), Origin.ANY);
            }
            return new Choice(v.getClass().getSimpleName(), Origin.ANY);
        }
        return null;
    }

    /// Kept for callers that only want the name.
    static String choiceName(Field f, Object v) {
        Choice c = choice(f, v);
        return c == null ? null : c.name();
    }

    /// The element name a collection item is written under, for sibling indexing.
    ///
    /// Three sources, in the order JAXB itself uses: a `JAXBElement`'s own QName (a
    /// substitution-group member), the matching choice alternative, or the field's declared name.
    private static String itemName(Field f, Object item, String declaredLast) {
        int br = declaredLast.indexOf('[');
        String declared = br < 0 ? declaredLast : declaredLast.substring(0, br);
        if (item instanceof JAXBElement<?> je) {
            return je.getName() == null ? declared : je.getName().getLocalPart();
        }
        Choice ch = choice(f, item);
        return ch == null ? declared : ch.name();
    }

    /// A token's lexical form, for `xs:list` joining.
    private static String token(Object v) {
        if (v == null) return "";
        if (v.getClass().isEnum()) {
            try {
                return String.valueOf(v.getClass().getMethod("value").invoke(v));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return ((Enum<?>) v).name();
            }
        }
        return String.valueOf(v);
    }

    /// Replace the last path segment's NAME, keeping any `[n]` sibling index.
    ///
    /// `emit` resolves a choice name twice for a collection item — once for the item's base path,
    /// once on the recursive call — so an index-dropping retarget would collapse every element of a
    /// multi-item choice list onto the first one's path.
    private static String retarget(String path, String newName) {
        if (newName == null) return path;
        int slash = path.lastIndexOf('/');
        String last = path.substring(slash + 1);
        int br = last.indexOf('[');
        String idx = br < 0 ? "" : last.substring(br);
        return path.substring(0, slash + 1) + newName + idx;
    }

    // ------------------------------------------------------------------ naming

    /// The XML name this field binds to, or null when it binds to nothing renderable. Mirrors
    /// [XmlDoc#flatten]'s path vocabulary so B and F/S/M/E paths join directly.
    static String xmlName(Field f) {
        XmlAttribute at = f.getAnnotation(XmlAttribute.class);
        if (at != null) return "@" + (isDefault(at.name()) ? f.getName() : at.name());
        if (f.isAnnotationPresent(XmlValue.class)) return "text()";
        if (f.isAnnotationPresent(XmlAnyAttribute.class)) return "@*";
        XmlElement el = f.getAnnotation(XmlElement.class);
        if (el != null) return isDefault(el.name()) ? f.getName() : el.name();
        XmlElementRef er = f.getAnnotation(XmlElementRef.class);
        if (er != null) return isDefault(er.name()) ? f.getName() : er.name();
        if (f.isAnnotationPresent(XmlElementRefs.class) || f.isAnnotationPresent(XmlElements.class)
                || f.isAnnotationPresent(XmlAnyElement.class)) {
            return f.getName();
        }
        // XmlAccessType.FIELD binds every non-transient field even without an explicit annotation.
        return f.getName();
    }

    /// The declaration that named a field, i.e. the [Origin] before any value is seen.
    static Origin declaredOrigin(Field f) {
        if (f.isAnnotationPresent(XmlAttribute.class)) return Origin.ATTR;
        if (f.isAnnotationPresent(XmlValue.class)) return Origin.TEXT;
        if (f.isAnnotationPresent(jakarta.xml.bind.annotation.XmlList.class)) return Origin.LIST;
        if (f.isAnnotationPresent(XmlAnyAttribute.class)) return Origin.ANY_ATTR;
        if (f.isAnnotationPresent(XmlElement.class)) return Origin.ELEMENT;
        if (f.isAnnotationPresent(XmlElementRef.class)) return Origin.ELEMENT_REF;
        if (f.isAnnotationPresent(XmlElements.class)) return Origin.CHOICE;
        if (f.isAnnotationPresent(XmlElementRefs.class)) return Origin.CHOICE_REF;
        if (f.isAnnotationPresent(XmlAnyElement.class)) return Origin.ANY;
        return Origin.FIELD_NAME;
    }

    private static boolean isDefault(String n) { return n == null || "##default".equals(n); }

    private static String typeName(Field f) {
        Class<?> t = f.getType();
        if (Collection.class.isAssignableFrom(t)) {
            String g = f.getGenericType().getTypeName();
            int lt = g.indexOf('<');
            if (lt > 0) {
                String inner = g.substring(lt + 1, g.length() - 1);
                return "List<" + simple(inner) + ">";
            }
            return "List";
        }
        return t.getSimpleName();
    }

    private static String simple(String fq) {
        int lt = fq.indexOf('<');
        if (lt > 0) return simple(fq.substring(0, lt)) + "<" + simple(fq.substring(lt + 1, fq.length() - 1)) + ">";
        int d = fq.lastIndexOf('.');
        return d < 0 ? fq : fq.substring(d + 1);
    }

    /// "Complex" = an XJC-generated content class, i.e. something with declared JAXB structure worth
    /// recursing into. Adapter-bound value types (`XmlDateTime`, `Duration`), enums,
    /// boxes and `String` are leaves.
    static boolean isComplex(Class<?> c) {
        if (c.isEnum() || c.isPrimitive() || c.isArray()) return false;
        if (c == String.class || Number.class.isAssignableFrom(c) || c == Boolean.class
                || c == Character.class) return false;
        return c.isAnnotationPresent(XmlType.class);
    }

    /// Scalar rendering. Strings are always quoted, so a trailing space is visible; enums print
    /// both the constant and its lexical value.
    static String scalar(Object v) {
        if (v instanceof String s) return "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\"";
        if (v instanceof byte[] b) return "byte[" + b.length + "]";
        if (v.getClass().isEnum()) {
            String lexical = null;
            try {
                lexical = String.valueOf(v.getClass().getMethod("value").invoke(v));
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            String n = ((Enum<?>) v).name();
            return lexical == null || lexical.equals(n) ? n : n + " (\"" + lexical + "\")";
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ field order

    /// One renderable field, resolved once per class instead of once per object.
    ///
    /// `nx bind` renders two beans per object over millions of objects, where per-row reflection
    /// dominates. The resolution is a pure function of the class, so it is memoised in a
    /// [ClassValue], which is also thread-safe without a lock — the sweep is multi-threaded.
    record Bind(Field f, String xmlName, String type, Origin origin) {}

    private static final ClassValue<List<Class<?>>> CHAIN = new ClassValue<>() {
        @Override protected List<Class<?>> computeValue(Class<?> c) {
            List<Class<?>> out = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) out.add(0, k);
            return List.copyOf(out);
        }
    };

    private static final ClassValue<List<Bind>> BINDS = new ClassValue<>() {
        @Override protected List<Bind> computeValue(Class<?> c) {
            List<Bind> out = new ArrayList<>();
            for (Field f : ordered(c)) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                if (f.isAnnotationPresent(XmlTransient.class)) continue;
                String name = xmlName(f);
                if (name == null) continue;
                try {
                    f.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // stays inaccessible; walk() will record it as UNREADABLE
                }
                out.add(new Bind(f, name, typeName(f), declaredOrigin(f)));
            }
            return List.copyOf(out);
        }
    };

    /// Superclass first (so `@id`/`@version` lead), then declaration order.
    static List<Class<?>> chain(Class<?> c) { return CHAIN.get(c); }

    /// This class's own renderable fields, in `propOrder` order. Memoised.
    static List<Bind> binds(Class<?> c) { return BINDS.get(c); }

    /// A class's fields in `@XmlType(propOrder=...)` order where it declares one — the schema's own
    /// `xsd:sequence` — so the B pane's line order is the order the fragment's children should be
    /// in, which makes a sibling-order defect legible against F. Fields absent from `propOrder`,
    /// i.e. the attributes, follow in declaration order.
    static List<Field> ordered(Class<?> c) {
        Field[] declared = c.getDeclaredFields();
        XmlType xt = c.getAnnotation(XmlType.class);
        if (xt == null || xt.propOrder().length == 0
                || (xt.propOrder().length == 1 && xt.propOrder()[0].isEmpty())) {
            return List.of(declared);
        }
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Field f : declared) byName.put(f.getName(), f);
        List<Field> out = new ArrayList<>();
        for (String p : xt.propOrder()) {
            Field f = byName.remove(p);
            if (f != null) out.add(f);
        }
        out.addAll(byName.values());
        return out;
    }
}
