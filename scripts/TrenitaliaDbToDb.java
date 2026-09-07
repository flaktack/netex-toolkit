// CLI over toolkit.harness.DbToDb: copy the source verbatim into a fresh target and, while doing
// so, give every numbered Trenitalia journey a first-class TrainNumber.
//
// The middle:
//
//   - everything stable is copyMap'd, which writes straight to the class dbi and never reaches
//     insertOne (hence DbToDb's two separate tallies, "cloned" and "wrote");
//   - one class map is re-emitted instead, so the synthesised links overlay the journeys under
//     their existing (id, version) — the key is preserved, so no reference is invalidated;
//   - the new objects are interleaved before their first referrer, so a TrainNumberRef resolves
//     the moment it is inserted rather than landing in _unresolved for the resolve pass to wire.
//
// No id changes anywhere, so references here are only ever created, never updated.

import noi.netex.model.ServiceJourney;
import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.TrainNumbers;

public class TrenitaliaDbToDb {

    private TrenitaliaDbToDb() {}

    public static void main(String[] args) {
        DbToDb.driverMain("trenitalia-db-to-db", args, TrenitaliaDbToDb::applyTrainNumbers);
    }

    /// Copies every class map but ServiceJourney, which is re-emitted with the minted TrainNumbers.
    public static void applyTrainNumbers(Store src, Txn stx, Store dst, Txn dtx) throws Exception {
        for (Class<?> clazz : src.dbNames(stx)) {
            if (clazz == ServiceJourney.class) continue;
            src.copyMap(stx, dst, dtx, clazz);
        }
        dst.insertAnyObjects(dtx, TrainNumbers.updates(src, stx));
    }
}
