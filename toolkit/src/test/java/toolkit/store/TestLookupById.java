package toolkit.store;

// Store.lookupById is the class-free address lookup: every _id_idx row at the `id NUL` prefix (or
// `id NUL version NUL`), in key order. It is a `default` method whose default is a filtered
// iterIdIndex, so its contract is identity with that filter — same rows, same order, same encoded
// bytes, same full_keys — and an override may only change the cost. The SPI writes the same rule
// for scanEdges and scanUnresolved.

import noi.netex.model.Line;
import noi.netex.model.Operator;
import noi.netex.model.Quay;
import org.junit.jupiter.api.Test;
import toolkit.keycodec.NulKeyCodec;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestLookupById {

    /// Five rows, of which three share the id "X:1" across two versions and two classes, one is the
    /// text-prefix decoy "X:10", and one is an unrelated id so the prefix never exhausts the index.
    /// Duplicate ids are a supported shape: NeTEx's primary key is (id, version).
    private static void fill(TestStore ts) {
        try (Txn txn = ts.store.rwTxn()) {
            ts.store.insertAnyObjects(txn, List.of(
                    line("X:1", "1"), line("X:1", "2"), operator("X:1", "1"),
                    line("X:10", "1"), quay("Y:1", "1")));
            txn.commit();
        }
    }

    private static Line line(String id, String version) {
        Line l = new Line();
        l.setId(id);
        l.setVersion(version);
        return l;
    }

    private static Operator operator(String id, String version) {
        Operator o = new Operator();
        o.setId(id);
        o.setVersion(version);
        return o;
    }

    private static Quay quay(String id, String version) {
        Quay q = new Quay();
        q.setId(id);
        q.setVersion(version);
        return q;
    }

    /// Every version and every class of the id, and nothing else.
    @Test
    void testEveryRowAtThePrefixComesBack(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            List<IdRow> rows = ts.store.lookupById(tx, "X:1", null);
            Check.equals(3, rows.size(), "three _id_idx rows carry the id X:1");
            List<String> got = describe(ts.store, rows);
            Check.that(got.contains("Line|X:1|1"), "the Line at version 1: " + got);
            Check.that(got.contains("Line|X:1|2"), "the Line at version 2: " + got);
            Check.that(got.contains("Operator|X:1|1"), "the Operator sharing the id: " + got);
            for (String s : got) Check.that(!s.contains("X:10"), "X:10 is not part of X:1: " + got);
        }
    }

    /// The prefix is the encoded `id NUL`, so the text prefix "X:1" does not reach "X:10".
    @Test
    void testTheIdPrefixIsNulTerminated(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            Check.equals(1, ts.store.lookupById(tx, "X:10", null).size(),
                    "X:10 has exactly its own row");
            Check.equals(List.of("Line|X:10|1"),
                    describe(ts.store, ts.store.lookupById(tx, "X:10", null)), "and it is the Line");
            Check.equals(0, ts.store.lookupById(tx, "X:", null).size(),
                    "a text prefix of a stored id is not an id and matches nothing");
        }
    }

    /// A non-null version narrows the prefix to `id NUL version NUL`.
    @Test
    void testVersionNarrowsThePrefix(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            Check.equals(List.of("Line|X:1|2"), describe(ts.store, ts.store.lookupById(tx, "X:1", "2")),
                    "version 2 of X:1 is the Line alone");
            Check.equals(2, ts.store.lookupById(tx, "X:1", "1").size(),
                    "version 1 of X:1 is the Line AND the Operator — the class is still not a filter");
            Check.equals(0, ts.store.lookupById(tx, "X:1", "9").size(), "no version 9");
        }
    }

    @Test
    void testAbsentIdIsAnEmptyList(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            List<IdRow> rows = ts.store.lookupById(tx, "NOPE:1", null);
            Check.that(rows != null, "an absent id is an empty list, never null");
            Check.equals(0, rows.size(), "and it is empty");
        }
    }

    /// `AbstractLmdbStore`'s prefix-cursor override against the interface default's whole-index
    /// filter, row for row, byte for byte, in order. The default is reproduced here rather than
    /// invoked, so the comparison is against the specification and survives an implementor who
    /// overrides it.
    @Test
    void testOverrideIsIdenticalToTheDefault(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            long whole = 0;
            for (IdRow ignored : ts.store.iterIdIndex(tx)) whole++;
            for (String[] q : new String[][] {{"X:1", null}, {"X:1", "1"}, {"X:10", null},
                    {"Y:1", null}, {"NOPE:1", null}}) {
                List<IdRow> mine = ts.store.lookupById(tx, q[0], q[1]);
                List<IdRow> theirs = interfaceDefault(ts.store, tx, q[0], q[1]);
                Check.equals(theirs.size(), mine.size(),
                        "row count for " + q[0] + "@" + q[1]);
                for (int i = 0; i < mine.size(); i++) {
                    Check.equals(theirs.get(i).fullKey(), mine.get(i).fullKey(),
                            "full_key[" + i + "] for " + q[0] + "@" + q[1]);
                    Check.that(Arrays.equals(theirs.get(i).encodedKey(), mine.get(i).encodedKey()),
                            "encoded key[" + i + "] for " + q[0] + "@" + q[1] + " is byte-identical");
                }
            }
            Check.equals(5L, whole, "the whole index is five rows");
            Check.that(whole > ts.store.lookupById(tx, "X:1", null).size(),
                    "the prefix answer is a strict subset, so agreement above is not two empty lists");
        }
    }

    /// [Store#lookupFullKey] still requires a non-null class — it calls `classIdx(clazz)` before it
    /// looks at anything. The precondition must not be widened: an abstract method's contract is
    /// not something the compiler can report to a third-party implementor.
    @Test
    void testLookupFullKeyStillRequiresItsClass(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            try {
                ts.store.lookupFullKey(tx, "X:1", null, null);
                Check.that(false, "lookupFullKey(clazz = null) must still fail, not answer");
            } catch (NullPointerException expected) {
            }
            try {
                ts.store.lookupFullKey(tx, "X:1", "1", null);
                Check.that(false, "…on the exact-key arm too");
            } catch (NullPointerException expected) {
            }
        }
    }

    /// On the `version == null` arm the class argument is a precondition rather than a filter: the
    /// class trailer is not part of the prefix. Two calls naming two different classes return the
    /// same row, so one of them returns an object of a class it did not ask for.
    @Test
    void testTheClassArgumentDoesNotFilterTheVersionNullArm(TestStore ts) {
        fill(ts);
        try (Txn tx = ts.store.roTxn()) {
            Check.that(ts.store.classIdxInt(Line.class) != ts.store.classIdxInt(Operator.class),
                    "the two classes have different idxs, so 'same row' below is meaningful");
            long asLine = ts.store.lookupFullKey(tx, "X:1", null, Line.class);
            long asOperator = ts.store.lookupFullKey(tx, "X:1", null, Operator.class);
            Check.that(asLine != -1, "the id resolves");
            Check.equals(asLine, asOperator, "both calls return the SAME row: the class is ignored");
            Check.equals(ts.store.lookupById(tx, "X:1", null).get(0).fullKey(), asLine,
                    "and that row is the first one at the prefix — lookupById's row 0");
        }
    }

    /// [Store#lookupById]'s documented default, reproduced so it can be compared against.
    private static List<IdRow> interfaceDefault(Store store, Txn tx, String id, String version) {
        byte[] prefix = NulKeyCodec.encodePrefix(id, version, null, false);
        List<IdRow> out = new ArrayList<>();
        for (IdRow row : store.iterIdIndex(tx)) {
            if (NulKeyCodec.startsWith(row.encodedKey(), prefix)) {
                out.add(new IdRow(row.encodedKey().clone(), row.fullKey()));
            } else if (!out.isEmpty()) {
                break;
            }
        }
        return out;
    }

    /// `Class|id|version` per row, from the encoded key alone.
    private static List<String> describe(Store store, List<IdRow> rows) {
        List<String> out = new ArrayList<>();
        for (IdRow r : rows) {
            byte[] k = r.encodedKey();
            byte[] body = new byte[NulKeyCodec.stripClazzLen(k)];
            System.arraycopy(k, 0, body, 0, body.length);
            Class<?> c = store.classForIdx(NulKeyCodec.classTrailer(k));
            out.add((c == null ? "?" : c.getSimpleName()) + "|" + NulKeyCodec.idPart(body) + "|"
                    + NulKeyCodec.versionPart(body));
        }
        return out;
    }
}
