package toolkit.store;

// Pins WHY the embedding-index allowlist is the set it is, so neither the removal nor the fix can
// silently become wrong. The list is now Stores.NETEX_EMBEDDING_SCOPE — injected into the store
// rather than compiled into it (the storage engine names no NeTEx class) — so this file asks the
// PROFILE for the set and asks the store only whether it can see the names. Everything checked
// before is checked here; what a store does with a scope OTHER than this one is
// TestEmbeddingScopeInjection.
//
//   1. every allowlist entry is a name AbstractLmdbStore.inEmbeddingScope can actually see, i.e.
//      a registrable model class (the store's class table is ModelRegistry.classes()) — with the
//      single, deliberate exception of `Entrance`, which is kept as documentation;
//   2. RouteView — the removed entry — is unreachable BY CONSTRUCTION, not by luck: it is
//      a DerivedViewStructure, not an EntityStructure, so it can carry no id and can never be an
//      embedding-index key. If a model regeneration ever made it an entity, this test fails and
//      the removal must be revisited;
//   3. `Entrance` is STILL LISTED and STILL cannot fire, because the NeTEx <Entrance> element
//      binds to SiteEntrance_VersionStructure in the JAXB name space, which is why a name-only
//      list does not work here. The allowlist lists SiteEntrance_VersionStructure ALONGSIDE it,
//      which is what makes every id-bearing Entrance index;
//   4. the SAME class of error is re-checked across the other visible entries: each must bind to
//      a class of exactly its own name. Many global NeTEx elements bind to a registrable class
//      with a different simple name, so this is asserted mechanically.

import noi.netex.model.EntityStructure;
import noi.netex.model.ObjectFactory;
import org.junit.jupiter.api.Test;
import toolkit.model.ModelRegistry;
import toolkit.test.Check;

