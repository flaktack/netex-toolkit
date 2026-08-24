package toolkit.codec;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Child-element ORDER normalisation for stored fragments — the missing half of the fragment format.
///
/// The fragment format stores the object's XML as captured from the source feed and the exporter
/// copies those bytes straight into the zip, so the export inherits the source's child-element
/// ORDER verbatim — and a source feed is free to be schema-invalid. Real feeds do emit inverted
/// pairs, and the export then fails `cvc-complex-type.2.4.a`. Only XSD validation sees this: the
/// export A/B declares element order a normalisation, the round-trip harness absorbs it, graph
/// dumps carry only `Class|id|version`, and JAXB readers accept out-of-order children.
///
/// **What this does.** [#normalise] takes a fragment and the class it was stored under, and returns
/// the fragment with its TOP-LEVEL children stably reordered into the class's declared JAXB
/// emission order — the order [FragmentCodec#marshalFragment] would have produced, which is the XSD
/// sequence. Everything else is untouched: attributes, quoting, namespace declarations,
/// inter-element whitespace positions and nested content are all byte-preserved, because the
/// rewrite MOVES child spans rather than re-serialising anything. A fragment already in order is
/// returned as the same array.
///
/// **What this deliberately does NOT do** (each case returns the fragment verbatim, and is
/// counted in [#skipped()]):
///
/// - a fragment containing a comment, CDATA section or processing instruction — the byte-level
///    scan does not model those, so it declines rather than guesses;
/// - a fragment with a child whose element name the class does not declare — substitution-group
///    members reached through `@XmlElementRef`, foreign-namespace children such as
///    `gml:LineString`, anything the model spells differently. KNOWN GAP (OPEN.md §16): a journey's
///    `ServiceJourneyPatternRef` substitutes for the declared `JourneyPatternRef`, so most journeys
///    decline; closing it means resolving `@XmlElementDecl(substitutionHeadName)` into the rank
///    table;
/// - a class whose emission order is not statically knowable — `@XmlAnyElement` (wildcard
///    content) or a name that two different properties both claim.
///
/// Declining is always safe.
///
/// Static: the counters are atomics, the rank cache is a [ConcurrentHashMap] and the scan is
/// stateless.
public final class FragmentOrder {

    /// Sentinel rank map meaning "this class cannot be ordered statically".
    private static final Map<String, Integer> UNORDERABLE = Map.of();

    private static final Map<Class<?>, Map<String, Integer>> RANKS = new ConcurrentHashMap<>();

    private static final AtomicLong REORDERED = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong SEEN = new AtomicLong();

    private FragmentOrder() {}

    /// Fragments whose children were actually moved.
    public static long reordered() {
        return REORDERED.get();
    }

    /// Fragments left verbatim because they could not be ranked (see the class javadoc).
    public static long skipped() {
        return SKIPPED.get();
    }

    /// Fragments offered to [#normalise].
    public static long seen() {
        return SEEN.get();
    }

    /// Test hook: zero the counters.
    public static void resetCounters() {
        REORDERED.set(0);
        SKIPPED.set(0);
        SEEN.set(0);
    }

    // ------------------------------------------------------------------ the rank table

    /// Element name -&gt; emission rank for the top-level children of `clazz`, or an EMPTY map
    /// when the class cannot be ranked. Built once per class from the JAXB annotations the model was
    /// generated with, walking the superclass chain root-first because that is the order JAXB emits
    /// inherited content in.
    public static Map<String, Integer> ranks(Class<?> clazz) {
        return RANKS.computeIfAbsent(clazz, FragmentOrder::buildRanks);
    }

    private static Map<String, Integer> buildRanks(Class<?> clazz) {
        Deque<Class<?>> chain = new ArrayDeque<>();
        for (Class<?> k = clazz; k != null && k != Object.class; k = k.getSuperclass()) chain.push(k);

        Map<String, Integer> ranks = new HashMap<>();
        int rank = 0;
        for (Class<?> k : chain) {
            for (Field f : orderedFields(k)) {
                if (f == null) return UNORDERABLE;               // propOrder named a field we cannot find
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
                if (f.isAnnotationPresent(XmlAttribute.class)
                        || f.isAnnotationPresent(XmlAnyAttribute.class)
                        || f.isAnnotationPresent(XmlValue.class)
                        || f.isAnnotationPresent(XmlTransient.class)) {
                    continue;
                }
                if (f.isAnnotationPresent(XmlAnyElement.class)) return UNORDERABLE; // wildcard content
                for (String name : elementNames(f)) {
                    Integer prev = ranks.put(name, rank);
                    // The same element name claimed by two properties at different ranks makes the
                    // permutation ambiguous. Refuse the whole class rather than pick.
                    if (prev != null && prev != rank) return UNORDERABLE;
                }
                rank++;
            }
        }
        return ranks.isEmpty() ? UNORDERABLE : Map.copyOf(ranks);
    }

