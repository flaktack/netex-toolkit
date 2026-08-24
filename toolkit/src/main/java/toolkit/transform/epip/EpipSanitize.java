package toolkit.transform.epip;

// Sanitizes stop assignments for the EPIP export.
//
// Binding decisions (choice groups):
//  - a StopPlace's quays are a mixed list of embedded quays and refs; the ref entries keep their
//    JAXBElement wrappers so the element names survive re-marshalling;
//  - an assignment's quay union splits into a ref half and an embedded half. A taxi-stand ref
//    EXTENDS the quay ref, so "is a real quay ref" is: present AND not a taxi-stand ref. Clearing
//    the union nulls BOTH halves;
//  - a quay is LOCATED by latitude/longitude, else by a gml:pos fallback.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.Level;
import noi.netex.model.LevelInStructure_VersionStructure;
import noi.netex.model.LevelRefStructure;
import noi.netex.model.Levels_RelStructure;
import noi.netex.model.LocationStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.SiteComponent_VersionStructure;
import noi.netex.model.SitePathLink_VersionStructure;
import noi.netex.model.SiteStructure_VersionStructure;
import noi.netex.model.Site_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.TaxiStandRefStructure;
import noi.netex.sites.PathLinkEnds;
import toolkit.codec.FragmentCodec;
import toolkit.model.RecursiveAttributes;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;

public final class EpipSanitize {

    private EpipSanitize() {}

    /// Repair the site and assignment defects that make consumers drop whole journeys:
    ///
    /// 1. an embedded Quay WITHOUT a coordinate is stripped from its StopPlace — such quays are
    ///    whole-stop placeholders, never locatable platforms;
    /// 2. an assignment whose quay ref no StopPlace embeds — after the strip — loses that ref and
    ///    becomes station-level. This converts placeholder-quay assignments and genuinely dangling
    ///    refs alike.
    ///
    /// Yield order: the changed StopPlaces in class-map order, then the cleared assignments, then
    /// the summary line. The assignment cursor opens only after the StopPlace cursor is drained —
    /// one live cursor at a time.
    public static Iterator<Object> epipSanitizeStopAssignments(
            Store dbRead, Txn txn, GeneratorDefaults generatorDefaults) {
        return new Iterator<>() {
            final Set<String> embedded = new HashSet<>();
            long stripped = 0;
            long parentRefsCleared = 0;
            long stops = 0;
            long cleared = 0;
            Iterator<Object> stopPlaces;
            Iterator<Object> psas;
            Object ready;
            boolean logged;

            private void pump() {
                if (stopPlaces == null) {
                    stopPlaces = dbRead.iterOnlyObjects(txn, StopPlace.class).iterator();
                }
                while (ready == null && stopPlaces.hasNext()) {
                    StopPlace sp = (StopPlace) stopPlaces.next();
                    List<JAXBElement<?>> quays = sp.getQuays() != null
                            ? sp.getQuays().getQuayRefOrQuay() : List.of();

                    List<JAXBElement<?>> real = new ArrayList<>();
                    Set<String> removedHere = new HashSet<>();
                    for (JAXBElement<?> el : quays) {
                        if (located(el)) real.add(el);
                        else removedHere.add(((Quay) el.getValue()).getId());
                    }
                    boolean changed = real.size() != quays.size();
                    for (JAXBElement<?> el : real) {
                        if (!(el.getValue() instanceof Quay q)) continue;   // keep refs untouched
                        embedded.add(q.getId());
                        // A surviving quay whose parent quay was just stripped reparents to the
                        // StopPlace implicitly.
                        QuayRefStructure pr = q.getParentQuayRef();
                        if (pr != null && removedHere.contains(pr.getRef())) {
                            q.setParentQuayRef(null);
                            parentRefsCleared++;
                            changed = true;
                        }
                    }
                    if (changed) {
                        stripped += quays.size() - real.size();
                        // Getter-only list: clear and refill.
                        List<JAXBElement<?>> live = sp.getQuays().getQuayRefOrQuay();
                        live.clear();
                        live.addAll(real);
                        stops++;
                        ready = sp;
                    }
                }
                if (ready == null) {
                    if (psas == null) {
                        psas = dbRead.iterOnlyObjects(txn, PassengerStopAssignment.class).iterator();
                    }
                    while (ready == null && psas.hasNext()) {
                        PassengerStopAssignment psa = (PassengerStopAssignment) psas.next();
                        // The ref half of the split union, minus the taxi-stand substitute.
                        JAXBElement<? extends QuayRefStructure> qrEl = psa.getQuayRef();
                        QuayRefStructure quayRef = qrEl == null ? null : qrEl.getValue();
                        if (quayRef != null && !(quayRef instanceof TaxiStandRefStructure)
                                && !embedded.contains(quayRef.getRef())) {
                            psa.setQuayRef(null);   // whole-union clear
                            psa.setQuay(null);
                            cleared++;
                            ready = psa;
                        }
                    }
                }
                if (ready == null && !logged) {
                    logged = true;
                    Log.info("[epip] sanitize_stop_assignments: stripped %d "
                            + "coordinate-less placeholder quays from %d StopPlaces "
                            + "(%d ParentQuayRefs reparented to the stop), "
                            + "cleared %d placeholder/dangling PSA quay refs",
                            stripped, stops, parentRefsCleared, cleared);
                }
            }

            @Override
            public boolean hasNext() {
                pump();
                return ready != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object out = ready;
                ready = null;
                return out;
            }
        };
    }

