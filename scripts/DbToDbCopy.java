// CLI over toolkit.harness.DbToDb: copy every object of every class from one store into a fresh
// one. The worked example for the TRANSFORM sandwich — the middle is three lines, and everything
// around it (the CLI, the existence guard, the two transactions, the commit, the mandatory
// resolve() + resolveEmbeddings() pair, the empty-output post-condition, the written-by-class tally
// and the common tail) belongs to DbToDb.
//
// Store.copyMap, not ObjectWriter.cloneFrom:
//
//   cloneFrom  is a raw byte copy straight to the class dbi. It never reaches insertOne, so a
//              driver built on it reports a tally of ZERO against a completely full target. It is
//              the right call for a clone and the wrong one for a worked example of a transform.
//   copyMap    decodes each object and re-inserts it, so references are re-extracted and the
//              "wrote N" line reports what this stage did.
//
// The cost is that the target's blobs are JAXB re-marshals, not the source-verbatim captured
// fragments, so a store that has been through here no longer round-trips its input byte for byte.
// When exported bytes must survive a transfer, take the raw fragment path.
//
// No extra flags: DbToDb.driverMain owns its whole Args spec and takes no extraFlags parameter
// (DbToZip.driverMain does — an export must read its own options before it opens anything).

import toolkit.harness.DbToDb;

public class DbToDbCopy {

    public static void main(String[] args) {
        DbToDb.driverMain("db-to-db-copy", args, (src, stx, dst, dtx) -> {
            // dbNames is the classes this store actually HAS a map for, so an absent class is
            // never asked for and the empty target case stays the post-condition's to refuse.
            for (Class<?> clazz : src.dbNames(stx)) {
                src.copyMap(stx, dst, dtx, clazz);
            }
        });
    }
}
