package toolkit.store;

// WHAT IS PINNED. toolkit.store.Store is no longer a 44-method list: it is the UNION of seven
// role supertypes — StoreTxns, ClassRegistry, ObjectReader, IdIndex, ObjectWriter, RefGraph,
// StoreResolve — and declares nothing itself but the handle (path/formatMarker/formatId/
// isReadonly/close) and copyMap, which needs two roles on `this` and so belongs to neither.
//
// WHY A TEST AND NOT JUST A COMPILE. `Store` keeps compiling either way, because every consumer in
// the repository is still typed `Store` — narrowing them would need
// `<S extends ObjectReader & ClassRegistry>` intersections through every caller. So the
// load-bearing claims are made HERE, each a compile-time or reflective fact a bad re-merge breaks:
//
//   * a variable of each role type is assignable from a Store AND can do that role's work —
//     if a method were left on Store instead of moved, the narrow variable would not compile;
//   * a double implementing ObjectReader ALONE exists below, in ~30 lines, over a HashMap, with
//     no txn, no LMDB and no write path. That is the payoff of the split and it is exercised
//     through the same helper the real store goes through;
//   * Store.class.getMethods() is exactly the union of the roles' declared methods plus Store's
//     own six. Adding a method to Store instead of to a role fails this;
//   * Store.MergeFn — the pre-split spelling, used at four call sites in toolkit/ and tools/ —
//     still resolves now that the type is declared inside ObjectWriter, and is the same type.
//
// NEUTER PROOF (each test names the edit that must break it):
//   * testEachRoleIsUsableOnItsOwn        — move any of the named methods back onto Store; the
//     narrow local stops compiling.
//   * testAReaderDoubleNeedsNothingElse   — make ObjectReader extend StoreTxns or ClassRegistry;
//     MapReader below no longer compiles.
//   * testStoreDeclaresOnlyTheHandle      — add a method to Store's own body; the sets diverge.
//   * testStoreIsExactlyTheUnionOfItsRoles— drop a role from the extends clause, or drop a method
//     from a role; the union stops matching getMethods().
//   * testMergeFnStillResolvesThroughStore— the assignment is the test: move MergeFn somewhere a
//     subinterface does not inherit it and this file does not compile.