    /// The class's OWN element properties in emission order: `@XmlType(propOrder)` when it
    /// declares one (XJC emits it whenever there is more than one property), otherwise declared
    /// field order, which is what JAXB falls back to and what XJC's single-property classes carry.
    private static List<Field> orderedFields(Class<?> k) {
        XmlType xt = k.getDeclaredAnnotation(XmlType.class);
        String[] propOrder = xt == null ? null : xt.propOrder();
        List<Field> out = new ArrayList<>();
        if (propOrder == null || propOrder.length == 0 || (propOrder.length == 1 && propOrder[0].isEmpty())) {
            for (Field f : k.getDeclaredFields()) out.add(f);
            return out;
        }
        for (String prop : propOrder) {
            Field f = null;
            try {
                f = k.getDeclaredField(prop);
            } catch (NoSuchFieldException ignored) {
                // fall through: a null entry poisons the class (buildRanks returns UNORDERABLE)
            }
            out.add(f);
        }
        return out;
    }

    /// Every XML element name a field can appear as. Empty = the field emits no element.
    private static List<String> elementNames(Field f) {
        List<String> names = new ArrayList<>(1);
        XmlElement e = f.getAnnotation(XmlElement.class);
        if (e != null) {
            names.add(defaulted(e.name(), f));
            return names;
        }
        XmlElements es = f.getAnnotation(XmlElements.class);
        if (es != null) {
            for (XmlElement x : es.value()) names.add(defaulted(x.name(), f));
            return names;
        }
        XmlElementRef r = f.getAnnotation(XmlElementRef.class);
        if (r != null) {
            names.add(defaulted(r.name(), f));
            return names;
        }
        XmlElementRefs rs = f.getAnnotation(XmlElementRefs.class);
        if (rs != null) {
            for (XmlElementRef x : rs.value()) names.add(defaulted(x.name(), f));
            return names;
        }
        names.add(f.getName()); // unannotated property: the element takes the field name
        return names;
    }

    private static String defaulted(String name, Field f) {
        return name == null || name.isEmpty() || "##default".equals(name) ? f.getName() : name;
    }

    // ------------------------------------------------------------------ the rewrite

    /// The fragment with its top-level children in declared order — the SAME array when nothing
    /// needed moving or when the fragment declined ranking (see the class javadoc).
    public static byte[] normalise(Class<?> clazz, byte[] fragment) {
        SEEN.incrementAndGet();
        Map<String, Integer> ranks = ranks(clazz);
        if (ranks.isEmpty()) {                    // UNORDERABLE, or a class with no element content
            SKIPPED.incrementAndGet();
            return fragment;
        }
        Scan scan = scan(fragment);
        if (scan == null || scan.count < 2) {
            if (scan == null) SKIPPED.incrementAndGet();
            return fragment;
        }
        int[] rank = new int[scan.count];
        for (int i = 0; i < scan.count; i++) {
            Integer r = ranks.get(scan.names[i]);
            if (r == null) {                       // an unrankable child: decline the fragment
                SKIPPED.incrementAndGet();
                return fragment;
            }
            rank[i] = r;
        }
        boolean sorted = true;
        for (int i = 1; i < scan.count && sorted; i++) sorted = rank[i - 1] <= rank[i];
        if (sorted) return fragment;

        // Stable permutation: equal ranks (repeated elements, xsd:choice members) keep source order.
        Integer[] perm = new Integer[scan.count];
        for (int i = 0; i < scan.count; i++) perm[i] = i;
        Arrays.sort(perm, (a, b) -> Integer.compare(rank[a], rank[b]));

        // Rebuild by SLOT: the prefix, then child(perm[k]) followed by the gap that originally
        // followed slot k. Whitespace and the root's own tags therefore stay exactly where they
        // were; only the child spans move.
        byte[] out = new byte[fragment.length];
        int p = scan.starts[0];
        System.arraycopy(fragment, 0, out, 0, p);
        for (int k = 0; k < scan.count; k++) {
            int src = perm[k];
            int len = scan.ends[src] - scan.starts[src];
            System.arraycopy(fragment, scan.starts[src], out, p, len);
            p += len;
            int gapFrom = scan.ends[k];
            int gapTo = k + 1 < scan.count ? scan.starts[k + 1] : fragment.length;
            System.arraycopy(fragment, gapFrom, out, p, gapTo - gapFrom);
            p += gapTo - gapFrom;
        }
        if (p != fragment.length) {                // a guard before shipping bytes
            SKIPPED.incrementAndGet();
            return fragment;
        }
        REORDERED.incrementAndGet();
        return out;
    }

