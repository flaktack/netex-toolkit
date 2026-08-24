package toolkit.model;

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.EntityStructure;
import noi.netex.model.VersionOfObjectRefStructure;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/// The reflective walk over the model beans — the READ side, whose write side is [NetexUtils].
///
/// Per-class [VarHandle] accessors are cached at first touch through a [ClassValue], and only
/// fields whose declared type can hold a model object are ever read: scalars are pruned when the
/// cache is built.
///
/// - a sub-object that IS a reference — any [VersionOfObjectRefStructure] subclass, i.e. anything
///    carrying a `ref` — is yielded as a reference and NOT recursed into;
/// - every other model-typed sub-object is visited and recursed; those carrying an id are the
///    embedded objects;
/// - `List` elements and `JAXBElement` wrappers are unwrapped transparently.
///
/// [#onlyReferences] names a target class from `nameOfRefClass` when set AND registered, else from
/// the reference class's simple name (`XxxRefStructure`/`XxxRef` → `Xxx`, which is what the schema
/// fixes that attribute's default to), else `DataManagedObject`. Version is the ref's own
/// attribute, null encoding as absent. Dedup is by (targetClassName, ref, version) in encounter
/// order.
public final class RecursiveAttributes {

    /// One outward reference extracted from an object tree.
    public record Ref(String ref, String version, String targetClassName, Class<?> targetClass,
                      VersionOfObjectRefStructure refObject) {}

    private RecursiveAttributes() {}

    /// The model package, EXACT — `getPackageName().equals(...)`, never a prefix test.
    ///
    /// This walk prunes the GML package — geometry carries no ids or refs, and the geo walk in
    /// [toolkit.geo.Projection] covers both packages on purpose. Model and GML share the
    /// `noi.netex.` root, so a PREFIX test shortened by one component would pull GML into the
    /// reference walk with no error.
    private static final String MODEL_PKG = ModelRegistry.MODEL_PKG;