import noi.netex.model.Line;
import noi.netex.model.Operator;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestStoreRoles {

    /// The seven supertypes, in the order Store's extends clause names them.
    private static final Class<?>[] ROLES = {
        StoreTxns.class, ClassRegistry.class, ObjectReader.class, IdIndex.class,
        ObjectWriter.class, RefGraph.class, StoreResolve.class,
    };

    // ---------------------------------------------------------------- the fixture

    private static void fill(TestStore ts) {
        try (Txn txn = ts.store.rwTxn()) {
            ts.store.insertAnyObjects(txn, List.of(
                    line("X:1", "1", "one"), line("X:2", "1", "two"), operator("O:1", "1")));
            txn.commit();
        }
    }

    private static Line line(String id, String version, String name) {
        Line l = new Line();
        l.setId(id);
        l.setVersion(version);
        l.setName(Mls.of(name));
        return l;
    }

    private static Operator operator(String id, String version) {
        Operator o = new Operator();
        o.setId(id);
        o.setVersion(version);
        return o;
    }

    // ---------------------------------------------------------------- the tests

    /// One local per role, each typed to the role and NOT to `Store`, each doing that role's
    /// work. The assertions are almost beside the point: what is pinned is that these seven
    /// declarations compile, i.e. that every method really did move out of `Store`.
    @Test
    void testEachRoleIsUsableOnItsOwn(TestStore ts) {
        fill(ts);

        StoreTxns txns = ts.store;
        ClassRegistry registry = ts.store;
        ObjectReader reader = ts.store;
        IdIndex ids = ts.store;
        RefGraph graph = ts.store;
        StoreResolve resolve = ts.store;

        try (Txn tx = txns.roTxn()) {
            Check.that(registry.dbNames(tx).contains(Line.class), "the registry knows Line");
            Check.equals(registry.classIdxInt(Line.class),
                    toolkit.keycodec.NulKeyCodec.fullKeyClassIdx(
                            registry.fullKeyOf(Line.class, new byte[] {1, 0, 0, 0})),
                    "fullKeyOf travels with the registry, not with the reads");

            Check.equals(2, count(reader.iterOnlyObjects(tx, Line.class)),
                    "the reader alone iterates a class map");

            List<IdRow> rows = ids.lookupById(tx, "X:1", null);
            Check.equals(1, rows.size(), "the id index alone resolves an address");
            Object o = reader.loadObjectByFullKey(tx, rows.get(0).fullKey());
            Check.that(o instanceof Line, "and the reader alone decodes what it resolved to");

            Check.equals(0, count(graph.iterEdges(tx)),
                    "the graph role reads the edge index (empty here: nothing references anything)");
            Check.that(!resolve.blobRepairMarkerPresent(tx),
                    "the resolve role reads its own marker without a write txn");
        }

        // The write role, last, because it needs its own txn.
        ObjectWriter writer = ts.store;
        try (Txn tx = txns.rwTxn()) {
            writer.insertAnyObjects(tx, List.of(line("X:3", "1", "three")));
            tx.commit();
        }
        try (Txn tx = txns.roTxn()) {
            Check.equals(3, count(reader.iterOnlyObjects(tx, Line.class)), "the writer wrote");
        }
    }

    /// **The point of the whole exercise.** [MapReader] implements [ObjectReader] and NOTHING else
    /// — no transactions, no class registry, no id index, no write path, no LMDB — and the same
    /// helper reads it and the real store.
    @Test
    void testAReaderDoubleNeedsNothingElse(TestStore ts) {
        fill(ts);

        MapReader fake = new MapReader();
        fake.add(Line.class, line("F:1", "1", "fake one"));
        fake.add(Line.class, line("F:2", "1", "fake two"));
        fake.add(Operator.class, operator("F:9", "1"));

        Check.equals(List.of("fake one", "fake two"), names(fake, null),
                "the double answers the reader question");
        try (Txn tx = ts.store.roTxn()) {
            Check.equals(List.of("one", "two"), names(ts.store, tx),
                    "and the real store answers the same question through the same parameter");
        }
        // Not vacuous: the helper's parameter is ObjectReader, so neither call could have reached
        // a txn, the registry or an insert even if it wanted to.
        Check.equals(ObjectReader.class, namesParameterType(), "names(ObjectReader, Txn)");
    }

    /// `Store`'s own body is the handle and nothing else. A method that "moved" but was left
    /// behind, or a new method added to the union instead of to a role, shows up here.
    @Test
    void testStoreDeclaresOnlyTheHandle() {
        Check.equals(new TreeSet<>(Set.of(
                        "path()", "formatMarker()", "formatId()", "isReadonly()",
                        "copyMap(Txn,Store,Txn,Class)", "close()")),
                new TreeSet<>(declared(Store.class)),
                "Store declares the handle, plus copyMap which needs reader AND writer on `this`");
    }

    /// The union is exact: every method callable on a `Store` is declared by exactly one role
    /// or by `Store` itself, and every method a role declares is callable on a `Store`.
    /// Nothing was lost in the move and nothing was invented.
    @Test
    void testStoreIsExactlyTheUnionOfItsRoles() {
        Set<String> union = new LinkedHashSet<>(declared(Store.class));
        for (Class<?> role : ROLES) {
            Set<String> own = declared(role);
            Check.that(!own.isEmpty(), role.getSimpleName() + " declares something");
            for (String m : own) {
                Check.that(union.add(m), m + " is declared by exactly one role (" + role + ")");
            }
        }
        Set<String> callable = new LinkedHashSet<>();
        for (Method m : Store.class.getMethods()) {
            if (!m.isSynthetic() && !m.isBridge()) callable.add(signature(m));
        }
        Check.equals(new TreeSet<>(union), new TreeSet<>(callable),
                "Store's callable surface == the roles' declarations + its own");
        // Not vacuous: a two-method interface would satisfy an equality of two empty-ish sets.
        Check.that(union.size() >= 40, "the surface really is the whole SPI: " + union.size());

        Set<Class<?>> extended = Set.of(Store.class.getInterfaces());
        for (Class<?> role : ROLES) {
            Check.that(extended.contains(role), "Store extends " + role.getSimpleName());
        }
        Check.that(extended.contains(AutoCloseable.class), "…and is still AutoCloseable");
    }

    /// `MergeFn` lives inside [ObjectWriter]. Member types are inherited through subinterfaces, so
    /// `Store.MergeFn` — the spelling at every existing call site — still names it, and names the
    /// SAME type. Both assignments below are the assertion; the insert underneath proves the policy
    /// still reaches the insert path.
    @Test
    void testMergeFnStillResolvesThroughStore(TestStore ts) {
        Store.MergeFn viaStore = (existing, incoming) -> {
            Mls.setText(((Line) incoming).getName(),
                    Mls.text(((Line) existing).getName()) + "+" + Mls.text(((Line) incoming).getName()));
            return incoming;
        };
        ObjectWriter.MergeFn viaRole = viaStore;      // one type, two spellings
        Check.that(viaStore == viaRole, "Store.MergeFn and ObjectWriter.MergeFn are one type");

        fill(ts);
        // Three roles, three variables: the writer cannot open the txn it writes under, because a
        // txn is StoreTxns' to give. That separation is the reason resolve() is its own role too.
        StoreTxns txns = ts.store;
        ObjectWriter writer = ts.store;
        ObjectReader reader = ts.store;

        writer.setMergePolicy(Line.class, viaRole);
        try (Txn tx = txns.rwTxn()) {
            writer.insertAnyObjects(tx, List.of(line("X:1", "1", "again")));
            tx.commit();
        } finally {
            writer.setMergePolicy(Line.class, null);      // session state: clear it for the next test
        }

        try (Txn tx = txns.roTxn()) {
            Check.equals(List.of("one+again", "two"), names(reader, tx),
                    "the policy ran on the duplicate insert, so MergeFn reached the insert path");
        }
    }

    // ---------------------------------------------------------------- helpers

    /// Reads Line names through the narrow role — the parameter is what is being pinned.
    private static List<String> names(ObjectReader reader, Txn txn) {
        List<String> out = new ArrayList<>();
        for (Object o : reader.iterOnlyObjects(txn, Line.class)) {
            out.add(Mls.text(((Line) o).getName()));
        }
        return out;
    }

    private static Class<?> namesParameterType() {
        for (Method m : TestStoreRoles.class.getDeclaredMethods()) {
            if (m.getName().equals("names")) return m.getParameterTypes()[0];
        }
        throw new AssertionError("names() vanished");
    }

    private static int count(Iterable<?> it) {
        int n = 0;
        for (Object ignored : it) n++;
        return n;
    }

    /// `name(SimpleParamTypes)` for every method an interface declares itself.
    private static Set<String> declared(Class<?> iface) {
        Set<String> out = new LinkedHashSet<>();
        for (Method m : iface.getDeclaredMethods()) {
            if (!m.isSynthetic() && !m.isBridge()) out.add(signature(m));
        }
        return out;
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    /// An [ObjectReader] and nothing else, over a [HashMap]. It ignores the [Txn] entirely — a
    /// reader role holder never opened one and cannot — which is what makes it thirty lines instead
    /// of a fake LMDB.
    static final class MapReader implements ObjectReader {
        private final Map<Class<?>, List<Object>> byClass = new HashMap<>();

        void add(Class<?> clazz, Object o) {
            byClass.computeIfAbsent(clazz, k -> new ArrayList<>()).add(o);
        }

        @Override public Iterable<Object> iterOnlyObjects(Txn txn, Class<?> clazz) {
            return byClass.getOrDefault(clazz, List.of());
        }

        @Override public Iterable<ObjectRow> iterObjects(Txn txn, Class<?> clazz) {
            List<ObjectRow> out = new ArrayList<>();
            int i = 0;
            for (Object o : iterOnlyObjects(txn, clazz)) out.add(new ObjectRow(key(i++), o));
            return out;
        }

        @Override public Iterable<byte[]> scanObjects(Txn txn, Class<?> clazz) {
            List<byte[]> out = new ArrayList<>();
            for (int i = 0; i < byClass.getOrDefault(clazz, List.of()).size(); i++) out.add(key(i));
            return out;
        }

        @Override public Iterable<RawRow> iterRawObjects(Txn txn, Class<?> clazz) {
            throw new UnsupportedOperationException("no blobs: this double never stored bytes");
        }

        @Override public byte[] loadRawObject(Txn txn, Class<?> clazz, byte[] localKey) {
            return null;
        }

        @Override public Object loadObject(Txn txn, Class<?> clazz, byte[] localKey) {
            List<Object> l = byClass.getOrDefault(clazz, List.of());
            int i = localKey[0];
            return i < l.size() ? l.get(i) : null;
        }

        @Override public Object loadObjectByFullKey(Txn txn, long fullKey) {
            throw new UnsupportedOperationException("full_keys need a ClassRegistry, which this is not");
        }

        @Override public Object loadObjectByReference(Txn txn,
                noi.netex.model.VersionOfObjectRefStructure ref, boolean missingOk) {
            throw new UnsupportedOperationException("refs need an IdIndex, which this is not");
        }

        private static byte[] key(int i) {
            return new byte[] {(byte) i, 0, 0, 0};
        }
    }
}