    /// Top-level child spans and names of one fragment.
    private static final class Scan {
        int count;
        int[] starts;
        int[] ends;
        String[] names;
    }

    /// Byte-level scan for the root element's direct children. Returns null when the fragment holds
    /// anything this scanner does not model (comment, CDATA, processing instruction, malformed tag)
    /// — the caller then leaves the fragment alone.
    private static Scan scan(byte[] b) {
        int n = b.length;
        int i = 0;
        while (i < n && b[i] != '<') i++;
        if (i >= n) return null;
        if (i + 1 < n && (b[i + 1] == '!' || b[i + 1] == '?')) return null;
        int rootEnd = tagEnd(b, i);
        if (rootEnd < 0) return null;
        if (b[rootEnd - 2] == '/') return new Scan();  // self-closing root: no children

        Scan s = new Scan();
        int cap = 8;
        s.starts = new int[cap];
        s.ends = new int[cap];
        s.names = new String[cap];
        int depth = 0;                 // nesting BELOW the root
        int curStart = -1;
        String curName = null;
        i = rootEnd;
        while (i < n) {
            while (i < n && b[i] != '<') i++;
            if (i >= n) break;
            if (i + 1 >= n) return null;
            byte c = b[i + 1];
            if (c == '!' || c == '?') return null;    // comment / CDATA / PI: decline
            if (c == '/') {
                int e = tagEnd(b, i);
                if (e < 0) return null;
                if (depth == 0) break;                // the root's own close tag
                depth--;
                if (depth == 0 && curStart >= 0) {
                    if (s.count == cap) {
                        cap *= 2;
                        s.starts = Arrays.copyOf(s.starts, cap);
                        s.ends = Arrays.copyOf(s.ends, cap);
                        s.names = Arrays.copyOf(s.names, cap);
                    }
                    s.starts[s.count] = curStart;
                    s.ends[s.count] = e;
                    s.names[s.count] = curName;
                    s.count++;
                    curStart = -1;
                }
                i = e;
                continue;
            }
            int e = tagEnd(b, i);
            if (e < 0) return null;
            boolean selfClosing = b[e - 2] == '/';
            if (depth == 0) {
                curStart = i;
                curName = name(b, i + 1);
                if (curName == null) return null;
            }
            if (!selfClosing) {
                depth++;
            } else if (depth == 0) {
                if (s.count == cap) {
                    cap *= 2;
                    s.starts = Arrays.copyOf(s.starts, cap);
                    s.ends = Arrays.copyOf(s.ends, cap);
                    s.names = Arrays.copyOf(s.names, cap);
                }
                s.starts[s.count] = curStart;
                s.ends[s.count] = e;
                s.names[s.count] = curName;
                s.count++;
                curStart = -1;
            }
            i = e;
        }
        return curStart >= 0 ? null : s;              // an unclosed child means we mis-parsed
    }

    /// Index just PAST the '&gt;' of the tag starting at `from`, or -1. Quote-aware.
    private static int tagEnd(byte[] b, int from) {
        int n = b.length;
        byte quote = 0;
        for (int i = from + 1; i < n; i++) {
            byte c = b[i];
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            } else if (c == '<') {
                return -1;                            // '<' inside a tag: not XML we understand
            }
        }
        return -1;
    }

    /// The element name starting at `from` (ASCII; NeTEx element names are ASCII).
    private static String name(byte[] b, int from) {
        int n = b.length;
        int i = from;
        while (i < n) {
            byte c = b[i];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '>' || c == '/') break;
            i++;
        }
        if (i == from) return null;
        return new String(b, from, i - from, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
