// CLI over toolkit.harness.DbToDb: copy every object of every class from one store into a fresh
// one.
//
// Store.copyMap decodes each object and re-inserts it, so references are re-extracted and the
// "wrote N" tally is real. The target's blobs are then JAXB re-marshals rather than the
// source-verbatim captured fragments, so a store that has been through here no longer round-trips
// its input byte for byte. When exported bytes must survive a transfer, take the raw fragment path.

import toolkit.harness.DbToDb;

public class DbToDbCopy {

    public static void main(String[] args) {
        DbToDb.driverMain("db-to-db-copy", args, (src, stx, dst, dtx) -> {
            // dbNames is the classes this store actually has a map for, so an absent class is
            // never asked for and the empty target case stays the post-condition's to refuse.
            for (Class<?> clazz : src.dbNames(stx)) {
                src.copyMap(stx, dst, dtx, clazz);
            }
        });
    }
}
