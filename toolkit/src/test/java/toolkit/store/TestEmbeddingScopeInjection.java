package toolkit.store;

// The embedding-index scope is INJECTED (Stores.NETEX_EMBEDDING_SCOPE is a default, not a law), and
// a store's stored bytes depend on it. TestEmbeddingScope pins WHAT the profile's set is; this file
// pins what the STORE does with a set that is not the profile's, which is where an injectable scope
// can go wrong in a way no compile catches.
//
// THE HAZARD. The index has a completion marker whose VALUE is the scope's tag, and
// resolveEmbeddings trusts the index only when that tag is the one the running store asks under.
// That covers the read direction. The write direction is the hole: inserts feed the index through
// THIS store's scope and touch no marker, so opening a scope-A store read-WRITE under scope B
// leaves rows that are A for the old objects and B for the new ones under a marker still claiming
// A, and a later scope-A reader would trust an index missing containers. So the store drops the
// marker when it is opened for writing under a scope that did not build it.
//
// NEUTER PROOF:
//   * testIndexRowsFollowTheInjectedScope     — make inEmbeddingScope ignore embScope and read the
//     profile list; the narrowed store indexes the Quay anyway and the scope is decoration.
//   * testMarkerRecordsTheInjectedScope       — write embeddingScopeTag() from a static default;
//     both stores claim the same scope and every later comparison compares nothing.
//   * testWritableOpenUnderAnotherScopeRetractsTheMarker — delete the retractForeignScopeMarker
//     call; the store keeps a marker for rows it no longer has, which is the silent-wrong-index
//     state, and this test is the only thing that observes it.

