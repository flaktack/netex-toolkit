// CLI over toolkit.harness.InPlace: the same TrainNumber synthesis TrenitaliaDbToDb does, run over
// a store that already exists instead of into a fresh one.
//
// The worked example for the FIX sandwich, and what makes a fix different from a transform.
// InPlace hands the fix ONE read-write transaction and it both reads and writes through it: the
// lazy iterator below walks the ServiceJourney map while insertAnyObjects writes back into that
// same dbi, at the same keys, with a larger value (the journey has gained a <trainNumbers>
// element), so LMDB relocates the page rather than patching it.
//
// The TrainNumbers this mints go into a different class map, so they are never under the cursor.
//
// Idempotent: a journey that already carries trainNumbers is skipped, so a second run re-inserts
// nothing and InPlace reports "changed nothing", which for a fix is a valid outcome rather than an
// error (it is why this sandwich has no emptyOutput check).

import toolkit.harness.InPlace;
import toolkit.transform.common.TrainNumbers;

public class TrenitaliaInPlace {

    private TrenitaliaInPlace() {}

    public static void main(String[] args) {
        InPlace.driverMain("trenitalia-in-place", args,
                (db, txn) -> db.insertAnyObjects(txn, TrainNumbers.updates(db, txn)));
    }
}
