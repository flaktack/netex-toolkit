package toolkit.store;

// The seam AbstractLmdbStore was split along, pinned from outside: resolve() and
// resolveEmbeddings() live in ReferenceResolver, EmbeddingResolver, BlobRepair and ClosureQueries.
// Byte-identity tools answer "did the database change", not "is it still assembled this way", so
// three properties are asserted here. The tier loop's own blob repair reaches the stored bytes by
// patch — TestBlobSweepScan covers the one-shot sweep's, which finds nothing after a pure-insert
// load. The store holds one BlobRepair and both passes share it. And exactly one place opens the
// write transaction, which is what makes StoreResolve's rule ("opens and commits its own write
// txn, never call it under an open rwTxn") true.

import noi.netex.model.Line;
import noi.netex.model.Route;
import org.junit.jupiter.api.Test;
import toolkit.codec.FragmentCodec;
import toolkit.load.NetexLoader;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class TestResolveSeam {

    /// R:1's LineRef names a version L:1 does not have, so its exact id-index key misses and the
    /// id-prefix tier hits on the class trailer — a versionChange. L:2's OperatorRef names an id
    /// that exists under ANOTHER class, so the id+version tier hits — a classChange, and the source
    /// carried no `nameOfRefClass` at all, so the repair has to invent one.
    private static final String FRAME = """
        <SiteFrame id="sf" version="1">
          <stopPlaces><StopPlace id="SP:1" version="1"><Name>Stop</Name></StopPlace></stopPlaces>
        </SiteFrame>
        <ServiceFrame id="svf" version="1">
          <lines>
            <Line id="L:1" version="1"><Name>One</Name></Line>
            <Line id="L:2" version="1"><Name>Two</Name><OperatorRef ref="SP:1" version="1"/></Line>
          </lines>
          <routes>
            <Route id="R:1" version="1"><Name>Rotta</Name><LineRef ref="L:1" version="9"/></Route>
          </routes>
        </ServiceFrame>
        """;

    // TestStore.loadNetex resolves; every arm here needs the load and the pass apart, so the
    // loader is driven directly.
    private static NetexLoader loader;

    private static synchronized void load(Store target) throws Exception {
        if (loader == null) loader = new NetexLoader();
        String xml = TestStore.SKELETON.replace("__FRAMES__", FRAME);
        loader.loadStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                target, "seam");
    }

    private static String fragmentOf(Store store, Class<?> clazz, String id) {
        FragmentCodec codec = new FragmentCodec();
        try (Txn tx = store.roTxn()) {
            for (RawRow row : store.iterRawObjects(tx, clazz)) {
                String xml = new String(codec.decompress(row.blob()), StandardCharsets.UTF_8);
                if (xml.contains("id=\"" + id + "\"")) return xml;
            }
        }
        throw new AssertionError("no stored " + clazz.getSimpleName() + " " + id);
    }

    /// A tier-2 match rewrites a reference's version and a tier-1 match invents its
    /// nameOfRefClass, both in the stored fragment and without re-marshalling it.
    @Test
    void testABindTimeTierRepairPatchesTheStoredBytes(TestStore ts) throws Exception {
        load(ts.store);

        String before = fragmentOf(ts.store, Route.class, "R:1");
        Check.that(before.contains("version=\"9\""),
                "the capture holds the source's wrong version verbatim: " + before);

        Check.equals(2L, ts.store.resolve(),
                "both references match a tier: one on id+class, one on id+version");

        String route = fragmentOf(ts.store, Route.class, "R:1");
        Check.that(route.contains("<LineRef ref=\"L:1\" version=\"1\"/>"),
                "the tier-2 match rewrote the reference's version in the STORED bytes: " + route);
        Check.that(!route.contains("xmlns:ns2="),
                "and did it by patching, not by re-marshalling — the fragment is still the "
                        + "source's own bytes: " + route);

        String line = fragmentOf(ts.store, Line.class, "L:2");
        Check.that(line.contains("nameOfRefClass=\"StopPlace\""),
                "the tier-1 match INVENTED the nameOfRefClass the source never carried, naming "
                        + "the class the id actually resolves to: " + line);
        Check.that(!line.contains("xmlns:ns2="), "also patched, not re-marshalled: " + line);
    }

    /// Reflection rather than behaviour: one shared patcher and one per pass differ in no byte of
    /// the database. The RefPatcher's refusal and odd-name counters are cumulative over the store
    /// while the outcome tally resets per stage, so a second instance would silently make them
    /// per-pass.
    @Test
    void testTheStoreHoldsOneBlobRepairSharedByBothPasses(TestStore ts) throws Exception {
        Object store = ts.store;
        Class<?> abs = Class.forName("toolkit.store.AbstractLmdbStore");
        Check.that(abs.isInstance(store), "the v2 store is still an AbstractLmdbStore");

        Object blobRepair = read(abs, store, "blobRepair");
        Check.that(blobRepair != null, "the store constructs its BlobRepair once, as a field");
        Check.equals("BlobRepair", blobRepair.getClass().getSimpleName(), "and it is that class");

        for (String pass : new String[] {"references", "embeddings"}) {
            Object resolver = read(abs, store, pass);
            Check.that(resolver != null, pass + " is a per-store field too");
            Check.that(read(resolver.getClass(), resolver, "blobRepair") == blobRepair,
                    pass + " holds the store's OWN BlobRepair instance, not one of its own — the "
                            + "RefPatcher's refusal counters are cumulative over the store and a "
                            + "second instance would silently make them per-pass");
            Check.that(read(resolver.getClass(), resolver, "store") == store,
                    pass + " reaches its backend through exactly one field, the store itself");
        }
    }

    private static Object read(Class<?> owner, Object target, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    /// The refusal comes from the store's `checkWritable`, before any transaction is opened — the
    /// readonly half of "the store owns the single-writer lock".
    @Test
    void testAReadonlyStoreRefusesBothPassesBeforeOpeningAnything(TestStore ts)
            throws Exception {
        Path path = ts.tempPath("ro.lmdb");
        try (Store store = Stores.create(path, ts.format)) {
            load(store);
        }
        try (Store store = Stores.open(path, true, ts.format)) {
            Check.that(refusal(() -> store.resolve()).contains("readonly"),
                    "resolve() on a readonly store is refused by the store, not by LMDB");
            Check.that(refusal(() -> store.resolveEmbeddings()).contains("readonly"),
                    "and so is resolveEmbeddings()");
        }
    }

    /// Each pass commits its own transaction: reopening the database from scratch — a fresh handle
    /// that has never run the pass — finds the edges.
    @Test
    void testEachPassCommitsBeforeItReturns(TestStore ts) throws Exception {
        Path path = ts.tempPath("commit.lmdb");
        try (Store store = Stores.create(path, ts.format)) {
            load(store);
            Check.equals(2L, store.resolve(), "the tier loop wired both references");
            store.resolveEmbeddings();
        }
        try (Store reopened = Stores.open(path, true, ts.format)) {
            try (Txn tx = reopened.roTxn()) {
                int edges = 0;
                for (long[] ignored : reopened.iterEdges(tx)) edges++;
                Check.equals(2, edges, "both edges survived the close: the passes committed");
                Check.that(reopened.blobRepairMarkerPresent(tx),
                        "and so did the one-shot sweep's marker, written inside resolve()'s txn");
            }
        }
    }

    private static String refusal(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        throw new AssertionError("expected a refusal, got none");
    }
}
