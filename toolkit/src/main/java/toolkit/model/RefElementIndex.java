package toolkit.model;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementRefs;
import jakarta.xml.bind.annotation.XmlElements;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/// XML element name -&gt; the target-class names a reference written with that element could
/// resolve to.
///
/// [RecursiveAttributes#toRef] names a reference's target class from its `nameOfRefClass`
/// attribute when registered, else from the reference's JAVA class. Across the corpus that
/// attribute is usually ABSENT, so anything reading a stored fragment without unmarshalling it has
/// only the element name to go on. This index is the missing half.
///
/// A candidate SET, not a function: some NeTEx element names are declared with different types in
/// different parents, so the name alone does not determine the class. Rather than guess, this
/// returns every possibility, and the caller must reach the same verdict for ALL of them or fall
/// back to the unmarshal. An unknown name returns `null` — "no idea, decode it".
///
/// COMPLETENESS is the safety property: a missing candidate could let a caller skip an object it
/// should have repaired. So every way this model can name an element is enumerated — the
/// `ObjectFactory`'s `@XmlElementDecl`s, which cover substitution groups, and every field annotated
/// `@XmlElement(s)` / `@XmlElementRef(s)`. A ref-typed field with NO element annotation binds to an
/// element named after the field, JAXB's field-access default; those are recorded and counted.
public final class RefElementIndex {

    private static Map<String, String[]> byElement;
    private static Map<String, List<String>> substitutionMembers; // head element -> direct members
    private static int unannotated;

    private RefElementIndex() {}

    /// Every element name that may appear where an `@XmlElementRef(name = head)` field is
    /// declared: `head` itself plus its substitution group, transitively — JAXB's own rule,
    /// read off the `ObjectFactory`'s `substitutionHeadName` links.
    ///
    /// This is what lets a reader find, say, a ServiceJourney's `journeyPatternRef` in the stored
    /// fragment without unmarshalling it: the field accepts `JourneyPatternRef`,
    /// `ServiceJourneyPatternRef`, `DeadRunJourneyPatternRef` and `ServicePatternRef`, and nothing
    /// else.
    public static synchronized Set<String> substitutionGroup(String head) {
        if (substitutionMembers == null) buildSubstitutions();
        Set<String> out = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(head);
        while (!todo.isEmpty()) {
            String e = todo.removeFirst();
            if (!out.add(e)) continue;
            todo.addAll(substitutionMembers.getOrDefault(e, List.of()));
        }
        return out;
    }

