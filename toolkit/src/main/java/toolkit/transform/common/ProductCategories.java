package toolkit.transform.common;

// Labelling a Line from the product category its service belongs to.
//
// OTP reads a route's short name off Line/PublicCode and its long name off Line/Name, and reads
// TypeOfProductCategory nowhere. A feed that publishes the product only as a category therefore
// names its trains nothing a rider sees, which is what this repairs: the category's ShortName is
// the abbreviation (EC) and its Name the written-out form (EuroCity).
//
// A Line carrying a PublicCode of its own keeps it. The category is a line's public identity only
// where the feed gave it none -- in the OEBB export every long-distance line carries no PublicCode
// and is named by an internal corridor code (A1, A11, D9), while every numbered product (S1, REX6,
// CJX9) carries one.
//
// Where an uncoded line's journeys span several categories the line is split: the heaviest category
// keeps the source Line and every other gets one minted for it, so a journey's route badge is the
// product it runs under. Splitting changes which ServiceJourneyPattern id the EPIP journey pass
// derives for a moved journey, because the pattern hash folds in the line ref.
//
// A split re-points a journey's own LineRef and nothing else, so it is taken only where every
// journey in the store reaches its line that way. A Route carries one lineRef for journeys that may
// run under several categories and cannot be split with them; where the store holds any Route or
// pattern route-view, an uncoded multi-category line takes its heaviest category whole instead.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.Codespace;
import noi.netex.model.FlexibleLine;
import noi.netex.model.FlexibleLineRefStructure;
import noi.netex.model.Line;
import noi.netex.model.LineRefStructure;
import noi.netex.model.Line_VersionStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourney_VersionStructure;
import noi.netex.model.TemplateServiceJourney;
import noi.netex.model.TypeOfProductCategory;
import noi.netex.model.TypeOfValue_VersionStructure;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProductCategories {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// The class whose definitions name a product. Both places a publisher puts them are read,
    /// which is what [TypeOfValues] is for.
    private static final List<String> CATEGORY_CLASS = List.of("TypeOfProductCategory");

    private ProductCategories() {}

    /// What a category calls a product: the abbreviation a rider reads on the vehicle, and the
    /// written-out form.
    ///
    /// @param abbreviation the category's ShortName, else the identifier part of its id
    /// @param name the written-out form, null where the category carries none worth reading
    public record Label(String abbreviation, String name) {}

    /// The label of every product category the document defines, by category id.
    ///
    /// `name` is null where the category's Name is absent, blank, or repeats the abbreviation. A
    /// feed publishing `ShortName` ZUG beside `Name` ZUG offers no written-out form, and neither
    /// does one publishing no `Name` at all.
    public static Map<String, Label> labels(Store db, Txn txn) {
        Map<String, Label> out = new LinkedHashMap<>();
        for (Map.Entry<String, TypeOfValue_VersionStructure> e
                : TypeOfValues.index(db, txn, CATEGORY_CLASS).entrySet()) {
            if (!(e.getValue() instanceof TypeOfProductCategory cat)) continue;
            String abbreviation = Mls.text(cat.getShortName());
            if (abbreviation == null || abbreviation.isBlank()) {
                abbreviation = localPart(e.getKey());
            }
            if (abbreviation == null || abbreviation.isBlank()) continue;
            String name = Mls.text(cat.getName());
            if (name != null && (name.isBlank() || name.equalsIgnoreCase(abbreviation))) {
                name = null;
            }
            out.put(e.getKey(), new Label(abbreviation, name));
        }
        return out;
    }

    /// The categories a Line's service runs under, by Line id, each with the number of journeys
    /// that named it. A Line stating its own category answers with that alone, counted once;
    /// otherwise the answer is what its journeys state.
    ///
    /// Both journey classes are read: a TemplateServiceJourney names a line exactly as a
    /// ServiceJourney does.
    public static Map<String, Map<String, Long>> categoriesByLine(Store db, Txn txn,
                                                                  JourneyLines.Index ix) {
        Map<String, Map<String, Long>> out = new HashMap<>();
        // The lines that answer for themselves, so the journey pass leaves them alone.
        Map<String, String> stated = new HashMap<>();
        for (Object o : lines(db, txn)) {
            Line_VersionStructure line = (Line_VersionStructure) o;
            if (line.getTypeOfProductCategoryRef() != null
                    && line.getTypeOfProductCategoryRef().getRef() != null) {
                stated.put(line.getId(), line.getTypeOfProductCategoryRef().getRef());
            }
        }
        for (Class<?> c : List.of(ServiceJourney.class, TemplateServiceJourney.class)) {
            for (Object o : db.iterOnlyObjects(txn, c)) {
                ServiceJourney_VersionStructure sj = (ServiceJourney_VersionStructure) o;
                if (sj.getTypeOfProductCategoryRef() == null) continue;
                String cat = sj.getTypeOfProductCategoryRef().getRef();
                String lineId = JourneyLines.lineIdOf(sj, ix);
                if (cat == null || lineId == null || stated.containsKey(lineId)) continue;
                out.computeIfAbsent(lineId, k -> new LinkedHashMap<>()).merge(cat, 1L, Long::sum);
            }
        }
        for (Map.Entry<String, String> e : stated.entrySet()) {
            Map<String, Long> own = new LinkedHashMap<>();
            own.put(e.getValue(), 1L);
            out.put(e.getKey(), own);
        }
        return out;
    }

    /// The Lines this pass relabelled, the Lines it minted for a split, and the journeys it moved
    /// onto one — for a caller to overlay by class.
    ///
    /// @param db the store to read
    /// @param txn the transaction to read at
    /// @param fallback the codespace a minted Line's derived id falls back to
    public static List<Object> deriveLineLabels(Store db, Txn txn, Codespace fallback) {
        Map<String, Label> labels = labels(db, txn);
        // One index for all three passes below: building it scans every Route and every
        // ServiceJourneyPattern.
        JourneyLines.Index ix = JourneyLines.index(db, txn);
        Map<String, Map<String, Long>> byLine = categoriesByLine(db, txn, ix);
        boolean splittable = everyJourneyNamesItsLineDirectly(ix);

        List<Object> out = new ArrayList<>();
        // (line row, category id) -> the minted Line a journey of that pair moves onto. Keyed by the
        // ROW and not by the line id: the store keys a Line by (id, version, class) and a Mentz
        // export ships up to 37 versions of one id, each of which mints its own copy. An id-keyed
        // map would keep the last row's copies and point every journey of the id at them.
        Map<LineRow, Map<String, Line_VersionStructure>> minted = new HashMap<>();
        long labelled = 0;
        long named = 0;
        long split = 0;
        long whole = 0;

        for (Object o : lines(db, txn)) {
            Line_VersionStructure line = (Line_VersionStructure) o;
            Map<String, Long> counts = byLine.get(line.getId());
            if (counts == null || counts.isEmpty()) continue;

            if (Codes.hasText(line.getPublicCode())) {
                if (nameTheProductItAlreadyCodes(line, counts, labels)) {
                    named++;
                    out.add(line);
                }
                continue;
            }

            List<String> ranked = ranked(counts);
            Label top = labels.get(ranked.get(0));
            if (top == null) continue;
            applyLabel(line, top);
            labelled++;
            out.add(line);

            if (ranked.size() == 1) continue;
            if (!splittable) {
                whole++;
                Log.info("[product-lines] %s runs %d categories and takes %s whole: this store has "
                        + "Routes, whose journeys cannot be moved with it",
                        line.getId(), ranked.size(), top.abbreviation());
                continue;
            }
            Map<String, Line_VersionStructure> perCategory = new LinkedHashMap<>();
            for (String cat : ranked.subList(1, ranked.size())) {
                Label label = labels.get(cat);
                if (label == null) continue;
                Line_VersionStructure copy = mint(line, cat, label, fallback);
                perCategory.put(cat, copy);
                out.add(copy);
                split++;
            }
            if (!perCategory.isEmpty()) minted.put(LineRow.of(line), perCategory);
            Log.info("[product-lines] %s runs %d categories: %s keeps the line, %s split off",
                    line.getId(), ranked.size(), top.abbreviation(), perCategory.keySet());
        }

        long moved = minted.isEmpty() ? 0 : repointJourneys(db, txn, ix, minted, out);
        Log.info("[product-lines] %,d lines took a product code, %,d were named from the product "
                + "they already code, %,d lines minted for a split moving %,d journeys, %,d "
                + "multi-category lines taken whole",
                labelled, named, split, moved, whole);
        return out;
    }

    /// Whether every journey in the store names its line through its own lineRef, which is what
    /// makes a split a journey-only rewrite.
    private static boolean everyJourneyNamesItsLineDirectly(JourneyLines.Index ix) {
        return ix.routeLine().isEmpty() && ix.sjpLine().isEmpty();
    }

    /// Category ids by descending journey count, ties broken on the id so a run is repeatable.
    private static List<String> ranked(Map<String, Long> counts) {
        List<String> ranked = new ArrayList<>(counts.keySet());
        ranked.sort((a, b) -> {
            int byCount = Long.compare(counts.get(b), counts.get(a));
            return byCount != 0 ? byCount : a.compareTo(b);
        });
        return ranked;
    }

    /// Give `line` the product's abbreviation as its PublicCode and the written-out form as its
    /// Name. Where the category offers no written-out form the Name is emptied rather than left
    /// holding the internal code it had, which would read beside the new PublicCode as if it named
    /// something.
    ///
    /// The element stays: OTP dereferences a Line's Name without a null guard.
    private static void applyLabel(Line_VersionStructure line, Label label) {
        line.setPublicCode(Codes.of(label.abbreviation()));
        if (line.getName() == null) line.setName(Mls.of(null));
        Mls.setText(line.getName(), label.name());
    }

    /// Name a Line after the product it already codes: where its PublicCode IS the abbreviation,
    /// the line is the product rather than a numbered service on it, so the written-out form is its
    /// name. Whether it fired.
    ///
    /// A numbered line is untouched, its PublicCode carrying the number beside the abbreviation
    /// (RE27 against RE).
    private static boolean nameTheProductItAlreadyCodes(
            Line_VersionStructure line, Map<String, Long> counts, Map<String, Label> labels) {
        if (counts.size() != 1) return false;
        Label label = labels.get(counts.keySet().iterator().next());
        if (label == null || label.name() == null) return false;
        String code = Codes.text(line.getPublicCode());
        if (!label.abbreviation().equals(code)) return false;
        String name = Mls.text(line.getName());
        if (name != null && !name.equals(code) && !name.equals(Mls.text(line.getShortName()))) {
            return false;
        }
        if (line.getName() == null) line.setName(Mls.of(null));
        Mls.setText(line.getName(), label.name());
        return true;
    }

    /// A copy of `line` identified for `category` and labelled from it. The copy takes the source's
    /// grammar with the category's own identifier appended, so the two sit in one codespace and
    /// name their origin.
    private static Line_VersionStructure mint(Line_VersionStructure line, String category,
                                              Label label, Codespace fallback) {
        Class<?> clazz = line.getClass();
        String technical = localPart(line.getId()) + "-" + localPart(category);
        Line_VersionStructure copy = (Line_VersionStructure) NetexUtils.project(
                line, clazz,
                Map.of("id", NetexUtils.deriveId(line.getId(), clazz, technical, fallback)));
        copy.setName(Mls.of(null));
        applyLabel(copy, label);
        return copy;
    }

    /// A Line as the store keys it, so a map over several versions of one id addresses the row that
    /// minted a copy rather than whichever row was read last. An absent version attribute is legal
    /// and needs a stable stand-in, the same reason `MentzLineVersions` has one.
    private record LineRow(String id, String version) {
        static LineRow of(Line_VersionStructure line) {
            return new LineRow(line.getId(), line.getVersion() == null ? "" : line.getVersion());
        }
    }

    /// Move every journey whose line was split onto the Line minted for its category, appending the
    /// changed journeys to `out`; how many moved.
    ///
    /// A split only happens where [#everyJourneyNamesItsLineDirectly], so a journey's own lineRef is
    /// the only path to its line here and it is the thing that carries the version. Where it names
    /// one, the row is addressed exactly; where it omits it, the ref names the line rather than one
    /// of its rows, which is answerable only for an id that minted from a single row.
    private static long repointJourneys(Store db, Txn txn, JourneyLines.Index ix,
                                        Map<LineRow, Map<String, Line_VersionStructure>> minted,
                                        List<Object> out) {
        // The unversioned fallback, prepared rather than scanned per journey: an id that minted from
        // two rows is absent, so a versionless ref over it resolves to nothing instead of to a coin
        // toss.
        Map<String, Map<String, Line_VersionStructure>> soleRow = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Map.Entry<LineRow, Map<String, Line_VersionStructure>> e : minted.entrySet()) {
            String id = e.getKey().id();
            if (soleRow.put(id, e.getValue()) != null) ambiguous.add(id);
        }
        soleRow.keySet().removeAll(ambiguous);

        long moved = 0;
        for (Class<?> c : List.of(ServiceJourney.class, TemplateServiceJourney.class)) {
            for (Object o : db.iterOnlyObjects(txn, c)) {
                ServiceJourney_VersionStructure sj = (ServiceJourney_VersionStructure) o;
                if (sj.getTypeOfProductCategoryRef() == null) continue;
                String lineId = JourneyLines.lineIdOf(sj, ix);
                if (lineId == null) continue;
                Map<String, Line_VersionStructure> perCategory = null;
                LineRefStructure own = sj.getLineRef() == null ? null : sj.getLineRef().getValue();
                if (own != null && own.getVersion() != null) {
                    perCategory = minted.get(new LineRow(lineId, own.getVersion()));
                }
                if (perCategory == null) perCategory = soleRow.get(lineId);
                if (perCategory == null) continue;
                Line_VersionStructure target =
                        perCategory.get(sj.getTypeOfProductCategoryRef().getRef());
                if (target == null) continue;
                sj.setLineRef(lineRef(target));
                out.add(sj);
                moved++;
            }
        }
        return moved;
    }

    /// A reference to `line`, versioned as the line is. A FlexibleLine takes a FlexibleLineRef:
    /// both substitute into a journey's lineRef, and the element name has to be the one the
    /// target's class answers to.
    private static JAXBElement<? extends LineRefStructure> lineRef(Line_VersionStructure line) {
        if (line instanceof FlexibleLine) {
            FlexibleLineRefStructure ref = new FlexibleLineRefStructure();
            ref.setRef(line.getId());
            ref.setVersion(line.getVersion());
            return FACTORY.createFlexibleLineRef(ref);
        }
        LineRefStructure ref = new LineRefStructure();
        ref.setRef(line.getId());
        ref.setVersion(line.getVersion());
        return FACTORY.createLineRef(ref);
    }

    /// Give a Line a Name where it has none: its PublicCode, else its ShortName, else the
    /// identifier part of its id. Whether it fired.
    ///
    /// The profile requires the field, and OTP dereferences it without a null guard, so a Line
    /// reaching an export without one fails a graph build.
    public static boolean lineNameOp(Object obj) {
        Line_VersionStructure line = (Line_VersionStructure) obj;
        if (Mls.text(line.getName()) != null) return false;
        String fallback = Codes.text(line.getPublicCode());
        if (fallback == null || fallback.isBlank()) fallback = Mls.text(line.getShortName());
        if (fallback == null || fallback.isBlank()) fallback = localPart(line.getId());
        if (fallback == null || fallback.isBlank()) return false;
        if (line.getName() == null) line.setName(Mls.of(null));
        Mls.setText(line.getName(), fallback);
        return true;
    }

    /// The identifier part of `id`: everything after its class token, with a trailing `':'`
    /// dropped. A Mentz id ends on that colon, so keeping it would put two separators in a derived
    /// id.
    static String localPart(String id) {
        if (id == null) return null;
        String prefix = NetexUtils.codespacePrefix(id);
        if (prefix == null) return id;
        int tokenEnd = id.indexOf(':', prefix.length());
        String local = tokenEnd < 0 ? "" : id.substring(tokenEnd + 1);
        return local.endsWith(":") ? local.substring(0, local.length() - 1) : local;
    }

    /// Every Line of a store, both concrete classes, as one sequence. A FlexibleLine is a Line
    /// with flexible-service fields added, so every field this pass reads is on the shared
    /// supertype.
    private static Iterable<Object> lines(Store db, Txn txn) {
        return () -> new Iterator<>() {
            private final Iterator<Object> plain = db.iterOnlyObjects(txn, Line.class).iterator();
            private final Iterator<Object> flexible =
                    db.iterOnlyObjects(txn, FlexibleLine.class).iterator();

            @Override
            public boolean hasNext() {
                return plain.hasNext() || flexible.hasNext();
            }

            @Override
            public Object next() {
                return plain.hasNext() ? plain.next() : flexible.next();
            }
        };
    }
}
