package toolkit.prov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// The capability vector every provenance stamp is derived from.
///
/// A capability is a fix whose absence changes stored or exported bytes. A pure performance change
/// never earns a name.
///
/// The list is pinned from both ends by `toolkit/src/test/java/toolkit/prov/TestProvenance.java`:
/// every name here must appear in at least one `PROV-CAP: <name>` marker at its fix site, and every
/// marker in the tree must name a capability here.
public final class Capabilities {

    private Capabilities() {}

    /// One behaviour-changing fix.
    public record Cap(String name, String what) {}

    /// The vector. Order is cosmetic — comparisons are set-based — but kept stable.
    public static final List<Cap> ALL = List.of(
            new Cap("denorm-depth",
                    "reference denormalisation applied at EVERY depth of a captured fragment, not "
                    + "only at its root"),
            new Cap("derived-id-class-token",
                    "a synthesized id swaps the class token of the ancestor it derives from, and "
                    + "mints one where that id names no class, so a child no longer wears its "
                    + "parent's id on another class"),
            new Cap("srsname-depth",
                    "srsName injected at depth as well as at the fragment root"),
            new Cap("schema-defaults",
                    "omit-empty / omit-default marshal rule: schema-default attributes and empty "
                    + "<DaysOfWeek/> are not materialised"),
            new Cap("adapters-lossless",
                    "xs:time / xs:date / xs:dateTime / xs:duration / xs:normalizedString retain "
                    + "their source lexical form (offsets, hour 24..47, sub-second durations)"),
            new Cap("no-remarshal-resolve",
                    "resolve() patches reference attributes in place instead of re-marshalling the "
                    + "whole stored blob, so no adapter loss is baked into storage"),
            new Cap("no-invent-nameofrefclass",
                    "RefPatcher never ADDS a nameOfRefClass attribute to a reference whose source "
                    + "fragment did not carry one"),
            new Cap("hour24-semantics",
                    "an xs:time with hour >= 24 keeps its wall-clock reading instead of being "
                    + "folded to 00, so it orders after 23:59:59"),
            new Cap("embed-scope-siteentrance",
                    "the embedding-index allowlist also lists the DECLARED-TYPE simple names, so "
                    + "<Entrance> -> SiteEntrance_VersionStructure is indexed"),
            new Cap("enum-collapse",
                    "xs:collapse applied to enum-valued element text before the JAXB RI parses it, "
                    + "so <TransportMode>bus </TransportMode> binds instead of silently binding null"),
            new Cap("dedup-definition-days",
                    "where two versions of one duplicate-id Line place the same journey definition "
                    + "(line, stop sequence, passing times) on the same day, exactly one keeps it"),
            new Cap("strip-orphan-levelrefs",
                    "a LevelRef whose target is not a Level its own Site defines is cleared in the "
                    + "EPIP transform, on both shapes — any SiteComponent's levelRef and a "
                    + "<LevelRef> held directly in a Site's <levels> — so the export stops emitting "
                    + "references EPIP's Level_KeyRef requires to resolve in the same document"),
            new Cap("netex-2.0-model",
                    "the model is generated from the NeTEx 2.0.0 schemas. Two consequences a store "
                    + "carries: the class table enumerates 1,544 registrable classes against "
                    + "1,421, and _class_idx is written once at creation, so a 1.15.2-built store "
                    + "cannot be a clone source for a 2.0 chain; and 2.0 redefines "
                    + "MultilingualString as a mixed type, so an empty one marshals <Description/> "
                    + "rather than <Description></Description>"));

    /// The capability set of the code that is running right now — [#ALL] minus every
    /// capability whose gating knob does not hold.
    ///
    /// Knobs decide entries: `-Dtoolkit.refPatch=false` takes the whole-blob re-marshal path, which
    /// is the absence of `no-remarshal-resolve`. The gate lives on the [toolkit.util.Conf.Flag], not
    /// on [Cap]: a set of names crosses, nothing else.
    public static Set<String> current() {
        Set<String> withheld = toolkit.util.Conf.withheldCaps();
        Set<String> s = new LinkedHashSet<>();
        for (Cap c : ALL) if (!withheld.contains(c.name())) s.add(c.name());
        return Collections.unmodifiableSet(s);
    }

    /// Every capability this build can have, regardless of configuration.
    public static Set<String> declared() {
        Set<String> s = new LinkedHashSet<>();
        for (Cap c : ALL) s.add(c.name());
        return Collections.unmodifiableSet(s);
    }

    /// Human blurb for a name, or null.
    public static String describe(String name) {
        for (Cap c : ALL) if (c.name().equals(name)) return c.what();
        return null;
    }

    /// Names sorted for stable display.
    public static List<String> sorted(Set<String> caps) {
        List<String> l = new ArrayList<>(caps);
        Collections.sort(l);
        return l;
    }
}
