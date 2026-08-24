package model;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/// The enumeration-facet diff, and the reason it has three verdicts.
///
/// ## What it is for
///
/// A model census records a field's `declaredType` as a type-NAME string, so a sweep over changed
/// cells only sees a field whose type name moved. An enumeration that keeps its name and changes
/// its token set is invisible to that, and it is a wire change: a lexical the new version dropped
/// binds null silently. This reads the token sets themselves.
///
/// ## The three verdicts
///
/// - REMOVED — the concept is gone; a source value has no representation at the new version. A
///    decision to take: forward it, or drop it.
/// - RESPELLED — the same concept under a new lexical: a case fix, a typo fix, a transposition.
///    The value is representable, so this is repairable. This tool repairs nothing: it says
///    "repair candidate" and stops.
/// - ADDED — a pure widening. It cannot lose a bind and needs nothing.
///
/// The first two fail to bind identically and nothing downstream can tell them apart, so the split
/// is made here, at the schema.
///
/// ## How RESPELLED is decided, and what that cannot know
///
/// A type whose lost and gained token sets are both non-empty is a CANDIDATE. Each lost token is
/// paired with a gained one by one of three NAMED lexical relations, and by no other:
///
/// ```
/// CASE           equalsIgnoreCase                          qrcode -> qrCode
/// TYPO           Levenshtein <= 1                          stepingDetector -> steppingDetector
/// TRANSPOSITION  Levenshtein == 2 and the two are anagrams  apnr -> anpr
/// ```
///
/// RESPELLED only when EVERY lost token pairs and EVERY gained token is used — a bijection under
/// those relations. Anything else is MIXED and reported token by token, never averaged away.
///
/// The verdict looks semantic and is not: these are LEXICAL relations. A genuine rename to a
/// distant word reads as REMOVED + ADDED, and a coincidental near-match between an unrelated
/// removal and an unrelated addition reads as RESPELLED. The concept-identity claim is a human's.
/// Conservative by design — a false REMOVED costs a needless decision, a false RESPELLED would
/// license a repair that changes meaning.
///
/// ## Two modes, two subjects
///
/// ```
/// EnumFacetDiff census <a.tsv> <b.tsv> [--out=f] [--label-a=] [--label-b=]
/// EnumFacetDiff xsd    <treeA>  <treeB>  [--out=f] [--ns=]
/// EnumFacetDiff --self-test
/// ```
///
/// `census` reads the enum rows of two model censuses — the BINDING, what XJC generated. `xsd`
/// reads the two committed schema trees — the SCHEMA. Different subjects, not required to agree;
/// where they disagree, the disagreement is the finding. Some schema enumerations carry facets
/// that cannot be Java constants, so XJC binds them as `String`, and a token loss there cannot
/// produce a null bind.
///
/// ## Duplicate declarations
///
/// A type declared TWICE in one tree with different facets makes a naive scan report a phantom
/// narrowing. So `xsd` scans the transitive include/import closure of the ONE schema file the
/// build hands XJC, and keys on (targetNamespace, name). Any duplicate that survives the closure
/// is REFUSED, not merged: a union of two declarations is not a token set.
///
/// ## Refusals
///
/// An empty diff over an empty scan looks exactly like an empty diff over an unchanged model, so
/// every floor below refuses rather than passing whenever it cannot show that it looked.
public final class EnumFacetDiff {

    static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    static final String NETEX_NS = "http://www.netex.org.uk/netex";

    /// A side is not a measurement below this many enumeration types.
    static final int MIN_TYPES = 100;
    /// Two sides sharing fewer names than this are not two versions of one model.
    static final int MIN_COMMON = 200;

    static int rc = 0;

    static void refuse(String msg) {
        System.err.println("EnumFacetDiff INOPERATIVE: " + msg);
        System.exit(2);
    }

    // ------------------------------------------------------------------ the classifier

    enum Verdict { REMOVED, RESPELLED, ADDED, MIXED, DEDUPED, REORDERED }