import noi.netex.model.ObjectFactory;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.StopPlace;
import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TestEmbeddingScopeInjection {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// The profile scope minus Quay — narrower, and narrower in the one class the fixture embeds.
    private static final AbstractLmdbStore.EmbeddingScope NO_QUAY = withoutQuay();

    private static AbstractLmdbStore.EmbeddingScope withoutQuay() {
        Set<String> names = new HashSet<>(Stores.NETEX_EMBEDDING_SCOPE.classNames());
        Check.that(names.remove("Quay"), "the fixture below embeds a Quay, so the profile must list it");
        return new AbstractLmdbStore.EmbeddingScope(names);
    }

    /// The injected scope reaches the INSERT path, which is the only place index rows are made. Same
    /// fixture, same loader, two scopes: the row exists under the profile's and does not exist under
    /// one that drops Quay. Row COUNTS rather than contents, the assertion being that the store
    /// asked the scope at all.
    @Test
    void testIndexRowsFollowTheInjectedScope(TestStore ts) throws Exception {
        Path wide = writeStopPlace(ts.tempPath("wide.lmdb"), Stores.NETEX_EMBEDDING_SCOPE);
        Path narrow = writeStopPlace(ts.tempPath("narrow.lmdb"), NO_QUAY);
        Check.equals(1, embIdxRows(wide),
                "the profile scope lists Quay, so the embedded Quay gets its index row");
        Check.equals(0, embIdxRows(narrow),
                "a scope without Quay must produce NO row — otherwise the allowlist is not what "
                        + "the insert path consults and injection is cosmetic");
    }

    /// The marker records the scope the store was opened with, not a compiled-in one. Two stores,
    /// two scopes, two tags — and the profile store's tag is the profile's, so today's stores are
    /// unchanged by the scope having become injectable.
    @Test
    void testMarkerRecordsTheInjectedScope(TestStore ts) throws Exception {
        Path wide = writeStopPlace(ts.tempPath("marker-wide.lmdb"), Stores.NETEX_EMBEDDING_SCOPE);
        Path narrow = writeStopPlace(ts.tempPath("marker-narrow.lmdb"), NO_QUAY);
        Check.equals(Stores.NETEX_EMBEDDING_SCOPE.tag(), marker(wide),
                "a store built under the profile scope marks itself complete FOR that scope");
        Check.equals(NO_QUAY.tag(), marker(narrow),
                "…and one built under another scope marks itself for that one instead");
        Check.that(!Stores.NETEX_EMBEDDING_SCOPE.tag().equals(NO_QUAY.tag()),
                "two scopes that answer differently must have different tags, or the marker "
                        + "cannot tell an index apart from one built for another question");
    }

    /// The write-direction hole, closed: a store opened READ-WRITE under a scope that did not build
    /// its index loses the completeness marker before any insert can run. Without this the store
    /// would go on claiming scope A while accumulating scope-B rows, and the next scope-A reader
    /// would take the index-driven path over an index missing containers.
    @Test
    void testWritableOpenUnderAnotherScopeRetractsTheMarker(TestStore ts) throws Exception {
        Path db = writeStopPlace(ts.tempPath("retract.lmdb"), Stores.NETEX_EMBEDDING_SCOPE);
        try (Store s = Stores.open(db, false, Stores.V2, NO_QUAY)) {
            Check.equals(NO_QUAY.tag(), ((AbstractLmdbStore) s).embeddingScopeTag(),
                    "the store answers under the scope it was OPENED with, not the one on disk");
        }
        Check.isNull(marker(db),
                "a writable open under a foreign scope must drop the marker: from here on the rows "
                        + "are a mixture and no scope can honestly claim them");
        // And it does not come back by reopening under the ORIGINAL scope: the rows really are a
        // mixture now, and only a rebuild can restore a claim. resolveEmbeddings' corpus-scan
        // fallback is what makes that safe rather than lossy.
        try (Store s = Stores.open(db, false, Stores.V2, Stores.NETEX_EMBEDDING_SCOPE)) {
            Check.that(s.formatId().equals(Stores.V2), "…the store still opens normally");
        }
        Check.isNull(marker(db), "…and the retraction is permanent until the index is rebuilt");
    }

    /// The retraction is a WRITE, so a readonly open must not perform it — a reporting tool that
    /// opens a national store under the wrong scope would otherwise mutate it, and readonly stores
    /// cannot write at all (the rw txn would throw before it got that far).
    @Test
    void testReadonlyOpenUnderAnotherScopeChangesNothing(TestStore ts) throws Exception {
        Path db = writeStopPlace(ts.tempPath("readonly.lmdb"), Stores.NETEX_EMBEDDING_SCOPE);
        try (Store s = Stores.open(db, true, Stores.V2, NO_QUAY)) {
            Check.that(s.formatId().equals(Stores.V2), "the store opens readonly under any scope");
        }
        Check.equals(Stores.NETEX_EMBEDDING_SCOPE.tag(), marker(db),
                "a reader that disagrees with the marker falls back to the corpus scan; it does "
                        + "not get to rewrite the store it is reading");
    }

    // ---------------------------------------------------------------- fixture + raw readers

    /// One StopPlace embedding one id-carrying Quay, in a fresh store under `scope`.
    private static Path writeStopPlace(Path path, AbstractLmdbStore.EmbeddingScope scope) {
        Quay q = new Quay();
        q.setId("q1");
        q.setVersion("1");
        StopPlace sp = new StopPlace();
        sp.setId("sp1");
        sp.setVersion("1");
        Quays_RelStructure quays = new Quays_RelStructure();
        quays.getQuayRefOrQuay().add(FACTORY.createQuay(q));
        sp.setQuays(quays);
        try (Store s = Stores.create(path, Stores.V2, scope); Txn t = s.rwTxn()) {
            s.insertAnyObjects(t, List.of(sp));
            t.commit();
        }
        return path;
    }

    /// `_meta/emb_idx_done` of a CLOSED store, read without the store's own code.
    private static String marker(Path db) {
        try (Env<byte[]> env = readEnv(db)) {
            Dbi<byte[]> meta = env.openDbi("_meta".getBytes(StandardCharsets.UTF_8));
            try (org.lmdbjava.Txn<byte[]> t = env.txnRead()) {
                byte[] v = meta.get(t, "emb_idx_done".getBytes(StandardCharsets.UTF_8));
                return v == null ? null : new String(v, StandardCharsets.UTF_8);
            }
        }
    }

    /// How many rows `_embedding_idx` holds. DUPSORT, so iterate rather than stat.
    private static int embIdxRows(Path db) {
        try (Env<byte[]> env = readEnv(db)) {
            Dbi<byte[]> idx = env.openDbi("_embedding_idx".getBytes(StandardCharsets.UTF_8),
                    DbiFlags.MDB_DUPSORT, DbiFlags.MDB_DUPFIXED);
            int n = 0;
            try (org.lmdbjava.Txn<byte[]> t = env.txnRead();
                 CursorIterable<byte[]> ci = idx.iterate(t)) {
                for (CursorIterable.KeyVal<byte[]> ignored : ci) n++;
            }
            return n;
        }
    }

    /// Map size and max-dbs match V2Store's, so this opens the same environment it does.
    private static Env<byte[]> readEnv(Path db) {
        return Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(64L << 30)
                .setMaxDbs(4096)
                .setMaxReaders(126)
                .open(db.toFile(), EnvFlags.MDB_RDONLY_ENV, EnvFlags.MDB_NOTLS);
    }
}
