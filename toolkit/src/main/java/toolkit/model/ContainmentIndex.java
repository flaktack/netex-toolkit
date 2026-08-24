package toolkit.model;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementRefs;
import jakarta.xml.bind.annotation.XmlElements;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The STATIC type-level INVERSE containment index: for each missing class M, which model classes
/// can transitively contain an M as an attribute. The embedding pass uses it to pick the candidate
/// container classes worth scanning.
///
/// The beans hide many substitution-union members behind `List<JAXBElement<?>>` fields, so the
/// walk combines three sources per field:
///
/// - the generic signature — parameterized args, wildcard upper bounds, arrays;
/// - `@XmlElements`: each declared member `type()`;
/// - `@XmlElementRef(s)`: the element name resolved through [ModelRegistry], NeTEx element names
///    being the model class simple names.
///
/// A discovered ABSTRACT class expands to its concrete subclasses, standing in for the
/// schema-expanded union; concrete classes are taken as-is. References ARE contained types.
public final class ContainmentIndex {

    private ContainmentIndex() {}

    /// The model package, EXACT — `getPackageName().equals(...)`, never a prefix test.
    ///
    /// The walk must exclude GML: geometry carries no ids or refs and is not part of the
    /// containment shape. Model and GML share the `noi.netex.` root, so a PREFIX test shortened by
    /// one component would pull every GML class into the walk with no error anywhere.
    private static final String MODEL_PKG = ModelRegistry.MODEL_PKG;

    /// Per-class DIRECT containment contributions (field-level, expansion applied).
    private static final ClassValue<Set<Class<?>>> DIRECT = new ClassValue<>() {
        @Override
        protected Set<Class<?>> computeValue(Class<?> type) {
            Set<Class<?>> out = new LinkedHashSet<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    harvestType(f.getGenericType(), out);
                    XmlElements els = f.getAnnotation(XmlElements.class);
                    if (els != null) {
                        for (XmlElement e : els.value()) addWithExpansion(e.type(), out);
                    }
                    XmlElement el = f.getAnnotation(XmlElement.class);
                    if (el != null) addWithExpansion(el.type(), out);
                    XmlElementRef er = f.getAnnotation(XmlElementRef.class);
                    if (er != null) addByElementName(er.name(), out);
                    XmlElementRefs ers = f.getAnnotation(XmlElementRefs.class);
                    if (ers != null) {
                        for (XmlElementRef e : ers.value()) addByElementName(e.name(), out);
                    }
                }
            }
            return out;
        }
    };

    private static void harvestType(Type t, Set<Class<?>> out) {
        if (t instanceof Class<?> c) {
            addWithExpansion(c, out);
        } else if (t instanceof ParameterizedType p) {
            if (p.getRawType() instanceof Class<?> raw) addWithExpansion(raw, out);
            for (Type a : p.getActualTypeArguments()) harvestType(a, out);
        } else if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) harvestType(b, out);
        } else if (t instanceof GenericArrayType g) {
            harvestType(g.getGenericComponentType(), out);
        }
    }

    private static void addWithExpansion(Class<?> c, Set<Class<?>> out) {
        if (c == null || !isModel(c)) return;
        if (Modifier.isAbstract(c.getModifiers())) {
            out.addAll(ModelRegistry.concreteSubclasses(c));
        } else {
            out.add(c);
        }
    }

    private static void addByElementName(String name, Set<Class<?>> out) {
        if (name == null || name.isEmpty()) return;
        Class<?> c = ModelRegistry.classForName(name);
        if (c != null) addWithExpansion(c, out);
    }

    private static boolean isModel(Class<?> c) {
        return c.getPackageName().equals(MODEL_PKG) && !c.isEnum() && !c.isInterface()
                && c != JAXBElement.class;
    }

    /// For each class of `scopeClasses` (the missing classes),
    /// the subset of `classes` (the db's classes) that can transitively contain it.
    public static Map<Class<?>, Set<Class<?>>> collect(Iterable<Class<?>> classes, Set<Class<?>> scopeClasses) {
        Map<Class<?>, Set<Class<?>>> index = new HashMap<>();
        Map<Class<?>, Set<Class<?>>> closureCache = new HashMap<>();
        for (Class<?> clazz : classes) {
            if (!isModel(clazz) || Modifier.isAbstract(clazz.getModifiers())) continue;
            Set<Class<?>> contained = closureCache.computeIfAbsent(clazz, ContainmentIndex::closure);
            for (Class<?> m : scopeClasses) {
                if (contained.contains(m)) {
                    index.computeIfAbsent(m, k -> new HashSet<>()).add(clazz);
                }
            }
        }
        return index;
    }

    /// Transitive containment closure of one class (the class itself excluded unless self-contained).
    private static Set<Class<?>> closure(Class<?> root) {
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        for (Class<?> c : DIRECT.get(root)) {
            if (seen.add(c)) stack.push(c);
        }
        while (!stack.isEmpty()) {
            Class<?> c = stack.pop();
            for (Class<?> d : DIRECT.get(c)) {
                if (seen.add(d)) stack.push(d);
            }
        }
        return seen;
    }

}
