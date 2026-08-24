// CLI + wiring only. The merge itself is toolkit.harness.MergeToDb — the fourth driver sandwich,
// beside FeedToDb (load), DbToDb (transform) and InPlace (fix).
//
// THE GENERIC MERGE. N per-source databases into one: raw-clone each with a cumulative key offset,
// drop rows whose (id, version, class) already arrived from an earlier source (FIRST SOURCE WINS),
// then resolve() + resolveEmbeddings() to wire the cross-source references that no per-source
// store could resolve on its own.
//
// The other answer to the collision question qualifies colliding ids with a per-source tag so both
// copies survive and stay traceable. That needs a tag per source and is pipeline policy; this one
// needs nothing about the feeds, which is why it is the library's.
//
// Nothing compiled can call this: every driver here is in the DEFAULT PACKAGE, which no named
// package may import. The suite exercises `MergeToDb.driverMain` directly.

import toolkit.harness.MergeToDb;

public class MergeDbToDb {

    private MergeDbToDb() {}

    public static void main(String[] args) throws Exception {
        MergeToDb.driverMain("merge-db", args);
    }
}