    record Change(String type, Verdict verdict, int nA, int nB,
                  List<String> lost, List<String> gained, List<String> pairs, String note) {}

    /// Tokens appearing more than once in one declaration. See [#classify].
    static List<String> dups(List<String> t) {
        List<String> seen = new ArrayList<>(), d = new ArrayList<>();
        for (String s : t) {
            if (seen.contains(s)) { if (!d.contains(s)) d.add(s); } else seen.add(s);
        }
        return d;
    }

    static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    static boolean anagram(String a, String b) {
        char[] x = a.toCharArray(), y = b.toCharArray();
        Arrays.sort(x); Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    /// The named lexical relation between a lost and a gained token, or null if there is none.
    static String relation(String lost, String gained) {
        if (lost.equals(gained)) return null;               // not a change at all
        if (lost.equalsIgnoreCase(gained)) return "CASE";
        int d = lev(lost, gained);
        if (d <= 1) return "TYPO(d=1)";
        if (d == 2 && anagram(lost, gained)) return "TRANSPOSITION";
        return null;
    }

    static Change classify(String type, List<String> a, List<String> b) {
        Set<String> sa = new LinkedHashSet<>(a);
        Set<String> sb = new LinkedHashSet<>(b);
        List<String> lost = new ArrayList<>(sa); lost.removeAll(sb);
        List<String> gained = new ArrayList<>(sb); gained.removeAll(sa);
        // Counts are DISTINCT counts: a schema tree can declare the same xs:enumeration facet twice
        // and the next version tidy it up, at which point a raw facet count reads as a LOSS while
        // the token set is identical. The duplicate gets its own verdict rather than being
        // swallowed. The census arm cannot see this — Java enum constants are unique, so XJC has
        // already deduplicated.
        List<String> da = dups(a), dbl = dups(b);
        String note = (da.isEmpty() ? "" : " dupA:" + String.join(",", da))
                + (dbl.isEmpty() ? "" : " dupB:" + String.join(",", dbl));
        int nA = sa.size(), nB = sb.size();
        if (lost.isEmpty() && gained.isEmpty()) {
            if (a.equals(b)) return null;
            if (!da.isEmpty() || !dbl.isEmpty()) {
                // Same token set, different facet multiset: a duplicate facet was added or
                // removed. Zero binding consequence — JAXB binds by lexical.
                return new Change(type, Verdict.DEDUPED, nA, nB,
                        List.of(), List.of(), List.of(), note.trim());
            }
            // Same SET and same multiset. JAXB binds by lexical, not by ordinal, so an order
            // change is not a wire change — but it moves the row, so it is reported.
            return new Change(type, Verdict.REORDERED, nA, nB,
                    List.of(), List.of(), List.of(), "");
        }
        if (lost.isEmpty()) {
            return new Change(type, Verdict.ADDED, nA, nB, List.of(), gained, List.of(), note.trim());
        }
        if (gained.isEmpty()) {
            return new Change(type, Verdict.REMOVED, nA, nB, lost, List.of(), List.of(), note.trim());
        }
        // Both non-empty: a respelling CANDIDATE. Pair greedily, best relation first.
        List<String> pairs = new ArrayList<>();
        Set<String> usedG = new HashSet<>();
        Set<String> pairedL = new HashSet<>();
        for (String l : lost) {
            String best = null, bestRel = null;
            int bestD = Integer.MAX_VALUE;
            for (String g : gained) {
                if (usedG.contains(g)) continue;
                String rel = relation(l, g);
                if (rel == null) continue;
                int d = lev(l, g);
                if (d < bestD) { bestD = d; best = g; bestRel = rel; }
            }
            if (best != null) {
                usedG.add(best);
                pairedL.add(l);
                pairs.add(l + "->" + best + "[" + bestRel + "]");
            }
        }
        boolean bijection = pairedL.size() == lost.size() && usedG.size() == gained.size();
        return new Change(type, bijection ? Verdict.RESPELLED : Verdict.MIXED,
                nA, nB, lost, gained, pairs, note.trim());
    }

    // ------------------------------------------------------------------ input: census E rows

    /// type -> tokens, from the `E` rows of a ModelGate census.tsv.
    static Map<String, List<String>> readCensus(Path p) {
        Map<String, List<String>> out = new TreeMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            refuse("cannot read " + p + ": " + e);
            return out;
        }
        int nC = 0, nF = 0;
        for (String ln : lines) {
            if (ln.startsWith("#")) continue;
            if (ln.startsWith("C\t")) { nC++; continue; }
            if (ln.startsWith("F\t")) { nF++; continue; }
            if (!ln.startsWith("E\t")) continue;
            String[] c = ln.split("\t", -1);
            if (c.length < 5) { refuse(p + " has a malformed E row: " + ln); }
            String type = c[1].startsWith("@") ? c[1].substring(1) : c[1];
            int n = Integer.parseInt(c[3]);
            List<String> toks = c[4].isEmpty() ? List.of() : Arrays.asList(c[4].split(",", -1));
            if (toks.size() != n) {
                refuse(p + ": E row " + type + " declares " + n + " tokens and carries "
                        + toks.size() + ". The column is not self-consistent, so neither is any"
                        + " diff over it.");
            }
            if (out.put(type, toks) != null) {
                refuse(p + ": duplicate E row for " + type + ". A census keyed on type name that"
                        + " carries the same name twice cannot be diffed — merging two"
                        + " declarations would invent a token set that neither one has.");
            }
        }
        // A census with plenty of other row kinds and no E rows predates the enumeration column,
        // and must not read as "no enumeration changed".
        if (out.isEmpty()) {
            refuse(p + " carries " + nC + " C rows and " + nF + " F rows and ZERO E rows."
                    + " Either the census was produced by a ModelGate without the enumeration-facet"
                    + " column, or the rows were filtered out. Nothing below would be a comparison.");
        }
        return out;
    }

