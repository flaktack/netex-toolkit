package toolkit.transform.common;

// TrainNumber synthesis, on both sandwiches.
//
// The transform is the worked example for DbToDb (scripts/TrenitaliaDbToDb) and for InPlace
// (scripts/TrenitaliaInPlace), and the two paths are not the same operation: db-to-db copies every
// other class and writes the journeys into a FRESH map, while in-place writes them back into the
// map it is reading, under one transaction, at the same keys and with a longer value. The last two
// methods assert the outcomes are identical anyway, which is the claim shipping both shapes makes.
//
// The driver is in the default package and unimportable, so the transform is passed to runDbToDb
// directly. That is what the driver passes too — its `applyTrainNumbers` is these four lines.

import noi.netex.model.ServiceJourney;
import noi.netex.model.TrainNumber;
import noi.netex.model.TrainNumberRefStructure;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.harness.InPlace;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.test.Xml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TestTrainNumbers {

    /// scripts/TrenitaliaDbToDb.applyTrainNumbers, which is the driver's whole contribution.
    private static void applyTrainNumbers(Store src, Txn stx, Store dst, Txn dtx) throws Exception {
        for (Class<?> clazz : src.dbNames(stx)) {
            if (clazz == ServiceJourney.class) continue;
            src.copyMap(stx, dst, dtx, clazz);
        }
        dst.insertAnyObjects(dtx, TrainNumbers.updates(src, stx));
    }

    private static final String ONE_NUMBERED_JOURNEY = """
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="sj1" version="1"><Name>25</Name></ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    @Test
    void testAddTrainNumbersCreatesAndLinksTrainNumber(TestStore db) throws Exception {
        db.loadNetex(ONE_NUMBERED_JOURNEY);

        TestStore target = db.runDbToDb(TestTrainNumbers::applyTrainNumbers);

        Xml.assertXmlEqual(
                target.exportNetex(),
                """
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" version="ntx:1.1">
                  <PublicationTimestamp>2026-01-01T00:00:00</PublicationTimestamp>
                  <ParticipantRef>PyNeTExConv</ParticipantRef>
                  <dataObjects>
                    <GeneralFrame id="Database" version="1">
                      <members>
                        <TrainNumber id="IT:TrainNumber:25" version="1">
                          <ForAdvertisement>25</ForAdvertisement>
                        </TrainNumber>
                        <ServiceJourney id="sj1" version="1">
                          <Name>25</Name>
                          <trainNumbers>
                            <TrainNumberRef version="1" ref="IT:TrainNumber:25"/>
                          </trainNumbers>
                        </ServiceJourney>
                      </members>
                    </GeneralFrame>
                  </dataObjects>
                </PublicationDelivery>""");
    }

    @Test
    void testDistinctNumberIsDeduplicatedAcrossJourneys(TestStore db) throws Exception {
        db.loadNetex("""
            <TimetableFrame id="tf" version="1">
              <vehicleJourneys>
                <ServiceJourney id="sj1" version="1"><Name>25</Name></ServiceJourney>
                <ServiceJourney id="sj2" version="1"><Name>25</Name></ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            """);

        TestStore target = db.runDbToDb(TestTrainNumbers::applyTrainNumbers);

        // Two journeys share number 25 -> exactly one first-class TrainNumber...
        Check.equals(List.of("IT:TrainNumber:25"), trainNumberIds(target.store), "TrainNumber ids");
        // ...and both journeys link to it.
        Check.equals(List.of("IT:TrainNumber:25|1", "IT:TrainNumber:25|1"), refs(target.store),
                "journey trainNumber refs");
    }

    @Test
    void testAddTrainNumbersIgnoresWords(TestStore db) throws Exception {
        db.loadNetex("""
            <TimetableFrame id="tf" version="1">
              <vehicleJourneys>
                <ServiceJourney id="sj1" version="1"><Name>Frecciarossa</Name></ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            """);

        TestStore target = db.runDbToDb(TestTrainNumbers::applyTrainNumbers);

        Check.equals(List.of(), trainNumberIds(target.store),
                "no TrainNumber for a name that carries no digits");
    }

    @Test
    void testTrainNumberReadFromTexttypeName() {
        ServiceJourney numbered = new ServiceJourney();
        numbered.setId("x");
        numbered.setVersion("1");
        numbered.setName(Mls.of("rj 25"));
        Check.equals("25", TrainNumbers.trainNumberForJourney(numbered), "numbered name");

        ServiceJourney named = new ServiceJourney();
        named.setId("y");
        named.setVersion("1");
        named.setName(Mls.of("Frecciarossa"));
        Check.isNull(TrainNumbers.trainNumberForJourney(named), "word name");
    }

    // ================================================================ the other sandwich

    /// scripts/TrenitaliaInPlace, on a store that already exists: the read cursor walks the
    /// ServiceJourney map while insertAnyObjects writes back into it, and the journey has GROWN by
    /// a `<trainNumbers>` element, so the write relocates the page rather than patching it.
    /// TestInPlaceCursorStability is the 300-row proof that this visits every row exactly once;
    /// this is the same idiom with the real transform on it.
    @Test
    void testTheInPlaceFixProducesTheSameResult(TestStore db) throws Exception {
        Path dir = db.buildDbFile(ONE_NUMBERED_JOURNEY, "inplace.lmdb");

        InPlace.runInPlace((store, txn) -> store.insertAnyObjects(txn, TrainNumbers.updates(store, txn)),
                dir, "test-trenitalia-in-place", true);

        try (Store store = Stores.open(dir, true)) {
            Check.equals(List.of("IT:TrainNumber:25"), trainNumberIds(store), "TrainNumber ids");
            Check.equals(List.of("IT:TrainNumber:25|1"), refs(store), "journey trainNumber refs");
        }
    }

    /// A second run must change nothing: a journey that already carries trainNumbers is skipped, so
    /// no TrainNumber is minted twice and no journey collects a second ref. A fix that is not
    /// idempotent is one that cannot be re-run over a store somebody else may already have fixed.
    @Test
    void testTheInPlaceFixIsIdempotent(TestStore db) throws Exception {
        Path dir = db.buildDbFile(ONE_NUMBERED_JOURNEY, "inplace.lmdb");
        for (int run = 0; run < 2; run++) {
            InPlace.runInPlace((store, txn) -> store.insertAnyObjects(txn, TrainNumbers.updates(store, txn)),
                    dir, "test-trenitalia-in-place", true);
        }

        try (Store store = Stores.open(dir, true)) {
            Check.equals(List.of("IT:TrainNumber:25"), trainNumberIds(store),
                    "the second run mints no second TrainNumber");
            Check.equals(List.of("IT:TrainNumber:25|1"), refs(store),
                    "and the journey collects no second ref");
        }
    }

    // ================================================================ readers

    private static List<String> trainNumberIds(Store store) throws Exception {
        List<String> ids = new ArrayList<>();
        try (Txn txn = store.roTxn()) {
            for (Object o : store.iterOnlyObjects(txn, TrainNumber.class)) {
                ids.add(((TrainNumber) o).getId());
            }
        }
        ids.sort(null);
        return ids;
    }

    /// Every journey's `ref|version`, sorted — the link, from the referrer's side.
    private static List<String> refs(Store store) throws Exception {
        List<String> refs = new ArrayList<>();
        try (Txn txn = store.roTxn()) {
            for (Object o : store.iterOnlyObjects(txn, ServiceJourney.class)) {
                ServiceJourney sj = (ServiceJourney) o;
                if (sj.getTrainNumbers() == null) continue;
                for (TrainNumberRefStructure ref : sj.getTrainNumbers().getTrainNumberRef()) {
                    refs.add(ref.getRef() + "|" + ref.getVersion());
                }
            }
        }
        refs.sort(null);
        return refs;
    }
}
