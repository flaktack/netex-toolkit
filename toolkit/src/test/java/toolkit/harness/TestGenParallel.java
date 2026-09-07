package toolkit.harness;

// The fixture-scale gate on -Dtoolkit.epip.parallelJourneys.
//
// The flag moves two PURE FUNCTIONS off the write-transaction thread — the JAXB decode of each
// stored ServiceJourney and the JAXB marshal of each emitted object — and claims the resulting
// database is byte-identical to the serial one.
//
// It runs the REAL production path (EpipToDb.inPlace) over TestEpipTransform's fixture, which
// carries both journey shapes the generator dispatches on: sj1 arrives WITH passing times, sj2 with
// CALLS (the md5 pattern synthesis + the TimetabledPassingTimes profile). Both arms run in ONE JVM,
// which is why GenParallel.workers() reads the property per call rather than at class-init.

import org.junit.jupiter.api.Test;
import toolkit.store.RawRow;
import toolkit.store.UnresolvedRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.util.Conf;
import toolkit.transform.epip.GeneratorDefaults;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestGenParallel {

    /// Order-independent digest of every stored object plus the two reference tables: one line per
    /// row, sorted, hashed. A pipeline that reordered the INSERTS is still caught, by the local
    /// keys the store hands out in insert order.
    private static String digest(Path database) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Store db = Stores.open(database, true); Txn txn = db.roTxn()) {
            for (Class<?> clazz : db.dbNames(txn)) {
                for (RawRow r : db.iterRawObjects(txn, clazz)) {
                    lines.add(clazz.getSimpleName() + "\t" + hex(r.localKey()) + "\t" + sha(r.blob()));
                }
            }
            for (long[] e : db.iterEdges(txn)) lines.add("edge\t" + e[0] + "\t" + e[1]);
            for (UnresolvedRow u : db.iterUnresolved(txn)) {
                lines.add("unres\t" + u.referrerFullKey() + "\t" + hex(u.encodedTarget()));
            }
        }
        Collections.sort(lines);
        return sha(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "/" + lines.size();
    }

    private static String sha(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String transformDigest(TestStore ts, String name, GeneratorDefaults gd)
            throws Exception {
        Path db = ts.buildDbFile(TestEpipTransform.NETEX, name);
        EpipToDb.inPlace(db, gd);
        return digest(db);
    }

    // ------------------------------------------------------------------ the claim

    @Test
    void testParallelJourneysIsByteIdentical(TestStore ts) throws Exception {
        Check.equals(0, GenParallel.workers(), "the default arm must be serial");
        String serial = transformDigest(ts, "genpar-serial.lmdb", GeneratorDefaults.TEST);

        String parallel;
        Conf.override(Conf.EPIP_PARALLEL_JOURNEYS, 4);
        try {
            Check.equals(4, GenParallel.workers(), "the flag must be live inside this JVM");
            parallel = transformDigest(ts, "genpar-parallel.lmdb", GeneratorDefaults.TEST);
        } finally {
            Conf.clearOverride(Conf.EPIP_PARALLEL_JOURNEYS);   // surefire runs the suite in one JVM
        }
        Check.equals(serial, parallel, "parallel arm must be byte-identical to the serial arm");
    }

    /// N = 2, the recommended production setting. Its in-flight window is `max(8, 4*2) = 8` against
    /// N = 4's 16, and the window sets how far the generator's source cursor runs ahead of the
    /// insert path's writes.
    @Test
    void testTwoWorkersIsAlsoByteIdentical(TestStore ts) throws Exception {
        String serial = transformDigest(ts, "genpar-serial2.lmdb", GeneratorDefaults.TEST);
        String parallel;
        Conf.override(Conf.EPIP_PARALLEL_JOURNEYS, 2);
        try {
            parallel = transformDigest(ts, "genpar-parallel2.lmdb", GeneratorDefaults.TEST);
        } finally {
            Conf.clearOverride(Conf.EPIP_PARALLEL_JOURNEYS);
        }
        Check.equals(serial, parallel, "the N=2 arm must be byte-identical too");
    }

    /// One worker is a different pipeline shape (window still 8) and must land in the same place.
    @Test
    void testOneWorkerIsAlsoByteIdentical(TestStore ts) throws Exception {
        String serial = transformDigest(ts, "genpar-serial1.lmdb", GeneratorDefaults.TEST);
        String parallel;
        Conf.override(Conf.EPIP_PARALLEL_JOURNEYS, 1);
        try {
            parallel = transformDigest(ts, "genpar-parallel1.lmdb", GeneratorDefaults.TEST);
        } finally {
            Conf.clearOverride(Conf.EPIP_PARALLEL_JOURNEYS);
        }
        Check.equals(serial, parallel, "one-worker arm must be byte-identical too");
    }

    // ------------------------------------------------------------------ the controls

    /// POSITIVE control: two serial runs agree, so "identical" above is a result, not a tautology.
    @Test
    void testDigestPositiveControl(TestStore ts) throws Exception {
        Check.equals(transformDigest(ts, "genpar-p1.lmdb", GeneratorDefaults.TEST),
                transformDigest(ts, "genpar-p2.lmdb", GeneratorDefaults.TEST),
                "the transform must be deterministic run to run");
    }

    /// NEGATIVE control: the digest MUST fail on a run that genuinely differs.
    @Test
    void testDigestNegativeControl(TestStore ts) throws Exception {
        String test = transformDigest(ts, "genpar-n1.lmdb", GeneratorDefaults.TEST);
        String production = transformDigest(ts, "genpar-n2.lmdb", GeneratorDefaults.PRODUCTION);
        Check.that(!test.equals(production),
                "the digest must SEE a difference in the generated ids -- a comparator that "
                        + "cannot fail proves nothing about the arms above");
    }

    /// A malformed flag value must REFUSE, and a negative one must still mean serial: `-1` is a
    /// deliberate way to spell "serial", where a misspelt value would be a silent no-op whose only
    /// symptom is a slow run.
    @Test
    void testBadFlagValueIsRefused() {
        IllegalStateException e = null;
        try {
            Conf.override(Conf.EPIP_PARALLEL_JOURNEYS, "not-a-number");
        } catch (IllegalStateException ex) {
            e = ex;
        } finally {
            Conf.clearOverride(Conf.EPIP_PARALLEL_JOURNEYS);
        }
        Check.that(e != null, "a bad value must refuse rather than quietly run serial");
        Check.that(e.getMessage().contains(GenParallel.PROPERTY),
                "the refusal must name the knob, got: " + e.getMessage());

        Conf.override(Conf.EPIP_PARALLEL_JOURNEYS, -3);
        try {
            Check.equals(0, GenParallel.workers(), "a negative value must mean serial");
        } finally {
            Conf.clearOverride(Conf.EPIP_PARALLEL_JOURNEYS);
        }
    }
}