    private static void buildSubstitutions() {
        Map<String, List<String>> members = new HashMap<>();
        try {
            Class<?> of = Class.forName(ModelRegistry.MODEL_PKG + ".ObjectFactory");
            for (Method m : of.getDeclaredMethods()) {
                XmlElementDecl d = m.getAnnotation(XmlElementDecl.class);
                if (d == null || d.substitutionHeadName() == null
                        || d.substitutionHeadName().isEmpty()) {
                    continue;
                }
                members.computeIfAbsent(d.substitutionHeadName(), k -> new ArrayList<>()).add(d.name());
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("model ObjectFactory not found", e);
        }
        for (List<String> v : members.values()) v.sort(null);
        substitutionMembers = members;
    }

    /// Every target-class name a reference element with this local name could resolve to, or
    /// `null` when the model declares no such reference element (caller must unmarshal).
    /// Namespaces are deliberately merged: two same-named elements in different namespaces yield
    /// both candidates, which can only make a caller more conservative.
    public static synchronized String[] candidates(String localName) {
        if (byElement == null) build();
        return byElement.get(localName);
    }

    /// The target-class names a reference element named `elementName` can have when it is a
    /// DIRECT child of an object of class `owner` — `null` when `owner` does not
    /// fix the type and the caller must fall back to [#candidates(String)].
    ///
    /// This disambiguates the shared names: `FromPointRef` means several different things across
    /// NeTEx, but a `ServiceLink`'s `FromPointRef` is declared `ScheduledStopPointRefStructure` and
    /// nothing else, and the owner class of a depth-1 reference is the stored object's own class,
    /// which the full_key already gives.
    ///
    /// Only fields whose annotation FIXES the type count — `@XmlElement` / `@XmlElements`, and
    /// unannotated fields. `@XmlElementRef` fields are deliberately excluded: their element name
    /// resolves through the global declaration and may be substituted by a subtype, so the field's
    /// own bound is not authoritative; those names fall back to the global candidate set. Within one
    /// class an element name binds to exactly one field, so there is no per-owner ambiguity.
    public static String[] candidatesUnder(Class<?> owner, String elementName) {
        return owner == null || elementName == null ? null : OWN.get(owner).get(elementName);
    }

    /// Per-class {element name -> candidates} for the fields that fix their own type.
    private static final ClassValue<Map<String, String[]>> OWN = new ClassValue<>() {
        @Override
        protected Map<String, String[]> computeValue(Class<?> type) {
            Map<String, TreeSet<String>> acc = new HashMap<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getAnnotation(XmlElementRef.class) != null
                            || f.getAnnotation(XmlElementRefs.class) != null) {
                        continue;
                    }
                    Class<?> v = valueType(f.getGenericType());
                    XmlElement e = f.getAnnotation(XmlElement.class);
                    XmlElements es = f.getAnnotation(XmlElements.class);
                    if (e != null) {
                        record(acc, e.name(), v);
                    } else if (es != null) {
                        for (XmlElement x : es.value()) record(acc, x.name(), valueType(x.type()));
                    } else if (v != null && VersionOfObjectRefStructure.class.isAssignableFrom(v)) {
                        record(acc, f.getName(), v); // JAXB FIELD-access default naming
                    }
                }
            }
            Map<String, String[]> out = new HashMap<>(acc.size() * 2);
            for (Map.Entry<String, TreeSet<String>> e : acc.entrySet()) {
                out.put(e.getKey(), e.getValue().toArray(new String[0]));
            }
            return out;
        }
    };

    /// Diagnostics: {distinct element names, ambiguous names, unannotated ref fields}.
    public static synchronized int[] stats() {
        if (byElement == null) build();
        int ambiguous = 0;
        for (String[] v : byElement.values()) if (v.length > 1) ambiguous++;
        return new int[] {byElement.size(), ambiguous, unannotated};
    }

    private static void build() {
        Map<String, TreeSet<String>> out = new TreeMap<>();
        int un = 0;
        try {
            Class<?> of = Class.forName(ModelRegistry.MODEL_PKG + ".ObjectFactory");
            for (Method m : of.getDeclaredMethods()) {
                XmlElementDecl d = m.getAnnotation(XmlElementDecl.class);
                if (d != null) record(out, d.name(), valueType(m.getGenericReturnType()));
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("model ObjectFactory not found", e);
        }
        for (Class<?> c : ModelRegistry.allModelClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> v = valueType(f.getGenericType());
                boolean isRef = v != null && VersionOfObjectRefStructure.class.isAssignableFrom(v);
                boolean named = false;
                XmlElement e = f.getAnnotation(XmlElement.class);
                if (e != null) named |= record(out, e.name(), v);
                XmlElements es = f.getAnnotation(XmlElements.class);
                if (es != null) for (XmlElement x : es.value()) named |= record(out, x.name(), valueType(x.type()));
                XmlElementRef er = f.getAnnotation(XmlElementRef.class);
                if (er != null) named |= record(out, er.name(), valueType(er.type()));
                XmlElementRefs ers = f.getAnnotation(XmlElementRefs.class);
                if (ers != null) for (XmlElementRef x : ers.value()) named |= record(out, x.name(), valueType(x.type()));
                if (isRef && !named) {
                    // JAXB FIELD-access default: the element is named after the field itself.
                    record(out, f.getName(), v);
                    un++;
                }
            }
        }
        Map<String, String[]> table = new HashMap<>(out.size() * 2);
        for (Map.Entry<String, TreeSet<String>> e : out.entrySet()) {
            table.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
        byElement = table;
        unannotated = un;
        int ambiguous = 0;
        for (String[] v : table.values()) if (v.length > 1) ambiguous++;
        Log.info("[ref-element-index] %d reference element names (%d ambiguous, %d unannotated fields)",
                table.size(), ambiguous, un);
    }

    /// Record `element -> the target-class name for this ref type`. Returns true when the
    /// annotation named an element at all (whether or not it is a reference), so an unnamed ref
    /// field can be told apart from an annotated non-ref one.
    private static boolean record(Map<String, TreeSet<String>> out, String name, Class<?> type) {
        if (name == null || name.isEmpty() || "##default".equals(name)) return false;
        if (type == null || !VersionOfObjectRefStructure.class.isAssignableFrom(type)) return true;
        String derived = RecursiveAttributes.deriveTargetName(type);
        if (!ModelRegistry.isRegistered(derived)) derived = "DataManagedObject";
        out.computeIfAbsent(name, k -> new TreeSet<>()).add(derived);
        return true;
    }

    /// The class a field/return type ultimately holds: `List<>` and `JAXBElement<>` unwrapped.
    private static Class<?> valueType(Type t) {
        Class<?> r = rawType(t);
        if (r == null) return null;
        if (List.class.isAssignableFrom(r) || JAXBElement.class.isAssignableFrom(r)) {
            if (t instanceof ParameterizedType p && p.getActualTypeArguments().length == 1) {
                Type a = p.getActualTypeArguments()[0];
                if (a instanceof WildcardType w && w.getUpperBounds().length == 1) a = w.getUpperBounds()[0];
                return valueType(a);
            }
            return null;
        }
        return r;
    }

    private static Class<?> rawType(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return rawType(p.getRawType());
        return null;
    }

    /// Every element name in the index, sorted — for tests and diagnostics.
    public static synchronized List<String> elementNames() {
        if (byElement == null) build();
        return new ArrayList<>(new TreeSet<>(byElement.keySet()));
    }
}