    /// Ref entries are kept untouched; a Quay is located iff a coordinate can be read, INCLUDING
    /// the gml:pos fallback.
    private static boolean located(JAXBElement<?> el) {
        if (!(el.getValue() instanceof Quay q)) return true;
        return quayCoord(q) != null;
    }

    /// The coordinate read, applied to a Quay; [ScheduledStopPoints#stopplaceCoord] is the
    /// StopPlace-typed twin.
    private static double[] quayCoord(Quay q) {
        SimplePoint_VersionStructure c = q.getCentroid();
        LocationStructure loc = c != null ? c.getLocation() : null;
        if (loc == null) return null;
        if (loc.getLatitude() != null && loc.getLongitude() != null) {
            return new double[] {loc.getLatitude().doubleValue(), loc.getLongitude().doubleValue()};
        }
        if (loc.getPos() != null && loc.getPos().getValue() != null && loc.getPos().getValue().size() >= 2) {
            return new double[] {loc.getPos().getValue().get(0), loc.getPos().getValue().get(1)};
        }
        return null;
    }

    // ==================================================================================
    // ORPHANED LevelRefs — cleared, deliberately. See below for why the absence is conformant.
    //
    // PROV-CAP: strip-orphan-levelrefs
    // ==================================================================================
    //
    // `Level` and `LevelRef` are both OPTIONAL in EPIP, but the profile declares `Level_KeyRef`
    // with selector `.//netex:LevelRef` and requires an internal reference to resolve IN THE SAME
    // DOCUMENT. So a LevelRef whose Level is not in the document is a profile violation, while its
    // ABSENCE is conformant. This pass removes the ones that refer to nothing, and nothing is
    // lost by doing so: no consumer reads a LevelRef, they read a site's levels instead.
    //
    // THREE SHAPES, not one:
    //   (a) the `levelRef` field on an embedded site component — Quay AND Entrance AND AccessSpace
    //       and every other subclass, which is the trap: a Quay-only traversal misses references
    //       on sibling subclasses inside the very fragments it walks;
    //   (b) containment-by-reference — a `<LevelRef>` sitting directly in a site's `<levels>`,
    //       which binds as a list of Level OR ref, so this shape is a member of the same list the
    //       definitions live in;
    //   (c) `<LevelInStructure>`, whose content group carries a MANDATORY `<LevelRef>`, two hops
    //       from a site. Because the ref is mandatory, the repair is to REMOVE THE WHOLE element
    //       rather than null the field.
    //
    // The model declares a ref member in exactly five places, pinned by a test. The traversal is
    // the same reflective walker the insert path uses, with every declared carrier handled BY TYPE.
    // The completeness gate takes its denominator from the store's own STORED FRAGMENT BYTES rather
    // than from the traversal, so a container this walk misses cannot pass silently.
    //
    // Done HERE and not in the merge that creates most of them, so the change is confined to the
    // EXPORT arm.

    /// TEST SEAM, null in every shipped run. Set it and the MAIN pass uses it instead of
    /// [#isOrphan], while the independent pre-pass keeps the real predicate — so the guard must
    /// catch the disagreement.
    private static BiPredicate<Set<String>, String> orphanTest = null;

    /// Install (or, with `null`, remove) the seam above. A shipped run never calls it.
    public static void setOrphanTest(BiPredicate<Set<String>, String> hook) { orphanTest = hook; }

    /// THE SECOND TEST SEAM: [#orphanTest] changes what the pass DECIDES, this changes what it can
    /// SEE. Set it and every node it accepts is skipped by both the pre-pass and the strip — a
    /// traversal narrower than the store, which only the fragment-byte gate catches.
    private static java.util.function.Predicate<Object> reachBlind = null;

    /// Install (or, with `null`, remove) the reach seam above. A shipped run never calls it.
    public static void setReachBlindSpot(java.util.function.Predicate<Object> hook) {
        reachBlind = hook;
    }

    /// True when the seam says this node is invisible to the traversal. Always false when shipped.
    private static boolean blind(Object node) {
        java.util.function.Predicate<Object> hook = reachBlind;
        return hook != null && hook.test(node);
    }