    // ------------------------------------------------------------------ input: the schema tree

    static Set<Path> closure(Path rootXsd) {
        Set<Path> seen = new LinkedHashSet<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(rootXsd.toAbsolutePath().normalize());
        DocumentBuilder db = builder();
        while (!stack.isEmpty()) {
            Path f = stack.pop();
            if (!seen.add(f) || !Files.isRegularFile(f)) continue;
            Document d;
            try {
                d = db.parse(f.toFile());
            } catch (Exception e) {
                refuse("cannot parse " + f + ": " + e);
                return seen;
            }
            NodeList kids = d.getDocumentElement().getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!XSD_NS.equals(n.getNamespaceURI())) continue;
                String ln = n.getLocalName();
                if (!ln.equals("include") && !ln.equals("import") && !ln.equals("redefine")) continue;
                String loc = ((Element) n).getAttribute("schemaLocation");
                if (loc.isEmpty()) continue;
                stack.push(f.getParent().resolve(loc).normalize());
            }
        }
        return seen;
    }

    static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /// name -> tokens for every global named simpleType with `xs:enumeration` facets in the
    /// include/import CLOSURE of `root/NeTEx_publication.xsd`, restricted to one target
    /// namespace. Keyed on `(targetNamespace, name)` internally; a surviving duplicate is a
    /// refusal, never a merge.
    static Map<String, List<String>> readXsd(Path root, String ns) {
        Path entry = root.resolve("NeTEx_publication.xsd");
        if (!Files.isRegularFile(entry)) {
            refuse("no " + entry + " — that is the ONE schema model/pom.xml hands XJC, and"
                    + " without it there is no closure to scan and no way to know which of two"
                    + " same-named declarations the model actually binds.");
        }
        Set<Path> cl = closure(entry);
        if (cl.size() < 100) {
            refuse("the include closure of " + entry + " is only " + cl.size() + " file(s)."
                    + " A NeTEx publication schema pulls in ~400. The scan did not run.");
        }
        Map<String, List<String>> out = new TreeMap<>();
        Map<String, String> where = new LinkedHashMap<>();
        DocumentBuilder db = builder();
        List<Path> ordered = new ArrayList<>(cl);
        ordered.sort(Comparator.comparing(Path::toString));
        for (Path f : ordered) {
            Document d;
            try {
                d = db.parse(f.toFile());
            } catch (Exception e) {
                continue;
            }
            Element r = d.getDocumentElement();
            String tns = r.getAttribute("targetNamespace");
            if (!ns.equals(tns)) continue;
            NodeList kids = r.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node n = kids.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!XSD_NS.equals(n.getNamespaceURI()) || !"simpleType".equals(n.getLocalName())) continue;
                String nm = ((Element) n).getAttribute("name");
                if (nm.isEmpty()) continue;
                List<String> toks = new ArrayList<>();
                collectFacets(n, toks);
                if (toks.isEmpty()) continue;
                if (out.containsKey(nm)) {
                    refuse("simpleType " + nm + " is declared TWICE inside the closure of " + entry
                            + " (" + where.get(nm) + " and " + rel(root, f) + ")."
                            + " The union of two declarations is not a token set: merging them"
                            + " invents narrowings that neither declaration has. This is the exact"
                            + " shape that made ContactTypeEnumeration look like a sixth case"
                            + " during the 2.0 flip. Resolve which one XJC binds before diffing.");
                }
                out.put(nm, toks);
                where.put(nm, rel(root, f));
            }
        }
        return out;
    }

    /// The closure walk normalises to absolute paths, so relativize needs the same treatment.
    static String rel(Path root, Path f) {
        return root.toAbsolutePath().normalize().relativize(f.toAbsolutePath().normalize()).toString();
    }

    static void collectFacets(Node n, List<String> out) {
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            if (XSD_NS.equals(k.getNamespaceURI()) && "enumeration".equals(k.getLocalName())) {
                out.add(((Element) k).getAttribute("value"));
            } else {
                collectFacets(k, out);
            }
        }
    }

    // ------------------------------------------------------------------ the diff

    static List<Change> diff(Map<String, List<String>> a, Map<String, List<String>> b,
                             String la, String lb) {
        if (a.size() < MIN_TYPES || b.size() < MIN_TYPES) {
            refuse("only " + a.size() + " / " + b.size() + " enumeration types (floor " + MIN_TYPES
                    + " a side). A diff over a handful of types reporting 'nothing narrowed' is"
                    + " indistinguishable from a scan that found nothing.");
        }
        Set<String> common = new LinkedHashSet<>(a.keySet());
        common.retainAll(b.keySet());
        if (common.size() < MIN_COMMON) {
            refuse("the two sides share only " + common.size() + " type names (floor " + MIN_COMMON
                    + "). " + la + " and " + lb + " are not two versions of the same model, so a"
                    + " 'same name, different tokens' diff over them means nothing.");
        }
        List<Change> out = new ArrayList<>();
        for (String t : common) {
            Change c = classify(t, a.get(t), b.get(t));
            if (c != null) out.add(c);
        }
        out.sort(Comparator.comparing((Change c) -> c.verdict().ordinal())
                .thenComparing(Change::type));
        return out;
    }

    // ------------------------------------------------------------------ self-test

    /// The classifier is the only part with a judgement in it: three positives, three negatives,
    /// and one that must NOT collapse into a respelling.
    static void selfTest() {
        int bad = 0;
        bad += expect("case", classify("T", List.of("a", "qrcode"), List.of("a", "qrCode")),
                Verdict.RESPELLED);
        bad += expect("typo", classify("T", List.of("stepingDetector"), List.of("steppingDetector")),
                Verdict.RESPELLED);
        bad += expect("transposition", classify("T", List.of("apnr", "qrcode"),
                List.of("anpr", "qrCode")), Verdict.RESPELLED);
        bad += expect("pure removal", classify("T", List.of("a", "foldingSeat"), List.of("a")),
                Verdict.REMOVED);
        bad += expect("pure widening", classify("T", List.of("a"), List.of("a", "b")),
                Verdict.ADDED);
        // A distant rename must NOT be waved through as a respelling: that would license a repair
        // changing the meaning of a value.
        bad += expect("distant rename", classify("T", List.of("foldingSeat"), List.of("tipUpSeat")),
                Verdict.MIXED);
        // A real removal standing next to an unrelated addition must not be paired away either.
        bad += expect("removal beside addition",
                classify("T", List.of("customerAccount", "x"), List.of("wholeNewThing", "x")),
                Verdict.MIXED);
        bad += expect("identical", classify("T", List.of("a", "b"), List.of("a", "b")), null);
        bad += expect("reorder only", classify("T", List.of("a", "b"), List.of("b", "a")),
                Verdict.REORDERED);
        // A duplicate facet must not read as a loss: real schema trees declare the same facet
        // twice, and a raw count then reads as a narrowing.
        bad += expect("duplicate facet dropped",
                classify("T", List.of("a", "b", "b"), List.of("a", "b")), Verdict.DEDUPED);
        if (bad != 0) {
            System.err.println("SELF-TEST FAILED: " + bad + " classifier arm(s) wrong."
                    + " Nothing this file prints is trustworthy until that is fixed.");
            System.exit(2);
        }
        System.out.println("EnumFacetDiff --self-test: 10 classifier arms OK"
                + " (CASE / TYPO / TRANSPOSITION positives, distant-rename and"
                + " removal-beside-addition negatives, identical / reorder / duplicate-facet"
                + " controls).");
    }

    static int expect(String name, Change got, Verdict want) {
        Verdict g = got == null ? null : got.verdict();
        if (g == want) { System.out.println("   [ok]   " + name + " -> " + g); return 0; }
        System.out.println("   [FAIL] " + name + " -> " + g + ", expected " + want);
        return 1;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String out = null, la = "A", lb = "B", ns = NETEX_NS;
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--self-test")) { selfTest(); return; }
            else if (a.startsWith("--out=")) out = a.substring(6);
            else if (a.startsWith("--label-a=")) la = a.substring(10);
            else if (a.startsWith("--label-b=")) lb = a.substring(10);
            else if (a.startsWith("--ns=")) ns = a.substring(5);
            else pos.add(a);
        }
        if (pos.size() != 3) {
            System.err.println("usage: EnumFacetDiff <census|xsd> <A> <B> [--out=f]"
                    + " [--label-a=] [--label-b=] [--ns=]   |   EnumFacetDiff --self-test");
            System.exit(2);
        }
        Map<String, List<String>> a, b;
        switch (pos.get(0)) {
            case "census" -> {
                a = readCensus(Path.of(pos.get(1)));
                b = readCensus(Path.of(pos.get(2)));
            }
            case "xsd" -> {
                a = readXsd(Path.of(pos.get(1)), ns);
                b = readXsd(Path.of(pos.get(2)), ns);
            }
            default -> { refuse("unknown mode " + pos.get(0)); return; }
        }
        List<Change> ch = diff(a, b, la, lb);

        Set<String> common = new LinkedHashSet<>(a.keySet());
        common.retainAll(b.keySet());
        List<String> onlyA = new ArrayList<>(a.keySet()); onlyA.removeAll(b.keySet());
        List<String> onlyB = new ArrayList<>(b.keySet()); onlyB.removeAll(a.keySet());

        System.out.println("== enumeration facets: " + la + " -> " + lb + "  (mode " + pos.get(0) + ")");
        System.out.println("   types: " + a.size() + " -> " + b.size()
                + "   common " + common.size() + "   only-" + la + " " + onlyA.size()
                + "   only-" + lb + " " + onlyB.size());
        System.out.println("   NOTE: only-" + la + " and only-" + lb + " are TYPE-NAME changes."
                + " Those DO move a `declaredType` cell and belong to the F rows — job 1's sweep"
                + " already covers them. This tool is about the ones that do not move a cell.");
        System.out.println();
        System.out.println("   token counts below are DISTINCT counts; see `dupA:`/`dupB:` notes.");
        System.out.println();
        System.out.printf("%-46s %-13s %5s %5s  %s%n", "type", "verdict", la, lb, "detail");
        for (Change c : ch) {
            String detail;
            if (c.verdict() == Verdict.RESPELLED) detail = String.join(" ", c.pairs());
            else if (c.verdict() == Verdict.REMOVED) detail = "-" + String.join(",", c.lost());
            else if (c.verdict() == Verdict.ADDED) detail = "+" + String.join(",", c.gained());
            else if (c.verdict() == Verdict.MIXED) detail = "-" + String.join(",", c.lost())
                    + "  +" + String.join(",", c.gained())
                    + (c.pairs().isEmpty() ? "" : "  paired: " + String.join(" ", c.pairs()));
            else if (c.verdict() == Verdict.DEDUPED) detail = "same token set; a duplicate facet";
            else detail = "same token set, different order";
            System.out.printf("%-46s %-13s %5d %5d  %s%s%n",
                    c.type(), c.verdict(), c.nA(), c.nB(), detail,
                    c.note().isEmpty() ? "" : "   [" + c.note() + "]");
        }
        int nrem = 0, nres = 0, nadd = 0, nmix = 0, nreo = 0, ndup = 0;
        for (Change c : ch) {
            switch (c.verdict()) {
                case REMOVED -> nrem++;
                case RESPELLED -> nres++;
                case ADDED -> nadd++;
                case MIXED -> nmix++;
                case DEDUPED -> ndup++;
                case REORDERED -> nreo++;
            }
        }
        System.out.println();
        System.out.println("TYPES_A=" + a.size());
        System.out.println("TYPES_B=" + b.size());
        System.out.println("COMMON=" + common.size());
        System.out.println("ONLY_A=" + onlyA.size());
        System.out.println("ONLY_B=" + onlyB.size());
        System.out.println("CHANGED=" + ch.size());
        System.out.println("REMOVED=" + nrem);
        System.out.println("RESPELLED=" + nres);
        System.out.println("ADDED=" + nadd);
        System.out.println("MIXED=" + nmix);
        System.out.println("DEDUPED=" + ndup);
        System.out.println("REORDERED=" + nreo);
        // The legend is commented because the block above is machine-read by a `sed -n
        // 's/^REMOVED=//p'` gate: an uncommented legend line beginning "RESPELLED=" would be picked
        // up as a second value for the pin.
        System.out.println();
        System.out.println("# REMOVED   the concept is gone; a source value has no " + lb
                + " representation. A DECISION, not a repair.");
        System.out.println("# RESPELLED the value IS representable, spelled differently."
                + " A REPAIR CANDIDATE — `enum-collapse` is the existing machinery,"
                + " and `nx bind`'s REPAIRED row pins at 460. NOTHING IS REPAIRED HERE.");
        System.out.println("# ADDED     pure widening; cannot lose a bind.");
        System.out.println("# MIXED     losses that do not pair. Read them token by token.");
        System.out.println("# DEDUPED   the same token set with a duplicate xs:enumeration facet"
                + " added or removed. No binding consequence; reported so a raw facet count"
                + " cannot be mistaken for a narrowing.");

        if (out != null) {
            List<String> rows = new ArrayList<>();
            rows.add("#type\tverdict\tnA\tnB\tlost\tgained\tpairs\tnote");
            for (Change c : ch) {
                rows.add(c.type() + "\t" + c.verdict() + "\t" + c.nA() + "\t" + c.nB() + "\t"
                        + String.join(",", c.lost()) + "\t" + String.join(",", c.gained()) + "\t"
                        + String.join(" ", c.pairs()) + "\t" + c.note());
            }
            Files.write(Path.of(out), rows);
            System.out.println("wrote " + out + " (" + ch.size() + " rows)");
        }
        System.exit(rc);
    }
}
