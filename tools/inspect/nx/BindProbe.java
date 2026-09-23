package inspect.nx;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.util.ValidationEventCollector;

import toolkit.codec.BindEvents;
import toolkit.codec.EnumWhitespace;
import toolkit.codec.FragmentCodec;
import toolkit.model.ModelRegistry;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import java.io.ByteArrayInputStream;
import java.io.BufferedWriter;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/// `nx bind probe` — `nx bind`'s version arm.
///
/// No store, so it reaches a schema version no store was ever built at, where a narrowing enum
/// token set, a narrowing cardinality, an element-case change or a type change is otherwise
/// unobservable. It drives the same two arms `nx bind sweep` drives — B0 (plain JAXB, no
/// interception, with a [ValidationEventCollector] attached so "nothing was raised" is a
/// measurement) and B ([FragmentCodec#decode], what the toolkit binds) — over synthetic
/// single-object fragments, plus a third the sweep has no use for: M, re-marshalling the B bean and
/// asking whether the element is still there.
///
/// Run it under either model jar and it answers at that version: it names no version-specific
/// symbol and resolves every class and field reflectively, so the same binary runs at both, and CI
/// diffs the two runs.
///
/// Four refusals, each exit 2 rather than a zero:
///
/// 1. a named CLASS that does not exist at the running version;
/// 2. a named FIELD that does not exist on it — a renamed property is exactly how a shape stops
///    being probed while the probe stays green;
/// 3. an abstract owner with no concrete subclass. Otherwise the owner is resolved to a concrete
///    subclass so JAXB has something to instantiate, and which one is printed;
/// 4. either positive control failing: `ctl-bind`, a plain enum token, must bind on both arms, and
///    `ctl-104`, the same token with a trailing space, must be repaired — B0 null, B bound.
///
/// What it cannot see:
///
/// - Corpus scale. One synthetic object per row: it says what the binding does with a lexical,
///    never how often that lexical occurs.
/// - The store. The store persists source bytes and re-marshals only what a transformer created,
///    while this marshals unconditionally, so the M arm answers "IF this object were
///    re-marshalled".
/// - Anything nested. The fragments are one element deep plus one child.
/// - Which owner XJC binds when a name is declared twice. It probes the field it is told to probe.
///
/// A diagnostic, but unlike `sweep` it HAS pass/fail: exit 0 means every row produced a verdict and
/// both controls held. It never fails on a FINDING.
public final class BindProbe {

    // ================================================================== verdicts

    /// Both arms bound a non-null value.
    static final String BOUND = "BOUND";
    /// B0 null, B bound — an interception caught it.
    static final String REPAIRED = "REPAIRED";
    /// Both arms null, from a non-empty lexical. The predicate `nx bind` exists for.
    static final String SILENT_NULL = "SILENT_NULL";
    /// B0 bound, B null — our own interception lost a value. Nothing should produce this.
    static final String TOOLKIT_NULL = "TOOLKIT_NULL";
    /// The element name resolved to no field at all at this version.
    static final String NO_FIELD = "NO_FIELD";

    private static final String RULE = "=".repeat(100);

    // ================================================================== the shapes

    /// One probe row.
    ///
    /// @param id        stable across versions; CI joins the two runs on it
    /// @param shape     the shape family, so the report can be read by shape
    /// @param cell      the source cell number, or `-` for the controls
    /// @param owner     the class named. Resolved to a concrete subclass if it is abstract
    /// @param element   the XML element name, as the source spells it
    /// @param field     the Java field name, checked to exist so a rename refuses
    /// @param values    the lexical(s) placed in the fragment; two entries drive the cardinality arm
    /// @param note      what the row is for
    record Shape(String id, String shape, String cell, String owner, String element, String field,
                 String[] values, String note) {}

