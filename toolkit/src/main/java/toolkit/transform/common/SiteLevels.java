package toolkit.transform.common;

// The embedded <levels> of a Site: the union two copies of one Site take, the fold that collapses
// members describing one floor, and the LevelRef re-point the fold needs.
//
// Levels_RelStructure is declared on Site_VersionStructure alone, so a Level's scope is the Site
// that embeds it, and every operation here is per Site. A Level carries no id-index row either, so a
// reference to one resolves through the embedding index rather than through resolve().

import noi.netex.model.Level;
import noi.netex.model.LevelInStructure_VersionStructure;
import noi.netex.model.LevelRefStructure;
import noi.netex.model.Levels_RelStructure;
import noi.netex.model.MultilingualString;
import noi.netex.model.PublicCodeStructure;
import noi.netex.model.SiteComponent_VersionStructure;
import noi.netex.model.SitePathLink_VersionStructure;
import noi.netex.model.SiteStructure_VersionStructure;
import noi.netex.model.Site_VersionStructure;
import noi.netex.model.VersionOfObjectRefStructure;
import noi.netex.sites.PathLinkEnds;
import noi.netex.text.Mls;
import toolkit.model.RecursiveAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SiteLevels {

    private SiteLevels() {}

    /// What makes two Levels one floor: every field of the schema's `LevelGroup`, compared as it is
    /// written. Whitespace and language tag are part of the answer, so `Name lang="de"` and
    /// `Name lang="en"` carrying one spelling are two floors.
    ///
    /// @return null for a Level carrying an `AccessibilityAssessment` or a `SiteRef`, which then
    ///         matches nothing, including a Level identical to it
    public static List<Object> key(Level l) {
        if (l.getAccessibilityAssessment() != null || l.getSiteRef() != null) return null;
        PublicCodeStructure pc = l.getPublicCode();
        return Arrays.asList(
                text(l.getName()), lang(l.getName()),
                text(l.getShortName()), lang(l.getShortName()),
                text(l.getDescription()), lang(l.getDescription()),
                pc == null ? null : pc.getValue(),
                pc == null ? null : pc.getType(),
                l.isPublicUse(),
                l.getRelativeLevelOrder(),
                l.isAllAreasWheelchairAccessible());
    }

    private static String text(MultilingualString m) {
        return m == null ? null : Mls.text(m);
    }

    private static String lang(MultilingualString m) {
        return m == null ? null : m.getLang();
    }

    /// Append `site`'s levels to `into`, skipping ids already in `seen`. Both arms of the union
    /// carry an id — an embedded `Level` its own, a `LevelRefStructure` its `ref` — into one id
    /// space, so a `<LevelRef ref="X"/>` never lands beside a `<Level id="X">`. A member with
    /// neither is appended.
    public static void union(List<Object> into, Set<String> seen, Site_VersionStructure site) {
        if (site.getLevels() == null) return;
        for (Object member : site.getLevels().getLevelRefOrLevel()) {
            String id = id(member);
            if (id != null && !seen.add(id)) continue;
            into.add(member);
        }
    }

    /// The id a `<levels>` member occupies, or null for a member carrying neither.
    public static String id(Object member) {
        if (member instanceof Level l) return l.getId();
        if (member instanceof LevelRefStructure r) return r.getRef();
        return null;
    }

    /// Collapse the members of `levels` that answer one [#key], keeping the smallest id of each
    /// group and recording the rest in `remap`. Mutates `levels` in place. Only an embedded `Level`
    /// is considered as a fold candidate: a `LevelRef` member names a floor whose fields are in
    /// another Site.
    ///
    /// A `LevelRef` member naming a folded id goes with it. Every id the fold renames was a `Level`
    /// in this very list, so the member was containment-by-reference at a floor its own container
    /// defines, and the surviving `Level` is already in the list.
    ///
    /// The caller re-points the references itself, through [#repoint] once its Site holds every
    /// component the fold concerns.
    ///
    /// @return how many members were removed
    public static int fold(List<Object> levels, Map<String, String> remap) {
        if (levels.size() < 2) return 0;
        // Group first, then elect: electing pairwise lets a Level that is itself folded away become
        // another Level's recorded survivor (C -> A while A -> B), and the chain is never resolved,
        // so C's referrers end up naming a Level that was removed.
        Map<List<Object>, List<Level>> groups = new LinkedHashMap<>();
        for (Object member : levels) {
            if (!(member instanceof Level l) || l.getId() == null) continue;
            List<Object> key = key(l);
            if (key == null) continue;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
        }
        Map<String, String> local = new HashMap<>();   // folded id -> survivor id
        for (List<Level> group : groups.values()) {
            if (group.size() < 2) continue;
            // Smallest id wins, so the choice does not depend on iteration order.
            String keep = group.get(0).getId();
            for (Level l : group) if (l.getId().compareTo(keep) < 0) keep = l.getId();
            for (Level l : group) if (!l.getId().equals(keep)) local.put(l.getId(), keep);
        }
        if (local.isEmpty()) return 0;
        remap.putAll(local);
        int before = levels.size();
        levels.removeIf(member -> local.containsKey(id(member)));
        return before - levels.size();
    }

    /// Install `levels` as `site`'s container, dropping the container when the list is empty:
    /// `levels` is `minOccurs="0"`, so its absence is conformant and an empty one is not.
    public static void install(Site_VersionStructure site, List<Object> levels) {
        if (levels.isEmpty()) {
            site.setLevels(null);
            return;
        }
        Levels_RelStructure container = new Levels_RelStructure();
        container.getLevelRefOrLevel().addAll(levels);
        site.setLevels(container);
    }

    /// Re-point every `LevelRef` under `site` that `remap` renames.
    ///
    /// @return how many references moved
    public static int repoint(Site_VersionStructure site, Map<String, String> remap) {
        if (remap.isEmpty()) return 0;
        int[] moved = {0};
        visit(site, new RefVisitor() {
            @Override public void ref(LevelRefStructure r) {
                String to = remap.get(r.getRef());
                if (to == null) return;
                r.setRef(to);
                moved[0]++;
            }
        });
        return moved[0];
    }

    /// What [#visit] reports.
    public interface RefVisitor {

        /// One `LevelRef`, reached through the member that holds it.
        void ref(LevelRefStructure r);

        /// A Site the walk found inside the Site it started from. `own(s)` is then ambiguous for
        /// any reference below it.
        default void nestedSite(Site_VersionStructure nested) {}

        /// True when this node is invisible to the walk. A seam for a test that needs the traversal
        /// narrower than the store.
        default boolean blind(Object node) { return false; }
    }

    /// Hand every `LevelRef` under `site` to `visitor`, in walk order. The carriers below are the
    /// complete set of places the model declares a `LevelRefStructure` member, a list
    /// `TestEpipLevelRefs` pins against the model by reflection.
    ///
    /// [RecursiveAttributes#walk] does the navigating; this method only says what to do with each
    /// node it is handed. A reference arrives as a leaf and is never recursed into, so a
    /// `LevelRefStructure` reaching `reference()` is ignored there and reported through its holder,
    /// which is the only thing that can also rewrite or remove it.
    public static void visit(Site_VersionStructure site, RefVisitor visitor) {
        RecursiveAttributes.walk(site, new RecursiveAttributes.Visitor() {
            @Override public void reference(VersionOfObjectRefStructure ref) {}

            @Override public void node(Object o) {
                if (o instanceof Site_VersionStructure nested) visitor.nestedSite(nested);
                if (visitor.blind(o)) return;
                if (o instanceof SiteComponent_VersionStructure sc) {
                    if (sc.getLevelRef() != null) visitor.ref(sc.getLevelRef());
                } else if (o instanceof SitePathLink_VersionStructure pl) {
                    if (pl.getLevelRef() != null) visitor.ref(pl.getLevelRef());
                } else if (PathLinkEnds.isEnd(o)) {
                    LevelRefStructure r = PathLinkEnds.levelRef(o);
                    if (r != null) visitor.ref(r);
                } else if (o instanceof Levels_RelStructure lv) {
                    for (Object member : lv.getLevelRefOrLevel()) {
                        if (member instanceof LevelRefStructure r) visitor.ref(r);
                    }
                } else if (o instanceof SiteStructure_VersionStructure ss) {
                    if (ss.getLevelsInStructure() == null) return;
                    for (LevelInStructure_VersionStructure lis
                            : ss.getLevelsInStructure().getLevelInStructure()) {
                        if (lis.getLevelRef() != null) visitor.ref(lis.getLevelRef());
                    }
                }
            }
        });
    }
}
