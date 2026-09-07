// CLI + wiring only. The merge itself is toolkit.harness.MergeToDb — the fourth driver sandwich,
// beside FeedToDb (load), DbToDb (transform) and InPlace (fix).
//
// N per-source databases into one: raw-clone each with a cumulative key offset,
// drop rows whose (id, version, class) already arrived from an earlier source,
// then resolve() + resolveEmbeddings() to wire the cross-source references that no per-source
// store could resolve on its own.

import toolkit.harness.MergeToDb;

public class MergeDbToDb {

    private MergeDbToDb() {}

    public static void main(String[] args) throws Exception {
        MergeToDb.driverMain("merge-db", args);
    }
}
