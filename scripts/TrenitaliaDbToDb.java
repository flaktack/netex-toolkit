// CLI over toolkit.harness.DbToDb: copy the source verbatim into a fresh target and, while doing
// so, give every numbered Trenitalia journey a first-class TrainNumber.
//
// The worked example for a transform sandwich that transforms. DbToDbCopy shows the sandwich with
// a three-line middle that changes nothing; this one shows the shape a real transform has:
//
//   - everything stable is copyMap'd, which writes straight to the class dbi and never reaches
//     insertOne (hence DbToDb's two separate tallies, "cloned" and "wrote");
//   - ONE class map is re-emitted instead, so the synthesised links overlay the journeys under
//     their existing (id, version) — the key is preserved, so no reference is invalidated;
//   - the new objects are interleaved BEFORE their first referrer, so a TrainNumberRef resolves
//     the moment it is inserted rather than landing in _unresolved for the resolve pass to wire.
//
// No id changes anywhere, so references here are only ever created, never updated.
//
// The transform is toolkit.transform.common.TrainNumbers, in the jar. `TrenitaliaInPlace` is the
// same transform on the other sandwich, over a store that already exists.

import noi.netex.model.ServiceJourney;
import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.TrainNumbers;

public class TrenitaliaDbToDb {

    private TrenitaliaDbToDb() {}

    public static void main(String[] args) {
        // The caller (DbToDb) owns the transactions — source ro, target rw, commit + the mandatory
        // resolve() / resolveEmbeddings() pair afterwards.
        DbToDb.driverMain("trenitalia-db-to-db", args, TrenitaliaDbToDb::applyTrainNumbers);
    }

    /// The middle of the sandwich, and the whole of what this driver supplies.
    public static void applyTrainNumbers(Store src, Txn stx, Store dst, Txn dtx) throws Exception {
        for (Class<?> clazz : src.dbNames(stx)) {
            if (clazz == ServiceJourney.class) continue;
            src.copyMap(stx, dst, dtx, clazz);
        }
        dst.insertAnyObjects(dtx, TrainNumbers.updates(src, stx));
    }
}