import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TestEmbeddingScope {

    /// The profile's scope — the subject of this file. A store gets it by injection, not by import.
    private static final AbstractLmdbStore.EmbeddingScope SCOPE = Stores.NETEX_EMBEDDING_SCOPE;

    private static Set<String> allow() {
        return SCOPE.classNames();
    }

    /// Every allowlist entry except the deliberately-kept `Entrance` label names a
    /// registrable class — otherwise inEmbeddingScope's `nameToIdx.containsKey(name)` guard
    /// drops it silently.
    @Test
    void testEveryAllowlistEntryIsAVisibleName() {
        List<String> invisible = new ArrayList<>();
        for (String name : allow()) {
            if (ModelRegistry.classForName(name) == null) invisible.add(name);
        }
        Check.equals(List.of("Entrance"), invisible,
                "the ONLY allowlist entry the store cannot see is the kept-as-documentation "
                        + "Entrance label; its JAXB alias SiteEntrance_VersionStructure carries it");
    }

    /// The exact scope, pinned — every visible entry is an id-carrying entity, and the SET is the
    /// eight plus SiteEntrance_VersionStructure. The set assertion is what makes this test
    /// mutation-detectable.
    @Test
    void testEveryVisibleEntryCanCarryAnId() {
        Check.equals(
                Set.of("Quay", "StopPointInJourneyPattern", "Level",
                        "Entrance", "SiteEntrance_VersionStructure",
                        "PointOnRoute", "BoardingPosition", "FlexibleArea", "HailAndRideArea"),
                allow(),
                "the curated allowlist is the original nine minus RouteView "
                        + "plus SiteEntrance_VersionStructure");
        for (String name : allow()) {
            Class<?> c = ModelRegistry.classForName(name);
            if (c == null) continue;   // the Entrance label, asserted separately
            Check.that(EntityStructure.class.isAssignableFrom(c),
                    name + " must be an EntityStructure to be an embedding-index key");
            try {
                c.getMethod("getId");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(name + " must carry an id to be an embedding-index key");
            }
        }
    }

    /// RouteView was REMOVED as genuinely unreachable. The reason is structural: it is a view.
    /// Pin the reason, not the removal — a listed RouteView would still be dead, but an entity
    /// RouteView would mean the removal has become a real gap.
    @Test
    void testRouteViewIsAViewAndSoCanNeverBeIndexed() {
        Check.that(!allow().contains("RouteView"),
                "RouteView was removed from the allowlist");
        Check.isNull(ModelRegistry.classForName("RouteView"),
                "RouteView is not registrable — it is a DerivedViewStructure, not an entity");
        Class<?> rv = noi.netex.model.RouteView.class;
        Check.that(!EntityStructure.class.isAssignableFrom(rv),
                "RouteView is a view: it can carry no id, so it can never be an embedding key");
    }

    /// The Entrance name mismatch is CLOSED, and closed by addition rather than by substitution.
    /// The label stays (it is the element's name and deleting it would cement the gap),
    /// and the class the parser actually builds for `<Entrance>` is listed next to it.
    @Test
    void testEntranceIsCarriedByItsJaxbAlias() {
        Set<String> allow = allow();
        Check.that(allow.contains("Entrance"),
                "Entrance stays listed: it is the element name and deleting it would cement the gap");
        Check.isNull(ModelRegistry.classForName("Entrance"),
                "no JAXB class is named Entrance — XJC emits none for an element with a named type");
        Check.that(allow.contains("SiteEntrance_VersionStructure"),
                "the class the parser DOES build for <Entrance> is listed, so it indexes");
        Class<?> actual = ModelRegistry.classForName("SiteEntrance_VersionStructure");
        Check.that(actual != null, "…and SiteEntrance_VersionStructure is registrable");
        Check.that(EntityStructure.class.isAssignableFrom(actual),
                "…and IS an entity, so the miss was a naming defect, not an unindexable class");
        try {
            actual.getMethod("getId");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("…and it carries an id, so it indexes cleanly");
        }
        Check.that(elementBinding("Entrance") == actual,
                "…and it is exactly what the <Entrance> element declaration binds to");
    }

    /// The generalisation of the Entrance defect: for every allowlist name that IS visible, the
    /// NeTEx element of that name must bind to a class of exactly that name. A name/class mismatch
    /// is the failure mode that made Entrance dead, and it is common in the model at large (122
    /// global elements bind to a registrable class of a different name), so it is asserted here
    /// rather than assumed.
    ///
    /// `SiteEntrance_VersionStructure` is exempt: it is by construction the alias for an
    /// element named something else, which is what the entry is for.
    @Test
    void testNoOtherAllowlistNameIsAnElementClassMismatch() {
        List<String> mismatched = new ArrayList<>();
        for (String name : allow()) {
            if (name.equals("Entrance") || name.equals("SiteEntrance_VersionStructure")) continue;
            Class<?> bound = elementBinding(name);
            if (bound == null || !bound.getSimpleName().equals(name)) {
                mismatched.add(name + " -> "
                        + (bound == null ? "(no element decl)" : bound.getSimpleName()));
            }
        }
        Check.equals(List.of(), mismatched,
                "every other allowlist name must be the simple name of the class its NeTEx "
                        + "element actually binds to — the Entrance failure mode, re-checked");
    }

    /// The completion marker's value must NAME the scope, because that is the only reason the
    /// marker can survive a widening of the allowlist honestly. The bug it closes: the marker used
    /// to be a bare flag, so adding SiteEntrance_VersionStructure left every existing
    /// store asserting a completeness it no longer had — the index-driven path was taken and the
    /// new class's containers were never selected, silently.
    ///
    /// What is asserted is the PROPERTY the fix rests on, not the literal string: every
    /// allowlist entry appears in the tag, so no widening can leave it unchanged, and the
    /// generative half carries a version of its own since no name can speak for it.
    @Test
    void testScopeTagNamesEveryEntryAndTheGenerativeRule() {
        String tag = SCOPE.tag();
        for (String name : allow()) {
            Check.that(tag.contains(name),
                    name + " must appear in the scope tag — otherwise adding it leaves the tag "
                            + "unchanged and stale indexes keep claiming completeness");
        }
        Check.that(tag.startsWith("typeOfNonRef/"),
                "the TypeOf* half of inEmbeddingScope has no names to enumerate, so the tag "
                        + "carries a version for it that must be bumped when that rule changes");
        Check.equals(SCOPE.tag(), tag,
                "the tag must be stable for a given scope — it is compared against stored bytes");
    }

    /// The scope also decides the TypeOf* half's answers, and that half is CODE rather than data —
    /// a caller cannot vary it, which is why the tag versions it instead of listing it. Pinned
    /// because `inEmbeddingScope` delegates the whole predicate here and nothing else asserts
    /// that the generative rule survived the move out of the store.
    @Test
    void testTheGenerativeHalfIsPartOfEveryScope() {
        Check.that(SCOPE.admits("TypeOfProductCategory"),
                "TypeOf* non-Ref classes are in scope by RULE, listed nowhere");
        Check.that(!SCOPE.admits("TypeOfProductCategoryRef"),
                "…and the *Ref exclusion is the other half of that rule");
        AbstractLmdbStore.EmbeddingScope empty =
                new AbstractLmdbStore.EmbeddingScope(Set.of());
        Check.that(empty.admits("TypeOfProductCategory"),
                "an injected scope cannot switch the generative half off — it is not data");
        Check.that(!empty.admits("Quay"),
                "…but the curated half is entirely the caller's, and an empty one admits nothing");
    }

    /// The store-side static that `tools/inspect/nx` reports the scope from must be the
    /// profile's own set. It is the one place the engine still answers a question about NeTEx
    /// classes, and the answer has to be a delegation rather than a second copy — a drifting copy
    /// would make the inspector describe a scope no store was ever built under.
    @Test
    void testTheReportedDefaultIsTheProfileScope() {
        Check.equals(allow(), AbstractLmdbStore.embeddingIndexClassNames(),
                "AbstractLmdbStore.embeddingIndexClassNames() is Stores.NETEX_EMBEDDING_SCOPE's "
                        + "list, not a copy of it");
    }

    /// The Java class the global NeTEx element of this local name binds to.
    private static Class<?> elementBinding(String localName) {
        for (Method m : ObjectFactory.class.getMethods()) {
            XmlElementDecl d = m.getAnnotation(XmlElementDecl.class);
            if (d == null || !d.name().equals(localName)) continue;
            if (d.scope() != XmlElementDecl.GLOBAL.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1) return p[0];
        }
        for (Class<?> c : ModelRegistry.classes()) {
            XmlRootElement r = c.getAnnotation(XmlRootElement.class);
            if (r != null && r.name().equals(localName)) return c;
        }
        return null;
    }
}
