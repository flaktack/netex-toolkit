// Loader driver with fresh-target semantics: stream the feed in, then the mandatory resolve() +
// resolveEmbeddings() pair. The entry point of every chain — nothing else creates a store from a
// feed.
//
// FeedToDb accepts everything NetexLoader.load dispatches on, a bare .xml or .xml.gz included, so
// this driver is the harness call and nothing else.

import toolkit.harness.FeedToDb;
import toolkit.load.ZipLoad;

public class NetexToDb {

    public static void main(String[] args) throws Exception {
        FeedToDb.driverMain("netex-to-db", args, ZipLoad.Options.DEFAULTS);
    }
}