    /// The table: one row per identified cell, plus the two controls.
    ///
    /// Enumerated, not discovered: the subject is a finite list of cells a schema diff identified,
    /// and cannot be re-derived from the model.
    static final Shape[] SHAPES = {
        // ---- the two positive controls.
        new Shape("ctl-bind", "control", "-", "Line_VersionStructure", "TransportMode",
                "transportMode", new String[] {"bus"},
                "POSITIVE CONTROL — must BOUND at every version"),
        new Shape("ctl-104", "control", "-", "Line_VersionStructure", "TransportMode",
                "transportMode", new String[] {"bus "},
                "POSITIVE CONTROL — must be REPAIRED at every version (#104)"),

        // ---- shape 1: enum token sets that NARROW. 8 cells; these are the reachable ones.
        new Shape("enum-95", "enum", "95", "ServiceLink_VersionStructure", "VehicleMode",
                "vehicleMode", new String[] {"car"},
                "RANK 1 — the toolkit CONSTRUCTS ServiceLink"),
        new Shape("enum-52", "enum", "52", "JourneyTiming_VersionedChildStructure", "VehicleMode",
                "vehicleMode", new String[] {"car"},
                "RANK 1 — the toolkit CONSTRUCTS a JourneyTiming"),
        new Shape("enum-116", "enum", "116", "TimingLink_VersionStructure", "VehicleMode",
                "vehicleMode", new String[] {"bicycle"},
                "a second lost token, on a pass-through class"),
        new Shape("enum-25", "enum", "25", "DriverTripTime_VersionStructure", "TransportMode",
                "transportMode", new String[] {"other"},
                "RANK 2 — the ONLY lost token this corpus carries (3,034x), latent only "
                        + "because <DriverTripTime> occurs 0 times"),

        // ---- shape 2: the TYPE changes. Two of xs:duration -> xs:decimal, one hexBinary.
        new Shape("type-2", "type", "2", "AccountableElementStructure", "AccountingFactor",
                "accountingFactor", new String[] {"PT1M"},
                "THE #155 DECISION — xs:duration -> xs:decimal"),
        new Shape("type-26", "type", "26", "DriverTrip_VersionStructure", "AccountingFactor",
                "accountingFactor", new String[] {"PT1M"},
                "the second accountingFactor cell"),
        new Shape("type-79", "type", "79", "PrintPresentationStructure", "BackgroundColour",
                "backgroundColour", new String[] {"ff0000"},
                "the FIFTH shape: hexBinary -> normalizedString — the one the short "
                        + "'four shapes' summary drops"),

        // ---- shape 3: cardinality that NARROWS. Two rows: the narrowing has two shapes, and the
        //      one the schemas changed is an xs:LIST of an enumeration collapsing to ONE
        //      enumeration, whose values are space-separated INSIDE ONE ELEMENT rather than spread
        //      over sibling elements.
        new Shape("card-127-list", "cardinality", "127", "UsageValidityPeriod_VersionStructure",
                "ActivationMeans", "activationMeans", new String[] {"checkIn useOfValidator"},
                "THE ACTUAL SHAPE — one element, an xs:list of two tokens. Both tokens are among "
                        + "the 8 facets ActivationMeansEnumeration carries at BOTH versions, so "
                        + "the only thing this row measures is how many survive"),
        new Shape("card-127-repeat", "cardinality", "127", "UsageValidityPeriod_VersionStructure",
                "ActivationMeans", "activationMeans", new String[] {"checkIn", "useOfValidator"},
                "the repeated-sibling shape — a SECOND sibling element rather than one element "
                        + "carrying two tokens. Kept beside the row above because the two answers "
                        + "are only comparable if both shapes are measured"),

        // ---- shape 4: the <BookingArrangements> element-CASE change. An empty element is enough:
        //      if the name binds, the field holds an object; if it does not, the field is null.
        new Shape("case-113-upper", "case", "113",
                "StopPointInJourneyPattern_VersionedChildStructure", "BookingArrangements",
                "bookingArrangements", new String[] {"<EMPTY/>"},
                "the 1.15.2 spelling"),
        new Shape("case-113-lower", "case", "113",
                "StopPointInJourneyPattern_VersionedChildStructure", "bookingArrangements",
                "bookingArrangements", new String[] {"<EMPTY/>"},
                "the 2.0 spelling; at 2.0 the overlay declares BOTH properties"),
    };

