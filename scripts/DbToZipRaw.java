// CLI over toolkit.harness.DbToZip: one zip entry per class map, each a self-contained
// PublicationDelivery holding a single GeneralFrame of that class's stored fragments, VERBATIM.
//
// The worked example for the EXPORT sandwich. DbToZip owns the CLI, the read-only open, the read
// transaction, the atomic .tmp/rename, the throughput line and the common tail; FrameWriter owns
// decompress-and-emit; NetexEnvelope owns the per-entry preamble; ZipWriter owns the entries and
// the totals. What is left here — the twelve lines in the lambda — is the whole driver.
//
// Not a profile: it makes no claim about which class belongs in which frame, in what order, or
// what a consumer should expect to find where. A GeneralFrame of everything is legal NeTEx and is
// not EPIP; EpipDbToXml is the driver that answers those questions.
//
// It takes the no-hook FrameWriter constructor, so the child-order normalisation EpipDbToXml
// applies is declined here and the stored bytes come out verbatim.
//
// Entry names are the class simple name, so the entries sort alphabetically by class. Each entry
// stands alone: that is what the repeated preamble buys, and why a consumer can read one file.

import toolkit.harness.DbToZip;
import toolkit.harness.FrameWriter;
import toolkit.harness.NetexEnvelope;

public class DbToZipRaw {

    static final String USAGE = "DbToZipRaw <db> <out.zip> [--level N (0..9)] [--log-file F]";

    static final NetexEnvelope.Options ENVELOPE = new NetexEnvelope.Options(
            "RAW", "every stored object, by class, verbatim", "RAW_EXPORT", "raw:GENERAL");

    public static void main(String[] args) throws Exception {
        // No extra flags of its own: --level and --log-file come from DbToZip.
        DbToZip.driverMain("db-to-zip-raw", args, USAGE, new String[0], a -> (db, tx, out) -> {
            NetexEnvelope env = new NetexEnvelope(ENVELOPE, java.util.List.of(), null);
            FrameWriter fw = new FrameWriter(db, tx, out, env); // no hook: stored bytes, verbatim
            for (Class<?> clazz : db.dbNames(tx)) {
                // Asked before the entry is opened rather than after it is written: an empty entry
                // is indistinguishable from a full one from outside the zip, and a document with an
                // empty <members/> is not the same document as no entry at all.
                if (!fw.hasRows(clazz)) continue;
                String name = clazz.getSimpleName();
                fw.entry(name + ".xml");
                env.frameOpen(out, "GeneralFrame", "RAW_" + name, "raw:GENERAL");
                out.write("<members>");
                fw.all(clazz);
                out.write("</members></GeneralFrame>");
                fw.end();
            }
        });
    }
}
