// CLI over toolkit.harness.EpipToDb: raw-clone the source database into the target, then run the
// EPIP transformation on the target in place.

import toolkit.harness.Args;
import toolkit.harness.EpipToDb;
import toolkit.store.Stores;
import toolkit.transform.epip.GeneratorDefaults;
import toolkit.util.Log;

import java.nio.file.Path;

public class EpipDbToDb {

    public static void main(String[] args) {
        Args a = Args.parse(args,
                "EpipDbToDb <source_db> <target_db> [--log-file F] [--format v2] [--codespace S]",
                "--log-file=", "--format=", "--codespace=").expectPositional(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        Path source = Path.of(a.positional().get(0));
        Path target = Path.of(a.positional().get(1));
        if (!Stores.exists(source)) {
            Log.error("%s does not exist.", source);
            System.exit(1);
        }
        String sourceFmt = Stores.sniffFormat(source);
        String fmt = a.get("--format", null);
        if (fmt == null) {
            fmt = sourceFmt;
        } else if (!fmt.equals(sourceFmt)) {
            // The clone is raw bytes — a cross-format target would be corrupt.
            Log.error("[epip] --format %s does not match source format %s -- "
                    + "a raw clone cannot change formats", fmt, sourceFmt);
            System.exit(1);
        }
        if (Stores.exists(target)) {
            // Cloning into a populated db would silently collide local keys.
            Log.error("[epip] target %s already contains a database (data.mdb) -- "
                    + "refusing to clone into it", target);
            System.exit(1);
        }
        try {
            // --codespace decides the prefix of every id no source object could supply; unset, the
            // EPIP data codespace the profile reserves for exactly that.
            EpipToDb.cloneAndTransform(source, target, fmt,
                    GeneratorDefaults.withCodespace(a.get("--codespace", null)));
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }
}
