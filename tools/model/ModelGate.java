package model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementRefs;
import jakarta.xml.bind.annotation.XmlElements;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.annotation.XmlList;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlSchema;
import jakarta.xml.bind.annotation.XmlSchemaType;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.XmlValue;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/// Pre-flight probe for a NeTEx model swap. REFLECTION ONLY: this file imports no class of the
/// model or of the toolkit, so one source runs unchanged against either model jar — the model
/// package is a command-line argument.
///
/// Every subcommand writes ONE stable, sorted TSV. Model and GML type names are normalised to `@`
/// and `%` prefixes, so the baselines are package-independent: a pure package rename must move zero
/// bytes in every table, `ns` included — that one is about the XML namespace, not the Java package.
///
/// ```
/// ../bin/java.sh -cp "toolkit/target/netex-toolkit.jar:toolkit/target/deps/*" \
///     scratch/modelgate/ModelGate.java <cmd> [--pkg=...] [--gmlpkg=...] [--out=...]
/// ```
public final class ModelGate {

    // ---------------------------------------------------------------- configuration

    static String PKG = "noi.netex.model";
    static String GMLPKG = "net.opengis.gml._3";
    static Path OUT = Paths.get("scratch/modelgate/base");
    static Path SETS = Paths.get("toolkit/src/main/resources/toolkit/sets");
    static Path USED = Paths.get("scratch/modelgate/used_names.txt");

    /// GeoClasses.GEO_FIELD_TYPES, by simple name: 2 model + 3 GML.
    static final String[] GEO_MODEL = {"LocationStructure", "SimplePoint_VersionStructure"};
    static final String[] GEO_GML = {"LineStringType", "PolygonType", "MultiSurfaceType"};

    /// The GML types the toolkit names anywhere.
    static final String[] PORT_GML = {
            "AbstractRingPropertyType", "DirectPositionListType", "DirectPositionType",
            "LineStringType", "LinearRingType", "MultiSurfaceType", "PolygonType",
            "SurfacePropertyType"};

    /// AbstractLmdbStore.EMBEDDING_INDEX_CLASS_NAMES, verbatim.
    static final String[] EMBEDDING_ALLOWLIST = {
            "Quay", "StopPointInJourneyPattern", "RouteView", "Level", "Entrance",
            "PointOnRoute", "BoardingPosition", "FlexibleArea", "HailAndRideArea"};

    // ---------------------------------------------------------------- model scan state