    /// What the pass did and what the guard measured.
    ///
    /// @param sites          Sites examined (every class in the db whose type is a Site)
    /// @param sitesTouched   Sites that lost at least one ref
    /// @param refs           `Σ|refs(s)|` before the pass
    /// @param cleared        refs cleared
    /// @param intact         refs left in place; `refs == cleared + intact` (G1)
    /// @param levelsBefore   `<Level>` elements before (G2)
    /// @param levelsAfter    `<Level>` elements after — MUST equal `levelsBefore`
    /// @param refsAfter      refs the independent post-pass found (G5)
    /// @param orphansAfter   orphaned refs the post-pass found — MUST be 0 (G3)
    /// @param targetsStopPlace  distinct cleared targets, StopPlace-scoped (G6's subject)
    /// @param targetsOtherSite  distinct cleared targets on every other Site class
    /// @param g6Collisions   cleared StopPlace targets that ARE a Level somewhere in the store
    ///                       — MUST be 0 (G6)
    /// @param otherSiteCollisions the same count for the non-StopPlace sites: NOT zero today
    ///                       (a Parking's levels live inside StopPlaces), reported not asserted
    /// @param storeRefsBefore  `<LevelRef>` elements in the STORED FRAGMENT BYTES of EVERY
    ///                       class map before the pass — G7's denominator
    /// @param storeRefsAfter   the same count over the Site class maps after the writes (G7)
    /// @param storeRefsOutside how many of `storeRefsBefore` sit in a class map that is not
    ///                       a Site at all — MUST be 0, or the pass cannot reach them (G7)
    /// @param nestedSites    Site objects found INSIDE another Site by the walk — MUST be 0, or
    ///                       `own(s)` is ambiguous and the per-Site predicate ill-defined (G8)
    public record LevelRefResult(
            long sites, long sitesTouched, long refs, long cleared, long intact,
            long levelsBefore, long levelsAfter, long refsAfter, long orphansAfter,
            long targetsStopPlace, long targetsOtherSite,
            long g6Collisions, long otherSiteCollisions,
            long storeRefsBefore, long storeRefsAfter, long storeRefsOutside, long nestedSites) {

        @Override public String toString() {
            return String.format(
                    "sites=%d touched=%d refs=%d cleared=%d intact=%d "
                    + "<Level> %d -> %d refs after %d (orphan %d) "
                    + "cleared targets: StopPlace %d (G6 collisions %d), other Sites %d "
                    + "(Levels elsewhere %d) "
                    + "store bytes: %d -> %d (outside Sites %d, nested Sites %d)",
                    sites, sitesTouched, refs, cleared, intact,
                    levelsBefore, levelsAfter, refsAfter, orphansAfter,
                    targetsStopPlace, g6Collisions, targetsOtherSite, otherSiteCollisions,
                    storeRefsBefore, storeRefsAfter, storeRefsOutside, nestedSites);
        }
    }

    /// What a READ-ONLY look at a store says about its `LevelRef` population — the pass's three
    /// passes minus the pass. `orphans == 0` means the [Strip] iterator would yield nothing and no
    /// stored byte could move.
    ///
    /// @param sites     Sites examined
    /// @param refs      refs the bean traversal reached
    /// @param orphans   of those, the ones the pass WOULD clear
    /// @param levels    `<Level>` elements
    /// @param storeRefs `<LevelRef>` elements in the stored bytes of EVERY class map
    /// @param storeRefsOutside how many of those sit outside every Site class map
    /// @param nestedSites Sites found inside another Site
    public record LevelRefInspection(long sites, long refs, long orphans, long levels,
                                     long storeRefs, long storeRefsOutside, long nestedSites) {

        @Override public String toString() {
            return String.format(
                    "sites=%d refs=%d would-clear=%d <Level>=%d | store bytes: %d "
                    + "(outside Sites %d, nested Sites %d) | traversal reach %d/%d",
                    sites, refs, orphans, levels, storeRefs, storeRefsOutside, nestedSites,
                    refs, storeRefs);
        }
    }

    /// READ-ONLY. Run the pass's pre-pass and its byte census over an existing store and report;
    /// write nothing, mutate no bean the store owns. `LevelRefProbe --dry-run`
    /// is its front end.
    public static LevelRefInspection inspectLevelRefs(Store db, Txn txn) {
        List<Class<?>> siteClasses = siteClasses(db, txn);
        Survey s = survey(db, txn, siteClasses);
        long[] bytes = storedLevelRefs(db, txn, siteClasses, /*sitesOnly=*/false);
        return new LevelRefInspection(s.sites, s.refs, s.orphans, s.levels, bytes[0], bytes[1],
                s.nestedSites);
    }

    private static LevelRefResult last = null;

