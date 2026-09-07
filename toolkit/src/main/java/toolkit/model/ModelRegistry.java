package toolkit.model;

import noi.netex.gml.PolygonType;
import noi.netex.model.EntityStructure;
import toolkit.util.Log;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/// The registrable NeTEx model classes, discovered once per JVM from the model's code source.
///
/// Every class in [#MODEL_PKG] assignable to [EntityStructure] — everything that can carry an
/// `id` — registered under its simple name in sorted order, so a fresh database's idx assignment
/// is deterministic across runs and JVMs. The name space is the JAXB simple-name space, with
/// `DataManagedObject` aliased to `DataManagedObjectStructure` for the ref-class fallback.
///
/// PROV-CAP: netex-2.0-model — the marker's site rather than its subject, since the schema flip is
/// one line in `model/pom.xml` that the marker scan cannot see. [#classes()] is where the model jar
/// becomes the store's class table, feeding the `_class_idx` map a database writes at creation.
public final class ModelRegistry {
    /// The model package, derived from a class this toolkit imports rather than written out.
    ///
    /// Fed to [Class#forName] and to `JAXBContext.newInstance`. A literal disagreeing with the
    /// compile-time imports would neither fail the build nor throw: `classForName` would find
    /// nothing and every edge would land in `_unresolved`.
    public static final String MODEL_PKG = EntityStructure.class.getPackageName();

    /// The GML package, derived the same way. Excluded from the model walks.
    public static final String GML_PKG = PolygonType.class.getPackageName();

    public static final String NETEX_NS = "http://www.netex.org.uk/netex";

    private static List<Class<?>> classes;
    private static Map<String, Class<?>> byName;
    private static List<Class<?>> allModelClasses;
    private static Map<Class<?>, List<Class<?>>> concreteSubclasses;

    private ModelRegistry() {}

    /// All registrable classes, sorted by simple name.
    public static synchronized List<Class<?>> classes() {
        if (classes == null) scan();
        return classes;
    }

    /// Every class of the model package (nested classes included, enums excluded), not just the
    /// registrable [EntityStructure] subset — the traversal space of the containment index.
    public static synchronized List<Class<?>> allModelClasses() {
        if (allModelClasses == null) scan();
        return allModelClasses;
    }

    /// The concrete model classes assignable to `type` (type itself included when concrete) — the
    /// stand-in for the schema-expanded substitution unions, used by the containment index.
    public static synchronized List<Class<?>> concreteSubclasses(Class<?> type) {
        if (allModelClasses == null) scan();
        return concreteSubclasses.computeIfAbsent(type, t -> {
            List<Class<?>> out = new ArrayList<>();
            for (Class<?> c : allModelClasses) {
                if (t.isAssignableFrom(c) && !java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                    out.add(c);
                }
            }
            return out;
        });
    }

    /// name -> class ('DataManagedObject' aliased to DataManagedObjectStructure); null if unknown.
    public static Class<?> classForName(String name) {
        classes();
        return byName.get(name);
    }

    public static boolean isRegistered(String name) {
        return classForName(name) != null;
    }

    private static void scan() {
        try {
            List<Class<?>> out = new ArrayList<>(2200);
            List<Class<?>> all = new ArrayList<>(4096);
            Map<String, Class<?>> names = new HashMap<>(4096);
            ClassLoader cl = ModelRegistry.class.getClassLoader();
            List<String> unloadable = new ArrayList<>();
            for (String n : modelClassResources()) {
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                Class<?> c;
                try {
                    c = Class.forName(cn, false, cl);
                } catch (Throwable t) {
                    // Not a lookup miss: the name came from a .class resource just enumerated out
                    // of the model's own code source, so this is a classpath problem. Swallowing it
                    // resurfaces three layers away as the loader's `unmapped` on a valid feed.
                    unloadable.add(cn + ": " + t);
                    Log.warn("[model_registry] %s will not load from the model code source: %s",
                            cn, t);
                    continue;
                }
                if (!c.isEnum() && !c.isInterface()) all.add(c);
                if (n.contains("$")) continue;
                if (!EntityStructure.class.isAssignableFrom(c)) continue;
                out.add(c);
                names.put(c.getSimpleName(), c);
            }
            refuseUnloadable(unloadable);
            out.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
            // DataManagedObject (the ultimate ref fallback) has no JAXB twin.
            names.putIfAbsent("DataManagedObject",
                    Class.forName(MODEL_PKG + ".DataManagedObjectStructure", false, cl));
            classes = Collections.unmodifiableList(out);
            byName = names;
            all.sort((a, b) -> a.getName().compareTo(b.getName()));
            allModelClasses = Collections.unmodifiableList(all);
            concreteSubclasses = new HashMap<>();
        } catch (Exception e) {
            throw new IllegalStateException("cannot scan netex model code source", e);
        }
    }

    /// Refuse a scan that could not load every class it found — after the whole walk, so the
    /// message names all of them rather than whichever sorted first.
    ///
    /// Fatal, with no opt-in: a class dropped here shifts every idx assigned after it, leaving a
    /// store that is internally consistent, silently smaller than the model, and not comparable with
    /// any store built on a healthy classpath.
    ///
    /// Exposed rather than private so a test can exercise the refusal.
    public static void refuseUnloadable(List<String> unloadable) {
        if (unloadable.isEmpty()) return;
        throw new IllegalStateException(String.format(
                "%,d class file(s) in %s could not be loaded from the model's own code source. "
                + "The class table this feeds is written once into a fresh database, so a partial "
                + "scan silently shifts every class idx and the loader reports the missing types "
                + "as unmapped elements. Fix the classpath: %s",
                unloadable.size(), MODEL_PKG, unloadable));
    }

    /// Every `.class` resource of [#MODEL_PKG], as `a/b/C.class` paths, sorted.
    ///
    /// The code source is a jar off `target/deps` but a directory under a reactor build, an IDE or
    /// an exploded classpath; both are handled. Sorting makes the two enumerations agree byte for
    /// byte.
    private static List<String> modelClassResources() throws IOException, java.net.URISyntaxException {
        String prefix = MODEL_PKG.replace('.', '/') + "/";
        URI src = EntityStructure.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path p = Paths.get(src);
        List<String> outs = new ArrayList<>(4096);
        if (Files.isDirectory(p)) {
            Path pkgDir = p.resolve(prefix);
            if (Files.isDirectory(pkgDir)) {
                try (Stream<Path> s = Files.walk(pkgDir)) {
                    s.filter(f -> f.getFileName().toString().endsWith(".class"))
                            .forEach(f -> outs.add(p.relativize(f).toString().replace(java.io.File.separatorChar, '/')));
                }
            }
        } else {
            try (JarFile jar = new JarFile(p.toFile())) {
                Enumeration<JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (n.startsWith(prefix) && n.endsWith(".class")) outs.add(n);
                }
            }
        }
        Collections.sort(outs);
        return outs;
    }
}