    static ClassLoader CL = ModelGate.class.getClassLoader();
    /// Every class of PKG in jar-entry / directory-walk order (nested included).
    static List<Class<?>> RAW = new ArrayList<>();
    /// ModelRegistry.allModelClasses(): non-enum, non-interface, sorted by binary name.
    static List<Class<?>> ALL = new ArrayList<>();
    /// ModelRegistry.classes(): top-level EntityStructure subtypes, sorted by simple name.
    static List<Class<?>> REGISTRY = new ArrayList<>();
    /// ModelRegistry byName, DataManagedObject alias included.
    static Map<String, Class<?>> BY_NAME = new HashMap<>();
    /// AbstractLmdbStore.assignIdx(): simpleName -> idx, in registry order.
    static LinkedHashMap<String, Integer> IDX = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--pkg=")) PKG = a.substring(6);
            else if (a.startsWith("--gmlpkg=")) GMLPKG = a.substring(9);
            else if (a.startsWith("--out=")) OUT = Paths.get(a.substring(6));
            else if (a.startsWith("--sets=")) SETS = Paths.get(a.substring(7));
            else if (a.startsWith("--used=")) USED = Paths.get(a.substring(7));
            else pos.add(a);
        }
        if (pos.isEmpty()) {
            System.err.println("usage: ModelGate <ns|elements|registry|names|refs|geo|embed|contain"
                    + "|census|ctx|classtable <db>|verify|all> [--pkg=] [--gmlpkg=] [--out=]");
            System.exit(2);
        }
        Files.createDirectories(OUT);
        String cmd = pos.get(0);
        if (!cmd.equals("classtable")) scan();
        switch (cmd) {
            case "ns" -> ns();
            case "elements" -> elements();
            case "registry" -> registry();
            case "names" -> names();
            case "refs" -> refs();
            case "geo" -> geo();
            case "embed" -> embed();
            case "contain" -> contain();
            case "census" -> census();
            case "ctx" -> ctx();
            case "classtable" -> {
                scan();
                if (pos.size() < 2) throw new IllegalArgumentException("classtable needs a db path");
                for (int i = 1; i < pos.size(); i++) classtable(Paths.get(pos.get(i)));
            }
            case "verify" -> verify();
            case "all" -> {
                ns(); elements(); registry(); names(); refs(); geo(); embed();
                contain(); census(); ctx(); verify();
            }
            default -> throw new IllegalArgumentException("unknown subcommand " + cmd);
        }
    }

    // ================================================================ scanning (ModelRegistry port)

    static Class<?> anchor(String pkg) {
        for (String n : new String[] {"ObjectFactory", "EntityStructure", "package-info"}) {
            try {
                return Class.forName(pkg + "." + n, false, CL);
            } catch (Throwable ignored) {
                // try next anchor
            }
        }
        throw new IllegalStateException("cannot anchor package " + pkg + " on the classpath");
    }

    /// Binary names of every class file under `pkg` (subpackages included, as the jar scan does).
    static List<String> classFileNames(String pkg) throws Exception {
        URI src = anchor(pkg).getProtectionDomain().getCodeSource().getLocation().toURI();
        Path p = Paths.get(src);
        String prefix = pkg.replace('.', '/') + "/";
        List<String> out = new ArrayList<>();
        if (Files.isDirectory(p)) { // target/classes layout -- the jar assumption of ModelRegistry.scan()
            Path root = p.resolve(pkg.replace('.', '/'));
            if (Files.isDirectory(root)) {
                try (var s = Files.walk(root)) {
                    s.filter(f -> f.toString().endsWith(".class"))
                            .map(f -> p.relativize(f).toString())
                            .sorted()
                            .forEach(n -> out.add(n.substring(0, n.length() - 6).replace('/', '.')));
                }
            }
        } else {
            try (JarFile jar = new JarFile(p.toFile())) {
                Enumeration<JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (!n.startsWith(prefix) || !n.endsWith(".class")) continue;
                    out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            }
        }
        return out;
    }

    static void scan() throws Exception {
        Class<?> entity = Class.forName(PKG + ".EntityStructure", false, CL);
        for (String cn : classFileNames(PKG)) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, CL);
            } catch (Throwable t) {
                continue;
            }
            RAW.add(c);
            if (!c.isEnum() && !c.isInterface()) ALL.add(c);
            if (cn.contains("$")) continue;
            if (!entity.isAssignableFrom(c)) continue;
            REGISTRY.add(c);
            BY_NAME.put(c.getSimpleName(), c);
        }
        REGISTRY.sort(Comparator.comparing(Class::getSimpleName));
        ALL.sort(Comparator.comparing(Class::getName));
        try {
            BY_NAME.putIfAbsent("DataManagedObject",
                    Class.forName(PKG + ".DataManagedObjectStructure", false, CL));
        } catch (Throwable ignored) {
            // the alias target may not exist in a future model; the row then reads unregistered
        }
        int idx = 1;
        for (Class<?> c : REGISTRY) {
            while (idx % 256 == 0 || idx >= 0xFFF0) idx++;
            IDX.put(c.getSimpleName(), idx++);
        }
    }

    // ================================================================ subcommands

    static void ns() throws Exception {
        XmlSchema s = null;
        try {
            Class<?> pi = Class.forName(PKG + ".package-info", false, CL);
            s = pi.getAnnotation(XmlSchema.class);
        } catch (Throwable ignored) {
            // fall through to Package-level lookup
        }
        if (s == null) {
            Package p = anchor(PKG).getPackage();
            if (p != null) s = p.getAnnotation(XmlSchema.class);
        }
        List<String> rows = new ArrayList<>();
        if (s == null) {
            rows.add("namespace\t<ABSENT>");
            rows.add("elementFormDefault\t<ABSENT>");
            rows.add("attributeFormDefault\t<ABSENT>");
            rows.add("xmlns\t<ABSENT>");
        } else {
            rows.add("namespace\t" + s.namespace());
            rows.add("elementFormDefault\t" + s.elementFormDefault());
            rows.add("attributeFormDefault\t" + s.attributeFormDefault());
            List<String> xmlns = new ArrayList<>();
            for (jakarta.xml.bind.annotation.XmlNs n : s.xmlns()) {
                xmlns.add(n.prefix() + "=" + n.namespaceURI());
            }
            xmlns.sort(null);
            rows.add("xmlns\t" + String.join(",", xmlns));
        }
        // The GML package's namespace matters too (Projection/GeoClasses marshalling).
        try {
            Package g = anchor(GMLPKG).getPackage();
            XmlSchema gs = g == null ? null : g.getAnnotation(XmlSchema.class);
            if (gs == null) {
                gs = Class.forName(GMLPKG + ".package-info", false, CL).getAnnotation(XmlSchema.class);
            }
            rows.add("gml.namespace\t" + (gs == null ? "<ABSENT>" : gs.namespace()));
            rows.add("gml.elementFormDefault\t" + (gs == null ? "<ABSENT>" : gs.elementFormDefault()));
        } catch (Throwable t) {
            rows.add("gml.namespace\t<UNRESOLVED>");
            rows.add("gml.elementFormDefault\t<UNRESOLVED>");
        }
        write("ns", "key\tvalue", rows, null);
    }

    static void elements() throws Exception {
        List<String> rows = new ArrayList<>();
        int[] miss = new int[1];
        Map<String, List<String>> sets = new LinkedHashMap<>();
        sets.put("with_id", plainSet("with_id.txt"));
        sets.put("with_version", plainSet("with_version.txt"));
        sets.put("srs_name", plainSet("srs_name.txt"));
        sets.put("interesting", plainSet("interesting.txt"));
        Map<String, Integer> total = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        Map<String, Integer> gmlHit = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : sets.entrySet()) {
            int t = 0;
            int m = 0;
            int g = 0;
            for (String name : new java.util.TreeSet<>(e.getValue())) {
                // These sets are ELEMENT-name sets: NeTEx elements live in the model
                // package, GML elements (pos/posList/Polygon/...) in the GML package. A name is
                // "unresolved" only when NEITHER package has it.
                String where = resolves(PKG + "." + name) ? "model"
                        : resolves(GMLPKG + "." + name) ? "gml" : "none";
                rows.add(e.getKey() + "\t" + name + "\t" + !where.equals("none") + "\t" + where);
                t++;
                if (where.equals("none")) m++;
                if (where.equals("gml")) g++;
            }
            total.put(e.getKey(), t);
            missing.put(e.getKey(), m);
            gmlHit.put(e.getKey(), g);
        }
        StringBuilder note = new StringBuilder();
        for (String k : total.keySet()) {
            note.append("# ").append(k).append(": ").append(total.get(k)).append(" names, ")
                    .append(total.get(k) - missing.get(k) - gmlHit.get(k)).append(" model, ")
                    .append(gmlHit.get(k)).append(" gml, ")
                    .append(missing.get(k)).append(" UNRESOLVED\n");
        }
        miss[0] = missing.get("interesting");
        System.out.println(note.toString().trim());
        System.out.println("# interesting.txt unresolved = " + miss[0]
                + " (EXPECTED > 0: the NeTEx abstract substitution-group heads -- Entity, Site,"
                + " Zone, ... -- get no generated class)");
        write("elements", "set\tname\tresolved\twhere", rows, null);
    }

    static void registry() {
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : IDX.entrySet()) rows.add(e.getValue() + "\t" + e.getKey());
        System.out.println("# registry classes = " + REGISTRY.size()
                + "  first idx = " + IDX.values().iterator().next()
                + "  last idx = " + IDX.values().stream().reduce((a, b) -> b).orElse(-1)
                + "  idx values skipped (multiples of 256 / >=0xFFF0) = "
                + (IDX.values().stream().reduce((a, b) -> b).orElse(0) - REGISTRY.size()));
        write("registry", "idx\tsimpleName", rows, byInt(0));
    }

    static void names() throws Exception {
        Set<String> used = new HashSet<>();
        if (Files.exists(USED)) {
            for (String l : Files.readAllLines(USED)) if (!l.isBlank()) used.add(l.strip());
        }
        Set<String> simple = new java.util.TreeSet<>();
        Map<String, Class<?>> top = new HashMap<>();
        for (Class<?> c : RAW) {
            if (c.getName().contains("$")) continue;
            simple.add(c.getSimpleName());
            top.put(c.getSimpleName(), c);
        }
        List<String> rows = new ArrayList<>();
        int usedHit = 0;
        int refPairs = 0;
        for (String n : simple) {
            Class<?> c = top.get(n);
            String kind = c.isEnum() ? "enum" : c.isInterface() ? "interface"
                    : Modifier.isAbstract(c.getModifiers()) ? "abstract" : "class";
            boolean isUsed = used.contains(n);
            if (isUsed) usedHit++;
            String pair = "-";
            if (n.endsWith("RefStructure")) {
                String other = n.substring(0, n.length() - "Structure".length());
                if (simple.contains(other)) { pair = other; refPairs++; }
            } else if (n.endsWith("Ref")) {
                String other = n + "Structure";
                if (simple.contains(other)) pair = other;
            }
            rows.add(n + "\t" + kind + "\t" + IDX.containsKey(n) + "\t" + isUsed + "\t" + pair);
        }
        List<String> unusedNames = new ArrayList<>(used);
        unusedNames.removeAll(simple);
        java.util.Collections.sort(unusedNames);
        for (String n : unusedNames) rows.add(n + "\tMISSING\tfalse\ttrue\t-");
        System.out.println("# top-level simple names = " + simple.size()
                + "  port-used names = " + used.size() + " (resolved " + usedHit
                + ", MISSING " + unusedNames.size() + ")"
                + "  *Ref/*RefStructure pairs = " + refPairs);
        write("names", "simpleName\tkind\tregistrable\tusedByPort\trefPair", rows, null);
    }

    static void refs() throws Exception {
        Class<?> vor = Class.forName(PKG + ".VersionOfObjectRefStructure", false, CL);
        List<String> rows = new ArrayList<>();
        int concrete = 0;
        int unregistered = 0;
        for (Class<?> c : RAW) {
            if (c.isEnum() || c.isInterface()) continue;
            if (Modifier.isAbstract(c.getModifiers())) continue;
            if (!vor.isAssignableFrom(c)) continue;
            concrete++;
            String target = deriveTargetName(c.getSimpleName());
            boolean reg = BY_NAME.containsKey(target);
            if (!reg) unregistered++;
            rows.add(c.getSimpleName() + "\t" + target + "\t" + reg);
        }
        System.out.println("# concrete VersionOfObjectRefStructure subclasses = " + concrete
                + "  registered targets = " + (concrete - unregistered)
                + "  UNREGISTERED (-> DataManagedObject fallback) = " + unregistered);
        write("refs", "refClass\tderivedTargetName\tregistered", rows, null);
    }

    /// RecursiveAttributes.deriveTargetName, verbatim.
    static String deriveTargetName(String n) {
        if (n.endsWith("RefStructure")) return n.substring(0, n.length() - "RefStructure".length());
        if (n.endsWith("Ref")) return n.substring(0, n.length() - "Ref".length());
        return n;
    }

    static void geo() throws Exception {
        List<String> rows = new ArrayList<>();
        Set<Class<?>> geoTypes = new LinkedHashSet<>();
        for (String n : GEO_MODEL) {
            Class<?> c = tryLoad(PKG + "." + n);
            rows.add("geoFieldType\t@" + n + "\t" + (c != null));
            if (c != null) geoTypes.add(c);
        }
        for (String n : GEO_GML) {
            Class<?> c = tryLoad(GMLPKG + "." + n);
            rows.add("geoFieldType\t%" + n + "\t" + (c != null));
            if (c != null) geoTypes.add(c);
        }
        for (String n : PORT_GML) {
            rows.add("portGmlType\t%" + n + "\t" + resolves(GMLPKG + "." + n));
        }
        rows.add("portGmlType\t%ObjectFactory\t" + resolves(GMLPKG + ".ObjectFactory"));
        int n = 0;
        for (Class<?> c : REGISTRY) {
            if (geoTypes.contains(c)) continue;
            if (!carriesGeoField(c, geoTypes)) continue;
            rows.add("geoClass\t@" + c.getSimpleName() + "\ttrue");
            n++;
        }
        System.out.println("# geo field types resolved = " + geoTypes.size() + "/5"
                + "  GeoClasses.all() = " + n);
        if (n == 0) System.out.println("# *** ABORT CRITERION: GeoClasses is EMPTY ***");
        write("geo", "kind\tname\tvalue", rows, null);
    }

    static boolean carriesGeoField(Class<?> clazz, Set<Class<?>> geoTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (geoTypes.contains(f.getType())) return true;
            }
        }
        return false;
    }

    static void embed() {
        List<String> rows = new ArrayList<>();
        Set<String> allow = new java.util.TreeSet<>(Arrays.asList(EMBEDDING_ALLOWLIST));
        for (String n : allow) rows.add(n + "\tallowlist\t" + IDX.containsKey(n));
        int typeOf = 0;
        for (String n : IDX.keySet()) {
            if (!n.startsWith("TypeOf") || n.endsWith("Ref")) continue;
            if (allow.contains(n)) continue;
            rows.add(n + "\ttypeOfRule\ttrue");
            typeOf++;
        }
        // TypeOf* names EXCLUDED by the !endsWith("Ref") clause -- recorded so a naming change is visible.
        int excluded = 0;
        for (String n : IDX.keySet()) {
            if (n.startsWith("TypeOf") && n.endsWith("Ref")) { rows.add(n + "\ttypeOfExcluded\tfalse"); excluded++; }
        }
        long inScope = allow.stream().filter(IDX::containsKey).count() + typeOf;
        System.out.println("# embedding scope = " + inScope + " classes ("
                + allow.stream().filter(IDX::containsKey).count() + " allowlist of " + allow.size()
                + " + " + typeOf + " TypeOf*), TypeOf*Ref excluded = " + excluded);
        write("embed", "name\tsource\tinScope", rows, null);
    }

    // ---------------------------------------------------------------- ContainmentIndex port

    static Map<Class<?>, List<Class<?>>> CONCRETE = new HashMap<>();
    static Map<Class<?>, Set<Class<?>>> DIRECT = new HashMap<>();

    static List<Class<?>> concreteSubclasses(Class<?> t) {
        return CONCRETE.computeIfAbsent(t, k -> {
            List<Class<?>> out = new ArrayList<>();
            for (Class<?> c : ALL) {
                if (k.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers())) out.add(c);
            }
            return out;
        });
    }

    static boolean isModel(Class<?> c) {
        return c.getName().startsWith(PKG + ".") && !c.isEnum() && !c.isInterface()
                && c != JAXBElement.class;
    }

    static void addWithExpansion(Class<?> c, Set<Class<?>> out) {
        if (c == null || !isModel(c)) return;
        if (Modifier.isAbstract(c.getModifiers())) out.addAll(concreteSubclasses(c));
        else out.add(c);
    }

    static void harvestType(Type t, Set<Class<?>> out) {
        if (t instanceof Class<?> c) addWithExpansion(c, out);
        else if (t instanceof ParameterizedType p) {
            if (p.getRawType() instanceof Class<?> raw) addWithExpansion(raw, out);
            for (Type a : p.getActualTypeArguments()) harvestType(a, out);
        } else if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) harvestType(b, out);
        } else if (t instanceof GenericArrayType g) {
            harvestType(g.getGenericComponentType(), out);
        }
    }

    static Set<Class<?>> direct(Class<?> type) {
        Set<Class<?>> cached = DIRECT.get(type);
        if (cached != null) return cached;
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                harvestType(f.getGenericType(), out);
                XmlElements els = f.getAnnotation(XmlElements.class);
                if (els != null) for (XmlElement e : els.value()) addWithExpansion(e.type(), out);
                XmlElement el = f.getAnnotation(XmlElement.class);
                if (el != null) addWithExpansion(el.type(), out);
                XmlElementRef er = f.getAnnotation(XmlElementRef.class);
                if (er != null) addByElementName(er.name(), out);
                XmlElementRefs ers = f.getAnnotation(XmlElementRefs.class);
                if (ers != null) for (XmlElementRef e : ers.value()) addByElementName(e.name(), out);
            }
        }
        DIRECT.put(type, out);
        return out;
    }

    static void addByElementName(String name, Set<Class<?>> out) {
        if (name == null || name.isEmpty()) return;
        Class<?> c = BY_NAME.get(name);
        if (c != null) addWithExpansion(c, out);
    }

    static Set<Class<?>> closure(Class<?> root) {
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        for (Class<?> c : direct(root)) if (seen.add(c)) stack.push(c);
        while (!stack.isEmpty()) {
            Class<?> c = stack.pop();
            for (Class<?> d : direct(c)) if (seen.add(d)) stack.push(d);
        }
        return seen;
    }

    static void contain() {
        // Baseline scope = the whole registrable space on both sides (callers always pass a subset
        // of it: db classes as containers, missing classes as scope).
        Set<Class<?>> scope = new LinkedHashSet<>(REGISTRY);
        Map<Class<?>, Set<String>> index = new HashMap<>();
        for (Class<?> clazz : REGISTRY) {
            if (!isModel(clazz) || Modifier.isAbstract(clazz.getModifiers())) continue;
            Set<Class<?>> contained = closure(clazz);
            for (Class<?> m : scope) {
                if (contained.contains(m)) {
                    index.computeIfAbsent(m, k -> new java.util.TreeSet<>()).add(m2(clazz));
                }
            }
        }
        List<String> rows = new ArrayList<>();
        long pairs = 0;
        for (Map.Entry<Class<?>, Set<String>> e : index.entrySet()) {
            pairs += e.getValue().size();
            rows.add(e.getKey().getSimpleName() + "\t" + e.getValue().size()
                    + "\t" + String.join(",", e.getValue()));
        }
        System.out.println("# containment index: " + index.size() + " contained classes, "
                + pairs + " (contained,container) pairs");
        write("contain", "missingClass\tnContainers\tcontainers", rows, null);
    }

    static String m2(Class<?> c) {
        return c.getSimpleName();
    }

    // ---------------------------------------------------------------- census

    static void census() {
        List<String> rows = new ArrayList<>();
        Map<Class<?>, Object> probes = new HashMap<>();
        int fields = 0;
        for (Class<?> c : ALL) {
            XmlType xt = c.getAnnotation(XmlType.class);
            String propOrder = xt == null ? "-" : String.join(",", xt.propOrder());
            String xtName = xt == null ? "-" : xt.name();
            XmlRootElement xr = c.getAnnotation(XmlRootElement.class);
            rows.add("C\t" + norm(c.getName()) + "\t"
                    + (c.getSuperclass() == null ? "-" : norm(c.getSuperclass().getName())) + "\t"
                    + (Modifier.isAbstract(c.getModifiers()) ? "abstract" : "concrete") + "\t"
                    + xtName + "\t" + (xr == null ? "-" : xr.name()) + "\t" + propOrder);
            Field[] fs = c.getDeclaredFields();
            Arrays.sort(fs, Comparator.comparing(Field::getName));
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                fields++;
                rows.add("F\t" + norm(c.getName()) + "\t" + f.getName() + "\t"
                        + norm(f.getType().getName()) + "\t" + norm(f.getGenericType().getTypeName())
                        + "\t" + xmlKind(f) + "\t" + xmlName(f) + "\t" + xmlDefault(f)
                        + "\t" + (f.getAnnotation(XmlList.class) != null)
                        + "\t" + adapter(f) + "\t" + schemaType(f) + "\t" + xmlRequired(f)
                        + "\t" + fieldInit(c, f, probes));
            }
        }
        System.out.println("# census: " + ALL.size() + " classes, " + fields + " instance fields");
        enumRows(rows);
        // C rows: kind class superclass abstractness xmlTypeName xmlRootElementName propOrder
        // E rows: kind enumClass xmlEnumValueType nTokens tokens javaConstants
        // F rows: kind class field declaredType genericType xmlKind xmlName xmlDefault
        //         xmlList adapter xmlSchemaType required fieldInit
        write("census", "kind\tclass\tc3\tc4\tc5\tc6\tc7\tc8\tc9\tc10\tc11\tc12\tc13", rows, null);
    }

    // -------------------------------------------------------- the enumeration-facet column (E)
    //
    // The F row's third cell is `declaredType`, a TYPE-NAME STRING, so a sweep over changed cells
    // sees a field only when the NAME of its type moved. An enumeration that keeps its name and
    // changes its TOKEN SET is invisible to it, and that is a wire change: a dropped lexical binds
    // null, silently.
    //
    // A new row kind rather than a new column on F: the census is a frozen baseline diffed on
    // (kind, class, field) with column 3 read kind-aware, so widening F would move every F row's
    // byte layout. A new kind is additive — the rows sort into one contiguous block.
    //
    // Keyed on the enumeration's TYPE NAME, the one thing that did not change.
    //
    // What it cannot see:
    //   - WHICH FIELDS USE THE ENUM. That is the F rows' job; the join is not made here.
    //   - AN ENUM THAT LOST ITS TYPE ENTIRELY. That is a `declaredType` change and belongs to F.
    //   - WHETHER AN ORDER-ONLY CHANGE MATTERS. Tokens are emitted in declaration order, so a
    //     reorder moves the row; JAXB binds by lexical rather than ordinal, so it changes no bind.
    //   - THE CORPUS. Zero occurrences of a lost token is a fact about today's feeds.
    static void enumRows(List<String> rows) {
        List<Class<?>> es = new ArrayList<>();
        for (Class<?> c : RAW) if (c.isEnum()) es.add(c);
        es.sort(Comparator.comparing(Class::getName));
        int tokens = 0;
        for (Class<?> c : es) {
            XmlEnum xe = c.getAnnotation(XmlEnum.class);
            List<String> toks = new ArrayList<>();
            List<String> consts = new ArrayList<>();
            Object[] cs = c.getEnumConstants();
            if (cs == null) throw new IllegalStateException(
                    "enum " + c.getName() + " has no constants array — the model is not readable");
            for (Object o : cs) {
                String cn = ((Enum<?>) o).name();
                consts.add(cn);
                String lex = cn;
                try {
                    XmlEnumValue v = c.getField(cn).getAnnotation(XmlEnumValue.class);
                    if (v != null) lex = v.value();
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException("enum constant " + c.getName() + "." + cn
                            + " has no field — reflection is wrong, not the model", e);
                }
                // The row is TSV and the token list is comma-joined, so a token carrying either
                // separator corrupts the column silently. NMTOKEN forbids both, so this can only
                // fire on a generator change.
                if (lex.indexOf(',') >= 0 || lex.indexOf('\t') >= 0 || lex.indexOf('\n') >= 0) {
                    throw new IllegalStateException("enum token " + c.getName() + " -> '" + lex
                            + "' carries a separator; the E row encoding cannot represent it");
                }
                toks.add(lex);
            }
            tokens += toks.size();
            rows.add("E\t" + norm(c.getName()) + "\t"
                    + (xe == null ? "-" : norm(xe.value().getName())) + "\t"
                    + toks.size() + "\t" + String.join(",", toks)
                    + "\t" + String.join(",", consts));
        }
        // A model package with no enumerations is a scan that found nothing, not a NeTEx model.
        // Without this floor that reads as "no enumeration changed".
        if (es.size() < ENUM_FLOOR) {
            throw new IllegalStateException("census: only " + es.size() + " enumeration(s) found in "
                    + PKG + " (floor " + ENUM_FLOOR + "). A NeTEx model has hundreds. This run is"
                    + " not a measurement — check --pkg and the classpath.");
        }
        System.out.println("# census enums: " + es.size() + " enumerations, " + tokens + " tokens");
    }

    /// Floor for the enum rows: well under either model's count and well over any accident.
    static final int ENUM_FLOOR = 100;

    static String xmlKind(Field f) {
        List<String> k = new ArrayList<>();
        if (f.getAnnotation(XmlAttribute.class) != null) k.add("attribute");
        if (f.getAnnotation(XmlElement.class) != null) k.add("element");
        if (f.getAnnotation(XmlElements.class) != null) k.add("elements");
        if (f.getAnnotation(XmlElementRef.class) != null) k.add("elementRef");
        if (f.getAnnotation(XmlElementRefs.class) != null) k.add("elementRefs");
        if (f.getAnnotation(XmlValue.class) != null) k.add("value");
        if (f.getAnnotation(XmlTransient.class) != null) k.add("transient");
        return k.isEmpty() ? "-" : String.join("+", k);
    }

    static String xmlName(Field f) {
        List<String> n = new ArrayList<>();
        XmlAttribute a = f.getAnnotation(XmlAttribute.class);
        if (a != null) n.add(a.name() + (a.namespace().equals("##default") ? "" : "{" + a.namespace() + "}"));
        XmlElement e = f.getAnnotation(XmlElement.class);
        if (e != null) n.add(e.name() + (e.namespace().equals("##default") ? "" : "{" + e.namespace() + "}"));
        XmlElements es = f.getAnnotation(XmlElements.class);
        if (es != null) {
            List<String> m = new ArrayList<>();
            for (XmlElement x : es.value()) m.add(x.name() + ":" + norm(x.type().getName()));
            m.sort(null);
            n.add(String.join("|", m));
        }
        XmlElementRef r = f.getAnnotation(XmlElementRef.class);
        if (r != null) n.add(r.name() + ":" + norm(r.type().getName()));
        XmlElementRefs rs = f.getAnnotation(XmlElementRefs.class);
        if (rs != null) {
            List<String> m = new ArrayList<>();
            for (XmlElementRef x : rs.value()) m.add(x.name() + ":" + norm(x.type().getName()));
            m.sort(null);
            n.add(String.join("|", m));
        }
        return n.isEmpty() ? "-" : String.join(";", n);
    }

    /// JAXB's "no default" sentinel for @XmlElement.defaultValue() is a single NUL character.
    private static final String NO_DEFAULT = "\0";

    static String xmlDefault(Field f) {
        XmlElement e = f.getAnnotation(XmlElement.class);
        if (e == null) return "-";
        String d = e.defaultValue();
        return (d == null || d.equals(NO_DEFAULT)) ? "-" : d;
    }

    private static final Object NO_PROBE = new Object();

    /// Schema defaults baked into the field by the generator: the value a freshly constructed
    /// instance carries before anything is set. Makes a generator-config change
    /// (defaultvalueplugin, -Ximmutable Builders) visible; it cannot be read off the annotations.
    static String fieldInit(Class<?> c, Field f, Map<Class<?>, Object> probes) {
        if (Modifier.isAbstract(c.getModifiers())) return "-";
        if (c.isMemberClass() && !Modifier.isStatic(c.getModifiers())) return "-";
        Object probe = probes.computeIfAbsent(c, k -> {
            try {
                var ctor = k.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (Throwable t) {
                return NO_PROBE;
            }
        });
        if (probe == NO_PROBE) return "?";
        try {
            f.setAccessible(true);
            Object v = f.get(probe);
            return v == null ? "-" : norm(String.valueOf(v)).replace('\t', ' ').replace('\n', ' ');
        } catch (Throwable t) {
            return "?";
        }
    }

    static String xmlRequired(Field f) {
        XmlAttribute a = f.getAnnotation(XmlAttribute.class);
        if (a != null) return "attrRequired=" + a.required();
        XmlElement e = f.getAnnotation(XmlElement.class);
        if (e != null) return "elemRequired=" + e.required() + ",nillable=" + e.nillable();
        return "-";
    }

    static String adapter(Field f) {
        XmlJavaTypeAdapter a = f.getAnnotation(XmlJavaTypeAdapter.class);
        return a == null ? "-" : a.value().getName();
    }

    static String schemaType(Field f) {
        XmlSchemaType t = f.getAnnotation(XmlSchemaType.class);
        return t == null ? "-" : t.name();
    }

    // ---------------------------------------------------------------- JAXB context

    static void ctx() throws Exception {
        List<String> rows = new ArrayList<>();
        String status;
        try {
            JAXBContext jc = JAXBContext.newInstance(PKG, CL);
            status = jc.getClass().getName();
        } catch (Throwable t) {
            status = "FAILED: " + t;
        }
        rows.add("#context\t-\t-\t" + status);
        Class<?> of = Class.forName(PKG + ".ObjectFactory", false, CL);
        int decls = 0;
        for (Method m : of.getDeclaredMethods()) {
            XmlElementDecl d = m.getAnnotation(XmlElementDecl.class);
            if (d == null) continue;
            decls++;
            String ns = d.namespace().equals("##default") ? "" : d.namespace();
            String scope = d.scope() == XmlElementDecl.GLOBAL.class ? "GLOBAL" : norm(d.scope().getName());
            String sub = d.substitutionHeadName().isEmpty() ? "-"
                    : (d.substitutionHeadNamespace().equals("##default") ? "" : d.substitutionHeadNamespace())
                      + "#" + d.substitutionHeadName();
            Type g = m.getGenericReturnType();
            String bound = "-";
            if (g instanceof ParameterizedType p && p.getActualTypeArguments().length == 1) {
                bound = norm(p.getActualTypeArguments()[0].getTypeName());
            }
            rows.add("{" + ns + "}" + d.name() + "\t" + scope + "\t" + bound + "\t" + sub);
        }
        // Model classes with @XmlRootElement are the OTHER half of the element space.
        int roots = 0;
        for (Class<?> c : ALL) {
            XmlRootElement r = c.getAnnotation(XmlRootElement.class);
            if (r == null) continue;
            roots++;
            String ns = r.namespace().equals("##default") ? "" : r.namespace();
            String nm = r.name().equals("##default") ? c.getSimpleName() : r.name();
            rows.add("{" + ns + "}" + nm + "\tROOT\t" + norm(c.getName()) + "\t-");
        }
        System.out.println("# ObjectFactory @XmlElementDecl = " + decls
                + "  @XmlRootElement classes = " + roots + "  JAXBContext = " + status);
        write("ctx", "elementQName\tscope\tboundClass\tsubstitutionHead", rows, null);
    }

    // ---------------------------------------------------------------- persisted class tables

    static void classtable(Path db) {
        String label = db.getFileName().toString().replaceAll("\\.lmdb$", "");
        List<String> rows = new ArrayList<>();
        Env<byte[]> env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(64L << 30).setMaxDbs(4096).setMaxReaders(126)
                .open(db.toFile(), EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS);
        try {
            Set<String> dbis = env.getDbiNames().stream()
                    .map(b -> new String(b, StandardCharsets.UTF_8)).collect(Collectors.toSet());
            String fmt;
            TreeMap<Integer, String> table = new TreeMap<>();
            String marker = "-";
            if (dbis.contains("_class_idx")) {
                fmt = "v1";
                Dbi<byte[]> d = env.openDbi("_class_idx".getBytes(StandardCharsets.UTF_8));
                try (org.lmdbjava.Txn<byte[]> t = env.txnRead(); CursorIterable<byte[]> ci = d.iterate(t)) {
                    for (CursorIterable.KeyVal<byte[]> kv : ci) {
                        byte[] k = kv.key();
                        if (k.length != 2) continue;
                        int idx = (k[0] & 0xFF) | ((k[1] & 0xFF) << 8);
                        if (idx >= 0xFFF0) {
                            if (idx == 0xFFFF) marker = new String(kv.val(), StandardCharsets.UTF_8);
                            continue;
                        }
                        table.put(idx, new String(kv.val(), StandardCharsets.UTF_8));
                    }
                }
            } else if (dbis.contains("_meta")) {
                fmt = "v2";
                Dbi<byte[]> d = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8));
                try (org.lmdbjava.Txn<byte[]> t = env.txnRead(); CursorIterable<byte[]> ci = d.iterate(t)) {
                    for (CursorIterable.KeyVal<byte[]> kv : ci) {
                        byte[] k = kv.key();
                        if (k.length == 6 && new String(k, StandardCharsets.UTF_8).equals("format")) {
                            marker = new String(kv.val(), StandardCharsets.UTF_8);
                        }
                        if (k.length != 3 || k[0] != 'c') continue;
                        int idx = (k[1] & 0xFF) | ((k[2] & 0xFF) << 8);
                        table.put(idx, new String(kv.val(), StandardCharsets.UTF_8));
                    }
                }
            } else {
                throw new IllegalStateException(db + ": neither _class_idx nor _meta present");
            }
            int unresolved = 0;
            int mismatched = 0;
            for (Map.Entry<Integer, String> e : table.entrySet()) {
                boolean ok = resolves(PKG + "." + e.getValue()) || BY_NAME.containsKey(e.getValue());
                if (!ok) unresolved++;
                Integer live = IDX.get(e.getValue());
                boolean sameIdx = e.getKey().equals(live);
                if (!sameIdx) mismatched++;
                rows.add(e.getKey() + "\t" + e.getValue() + "\t" + ok + "\t"
                        + (live == null ? "-" : live) + "\t" + sameIdx);
            }
            System.out.println("# " + label + ": " + fmt + " marker=" + marker + "  rows=" + table.size()
                    + "  UNRESOLVABLE=" + unresolved + "  idx-differs-from-live=" + mismatched);
            if (unresolved > 0) System.out.println("# *** ABORT CRITERION: unresolvable stored class name ***");
            write("classtable." + label, "idx\tstoredName\tresolves\tliveIdx\tidxMatchesLive", rows, byInt(0));
        } finally {
            env.close();
        }
    }

    // ---------------------------------------------------------------- self-verification

    static void verify() throws Exception {
        List<String> problems = new ArrayList<>();
        Class<?> reg = tryLoad("toolkit.model.ModelRegistry");
        if (reg == null) {
            System.out.println("# verify: toolkit.model.ModelRegistry not on the classpath -- SKIPPED");
            return;
        }
        @SuppressWarnings("unchecked")
        List<Class<?>> live = (List<Class<?>>) reg.getMethod("classes").invoke(null);
        if (live.size() != REGISTRY.size()) {
            problems.add("registry size " + REGISTRY.size() + " != live " + live.size());
        }
        int n = Math.min(live.size(), REGISTRY.size());
        for (int i = 0; i < n; i++) {
            if (!live.get(i).getName().equals(REGISTRY.get(i).getName())) {
                problems.add("registry[" + i + "] " + REGISTRY.get(i).getName()
                        + " != live " + live.get(i).getName());
                if (problems.size() > 10) break;
            }
        }
        // AbstractLmdbStore.assignIdx() is protected static: reflect it and compare the numbering.
        Class<?> store = tryLoad("toolkit.store.AbstractLmdbStore");
        if (store != null) {
            Method assign = store.getDeclaredMethod("assignIdx");
            assign.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Integer> liveIdx = (Map<String, Integer>) assign.invoke(null);
            if (!liveIdx.equals(IDX)) {
                problems.add("assignIdx mine=" + IDX.size() + " live=" + liveIdx.size() + " (maps differ)");
                for (Map.Entry<String, Integer> e : IDX.entrySet()) {
                    if (!e.getValue().equals(liveIdx.get(e.getKey()))) {
                        problems.add("  assignIdx[" + e.getKey() + "] mine=" + e.getValue()
                                + " live=" + liveIdx.get(e.getKey()));
                        break;
                    }
                }
            }
        }
        Method isRegistered = reg.getMethod("isRegistered", String.class);
        Class<?> vor = Class.forName(PKG + ".VersionOfObjectRefStructure", false, CL);
        Class<?> ra = tryLoad("toolkit.model.RecursiveAttributes");
        Method derive = ra == null ? null : ra.getMethod("deriveTargetName", Class.class);
        int checked = 0;
        for (Class<?> c : RAW) {
            if (c.isEnum() || c.isInterface() || Modifier.isAbstract(c.getModifiers())) continue;
            if (!vor.isAssignableFrom(c)) continue;
            String mine = deriveTargetName(c.getSimpleName());
            if (derive != null) {
                String theirs = (String) derive.invoke(null, c);
                if (!mine.equals(theirs)) problems.add("deriveTargetName " + c.getSimpleName()
                        + ": mine=" + mine + " live=" + theirs);
            }
            boolean liveReg = (Boolean) isRegistered.invoke(null, mine);
            if (liveReg != BY_NAME.containsKey(mine)) {
                problems.add("isRegistered(" + mine + "): mine=" + BY_NAME.containsKey(mine)
                        + " live=" + liveReg);
            }
            checked++;
        }
        // GeoClasses
        Class<?> gc = tryLoad("toolkit.model.GeoClasses");
        if (gc != null) {
            @SuppressWarnings("unchecked")
            Set<Class<?>> liveGeo = (Set<Class<?>>) gc.getMethod("all").invoke(null);
            Set<Class<?>> geoTypes = new LinkedHashSet<>();
            for (String s : GEO_MODEL) { Class<?> t = tryLoad(PKG + "." + s); if (t != null) geoTypes.add(t); }
            for (String s : GEO_GML) { Class<?> t = tryLoad(GMLPKG + "." + s); if (t != null) geoTypes.add(t); }
            Set<String> mine = new java.util.TreeSet<>();
            for (Class<?> c : REGISTRY) {
                if (geoTypes.contains(c)) continue;
                if (carriesGeoField(c, geoTypes)) mine.add(c.getSimpleName());
            }
            Set<String> theirs = new java.util.TreeSet<>();
            for (Class<?> c : liveGeo) theirs.add(c.getSimpleName());
            if (!mine.equals(theirs)) problems.add("GeoClasses mine=" + mine.size()
                    + " live=" + theirs.size() + " symDiff=" + symDiff(mine, theirs));
        }
        System.out.println("# verify: registry " + REGISTRY.size() + " vs live " + live.size()
                + ", refs checked " + checked + ", problems " + problems.size());
        for (String p : problems) System.out.println("#   " + p);
        if (!problems.isEmpty()) {
            System.out.println("# *** VERIFY FAILED -- the reflection is wrong, baselines are NOT valid ***");
            System.exit(1);
        }
        System.out.println("# verify: OK");
    }

    static Set<String> symDiff(Set<String> a, Set<String> b) {
        Set<String> d = new java.util.TreeSet<>(a);
        d.removeAll(b);
        Set<String> e = new java.util.TreeSet<>(b);
        e.removeAll(a);
        d.addAll(e);
        return d;
    }

    // ---------------------------------------------------------------- helpers

    static String norm(String typeName) {
        return typeName.replace(PKG + ".", "@").replace(GMLPKG + ".", "%");
    }

    static Class<?> tryLoad(String fqn) {
        try {
            return Class.forName(fqn, false, CL);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean resolves(String fqn) {
        return tryLoad(fqn) != null;
    }

    static List<String> plainSet(String file) throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(SETS.resolve(file))) if (!l.isBlank()) out.add(l.strip());
        return out;
    }

    static Comparator<String> byInt(int col) {
        return (a, b) -> {
            String[] x = a.split("\t");
            String[] y = b.split("\t");
            int c = Integer.compare(Integer.parseInt(x[col]), Integer.parseInt(y[col]));
            return c != 0 ? c : a.compareTo(b);
        };
    }

    static void write(String name, String header, List<String> rows, Comparator<String> cmp) {
        try {
            rows.sort(cmp == null ? Comparator.naturalOrder() : cmp);
            List<String> out = new ArrayList<>(rows.size() + 1);
            out.add("#" + header);
            out.addAll(rows);
            Path p = OUT.resolve(name + ".tsv");
            Files.write(p, out);
            System.out.println("wrote " + p + " (" + rows.size() + " rows)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