    /// What the last [#stripOrphanLevelRefs] in this JVM measured, or `null`.
    public static LevelRefResult lastLevelRefResult() { return last; }

    /// Clear every `LevelRef` whose target is not a `Level` its own Site defines,
    /// write the changed Sites back through `insertAnyObjects`, and prove it.
    ///
    /// ```
    /// own(s)     = { l.id : l ∈ s.levels, l instanceof Level }
    /// refs(s)    = [ q.levelRef : q ∈ s.quays ] ++ [ r : r ∈ s.levels, r instanceof LevelRef ]
    /// cleared(s) = |{ r ∈ refs(s) : r.ref ∉ own(s) }|;   intact(s) = |refs(s)| − cleared(s)
    /// ```
    ///
    /// THREE passes. Pass 1 surveys the store and derives the expectation independently of the code
    /// that acts on it; pass 2 acts and counts what IT did; pass 3 re-reads the store — after the
    /// writes, so from re-encoded fragments rather than from the beans pass 2 held — and asks
    /// whether anything is left to clear.
    ///
    /// @throws IllegalStateException on G1–G6 (see the guard block at the tail)
    public static LevelRefResult stripOrphanLevelRefs(Store db, Txn txn) {
        List<Class<?>> siteClasses = siteClasses(db, txn);

        // ---- pass 0: the STORE's own LevelRef population, over stored fragment bytes (G7).
        // Every class map, not only the Site ones.
        long[] bytesBefore = storedLevelRefs(db, txn, siteClasses, /*sitesOnly=*/false);

        // ---- pass 1: the independent pre-pass (G4's other arm, and G2/G6's baseline)
        Survey before = survey(db, txn, siteClasses);

        // ---- pass 2: the strip, drained by the insert
        Strip strip = new Strip(db, txn, siteClasses);
        db.insertAnyObjects(txn, strip);

        // ---- pass 3: re-read what was written
        Survey after = survey(db, txn, siteClasses);
        // ---- pass 4: and re-count the stored bytes. Site class maps only, which is sound ONLY
        // because pass 0 measured 0 elsewhere and this pass writes nothing but Sites — the guard
        // below refuses the narrowing when that premise fails.
        long[] bytesAfter = storedLevelRefs(db, txn, siteClasses, /*sitesOnly=*/true);

        long g6 = intersectionSize(before.targetsStopPlace, before.levelIds);
        long otherCollisions = intersectionSize(before.targetsOtherSite, before.levelIds);
        LevelRefResult r = new LevelRefResult(
                before.sites, strip.touched, before.refs, strip.cleared, strip.intact,
                before.levels, after.levels, after.refs, after.orphans,
                before.targetsStopPlace.size(), before.targetsOtherSite.size(),
                g6, otherCollisions,
                bytesBefore[0], bytesAfter[0], bytesBefore[1], before.nestedSites);
        last = r;   // set BEFORE the guard, so a negative test can read what it measured

        // ------------------------------------------------------------------ THE GUARD
        List<String> fail = new ArrayList<>();

        // A pass that saw refs where the pre-pass saw none — or none where the pre-pass saw some
        // — has not measured the same store, and its zeros mean nothing.
        if (strip.refs != before.refs) {
            fail.add("INOPERATIVE: the strip pass saw " + strip.refs + " LevelRef(s) where the "
                    + "independent pre-pass saw " + before.refs + " — the two passes did not read "
                    + "the same store");
        }
        // G1 conservation.
        if (strip.cleared + strip.intact != strip.refs) {
            fail.add("G1 conservation: cleared " + strip.cleared + " + intact " + strip.intact
                    + " != " + strip.refs + " refs seen");
        }
        // G2 <Level> count unchanged. Stays GREEN under over-stripping, which is why G4's `intact`
        // arm below is not optional.
        if (after.levels != before.levels) {
            fail.add("G2 <Level> count moved: " + before.levels + " -> " + after.levels
                    + "; this pass removes references, never definitions");
        }
        // G3 soundness: nothing unresolvable is left.
        if (after.orphans != 0) {
            fail.add("G3 soundness: " + after.orphans + " orphaned LevelRef(s) survive the pass");
        }
        // G4 completeness, in total and per Site, against the pre-pass.
        if (strip.cleared != before.orphans) {
            fail.add("G4 cleared: the pass cleared " + strip.cleared + " ref(s) where the "
                    + "independent pre-pass counted " + before.orphans + " orphan(s)");
        }
        if (strip.intact != before.refs - before.orphans) {
            fail.add("G4 intact: the pass left " + strip.intact + " ref(s) in place where the "
                    + "independent pre-pass expected " + (before.refs - before.orphans));
        }
        // PER SITE, not only in total: a totals-only guard nets an over-strip on one Site against
        // an under-strip on another and reports zero. This cannot.
        List<String> diffs = perSiteDiff(before.perSite, strip.perSite);
        if (!diffs.isEmpty()) {
            fail.add("G4 per Site: " + diffs.size() + " Site(s) where the pass and the pre-pass "
                    + "disagree on (refs, cleared, intact). First: " + diffs.get(0));
        }
        // G5 a second pass clears 0 — i.e. what survives is exactly what was left intact, and a
        // re-run of the same predicate over the re-encoded fragments would remove nothing.
        if (after.refs != strip.intact) {
            fail.add("G5 second pass: the store holds " + after.refs + " LevelRef(s) after the "
                    + "pass, where " + strip.intact + " were left intact");
        }
        // G6 — what licenses the per-Site predicate standing in for the per-document one
        // Level_KeyRef states. Every StopPlace-scoped target this pass removes must be a Level that
        // exists NOWHERE in the store; the moment a feed defines a Level in one StopPlace and
        // references it from another, this fires and the per-Site predicate must be revisited.
        if (g6 != 0) {
            fail.add("G6 StopPlace scope: " + g6 + " cleared target(s) ARE a <Level> defined "
                    + "elsewhere in the store, so a per-Site predicate is NOT a per-document one "
                    + "here — refusing to strip a reference that would resolve in the export. "
                    + "First: " + firstIntersecting(before.targetsStopPlace, before.levelIds));
        }
        // ------------------------------------------------------------------------ G7
        // THE DENOMINATOR THE TRAVERSAL DOES NOT SUPPLY. G1-G6 are all scoped to what the bean
        // traversal reaches. G7 counts the store's <LevelRef> in ITS OWN STORED BYTES — which is
        // also what EpipDbToXml emits, fragment bytes verbatim — and makes the two reconcile. No
        // JAXB decode is involved, so this arm also fails if the binding ever drops a reference the
        // bytes carry.
        //
        // G3 says "no orphan survives among the refs the traversal reached"; G7 says "the traversal
        // reached every ref the store holds". Only the conjunction says what the profile requires.
        if (bytesBefore[1] != 0) {
            fail.add("G7 scope: " + bytesBefore[1] + " <LevelRef> live in a class map that is not "
                    + "a Site_VersionStructure, so this pass — which iterates Site classes — "
                    + "cannot reach them. Widen siteClasses(), or explain the exemption here");
        }
        if (bytesBefore[0] != strip.refs) {
            fail.add("G7 reach: the store's own fragment bytes hold " + bytesBefore[0]
                    + " <LevelRef>, the bean traversal reached " + strip.refs
                    + " — the pass is looking at less than the store contains, and every other "
                    + "guard here is scoped to the part it does look at");
        }
        if (bytesAfter[0] != strip.intact) {
            fail.add("G7 residue: the store's fragment bytes hold " + bytesAfter[0]
                    + " <LevelRef> after the pass, where " + strip.intact + " were left intact");
        }
        // G8 — the per-Site predicate needs "the Site" to be unambiguous. A Site nested inside
        // another Site would give a ref two candidate own() sets, and this walk would silently
        // use the outer one.
        if (before.nestedSites != 0) {
            fail.add("G8 nesting: " + before.nestedSites + " Site(s) found INSIDE another Site. "
                    + "own(s) is then ambiguous and the per-Site predicate is not well defined; "
                    + "decide the scope before stripping anything");
        }
        if (!fail.isEmpty()) {
            throw new IllegalStateException("[epip-levelref] GUARD: " + String.join(" | ", fail)
                    + ". Refusing to write. " + r);
        }

        // NOT ASSERTED, deliberately. G6 is FALSE for the non-StopPlace Sites: an AT Parking's
        // <levels><LevelRef/></levels> points at Levels held inside StopPlace fragments. What
        // licenses clearing them is DOCUMENT scope, which is what Level_KeyRef's selector says: the
        // exporter emits Parking into the OTHER_REFERENCED closure entry (shared-06) and StopPlaces
        // into the sites entry (shared-02), so a Parking's LevelRef is orphaned IN ITS OWN DOCUMENT
        // whatever the store holds. A property of the export split, which beans cannot see.
        Log.info("[epip-levelref] %s", r);
        return r;
    }

