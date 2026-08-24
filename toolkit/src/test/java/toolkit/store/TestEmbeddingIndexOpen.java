package toolkit.store;

// TWO DATABASES THAT LOOKED IDENTICAL.
//
// V2Store opens `_embedding_idx` on a readonly store through openExistingEmbIdx, which was
// `catch (Exception e) { return null; }` -- i.e. it inferred "this database predates the index"
// from the ABSENCE of a successful open, so every other way mdb_dbi_open can fail gave the same
// answer. Null is a SUPPORTED state: embeddingIndexReady returns false and resolveEmbeddings falls
// back to a full corpus scan. A store that could not read its index therefore paid a
// national-scale scan, produced the same correct result, said nothing, and exited 0.
//
// The fix makes the verdict positive evidence: getDbiNames() says whether the map is in the root db
// at all. That list is what these tests read back through the store's own field, because it is the
// only observable difference between the two states -- both stores open, both answer every query,
// and the one without an index just costs more.
//
// WHAT CANNOT BE TESTED HERE, stated rather than left as a gap. LMDB 0.9 does not validate the
// flags passed to mdb_dbi_open against an EXISTING named db -- it loads the stored MDB_db over them
// -- so a map re-created without MDB_DUPSORT still opens as if it had them, and there is no way
// from a test to produce "present but unopenable" (see editEmbIdx below, which still builds that
// fixture). What the change guarantees is the discrimination, not a new exception: "absent" is
// decided by the name list, so nothing else can be mistaken for it.
//
// NEUTER PROOF:
//   * testAnAbsentIndexIsStillAnOldDatabase -- make openExistingEmbIdx throw unconditionally; every
//     store written before the index stops opening at all.
//   * testAnIndexThatIsThereIsOpened        -- restore `catch (Exception) { return null; }` and
//     make openDbi fail; this store silently becomes an "old" one and the corpus scan hides it.

import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class TestEmbeddingIndexOpen {

    private static final byte[] NAME = "_embedding_idx".getBytes(StandardCharsets.UTF_8);

    private static final String FRAMES =
            "<ServiceFrame id=\"svf\" version=\"1\"><scheduledStopPoints>"
            + "<ScheduledStopPoint id=\"ssp1\" version=\"1\"><Name>Stazione</Name></ScheduledStopPoint>"
            + "</scheduledStopPoints></ServiceFrame>";

    /// A database written before the embedding index existed: the map is not in the root db.
    @Test
    void testAnAbsentIndexIsStillAnOldDatabase(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(FRAMES, "predates-index.lmdb");
        editEmbIdx(db, false);
        try (Store s = Stores.open(db, true)) {
            Check.isNull(embIdx(s),
                    "a map that is not in the root db is the ONE thing that means 'predates the "
                    + "index', and it must still open and still answer through the corpus scan");
            try (Txn tx = s.roTxn()) {
                Check.that(s.dbNames(tx).iterator().hasNext(), "…and the store is usable");
            }
        }
    }

    /// The other half, and the one the old catch could destroy silently: a store whose index IS
    /// there must come up WITH it, not with a null that reads as an old database.
    @Test
    void testAnIndexThatIsThereIsOpened(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(FRAMES, "has-index.lmdb");
        try (Store s = Stores.open(db, true)) {
            Check.that(embIdx(s) != null,
                    "this store was written by this jar, so _embedding_idx is in its root db; a "
                    + "null here is the failure mode the corpus-scan fallback used to hide");
        }
    }

    /// The measurement quoted in the header, kept executable: a map re-created WITHOUT
    /// MDB_DUPSORT/MDB_DUPFIXED still opens with them, because LMDB 0.9 ignores the flags of an
    /// existing named db. This is why there is no "corrupt index refuses" test — the fixture exists,
    /// it just is not corrupt as far as mdb_dbi_open is concerned.
    @Test
    void testFlagsAreNotValidatedAgainstAnExistingMap(TestStore ts) throws Exception {
        Path db = ts.buildDbFile(FRAMES, "plain-index.lmdb");
        editEmbIdx(db, true);
        try (Store s = Stores.open(db, true)) {
            Check.that(embIdx(s) != null,
                    "LMDB accepts the DUPSORT open against a plain map; the flags mismatch is not "
                    + "a shape openExistingEmbIdx can ever have caught");
        }
    }

    // ---------------------------------------------------------------- fixture surgery

    /// Drop `_embedding_idx` from a closed store, optionally re-creating it as a PLAIN map.
    /// Map size and max-dbs match `V2Store`'s so this opens the same environment it does.
    private static void editEmbIdx(Path db, boolean recreatePlain) {
        Env<byte[]> env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(64L << 30)
                .setMaxDbs(4096)
                .setMaxReaders(126)
                .open(db.toFile(), EnvFlags.MDB_NOTLS);
        try {
            Dbi<byte[]> idx = env.openDbi(NAME, DbiFlags.MDB_DUPSORT, DbiFlags.MDB_DUPFIXED);
            try (org.lmdbjava.Txn<byte[]> t = env.txnWrite()) {
                idx.drop(t, true); // del=1: the NAME leaves the root db, not just the rows
                t.commit();
            }
            if (recreatePlain) {
                Dbi<byte[]> plain = env.openDbi(NAME, DbiFlags.MDB_CREATE);
                try (org.lmdbjava.Txn<byte[]> t = env.txnWrite()) {
                    plain.put(t, new byte[] {1}, new byte[] {2});
                    t.commit();
                }
            }
        } finally {
            env.close();
        }
    }

    /// The store's own handle. Reflection because the field is protected and the state under test is
    /// not otherwise observable: both stores answer every query, and the difference is only what the
    /// next resolveEmbeddings has to pay.
    private static Object embIdx(Store s) throws Exception {
        Field f = AbstractLmdbStore.class.getDeclaredField("embIdx");
        f.setAccessible(true);
        return f.get(s);
    }
}
