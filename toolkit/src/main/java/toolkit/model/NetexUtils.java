package toolkit.model;

// The reflective id/ref/projection MECHANISM over the JAXB beans. Nothing here names a class, a
// rule, a profile or a vendor: the only NeTEx-specific knowledge is the model's naming convention.
// The WRITE side of the reflection whose read side is RecursiveAttributes.
//
// Profile config stays out: a codespace and a version are chosen by a profile, so the sugar that
// binds them lives in toolkit.transform.epip.
//
// NAME MAPPING: an object's XML element name IS its class simple name. The whole toolkit relies on it.

import noi.netex.model.Codespace;
import noi.netex.model.OrderedVersionOfObjectRefStructure;
import noi.netex.model.VersionOfObjectRefStructure;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NetexUtils {

    // Taken from ModelRegistry rather than written as a literal: refClassOf() feeds it to
    // Class.forName, so a spelling that disagreed with the compiled imports would resolve nothing
    // with no build signal. Trailing dot because every use concatenates a simple name onto it.
    private static final String MODEL_PKG = ModelRegistry.MODEL_PKG + ".";

    private NetexUtils() {}

    // ------------------------------------------------------------------ getId

    /// getId: {@code "{codespace.xmlns}:{ClassName}:{id with ':' replaced by '-'}"}.
    /// EVERY colon in the local id is sanitized to a dash (so composed ids like
    /// `"IT:x:1"` become one local token `"IT-x-1"`); the class name is the model class's
    /// SIMPLE name, not its binary name. Callers stringify the id.
    public static String getId(Codespace codespace, Class<?> clazz, String id) {
        return codespace.getXmlns() + ":" + clazz.getSimpleName() + ":" + id.replace(":", "-");
    }

    // ------------------------------------------------------------- getRef / getFakeRef

    /// A ref built from a bare id string, with no source object: `ref=id`, `version=version`
    /// (nullable).
    public static <T extends VersionOfObjectRefStructure> T getFakeRef(
            String id, Class<T> refClass, String version) {
        if (id == null) throw new IllegalArgumentException("A reference must start with a valid id");
        T ref = newRef(refClass);
        ref.setRef(id);
        ref.setVersion(version);
        return ref;
    }

    /// The ref class derived from the object's class name: the model spells its ref beans `XxxRef`
    /// or, usually, `XxxRefStructure`, and both are tried in that order.
    ///
    /// MEMOISED per object class — the `XxxRef` miss costs a full classloader delegation plus a
    /// `ClassNotFoundException`. The probe ORDER must not be swapped; see [#refClassOf].
    public static VersionOfObjectRefStructure getRef(Object obj) {
        Class<?> objClass = obj.getClass();
        Object looked = REF_CLASSES.get(objClass);
        if (looked == null) looked = REF_CLASSES.computeIfAbsent(objClass, NetexUtils::refClassOf);
        if (looked instanceof ClassNotFoundException e2) {
            throw new IllegalArgumentException(
                    "No ref class for " + objClass.getSimpleName(), e2);
        }
        Class<?> refClass = (Class<?>) looked;
        @SuppressWarnings("unchecked")
        Class<? extends VersionOfObjectRefStructure> rc =
                (Class<? extends VersionOfObjectRefStructure>) refClass;
        return getRef(obj, rc);
    }

    /// getRef: build a `refClass` reference to `obj`. Semantics, in order:
    ///
    /// - `ref` = the object's `id` if it has one (an id-bearing entity), else its
    ///    `ref` (making a ref from a ref); either must be non-null; neither present →
    ///    error ("Object does not have an id or ref").
    /// - `order` is copied ONLY when both sides have it (Java: the ref is an
    ///    [OrderedVersionOfObjectRefStructure] and the object has `getOrder`).
    /// - `version` is always assigned: the object's version, or null when absent
    ///    (every VersionOfObjectRefStructure has the attribute).
    /// - `nameOfRefClass` is set ONLY when the ref-class name does not prefix-match the object's:
    ///    `UicOperatingPeriod` → `OperatingPeriodRef` carries it, `Operator` → `OperatorRef` does
    ///    not. The object name is its simple name with a trailing "RefStructure" shortened to
    ///    "Ref" — the ref-of-a-ref rule.
    public static <T extends VersionOfObjectRefStructure> T getRef(Object obj, Class<T> refClass) {
        if (obj == null) throw new IllegalArgumentException("A reference must be made from an existing object.");

        T ref = newRef(refClass);
        Method getId = method(obj.getClass(), "getId");
        if (getId != null) {
            String id = (String) invoke(getId, obj);
            if (id == null) throw new IllegalStateException("Object does not have an id");
            ref.setRef(id);
        } else {
            Method getRef = method(obj.getClass(), "getRef");
            if (getRef == null) throw new IllegalArgumentException("Object does not have an id or ref");
            String r = (String) invoke(getRef, obj);
            if (r == null) throw new IllegalStateException("Object does not have a ref");
            ref.setRef(r);
        }

        Method getOrder = method(obj.getClass(), "getOrder");
        if (ref instanceof OrderedVersionOfObjectRefStructure ordered && getOrder != null) {
            ordered.setOrder((BigInteger) invoke(getOrder, obj));
        }

        Method getVersion = method(obj.getClass(), "getVersion");
        ref.setVersion(getVersion != null ? (String) invoke(getVersion, obj) : null);

        String name = obj.getClass().getSimpleName();
        if (name.endsWith("RefStructure")) name = name.replace("RefStructure", "Ref");
        if (!refClass.getSimpleName().startsWith(name)) ref.setNameOfRefClass(name);
        return ref;
    }

    // ---------------------------------------------------------------- project

    /// project: a new `targetClass` instance populated from `source`, then
    /// `overrides` applied on top. Copy rules:
    ///
    /// - Iterate the TARGET's properties — setters, plus getter-only [List]s — and pull the
    ///    same-named value from the source where it has one. JAXBElement and List values copy by
    ///    REFERENCE, aliasing included; a List property has no setter, so its ELEMENTS are aliased
    ///    through addAll, one level shallower than aliasing the list itself.
    /// - Target properties the source lacks stay null.
    /// - id rewrite: when the target has an id, `":SourceClass:"` in the copied value becomes
    ///    `":TargetClass:"`. A plain substring replace, and a no-op when the id does not carry the
    ///    source class name.
    /// - `overrides` keys are bean property names; an unknown key throws. Values go through the
    ///    same setter path, null allowed.
    /// - A source value whose runtime type does not fit the target setter is SKIPPED and left
    ///    null; the call site must set the field itself.
    public static <T> T project(Object source, Class<T> targetClass, Map<String, Object> overrides) {
        T target;
        try {
            target = targetClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot instantiate " + targetClass.getName(), e);
        }

        // Target setters by property name (decapitalized, e.g. setIsAvailable -> isAvailable), and
        // the property walk order — computed ONCE per target class.
        TargetPlan plan = plan(targetClass);
        Map<String, Method> setters = plan.setters();
        Class<?> sourceClass = source.getClass();

        // Writable properties: copy the same-named source value where types allow.
        for (int i = 0; i < plan.writable().length; i++) {
            Method setter = plan.writable()[i];
            Method getter = sourceGetter(sourceClass, plan.writableSuffix()[i]);
            if (getter == null) continue;
            Object value = invoke(getter, source);
            if (value == null) continue;                       // fresh bean fields are already null
            Class<?> param = plan.writableParam()[i];
            if (param.isInstance(value)) invoke(setter, target, value);
            // else: statically-untypeable copy — skipped (see Javadoc).
        }

        // Getter-only List properties: element-aliasing copy.
        for (int i = 0; i < plan.listGetters().length; i++) {
            Method m = plan.listGetters()[i];
            Method getter = sourceGetter(sourceClass, plan.listSuffix()[i]);
            if (getter == null || !List.class.isAssignableFrom(getter.getReturnType())) continue;
            List<?> src = (List<?>) invoke(getter, source);
            if (src != null && !src.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Object> dst = (List<Object>) invoke(m, target);
                dst.addAll(src);
            }
        }

        // id rewrite :SourceClass: -> :TargetClass:.
        Method setId = setters.get("id");
        Method getIdT = method(targetClass, "getId");
        if (setId != null && getIdT != null) {
            String id = (String) invoke(getIdT, target);
            if (id != null) {
                invoke(setId, target, id.replace(
                        ":" + source.getClass().getSimpleName() + ":",
                        ":" + targetClass.getSimpleName() + ":"));
            }
        }

        // Overrides through the same setter path.
        if (overrides != null) {
            for (Map.Entry<String, Object> e : overrides.entrySet()) {
                Method setter = setters.get(e.getKey());
                if (setter == null) {
                    throw new IllegalArgumentException(
                            "No property '" + e.getKey() + "' on " + targetClass.getSimpleName());
                }
                invoke(setter, target, e.getValue());
            }
        }
        return target;
    }

    /// [Class, Map)][#project(Object,] without overrides.
    public static <T> T project(Object source, Class<T> targetClass) {
        return project(source, targetClass, null);
    }

    // project() copies JAXB's DEFAULTED GETTERS into real target FIELDS, so a projected bean would
    // marshal attributes that must never reach the wire. That is enforced at MARSHAL time instead;
    // see toolkit/codec/SchemaDefaults.

    // ---------------------------------------------------------------- plumbing

    // ---- reflection caches. PERF ONLY: every lookup returns what the uncached call returned.
    // Nothing here calls setAccessible, so handing out the SHARED Method rather than getMethod's
    // fresh copy is unobservable; ConcurrentHashMaps publish it safely.

    /// Stored for "no such public zero-arg method" — the NEGATIVE result is cached too.
    private static final Method NO_SUCH_METHOD;
    static {
        try {
            NO_SUCH_METHOD = NetexUtils.class.getDeclaredMethod("noSuchMethodSentinel");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// Never invoked — only its [Method] object is, as the negative-cache sentinel.
    private static void noSuchMethodSentinel() {}

    /// class -> (method name -> Method | NO_SUCH_METHOD).
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> METHODS =
            new ConcurrentHashMap<>();

    /// class -> (property suffix -> getX/isX Method | NO_SUCH_METHOD) — one lookup per call.
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> SOURCE_GETTERS =
            new ConcurrentHashMap<>();

    /// object class -> ref Class, or the ClassNotFoundException that stands for "none".
    private static final ConcurrentHashMap<Class<?>, Object> REF_CLASSES = new ConcurrentHashMap<>();

    /// target class -> the [#project] reflection plan (two getMethods() table copies).
    private static final ConcurrentHashMap<Class<?>, TargetPlan> TARGET_PLANS = new ConcurrentHashMap<>();

    /// The `XxxRef` / `XxxRefStructure` probe behind [#getRef(Object)], run once
    /// per object class.
    ///
    /// THE ORDER IS NOT SWAPPED. A number of model classes carry BOTH names, and in every one
    /// `XxxRef extends XxxRefStructure`, so trying `RefStructure` first would hand back the
    /// SUPERtype — a different XML element, and a ClassCastException at any call site casting to
    /// `XxxRef`.
    ///
    /// Returns the resolved [Class], or the `RefStructure` [ClassNotFoundException] to stand as
    /// the cause of the caller's IllegalArgumentException.
    ///
    /// NOT [ModelRegistry#classForName]: the registry holds only [noi.netex.model.EntityStructure]
    /// subclasses, and `VersionOfObjectRefStructure` extends nothing, so every name this probe
    /// looks for comes back null there. The two name spaces are disjoint by construction.
    private static Object refClassOf(Class<?> objClass) {
        String base = MODEL_PKG + objClass.getSimpleName();
        try {
            return Class.forName(base + "Ref");
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(base + "RefStructure");
            } catch (ClassNotFoundException e2) {
                return e2;
            }
        }
    }

    /// The per-target-class reflection plan behind [#project]: the setter map plus the two
    /// property walks, with each walk's setter/getter, its `get`/`is` suffix and (for
    /// the writable walk) its parameter type pre-extracted. The walks are order-independent: one
    /// property each.
    private record TargetPlan(Map<String, Method> setters,
                              Method[] writable, String[] writableSuffix, Class<?>[] writableParam,
                              Method[] listGetters, String[] listSuffix) {}

    private static TargetPlan plan(Class<?> targetClass) {
        TargetPlan p = TARGET_PLANS.get(targetClass);
        if (p == null) p = TARGET_PLANS.computeIfAbsent(targetClass, NetexUtils::buildPlan);
        return p;
    }

    private static TargetPlan buildPlan(Class<?> targetClass) {
        Map<String, Method> setters = new HashMap<>();
        for (Method m : targetClass.getMethods()) {
            if (m.getName().startsWith("set") && m.getParameterCount() == 1) {
                setters.put(decap(m.getName().substring(3)), m);
            }
        }
        List<Method> writable = new ArrayList<>();
        List<String> writableSuffix = new ArrayList<>();
        List<Class<?>> writableParam = new ArrayList<>();
        for (Map.Entry<String, Method> e : setters.entrySet()) {
            writable.add(e.getValue());
            writableSuffix.add(e.getValue().getName().substring(3));
            writableParam.add(e.getValue().getParameterTypes()[0]);
        }
        List<Method> listGetters = new ArrayList<>();
        List<String> listSuffix = new ArrayList<>();
        for (Method m : targetClass.getMethods()) {
            if (!m.getName().startsWith("get") || m.getParameterCount() != 0) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            if (setters.containsKey(decap(m.getName().substring(3)))) continue;
            listGetters.add(m);
            listSuffix.add(m.getName().substring(3));
        }
        return new TargetPlan(Collections.unmodifiableMap(setters),
                writable.toArray(new Method[0]),
                writableSuffix.toArray(new String[0]),
                writableParam.toArray(new Class<?>[0]),
                listGetters.toArray(new Method[0]),
                listSuffix.toArray(new String[0]));
    }

    private static <T> T newRef(Class<T> refClass) {
        try {
            return refClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot instantiate ref class " + refClass.getName(), e);
        }
    }

    /// Public zero-arg method, or null.
    /// Memoised per (class, name), NEGATIVES INCLUDED.
    ///
    /// Public only for the CACHE, so a caller doing its own reflective property lookup shares this
    /// one table. Its one caller, [toolkit.transform.epip.TimetabledPassingTimesProfile], is in a
    /// different package.
    public static Method method(Class<?> clazz, String name) {
        ConcurrentHashMap<String, Method> byName = METHODS.get(clazz);
        if (byName == null) byName = METHODS.computeIfAbsent(clazz, c -> new ConcurrentHashMap<>());
        Method m = byName.get(name);
        if (m == null) {
            try {
                m = clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                m = NO_SUCH_METHOD;
            }
            byName.put(name, m);
        }
        return m == NO_SUCH_METHOD ? null : m;
    }

    /// Source getter for a property suffix: getX, then isX (JAXB Boolean getters use isX).
    /// Memoised on the RESOLVED pair so the common case is ONE map lookup.
    private static Method sourceGetter(Class<?> clazz, String suffix) {
        ConcurrentHashMap<String, Method> bySuffix = SOURCE_GETTERS.get(clazz);
        if (bySuffix == null) {
            bySuffix = SOURCE_GETTERS.computeIfAbsent(clazz, c -> new ConcurrentHashMap<>());
        }
        Method m = bySuffix.get(suffix);
        if (m == null) {
            m = method(clazz, "get" + suffix);
            if (m == null) m = method(clazz, "is" + suffix);
            bySuffix.put(suffix, m != null ? m : NO_SUCH_METHOD);
            return m;
        }
        return m == NO_SUCH_METHOD ? null : m;
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static Object invoke(Method m, Object obj, Object... args) {
        try {
            return m.invoke(obj, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(m + " failed", e);
        }
    }
}
