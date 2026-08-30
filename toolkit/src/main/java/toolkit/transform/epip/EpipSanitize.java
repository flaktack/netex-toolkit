package toolkit.transform.epip;

// Sanitizes the sites an EPIP export emits: the orphaned-LevelRef strip, and nothing else.
//
// The coordinate-less placeholder-quay strip and the stop-assignment quay-ref clear that follows
// from it run per feed, on each feed db before this stage sees it. EpipLocations and the LevelRef
// strip below both assume they have run.

import noi.netex.model.Level;
import noi.netex.model.LevelInStructure_VersionStructure;
import noi.netex.model.LevelRefStructure;
import noi.netex.model.Levels_RelStructure;
import noi.netex.model.SiteComponent_VersionStructure;
import noi.netex.model.SitePathLink_VersionStructure;
import noi.netex.model.SiteStructure_VersionStructure;
import noi.netex.model.Site_VersionStructure;
import noi.netex.model.StopPlace;
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

    // Orphaned LevelRefs, cleared. PROV-CAP: strip-orphan-levelrefs
    //
    // `Level` and `LevelRef` are both optional in EPIP, but the profile declares `Level_KeyRef`
    // with selector `.//netex:LevelRef` and requires an internal reference to resolve in the same
    // document. So a LevelRef whose Level is not in the document is a profile violation, while its
    // absence is conformant. No consumer reads a LevelRef; they read a site's levels instead.
    //
    // The refs come in three shapes:
    //   (a) the `levelRef` field on an embedded site component — Quay, Entrance, AccessSpace and
    //       every other subclass;
    //   (b) containment-by-reference — a `<LevelRef>` sitting directly in a site's `<levels>`,
    //       which binds as a list of Level OR ref, so this shape is a member of the same list the
    //       definitions live in;
    //   (c) `<LevelInStructure>`, whose content group carries a mandatory `<LevelRef>`, two hops
    //       from a site. Because the ref is mandatory, the repair is to remove the whole element
    //       rather than null the field.

    /// Test seam, null in every shipped run. Set it and the main pass uses it instead of
    /// [#isOrphan], while the independent pre-pass keeps the real predicate — so the guard must
    /// catch the disagreement.
    private static BiPredicate<Set<String>, String> orphanTest = null;

    /// Install (or, with `null`, remove) the seam above. A shipped run never calls it.
    public static void setOrphanTest(BiPredicate<Set<String>, String> hook) { orphanTest = hook; }

    /// The second test seam: [#orphanTest] changes what the pass decides, this changes what it can
    /// see. Set it and every node it accepts is skipped by both the pre-pass and the strip — a
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
    /// @param levelsAfter    `<Level>` elements after — must equal `levelsBefore`
    /// @param refsAfter      refs the independent post-pass found (G5)
    /// @param orphansAfter   orphaned refs the post-pass found — must be 0 (G3)
    /// @param targetsStopPlace  distinct cleared targets, StopPlace-scoped (G6's subject)
    /// @param targetsOtherSite  distinct cleared targets on every other Site class
    /// @param g6Collisions   cleared StopPlace targets that are a Level somewhere in the store
    ///                       — must be 0 (G6)
    /// @param otherSiteCollisions the same count for the non-StopPlace sites: not zero today
    ///                       (a Parking's levels live inside StopPlaces), reported not asserted
    /// @param storeRefsBefore  `<LevelRef>` elements in the stored fragment bytes of every
    ///                       class map before the pass — G7's denominator
    /// @param storeRefsAfter   the same count over the Site class maps after the writes (G7)
    /// @param storeRefsOutside how many of `storeRefsBefore` sit in a class map that is not
    ///                       a Site at all — must be 0, or the pass cannot reach them (G7)
    /// @param nestedSites    Site objects found inside another Site by the walk — must be 0, or
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

    /// What a read-only look at a store says about its `LevelRef` population. `orphans == 0` means
    /// the [Strip] iterator would yield nothing and no stored byte could move.
    ///
    /// @param sites     Sites examined
    /// @param refs      refs the bean traversal reached
    /// @param orphans   of those, the ones the pass would clear
    /// @param levels    `<Level>` elements
    /// @param storeRefs `<LevelRef>` elements in the stored bytes of every class map
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

    /// Read-only. Run the pass's pre-pass and its byte census over an existing store and report;
    /// write nothing, mutate no bean the store owns.
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

    /// Clear every `LevelRef` whose target is not a `Level` its own Site defines, and write the
    /// changed Sites back through `insertAnyObjects`.
    ///
    /// ```
    /// own(s)     = { l.id : l ∈ s.levels, l instanceof Level }
    /// refs(s)    = [ q.levelRef : q ∈ s.quays ] ++ [ r : r ∈ s.levels, r instanceof LevelRef ]
    /// cleared(s) = |{ r ∈ refs(s) : r.ref ∉ own(s) }|;   intact(s) = |refs(s)| − cleared(s)
    /// ```
    ///
    /// Three passes: pass 1 surveys the store and derives the expectation independently of the code
    /// that acts on it, pass 2 acts and counts what it did, and pass 3 re-reads the store after the
    /// writes, so from re-encoded fragments rather than from the beans pass 2 held.
    ///
    /// @throws IllegalStateException on G1–G6 (see the guard block at the tail)
    public static LevelRefResult stripOrphanLevelRefs(Store db, Txn txn) {
        List<Class<?>> siteClasses = siteClasses(db, txn);

        // ---- pass 0: the store's own LevelRef population, over stored fragment bytes (G7).
        // Every class map, not only the Site ones.
        long[] bytesBefore = storedLevelRefs(db, txn, siteClasses, /*sitesOnly=*/false);

        // ---- pass 1: the independent pre-pass (G4's other arm, and G2/G6's baseline)
        Survey before = survey(db, txn, siteClasses);

        // ---- pass 2: the strip, drained by the insert
        Strip strip = new Strip(db, txn, siteClasses);
        db.insertAnyObjects(txn, strip);

        // ---- pass 3: re-read what was written
        Survey after = survey(db, txn, siteClasses);
        // ---- pass 4: and re-count the stored bytes. Site class maps only, which is sound only
        // because pass 0 measured 0 elsewhere and this pass writes nothing but Sites; the guard
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
        last = r;   // set before the guard, so a negative test can read what it measured

        // ------------------------------------------------------------------ the guard
        List<String> fail = new ArrayList<>();

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
        // G2 <Level> count unchanged.
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
        List<String> diffs = perSiteDiff(before.perSite, strip.perSite);
        if (!diffs.isEmpty()) {
            fail.add("G4 per Site: " + diffs.size() + " Site(s) where the pass and the pre-pass "
                    + "disagree on (refs, cleared, intact). First: " + diffs.get(0));
        }
        // G5: what survives the pass is exactly what it left intact.
        if (after.refs != strip.intact) {
            fail.add("G5 second pass: the store holds " + after.refs + " LevelRef(s) after the "
                    + "pass, where " + strip.intact + " were left intact");
        }
        // G6 — what licenses the per-Site predicate standing in for the per-document one
        // Level_KeyRef states. Every StopPlace-scoped target this pass removes must be a Level that
        // exists nowhere in the store; the moment a feed defines a Level in one StopPlace and
        // references it from another, this fires and the per-Site predicate must be revisited.
        if (g6 != 0) {
            fail.add("G6 StopPlace scope: " + g6 + " cleared target(s) ARE a <Level> defined "
                    + "elsewhere in the store, so a per-Site predicate is NOT a per-document one "
                    + "here — refusing to strip a reference that would resolve in the export. "
                    + "First: " + firstIntersecting(before.targetsStopPlace, before.levelIds));
        }
        // ------------------------------------------------------------------------ G7
        // G1-G6 are scoped to what the bean traversal reaches. G7 counts the store's <LevelRef> in
        // its own stored bytes — which is what the export emits, fragment bytes verbatim — and
        // makes the two reconcile. No JAXB decode is involved, so this arm also fails if the
        // binding ever drops a reference the bytes carry.
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
        // G8 — a Site nested inside another Site would give a ref two candidate own() sets, and
        // this walk would silently use the outer one.
        if (before.nestedSites != 0) {
            fail.add("G8 nesting: " + before.nestedSites + " Site(s) found INSIDE another Site. "
                    + "own(s) is then ambiguous and the per-Site predicate is not well defined; "
                    + "decide the scope before stripping anything");
        }
        if (!fail.isEmpty()) {
            throw new IllegalStateException("[epip-levelref] GUARD: " + String.join(" | ", fail)
                    + ". Refusing to write. " + r);
        }

        // Not asserted: G6 is false for the non-StopPlace Sites, where an AT Parking's
        // <levels><LevelRef/></levels> points at Levels held inside StopPlace fragments. What
        // licenses clearing them is document scope, which is what Level_KeyRef's selector says: the
        // exporter emits Parking into the OTHER_REFERENCED closure entry (shared-06) and StopPlaces
        // into the sites entry (shared-02), so a Parking's LevelRef is orphaned in its own document
        // whatever the store holds.
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

    /// One read-only walk of one Site: its refs in walk order, and G8's nested-Site count.
    private record SiteScan(List<String> refs, long nestedSites) {}

    /// `refs(s)` — every `LevelRef` anywhere under this Site, in walk order.
    /// [RecursiveAttributes#walk] does the navigating; this method only says what to do with each
    /// node it is handed. The carriers below are the complete set of places the model declares a
    /// `LevelRefStructure` member, a list TestEpipLevelRefs pins.
    ///
    /// `walk` yields a reference object as a leaf and never recurses into it, so a
    /// `LevelRefStructure` arrives via `reference()` and is ignored here — it is counted through
    /// its holder, which is the only thing that can also remove it.
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
                    // Shape (c), counted at the owner rather than at
                    // LevelInStructure_VersionStructure itself, because the strip has to remove the
                    // whole element and therefore needs the list.
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

    /// `<LevelRef>` elements in the stored fragment bytes, by decompress-and-scan — no JAXB
    /// decode, no getter, nothing this file's traversal touches. Reads every row of every class map
    /// once, and returns {@code {total, outsideSiteClasses}}.
    ///
    /// It matches the unprefixed spelling `<LevelRef`. Every fragment this codebase writes is
    /// unprefixed; a namespace-prefixed writer would make this count fall, which fires the guard.
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

    /// Occurrences of the start tag `<LevelRef...>` in one fragment. Name-exact: the byte after the
    /// name must end it, so a `<LevelRefView>` could never inflate the count. Public only so the
    /// test can drive that edge directly.
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
    /// what it did, never what it expected.
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

                // The same walk siteRefs() makes, with the action attached. Mutating in the node
                // callback is safe only by the walker's own order: descend() calls visitor.node(v)
                // before it reads v's fields, so a slot cleared here is simply not there when the
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
                            // Shape (c), the one carrier where clearing the field is not the
                            // repair. LevelInStructure_VersionStructure.levelRef is
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
                            // maxOccurs="unbounded" with minOccurs=1, so an emptied container is
                            // itself invalid and must go.
                            if (ss.getLevelsInStructure().getLevelInStructure().isEmpty()) {
                                ss.setLevelsInStructure(null);
                            }
                        }
                    }
                });
                long refsHere = here[0], clearedHere = here[1];

                // Drop a container the strip has emptied: `levels` is minOccurs="0", so its absence
                // is conformant.
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

    /// The per-Site key. Class name and (id, version), because a class map may legitimately hold
    /// two versions of one id; colliding keys accumulate on both sides.
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