    private static final ClassValue<VarHandle[]> FIELDS = new ClassValue<>() {
        @Override
        protected VarHandle[] computeValue(Class<?> type) {
            List<VarHandle> out = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                MethodHandles.Lookup lookup;
                try {
                    // both the model classes and this class live in the unnamed module, so a
                    // private lookup into the declaring class is permitted
                    lookup = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot obtain lookup for " + c, e);
                }
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    boolean interesting =
                            List.class.isAssignableFrom(t)
                                    || JAXBElement.class.isAssignableFrom(t)
                                    || t == Object.class
                                    || (t.getPackageName().equals(MODEL_PKG) && !t.isEnum());
                    if (!interesting) continue;
                    try {
                        out.add(lookup.unreflectVarHandle(f));
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("cannot access " + f, e);
                    }
                }
            }
            return out.toArray(new VarHandle[0]);
        }
    };

    /// Callback pair for one walk (references are not recursed into).
    public interface Visitor {
        void reference(VersionOfObjectRefStructure ref);

        void node(Object modelObject);
    }

    /// Walk all sub-objects of `root` (root itself is NOT visited), pre-order.
    public static void walk(Object root, Visitor visitor) {
        for (VarHandle vh : FIELDS.get(root.getClass())) {
            descend(vh.get(root), visitor);
        }
    }

    /// Generic node walk for transformer use: every model sub-object, references included.
    public static void walkNodes(Object root, Consumer<Object> consumer) {
        walk(root, new Visitor() {
            @Override
            public void reference(VersionOfObjectRefStructure ref) {
                consumer.accept(ref);
            }

            @Override
            public void node(Object o) {
                consumer.accept(o);
            }
        });
    }

    private static void descend(Object v, Visitor visitor) {
        if (v == null) return;
        if (v instanceof JAXBElement<?> je) {
            descend(je.getValue(), visitor);
            return;
        }
        if (v instanceof List<?> list) {
            for (Object x : list) descend(x, visitor);
            return;
        }
        Class<?> c = v.getClass();
        if (!c.getPackageName().equals(MODEL_PKG) || c.isEnum()) return;
        if (v instanceof VersionOfObjectRefStructure ref) {
            visitor.reference(ref);
            return; // references are leaves
        }
        visitor.node(v);
        for (VarHandle vh : FIELDS.get(c)) descend(vh.get(v), visitor);
    }

    /// Deduplicated (targetClassName, ref, version) triples, in encounter order.
    public static List<Ref> onlyReferences(Object root) {
        return refsAndEmbeddings(root, null).references();
    }

    /// Every reference object of the tree, NO dedup, in encounter order, with the target-class name
    /// derived by the SAME chain the insert path uses, so a key encoded from the returned tuple
    /// equals the stored unresolved row of an unmodified reference.
    public static List<Ref> referenceObjects(Object root) {
        List<Ref> out = new ArrayList<>();
        walk(root, new Visitor() {
            @Override
            public void reference(VersionOfObjectRefStructure r) {
                Ref ref = toRef(r);
                if (ref != null) out.add(ref);
            }

            @Override
            public void node(Object o) {}
        });
        return out;
    }

    private static Ref toRef(VersionOfObjectRefStructure r) {
        String refValue = r.getRef();
        if (refValue == null) return null; // tolerate and skip rather than assert
        String name = r.getNameOfRefClass();
        if (name == null || !ModelRegistry.isRegistered(name)) {
            name = deriveTargetName(r.getClass());
            if (!ModelRegistry.isRegistered(name)) name = "DataManagedObject";
        }
        return new Ref(refValue, r.getVersion(), name, ModelRegistry.classForName(name), r);
    }

    /// Combined result of the single-walk reference + scoped-embedding extraction.
    public record Extraction(List<Ref> references, List<EntityStructure> embedded) {}

    /// ONE walk yielding the dedup'd references (exactly what [#onlyReferences] yields) plus every
    /// embedded id-carrying entity whose class passes `embeddingScope`.
    public static Extraction refsAndEmbeddings(Object root, Predicate<Class<?>> embeddingScope) {
        Map<String, Ref> dedup = new LinkedHashMap<>();
        List<EntityStructure> embedded = new ArrayList<>();
        walk(root, new Visitor() {
            @Override
            public void reference(VersionOfObjectRefStructure r) {
                Ref ref = toRef(r);
                if (ref == null) return;
                String key = ref.targetClassName() + ' ' + ref.ref() + ' '
                        + (ref.version() == null ? "" : ref.version());
                dedup.putIfAbsent(key, ref);
            }

            @Override
            public void node(Object o) {
                if (embeddingScope != null && o instanceof EntityStructure e && e.getId() != null
                        && embeddingScope.test(o.getClass())) {
                    embedded.add(e);
                }
            }
        });
        return new Extraction(new ArrayList<>(dedup.values()), embedded);
    }

    /// XxxRefStructure / XxxRef -&gt; Xxx (the schema-fixed nameOfRefClass default).
    public static String deriveTargetName(Class<?> refClass) {
        String n = refClass.getSimpleName();
        if (n.endsWith("RefStructure")) return n.substring(0, n.length() - "RefStructure".length());
        if (n.endsWith("Ref")) return n.substring(0, n.length() - "Ref".length());
        return n;
    }

    /// Embedded sub-objects with an id whose class passes `scope` (root excluded) — the input of
    /// the embedding index and of resolveEmbeddings.
    public static List<EntityStructure> embeddedEntities(Object root, Predicate<Class<?>> scope) {
        List<EntityStructure> out = new ArrayList<>();
        walk(root, new Visitor() {
            @Override
            public void reference(VersionOfObjectRefStructure ref) {}

            @Override
            public void node(Object o) {
                if (o instanceof EntityStructure e && e.getId() != null && scope.test(o.getClass())) {
                    out.add(e);
                }
            }
        });
        return out;
    }
}