    /// The two rows whose failure is a broken instrument rather than a finding.
    private static final String CTL_BIND = "ctl-bind", CTL_104 = "ctl-104";

    // ================================================================== one result

    static final class Row {
        final Shape s;
        String resolvedOwner = "", declaredType = "", lexicals = "", verdict = "", b0 = "", b = "";
        /// Every Java field on the bound class carrying the probed element name, in declared order.
        String fields = "";
        String remarshal = "";       // PRESENT / ABSENT / n/a
        int b0Events, bEvents, bound0, bound1;
        String detail = "";

        Row(Shape s) { this.s = s; }
    }

    // ================================================================== CLI

    static int run(String[] args) throws Exception {
        Path tsv = null;
        Set<String> only = new LinkedHashSet<>();
        boolean listOnly = false;
        List<Shape> extra = new ArrayList<>();
        // Control expectations, overridable only so the self-test can drive the control machinery
        // through this entry point and watch it say no.
        String expectBind = BOUND, expect104 = REPAIRED;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tsv" -> tsv = Path.of(args[++i]);
                case "--only" -> only.addAll(List.of(args[++i].split(",")));
                case "--list" -> listOnly = true;
                case "--inject" -> {
                    // The self-test's poison: it drives the refusal paths through the real entry
                    // point.
                    String[] p = args[++i].split(":", 5);
                    if (p.length != 5) {
                        System.err.println("nx bind probe: --inject wants id:owner:field:element:value");
                        return 2;
                    }
                    extra.add(new Shape(p[0], "injected", "-", p[1], p[3], p[2],
                            new String[] {p[4]}, "injected by --inject"));
                    only.add(p[0]);
                    only.add(CTL_BIND);
                    only.add(CTL_104);
                }
                case "--control-expect" -> {
                    String[] p = args[++i].split("=", 2);
                    if (p.length != 2) {
                        System.err.println("nx bind probe: --control-expect wants id=VERDICT");
                        return 2;
                    }
                    if (CTL_BIND.equals(p[0])) expectBind = p[1];
                    else if (CTL_104.equals(p[0])) expect104 = p[1];
                    else {
                        System.err.println("nx bind probe: --control-expect names no control: " + p[0]);
                        return 2;
                    }
                }
                default -> {
                    System.err.println("nx bind probe: unknown option " + args[i]);
                    return 2;
                }
            }
        }
        List<Shape> table = new ArrayList<>(List.of(SHAPES));
        table.addAll(extra);
        PrintStream o = System.out;
        if (listOnly) {
            for (Shape s : table) o.printf("%-16s %-12s %-4s %s.%s%n", s.id(), s.shape(), s.cell(),
                    s.owner(), s.field());
            return 0;
        }
        if (!only.isEmpty() && (!only.contains(CTL_BIND) || !only.contains(CTL_104))) {
            System.err.println("PROBE INOPERATIVE: --only must include both positive controls ("
                    + CTL_BIND + ", " + CTL_104 + ").");
            System.err.println("  Without them a run that reports nothing is indistinguishable from "
                    + "a run that could");
            System.err.println("  not see anything, which is the whole failure mode this probe is "
                    + "written against.");
            return 2;
        }

        o.println(RULE);
        o.println("nx bind probe — the version arm (#155).  Synthetic fragments, no store.");
        o.println(RULE);
        o.printf("model              %,d registered classes, %,d model classes%n",
                ModelRegistry.classes().size(), ModelRegistry.allModelClasses().size());
        // Which schema version is on this classpath, taken from the model itself rather than from a
        // filename or a property. CI refuses on this line.
        boolean sitePathLinkEnd = exists("SitePathLinkEndStructure");
        boolean datedCall = exists("DatedCall");
        String ver = sitePathLinkEnd && !datedCall ? "2.0.0"
                : !sitePathLinkEnd && datedCall ? "1.15.2" : "UNKNOWN";
        o.printf("schema marker      SitePathLinkEndStructure=%-5s DatedCall=%-5s  ->  %s%n",
                sitePathLinkEnd, datedCall, ver);
        if ("UNKNOWN".equals(ver)) {
            System.err.println();
            System.err.println("PROBE INOPERATIVE: the model on this classpath carries neither marker "
                    + "cleanly.");
            System.err.println("  SitePathLinkEndStructure is 2.0-only and DatedCall is 1.15.2-only "
                    + "(ci/netex20-check.sh");
            System.err.println("  step 3). A model that has both or neither is not a version this "
                    + "probe can name, and");
            System.err.println("  reporting a version it cannot name is the false green the whole "
                    + "instrument is against.");
            return 2;
        }
        o.println();

        List<Row> rows = new ArrayList<>();
        for (Shape s : table) {
            if (!only.isEmpty() && !only.contains(s.id())) continue;
            Row r = probe(s, o);
            if (r == null) return 2;                 // refused; probe() has said why
            rows.add(r);
        }
        if (rows.isEmpty()) {
            System.err.println("PROBE INOPERATIVE: --only selected no rows. A run over nothing is "
                    + "not a run.");
            return 2;
        }

        report(o, rows, ver);
        if (tsv != null) writeTsv(tsv, rows, ver, o);

        // ---- the positive controls. Checked LAST so the report is printed either way.
        int bad = 0;
        for (Row r : rows) {
            if (r.s.id().equals(CTL_BIND) && !r.verdict.equals(expectBind)) {
                System.err.println();
                System.err.println("PROBE INOPERATIVE: positive control 'ctl-bind' is " + r.verdict
                        + ", expected " + expectBind + ".");
                System.err.println("  <TransportMode>bus</TransportMode> binds at BOTH schema "
                        + "versions: the two mode");
                System.err.println("  enumerations carry the same 21 facets in the XSDs at either "
                        + "version.");
                System.err.println("  A probe that cannot see a SUCCESSFUL bind cannot be believed "
                        + "when it reports a null,");
                System.err.println("  and a scanner that always says 'nothing bound' would otherwise "
                        + "pass every arm here.");
                bad++;
            }
            if (r.s.id().equals(CTL_104) && !r.verdict.equals(expect104)) {
                System.err.println();
                System.err.println("PROBE INOPERATIVE: positive control 'ctl-104' is " + r.verdict
                        + ", expected " + expect104 + ".");
                System.err.println("  A trailing space on an enum lexical is #104: B0 (plain JAXB) "
                        + "binds null, B");
                System.err.println("  (FragmentCodec.decode, which wraps EnumWhitespace) binds. If "
                        + "this row is not");
                System.err.println("  REPAIRED then either the interception is not firing or the two "
                        + "arms are not the");
                System.err.println("  two arms — in both cases every other row on this report is "
                        + "measuring something else.");
                bad++;
            }
        }
        if (bad > 0) return 2;

        o.println();
        o.println("nx bind probe: every row produced a verdict; both positive controls held.");
        o.println("A FINDING IS NOT A FAILURE. This exits 0 on any set of verdicts; it exits 2 only");
        o.println("when it could not measure what it claims to measure.");
        return 0;
    }

    // ================================================================== one shape

    private static Row probe(Shape s, PrintStream o) throws Exception {
        Row r = new Row(s);

        Class<?> owner = find(s.owner());
        if (owner == null) {
            refuse(s, "class " + s.owner() + " does not exist in the model on this classpath.",
                    "That class is a job 1 cell owner, so its absence means the cell moved and this"
                            + " row is\n  no longer probing anything. Re-derive the row; do not "
                            + "delete it silently.");
            return null;
        }
        Field f = declaredField(owner, s.field());
        if (f == null) {
            refuse(s, "class " + owner.getSimpleName() + " has no field '" + s.field() + "'.",
                    "A RENAMED PROPERTY IS EXACTLY HOW A SHAPE STOPS BEING PROBED WHILE THE PROBE\n"
                            + "  STAYS GREEN. Fields declared here: "
                            + declaredNames(owner));
            return null;
        }

        // An abstract owner cannot be unmarshalled into: resolve it to a concrete subclass.
        Class<?> target = owner;
        if (Modifier.isAbstract(owner.getModifiers())) {
            List<Class<?>> subs = ModelRegistry.concreteSubclasses(owner);
            if (subs.isEmpty()) {
                refuse(s, owner.getSimpleName() + " is abstract and has no concrete subclass.",
                        "There is nothing for JAXB to instantiate, so this row cannot be measured "
                                + "at all.");
                return null;
            }
            target = subs.get(0);
        }
        r.resolvedOwner = target.getSimpleName()
                + (target == owner ? "" : "  (concrete subclass of " + owner.getSimpleName() + ")");

        // The field SET, not the field. The named field is the anchor that makes a rename refuse,
        // but the predicate is "did the stated value land anywhere in the bean", and a class can
        // declare two properties for two spellings of one element.
        List<Field> targets = fieldsNamed(target, s.element());
        if (targets.isEmpty()) {
            // Not a refusal: "this element name binds to no field at this version" is a REAL
            // finding — precisely what an element-case change does — and NO_FIELD is the verdict.
            r.declaredType = "(no field carries <" + s.element() + "> at this version)";
            r.lexicals = "-";
            r.resolvedOwner += "";
            r.verdict = NO_FIELD;
            r.remarshal = "n/a";
            r.b0 = r.b = "(no field)";
            return r;
        }
        Field f0 = targets.get(0);
        r.fields = names(targets);
        Class<?> vt = valueType(f0);
        boolean list = List.class.isAssignableFrom(f0.getType());
        r.declaredType = (list ? "List<" : "") + (vt == null ? f0.getType().getSimpleName()
                : vt.getSimpleName()) + (list ? ">" : "")
                + (targets.size() > 1 ? "  [+" + (targets.size() - 1) + " sibling field(s)]" : "");
        boolean xsList = f0.isAnnotationPresent(jakarta.xml.bind.annotation.XmlList.class);
        if (vt != null && vt.isEnum()) {
            Set<String> lex = new TreeSet<>();
            for (Object k : vt.getEnumConstants()) lex.add(lexical(vt, (Enum<?>) k));
            r.lexicals = lex.size() + " lexical(s)" + (xsList ? ", @XmlList" : "");
            for (String v : s.values()) {
                if (v.startsWith("<")) continue;
                // An @XmlList field takes a whitespace-separated sequence of lexicals in ONE
                // element, so check the tokens; a scalar field takes the whole text.
                for (String tok : xsList ? v.trim().split("\\s+") : new String[] {v}) {
                    if (!lex.contains(EnumWhitespace.collapse(tok))) {
                        r.lexicals += "  [" + tok.trim() + " NOT among them]";
                    }
                }
            }
        } else {
            r.lexicals = "-" + (xsList ? " (@XmlList)" : "");
        }

        byte[] frag = fragment(target.getSimpleName(), s.element(), s.values());

        // ---- B0: plain JAXB, no toolkit interception, a collector so silence is a measurement.
        Object b0 = null;
        Unmarshaller naive = FragmentCodec.packageContext().createUnmarshaller();
        ValidationEventCollector vec = new ValidationEventCollector();
        naive.setEventHandler(vec);
        XMLInputFactory xif = new com.fasterxml.aalto.stax.InputFactoryImpl();
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            XMLStreamReader rd = xif.createXMLStreamReader(new ByteArrayInputStream(frag));
            rd.nextTag();
            JAXBElement<?> el = naive.unmarshal(rd, target);
            rd.close();
            b0 = el.getValue();
        } catch (Exception e) {
            r.detail = "B0 threw " + e.getClass().getSimpleName();
        }
        r.b0Events = vec.hasEvents() ? vec.getEvents().length : 0;

        // ---- B: FragmentCodec.decode — what the toolkit actually binds. BindEvents is a
        //      PROCESS-GLOBAL tally, so reset it per row to get a delta.
        FragmentCodec codec = new FragmentCodec();
        codec.scope("bind-probe:" + s.id());
        BindEvents.reset();
        Object b = null;
        try {
            b = codec.decode(codec.encodeRaw(frag), target);
        } catch (RuntimeException e) {
            r.detail = (r.detail.isEmpty() ? "" : r.detail + "; ") + "B threw "
                    + e.getClass().getSimpleName();
        }
        r.bEvents = (int) BindEvents.total();
        List<java.util.Map.Entry<BindEvents.Site, Long>> sites = BindEvents.sites();
        StringBuilder sb = new StringBuilder();
        for (var e : sites) {
            sb.append(sb.length() == 0 ? "" : " | ").append(e.getKey().element()).append(' ')
              .append(e.getKey().cause()).append(" x").append(e.getValue());
        }
        String siteText = sb.toString();
        BindEvents.reset();

        r.bound0 = count(b0, targets);
        r.bound1 = count(b, targets);
        r.b0 = render(b0, targets);
        r.b = render(b, targets);

        boolean n0 = r.bound0 == 0, n1 = r.bound1 == 0;
        r.verdict = !n0 && !n1 ? BOUND
                : n0 && !n1 ? REPAIRED
                : !n0 ? TOOLKIT_NULL
                : SILENT_NULL;

        // ---- M: re-marshal the B bean and ask whether the element survived. Only meaningful when
        //      something bound: an absent element after an absent value is arithmetic.
        if (b != null) {
            try {
                String xml = new String(codec.marshalFragment(b), StandardCharsets.UTF_8);
                boolean present = xml.contains("<" + s.element() + ">")
                        || xml.contains("<" + s.element() + "/>")
                        || xml.contains("<" + s.element() + " ");
                r.remarshal = present ? "PRESENT" : "ABSENT";
                r.detail = (r.detail.isEmpty() ? "" : r.detail + "; ") + "M=" + xml;
            } catch (RuntimeException e) {
                r.remarshal = "marshal threw " + e.getClass().getSimpleName();
            }
        } else {
            r.remarshal = "n/a";
        }
        if (!siteText.isEmpty()) {
            r.detail = (r.detail.isEmpty() ? "" : r.detail + "; ") + "BindEvents[" + siteText + "]";
        }
        return r;
    }

    private static void refuse(Shape s, String what, String why) {
        System.err.println();
        System.err.println("PROBE INOPERATIVE (row " + s.id() + ", job 1 cell #" + s.cell() + "): "
                + what);
        System.err.println("  " + why);
        System.err.println("  Reporting a comfortable zero over a subject that moved is the failure "
                + "mode this");
        System.err.println("  probe is written against — eight instances "
                + "and counting.");
    }

    // ================================================================== the fragment

    /// `<Cls id=".." version="1"><Elem>v</Elem>...</Cls>`, in the NeTEx namespace.
    ///
    /// The root element NAME is irrelevant to both arms — `unmarshal(reader, declaredType)` and
    /// `FragmentCodec.decode` both bind by declared type — so the class simple name is used and
    /// nothing depends on the class having an `@XmlRootElement`. `<EMPTY/>` writes a childless
    /// element, which is how the `<BookingArrangements>` case rows ask "does this NAME bind to a
    /// field" without needing to know a valid child.
    static byte[] fragment(String cls, String element, String[] values) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<").append(cls).append(" xmlns=\"").append(ModelRegistry.NETEX_NS)
          .append("\" id=\"probe:1\" version=\"1\">");
        for (String v : values) {
            if ("<EMPTY/>".equals(v)) sb.append('<').append(element).append("/>");
            else sb.append('<').append(element).append('>').append(esc(v))
                   .append("</").append(element).append('>');
        }
        sb.append("</").append(cls).append(">");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ================================================================== reflection helpers

    private static boolean exists(String simpleName) {
        return find(simpleName) != null;
    }

    private static Class<?> find(String simpleName) {
        Class<?> c = ModelRegistry.classForName(simpleName);
        if (c != null) return c;
        // classForName covers the REGISTERED classes; several cell owners are model classes that
        // are not registered, so fall back to the model package by name.
        try {
            return Class.forName(ModelRegistry.MODEL_PKG + "." + simpleName, false,
                    BindProbe.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /// Declared on this class or on any supertype — a cell names the DECLARING class.
    private static Field declaredField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { /* keep walking */ }
        }
        return null;
    }

    private static String declaredNames(Class<?> c) {
        Set<String> out = new TreeSet<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) out.add(f.getName());
            }
        }
        String s = out.toString();
        return s.length() > 400 ? s.substring(0, 400) + " …" : s;
    }

    /// How many values landed: 0 or 1 for a scalar, the count of NON-NULL entries for a collection.
    ///
    /// JAXB appends a null to the list for an occurrence whose lexical fails to bind, so `size()`
    /// answers "how many elements were in the document", not "how many values the bean holds" —
    /// and a row reading `[null]` would come out BOUND.
    private static int count(Object bean, List<Field> fs) {
        if (bean == null) return 0;
        int n = 0;
        for (Field f : fs) {
            try {
                Object v = f.get(bean);
                if (v == null) continue;
                if (v instanceof List<?> l) { for (Object x : l) if (x != null) n++; }
                else if (v instanceof byte[] a) { if (a.length != 0) n++; }
                else if (v instanceof String s) { if (!s.isEmpty()) n++; }
                else n++;
            } catch (IllegalAccessException ignored) { /* counted as absent */ }
        }
        return n;
    }

    private static String render(Object bean, List<Field> fs) {
        if (bean == null) return "(no bean)";
        StringBuilder sb = new StringBuilder();
        for (Field f : fs) {
            if (sb.length() > 0) sb.append(" | ");
            if (fs.size() > 1) sb.append(f.getName()).append('=');
            try {
                Object v = f.get(bean);
                if (v == null) sb.append("null");
                else if (v instanceof byte[] a) sb.append("hex[").append(hex(a)).append(']');
                else if (v instanceof List<?> l) sb.append(l);
                else sb.append(v);
            } catch (IllegalAccessException e) {
                sb.append("(inaccessible)");
            }
        }
        return sb.toString();
    }

    /// Every field on `c` (and its supertypes) whose XML element name is `element`.
    ///
    /// More than one is legal: the 2.0 `<BookingArrangements>` overlay declares two properties, one
    /// per spelling, so it can express a document a single-spelling model cannot.
    private static List<Field> fieldsNamed(Class<?> c, String element) {
        List<Field> out = new ArrayList<>(2);
        Set<String> seen = new LinkedHashSet<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!seen.add(f.getName())) continue;           // a subclass override wins
                String n = BeanRender.xmlName(f);
                if (element.equals(n) || ("@" + element).equals(n)) {
                    f.setAccessible(true);
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static String names(List<Field> fs) {
        StringBuilder sb = new StringBuilder();
        for (Field f : fs) sb.append(sb.length() == 0 ? "" : ",").append(f.getName());
        return sb.toString();
    }

    private static String hex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String lexical(Class<?> enumType, Enum<?> constant) {
        try {
            XmlEnumValue v = enumType.getField(constant.name()).getAnnotation(XmlEnumValue.class);
            return v != null ? v.value() : constant.name();
        } catch (NoSuchFieldException e) {
            return constant.name();
        }
    }

    static Class<?> valueType(Field f) {
        Class<?> t = f.getType();
        Type g = f.getGenericType();
        if (List.class.isAssignableFrom(t) || t == JAXBElement.class) {
            return g instanceof ParameterizedType p ? erase(p.getActualTypeArguments()[0]) : null;
        }
        return t;
    }

    private static Class<?> erase(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof WildcardType w) return erase(w.getUpperBounds()[0]);
        if (t instanceof ParameterizedType p) {
            return p.getRawType() == JAXBElement.class
                    ? erase(p.getActualTypeArguments()[0]) : erase(p.getRawType());
        }
        return null;
    }

    // ================================================================== report

    private static void report(PrintStream o, List<Row> rows, String ver) {
        o.println(RULE);
        o.println("THE ROWS   (B0 = plain JAXB, no interception · B = FragmentCodec.decode · "
                + "M = re-marshal)");
        o.println(RULE);
        o.printf("%-16s %-12s %-38s %-13s %-9s %-7s %-7s %-9s%n",
                "row", "shape", "field type at " + ver, "verdict", "in->B0/B", "B0 evt", "B evt",
                "re-marshal");
        for (Row r : rows) {
            o.printf("%-16s %-12s %-38s %-13s %-9s %-7d %-7d %-9s%n", r.s.id(), r.s.shape(),
                    r.declaredType, r.verdict,
                    r.s.values().length + "->" + r.bound0 + "/" + r.bound1,
                    r.b0Events, r.bEvents, r.remarshal);
            o.printf("    %s  <%s>%s  ->  field(s) {%s}  B0=%s  B=%s   %s%n",
                    r.resolvedOwner, r.s.element(), lex(r.s.values()),
                    r.fields.isEmpty() ? "-" : r.fields, r.b0, r.b, r.lexicals);
            if (!r.detail.isEmpty()) o.println("    " + r.detail);
        }
        o.println();

        o.println(RULE);
        o.println("BY VERDICT");
        o.println(RULE);
        for (String v : new String[] {BOUND, REPAIRED, SILENT_NULL, TOOLKIT_NULL, NO_FIELD}) {
            List<String> ids = new ArrayList<>();
            for (Row r : rows) if (r.verdict.equals(v)) ids.add(r.s.id());
            o.printf("  %-13s %2d   %s%n", v, ids.size(), ids);
        }
        long silentAndUnreported = 0;
        for (Row r : rows) {
            if (r.verdict.equals(SILENT_NULL) && r.b0Events == 0 && r.bEvents == 0) {
                silentAndUnreported++;
            }
        }
        o.println();
        o.printf("  SILENT_NULL rows with ZERO events on BOTH instruments: %d%n", silentAndUnreported);
        o.println("      This is the number the enum-whitespace repair exists for.");
        o.println("      A value the source stated, gone from the bean, and no ValidationEvent on");
        o.println("      either the collector (B0) or the production handler (B). Nothing in the");
        o.println("      stack reports it; only asking the BEAN what it holds can see it.");
        o.println();
    }

    private static String lex(String[] v) {
        return v.length == 1 ? "'" + v[0] + "'" : Arrays.toString(v);
    }

    private static void writeTsv(Path p, List<Row> rows, String ver, PrintStream o)
            throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write("row\tshape\tcell\tversion\towner\tfields\telement\tvalues\tdeclared_type"
                    + "\tverdict\tb0_events\tb_events\tbound_b0\tbound_b\tremarshal\n");
            for (Row r : rows) {
                w.write(String.join("\t", r.s.id(), r.s.shape(), r.s.cell(), ver,
                        r.resolvedOwner.replace('\t', ' '),
                        r.fields.isEmpty() ? "-" : r.fields, r.s.element(),
                        String.join(",", r.s.values()), r.declaredType, r.verdict,
                        Integer.toString(r.b0Events), Integer.toString(r.bEvents),
                        Integer.toString(r.bound0), Integer.toString(r.bound1), r.remarshal));
                w.write('\n');
            }
        }
        o.printf("  wrote %d row(s) to %s%n", rows.size(), p.toAbsolutePath());
    }

    private BindProbe() {}
}
