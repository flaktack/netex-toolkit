package toolkit.codec;

import jakarta.xml.bind.Marshaller;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Marshal-time omit-empty / omit-default: a field whose value equals its declared default is
/// never written (PROV-CAP: schema-defaults).
///
/// JAXB marshals whatever is in the field, and two shapes then write an element or attribute the
/// source never had:
///
/// - Empty collections. An `@XmlList` field emits an element when its list is non-null and empty
///    (`<DaysOfWeek></DaysOfWeek>`); the empty list arrives either from a create-on-get accessor
///    or straight from the source parse.
/// - Scalars equal to their schema default. xjc bakes an XSD `default` into the getter, so the
///    field only acquires the value when something copies getter&rarr;field — which
///    [toolkit.model.NetexUtils#project] does — or when the source spells it out.
///
/// KNOWN GAP, uncoverable: `@XmlElement(defaultValue=...)` on list fields, e.g.
/// `<HolidayTypes>AnyDay</HolidayTypes>`. That divergence is created at unmarshal — an empty source
/// element becomes `[ANY_DAY]` — so afterwards an empty source and one that genuinely says `AnyDay`
/// are indistinguishable in the bean. The empty-list rule leaves it alone because `[ANY_DAY]` is
/// not empty.
public final class SchemaDefaults {

    /// Install on any [Marshaller] that must reproduce the omission rule.
    /// [FragmentCodec#marshalFragment] does; `beforeMarshal` fires for every bean in the tree, so
    /// one listener covers the whole fragment.
    public static final Marshaller.Listener LISTENER = new Marshaller.Listener() {
        @Override
        public void beforeMarshal(Object source) {
            normalize(source);
        }
    };

    /// A field that must be cleared when it holds its declared default.
    ///
    /// @param field       the JAXB-mapped field, already `setAccessible(true)`
    /// @param emptyList   true when the default is "the empty collection" (a repeated element)
    /// @param defaultValue the scalar default; `null` iff `emptyList`
    private record Rule(Field field, boolean emptyList, Object defaultValue) {}

    private static final Rule[] NONE = new Rule[0];

    /// Per-class rule table, derived once per class from a virgin instance: a field is "defaulted"
    /// exactly when it is null on the virgin and its accessor nevertheless returns non-null, which
    /// is the xjc contract for an XSD default and for a repeated element.
    private static final ClassValue<Rule[]> RULES = new ClassValue<>() {
        @Override
        protected Rule[] computeValue(Class<?> type) {
            return derive(type);
        }
    };

    private SchemaDefaults() {}

    /// Clear every field of `bean` that holds its declared default. Shallow: the listener
    /// calls it once per bean, so JAXB's traversal supplies the recursion.
    public static void normalize(Object bean) {
        if (bean == null) return;
        Rule[] rules = RULES.get(bean.getClass());
        for (Rule r : rules) {
            try {
                Object v = r.field().get(bean);
                if (v == null) continue;
                if (r.emptyList()) {
                    if (v instanceof Collection<?> c && c.isEmpty()) r.field().set(bean, null);
                } else if (r.defaultValue().equals(v)) {
                    r.field().set(bean, null);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("SchemaDefaults: cannot normalise "
                        + bean.getClass().getName() + "." + r.field().getName(), e);
            }
        }
    }

    /// The derived rule table for a class, as `fieldName -> default` ("[]" for a list).
    public static Map<String, Object> rulesFor(Class<?> clazz) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Rule r : RULES.get(clazz)) {
            out.put(r.field().getName(), r.emptyList() ? "[]" : r.defaultValue());
        }
        return out;
    }

    // ---------------------------------------------------------------- derivation

    private static Rule[] derive(Class<?> clazz) {
        if (!clazz.getName().startsWith("noi.netex.")) return NONE;
        Object virgin;
        try {
            var ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            virgin = ctor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return NONE; // abstract, or no usable no-arg constructor: nothing to probe
        }

        Map<String, Method> accessors = accessors(clazz);
        List<Rule> rules = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getName().startsWith("noi.netex.")) break;
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                Method get = accessors.get(f.getName());
                if (get == null) continue;
                try {
                    f.setAccessible(true);
                    // Probe order is load-bearing: read the field first, because a create-on-get
                    // accessor assigns it as a side effect and asking afterwards always says
                    // "non-null". A field already non-null on a virgin instance is an initialiser,
                    // not a schema default, and must not be stripped.
                    if (f.get(virgin) != null) continue;
                    Object dflt = get.invoke(virgin);
                    if (dflt == null) continue;
                    // The accessor is matched by property name, so require that what it returned
                    // could have come out of this field: a shadowed or coincidentally-named
                    // accessor then yields no rule rather than a wrong one.
                    if (!f.getType().isInstance(dflt)) continue;
                    if (dflt instanceof Collection<?> col) {
                        if (!col.isEmpty()) continue; // not a "default = empty" shape
                        rules.add(new Rule(f, true, null));
                    } else {
                        rules.add(new Rule(f, false, dflt));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // An accessor that cannot be probed yields no rule; the failure direction is
                    // "never strip".
                }
            }
        }
        return rules.isEmpty() ? NONE : rules.toArray(new Rule[0]);
    }

    /// `propertyName -> zero-arg accessor`, keyed the way xjc names fields: `getDaysOfWeek` &rarr;
    /// `daysOfWeek`, `isIsAvailable` &rarr; `isAvailable`.
    private static Map<String, Method> accessors(Class<?> clazz) {
        Map<String, Method> map = new HashMap<>();
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String n = m.getName();
            String prop;
            if (n.startsWith("get") && n.length() > 3) prop = n.substring(3);
            else if (n.startsWith("is") && n.length() > 2) prop = n.substring(2);
            else continue;
            map.putIfAbsent(Introspector.decapitalize(prop), m);
        }
        return map;
    }
}
