// Loader driver with fresh-target semantics: stream the feed in, then the mandatory resolve() +
// resolveEmbeddings() pair. The entry point of every chain — nothing else creates a store from a
// feed.
//
// FeedToDb accepts everything NetexLoader.load dispatches on, a bare .xml or .xml.gz included, so
// this driver is the harness call and nothing else.
//
// THE FORMAT DEFAULT. Whatever this defaults to is what the whole downstream chain becomes, since
// every db-to-db driver inherits its source's format when no --format is given. There is one
// format now, and the flag is kept only so existing "--format v2" command lines keep parsing —
// FeedToDb still rejects v1 by name rather than with "unknown format".

import toolkit.harness.FeedToDb;
import toolkit.load.ZipLoad;

public class NetexToDb {

    public static void main(String[] args) throws Exception {
        FeedToDb.driverMain("netex-to-db", args, ZipLoad.Options.DEFAULTS);
    }
}