    /// The production predicate: a ref is orphaned iff its target is not one this Site defines.
    private static boolean isOrphan(Set<String> own, String ref) {
        return ref == null || !own.contains(ref);
    }

    /// Every class with a class map in this db whose objects are Sites (StopPlace, Parking, …).
    private static List<Class<?>> siteClasses(Store db, Txn txn) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c : db.dbNames(txn)) {
            if (Site_VersionStructure.class.isAssignableFrom(c)) out.add(c);
        }
        return out;
    }

    /// `own(s)`, plus the `<Level>` element count (ids may be absent; elements count).
    private static int ownLevelIds(Site_VersionStructure site, Set<String> out) {
        if (site.getLevels() == null) return 0;
        int levels = 0;
        for (Object e : site.getLevels().getLevelRefOrLevel()) {
            if (e instanceof Level l) {
                levels++;
                if (l.getId() != null) out.add(l.getId());
            }
        }
        return levels;
    }

    /// `refs(s)` — every `LevelRef` anywhere under this Site, in walk order.
    ///
    /// NOT a list of containers. [RecursiveAttributes#walk] does the navigating and this method
    /// only says what to do with each node it is handed. The four carriers below are the complete
    /// set of places the model declares a `LevelRefStructure` member;
    /// `TestEpipLevelRefs.modelDeclaresExactlyFourLevelRefCarriers` pins that list, so a model
    /// upgrade that adds a fifth fails a test instead of quietly halving this pass's reach.
    ///
    /// `walk` yields a reference object as a LEAF and never recurses into it, so a
    /// `LevelRefStructure` arrives via `reference()` and is deliberately ignored here — it is
    /// counted through its HOLDER, which is the only thing that can also remove it.
    ///
    /// One read-only walk of one Site: its refs in walk order, and G8's nested-Site count.
    private record SiteScan(List<String> refs, long nestedSites) {}

    private static SiteScan siteRefs(Site_VersionStructure site) {
        List<String> refs = new ArrayList<>();
        long[] nested = {0};
        RecursiveAttributes.walk(site, new RecursiveAttributes.Visitor() {
            @Override public void reference(noi.netex.model.VersionOfObjectRefStructure ref) {}

            @Override public void node(Object o) {
                if (o instanceof Site_VersionStructure) nested[0]++;
                if (blind(o)) return;
                if (o instanceof SiteComponent_VersionStructure sc) {
                    if (sc.getLevelRef() != null) refs.add(sc.getLevelRef().getRef());
                } else if (o instanceof SitePathLink_VersionStructure pl) {
                    if (pl.getLevelRef() != null) refs.add(pl.getLevelRef().getRef());
                } else if (PathLinkEnds.isEnd(o)) {
                    LevelRefStructure r = PathLinkEnds.levelRef(o);
                    if (r != null) refs.add(r.getRef());
                } else if (o instanceof Levels_RelStructure lv) {
                    for (Object e : lv.getLevelRefOrLevel()) {
                        if (e instanceof LevelRefStructure r) refs.add(r.getRef());
                    }
                } else if (o instanceof SiteStructure_VersionStructure ss) {
                    // Shape (c), the fifth carrier. Counted at the OWNER rather than at
                    // LevelInStructure_VersionStructure itself, because the strip has to remove the
                    // whole element and therefore needs the list; counting at both would double it.
                    if (ss.getLevelsInStructure() != null) {
                        for (LevelInStructure_VersionStructure lis
                                : ss.getLevelsInStructure().getLevelInStructure()) {
                            if (lis.getLevelRef() != null) refs.add(lis.getLevelRef().getRef());
                        }
                    }
                }
            }
        });
        return new SiteScan(refs, nested[0]);
    }

    // -------------------------------------------------------- G7: the store's own population

    /// `<LevelRef>` elements in the STORED FRAGMENT BYTES, by decompress-and-scan — no JAXB
    /// decode, no getter, nothing this file's traversal touches. Returns
    /// {@code {total, outsideSiteClasses}}.
    ///
    /// Reads every row of every class map once. The post-pass call narrows to the Site class maps,
    /// which is licensed by the pre-pass having measured 0 elsewhere AND by this pass writing
    /// nothing but Sites — and the guard refuses the narrowing if that premise fails.
    ///
    /// It matches the unprefixed spelling `<LevelRef`. Every fragment this codebase writes is
    /// unprefixed; a namespace-prefixed writer would make this count FALL, which fires the guard.
    private static long[] storedLevelRefs(Store db, Txn txn, List<Class<?>> siteClasses,
                                          boolean sitesOnly) {
        FragmentCodec codec = new FragmentCodec();
        long total = 0, outside = 0;
        for (Class<?> c : db.dbNames(txn)) {
            boolean isSite = siteClasses.contains(c);
            if (sitesOnly && !isSite) continue;
            long here = 0;
            for (RawRow row : db.iterRawObjects(txn, c)) {
                here += countLevelRefElements(codec.decompress(row.blob()));
            }
            total += here;
            if (!isSite) outside += here;
        }
        return new long[] {total, outside};
    }

    private static final byte[] LEVELREF_NAME = {'L', 'e', 'v', 'e', 'l', 'R', 'e', 'f'};

    /// Occurrences of the START TAG `<LevelRef...>` in one fragment. Name-EXACT: the byte after the
    /// name must end it, so a hypothetical `<LevelRefView>` could never inflate the count and make
    /// G7 pass by accident. Public only so the test can drive that edge directly.
    public static long countLevelRefElements(byte[] x) {
        long n = 0;
        int end = x.length - LEVELREF_NAME.length - 1;
        outer:
        for (int i = 0; i <= end; i++) {
            if (x[i] != '<') continue;
            for (int j = 0; j < LEVELREF_NAME.length; j++) {
                if (x[i + 1 + j] != LEVELREF_NAME[j]) continue outer;
            }
            byte c = x[i + 1 + LEVELREF_NAME.length];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>') n++;
        }
        return n;
    }

    /// What one read-only sweep of every Site in the store says. Pass 1 and pass 3 are both this.
    private static final class Survey {
        long sites, refs, orphans, levels, nestedSites;
        /// Orphan-carrying Sites only: key -> {refs, cleared, intact}.
        final Map<String, long[]> perSite = new HashMap<>();
        final Set<String> targetsStopPlace = new LinkedHashSet<>();
        final Set<String> targetsOtherSite = new LinkedHashSet<>();
        /// Every Level id anywhere in the store — G6's right-hand side.
        final Set<String> levelIds = new HashSet<>();
    }

    private static Survey survey(Store db, Txn txn, List<Class<?>> siteClasses) {
        Survey s = new Survey();
        for (Class<?> c : siteClasses) {
            for (Object o : db.iterOnlyObjects(txn, c)) {
                Site_VersionStructure site = (Site_VersionStructure) o;
                s.sites++;
                Set<String> own = new HashSet<>();
                s.levels += ownLevelIds(site, own);
                s.levelIds.addAll(own);
                SiteScan scan = siteRefs(site);
                s.nestedSites += scan.nestedSites();
                List<String> refs = scan.refs();
                long orphansHere = 0;
                for (String ref : refs) {
                    if (!isOrphan(own, ref)) continue;
                    orphansHere++;
                    if (ref == null) continue;
                    (site instanceof StopPlace ? s.targetsStopPlace : s.targetsOtherSite).add(ref);
                }
                s.refs += refs.size();
                s.orphans += orphansHere;
                if (orphansHere > 0) {
                    bump(s.perSite, siteKey(c, site), refs.size(), orphansHere);
                }
            }
        }
        // A Level that is a first-class row rather than an embedded one still counts.
        if (db.dbNames(txn).contains(Level.class)) {
            for (Object o : db.iterOnlyObjects(txn, Level.class)) {
                s.levels++;
                if (((Level) o).getId() != null) s.levelIds.add(((Level) o).getId());
            }
        }
        return s;
    }

    /// Pass 2. Mutates and yields the Sites it changed, lazily, so the insert drains it. Counts
    /// what IT did, never what it expected.
    private static final class Strip implements Iterator<Object> {
        private final Store db;
        private final Txn txn;
        private final List<Class<?>> classes;
        private int classIdx = -1;
        private Class<?> clazz;
        private Iterator<Object> sites = java.util.Collections.emptyIterator();
        private Object ready;

        long refs, cleared, intact, touched;
        final Map<String, long[]> perSite = new HashMap<>();

        Strip(Store db, Txn txn, List<Class<?>> classes) {
            this.db = db;
            this.txn = txn;
            this.classes = classes;
        }

        private void pump() {
            while (ready == null) {
                if (!sites.hasNext()) {
                    if (++classIdx >= classes.size()) return;
                    clazz = classes.get(classIdx);
                    sites = db.iterOnlyObjects(txn, clazz).iterator();
                    continue;
                }
                Site_VersionStructure site = (Site_VersionStructure) sites.next();
                Set<String> own = new HashSet<>();
                ownLevelIds(site, own);
                long[] here = {0, 0};   // {refs, cleared}

                // The SAME walk siteRefs() makes, with the action attached. Mutating in the node
                // callback is safe by the walker's own order: descend() calls visitor.node(v)
                // BEFORE it reads v's fields, so a slot cleared here is simply not there when the
                // walk descends, and a list shortened here is iterated once, afterwards, in its
                // new state.
                RecursiveAttributes.walk(site, new RecursiveAttributes.Visitor() {
                    @Override public void reference(
                            noi.netex.model.VersionOfObjectRefStructure ref) {}

                    @Override public void node(Object o) {
                        if (blind(o)) return;
                        if (o instanceof SiteComponent_VersionStructure sc) {
                            if (sc.getLevelRef() == null) return;
                            here[0]++;
                            if (!orphan(own, sc.getLevelRef().getRef())) return;
                            sc.setLevelRef(null);
                            here[1]++;
                        } else if (o instanceof SitePathLink_VersionStructure pl) {
                            if (pl.getLevelRef() == null) return;
                            here[0]++;
                            if (!orphan(own, pl.getLevelRef().getRef())) return;
                            pl.setLevelRef(null);
                            here[1]++;
                        } else if (PathLinkEnds.isEnd(o)) {
                            LevelRefStructure r = PathLinkEnds.levelRef(o);
                            if (r == null) return;
                            here[0]++;
                            if (!orphan(own, r.getRef())) return;
                            PathLinkEnds.clearLevelRef(o);
                            here[1]++;
                        } else if (o instanceof Levels_RelStructure lv) {
                            for (Iterator<Object> it = lv.getLevelRefOrLevel().iterator();
                                    it.hasNext(); ) {
                                if (!(it.next() instanceof LevelRefStructure r)) continue;
                                here[0]++;
                                if (!orphan(own, r.getRef())) continue;
                                it.remove();
                                here[1]++;
                            }
                        } else if (o instanceof SiteStructure_VersionStructure ss) {
                            // SHAPE (c), THE FIFTH CARRIER — the ONLY one where clearing the field
                            // is not the repair. LevelInStructure_VersionStructure.levelRef is
                            // `@XmlElement(name="LevelRef", required=true)`, so a LevelInStructure
                            // with its LevelRef nulled would trade a Level_KeyRef violation for a
                            // content-model violation. The whole <LevelInStructure> goes instead.
                            if (ss.getLevelsInStructure() == null) return;
                            for (Iterator<LevelInStructure_VersionStructure> it =
                                    ss.getLevelsInStructure().getLevelInStructure().iterator();
                                    it.hasNext(); ) {
                                LevelRefStructure r = it.next().getLevelRef();
                                if (r == null) continue;
                                here[0]++;
                                if (!orphan(own, r.getRef())) continue;
                                it.remove();
                                here[1]++;
                            }
                            // levelsInStructure is minOccurs="0" but its content is
                            // maxOccurs="unbounded" with minOccurs=1, so an EMPTIED container is
                            // itself invalid and must go.
                            if (ss.getLevelsInStructure().getLevelInStructure().isEmpty()) {
                                ss.setLevelsInStructure(null);
                            }
                        }
                    }
                });
                long refsHere = here[0], clearedHere = here[1];

                // Drop a container the strip has emptied: `levels` is minOccurs="0", so its absence
                // is conformant. Only ever reached when no <Level> remains, so G2 cannot see it.
                if (site.getLevels() != null && site.getLevels().getLevelRefOrLevel().isEmpty()) {
                    site.setLevels(null);
                }

                refs += refsHere;
                cleared += clearedHere;
                intact += refsHere - clearedHere;
                if (clearedHere > 0) {
                    touched++;
                    bump(perSite, siteKey(clazz, site), refsHere, clearedHere);
                    ready = site;
                }
            }
        }

        private boolean orphan(Set<String> own, String ref) {
            BiPredicate<Set<String>, String> hook = orphanTest;
            return hook != null ? hook.test(own, ref) : isOrphan(own, ref);
        }

        @Override public boolean hasNext() {
            pump();
            return ready != null;
        }

        @Override public Object next() {
            if (!hasNext()) throw new NoSuchElementException();
            Object out = ready;
            ready = null;
            return out;
        }
    }

    // ---------------------------------------------------------------- guard plumbing

    /// The per-Site key. Class name AND (id, version), because a class map may legitimately hold
    /// two versions of one id; colliding keys ACCUMULATE on both sides.
    private static String siteKey(Class<?> clazz, Site_VersionStructure site) {
        return clazz.getSimpleName() + '|' + site.getId() + '|' + site.getVersion();
    }

    private static void bump(Map<String, long[]> m, String key, long refs, long cleared) {
        long[] v = m.computeIfAbsent(key, k -> new long[3]);
        v[0] += refs;
        v[1] += cleared;
        v[2] += refs - cleared;
    }

    /// Every key on which the two maps disagree, in a stable order, rendered for a message.
    private static List<String> perSiteDiff(Map<String, long[]> expected, Map<String, long[]> actual) {
        List<String> out = new ArrayList<>();
        for (String k : new TreeSet<>(union(expected.keySet(), actual.keySet()))) {
            long[] e = expected.get(k);
            long[] a = actual.get(k);
            if (e != null && a != null && e[0] == a[0] && e[1] == a[1] && e[2] == a[2]) continue;
            out.add(k + " expected " + render(e) + " actual " + render(a));
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static String render(long[] v) {
        return v == null ? "(refs 0, cleared 0, intact 0)"
                : "(refs " + v[0] + ", cleared " + v[1] + ", intact " + v[2] + ")";
    }

    private static long intersectionSize(Set<String> a, Set<String> b) {
        long n = 0;
        for (String s : a) if (b.contains(s)) n++;
        return n;
    }

    private static String firstIntersecting(Set<String> a, Set<String> b) {
        for (String s : a) if (b.contains(s)) return s;
        return "(none)";
    }
}
