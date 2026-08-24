package toolkit.harness;

// Raw-clone the source database into the target, then run the EPIP transformation on the target
// in place. inPlace is the composition form the pipelines' fused drivers reuse, so the
// split and fused paths produce identical output by construction.
//
// Phases, one runInPlace (= one transaction) each:
//   A1. de-embed the ServiceCalendar children — every id-carrying registrable entity embedded in
//       a ServiceCalendar is re-inserted as a first-class row; the calendar keeps its copy.
//   A2. PassengerStopAssignment StopPlaceRef backfill. A PSA whose stop-place union is already
//       populated is skipped entirely, so it keeps its source-faithful stored fragment.
//   B.  the six EPIP generators IN ORDER: sanitize, calendar, interchange, lines, locations,
//       journeys. Calendar and interchange must see the PRE-transform corpus — the interchange
//       resolves JourneyMeeting points against the journeys' original patterns, and the calendar
//       regroups only the feeds' own calendar objects, never the journey transform's generated
//       DayTypes. Sanitize is first so every later stage sees repaired quays and no dangling
//       assignment quay refs.
//   C.  repair interchange points against the patterns consumers will see, then one streaming
//       ensemble pass fusing reprojection, the ServiceJourneyPattern direction inference (its
//       synthesized Directions drain AFTER the pass) and the profile backfills.
//   C2. strip orphan LevelRefs — a LevelRef whose target is not a Level its own Site defines is
//       cleared, on both shapes the corpus carries. EPIP's Level_KeyRef requires an emitted
//       LevelRef to resolve in the same document; LevelRef is minOccurs="0", so absence is
//       conformant.
//
// No resolve() or resolveEmbeddings() anywhere, deliberately: every runInPlace passes
// resolve=false. The export does not require the re-inserted rows' forward edges to be re-wired.
//
// A non-empty target is refused, and --format must equal the source's sniffed format: the clone
// is a raw byte copy, so a cross-format clone would corrupt the target.

import jakarta.xml.bind.JAXBElement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import noi.netex.model.EntityStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.ServiceCalendar;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.TaxiStandRefStructure;
import noi.netex.model.VehicleType;
import toolkit.geo.Projection;
import toolkit.model.GeoClasses;
import toolkit.model.ModelRegistry;
import toolkit.model.NetexUtils;
import toolkit.model.RecursiveAttributes;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.transform.epip.Backfills;
import toolkit.transform.epip.Direction;
import toolkit.transform.epip.EpipCalendar;
import toolkit.transform.epip.EpipInterchange;
import toolkit.transform.epip.EpipJourneys;
import toolkit.transform.epip.EpipLines;
import toolkit.transform.epip.EpipLocations;
import toolkit.transform.epip.EpipSanitize;
import toolkit.transform.epip.GeneratorDefaults;
import toolkit.transform.feedfix.DayOffsetRewrite;
import toolkit.util.Log;

public final class EpipToDb {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private EpipToDb() {}

    /// Raw-clone `source` into `target` (source opened READ-ONLY, one ro + one rw txn, commit, no
    /// resolve: the clone is byte-faithful, resolved state included), then run the in-place
    /// transformation there.
    public static void cloneAndTransform(Path source, Path target, String format) throws Exception {
        long t0 = System.nanoTime();
        try (Store src = Stores.open(source, true);
             Store dst = Stores.create(target, format)) {
            try (Txn stx = src.roTxn(); Txn dtx = dst.rwTxn()) {
                dst.cloneFrom(src, stx, dtx, null, 0);
                dtx.commit();
            }
        }
        Log.info("[epip] clone %s -> %s done (%.1f s)", source, target, Log.secs(t0));
        inPlace(target, GeneratorDefaults.PRODUCTION);
        Log.info("[epip_db_to_db] done (%.1f s)", Log.secs(t0));
        Log.info("[epip] jvm peak rss %d kB", Log.peakRssKb());
        toolkit.codec.BindEvents.report("epip-db-to-db");
    }

    /// The composition form of the EPIP transform (see the header for the phase map). Runs on a
    /// database that already holds the full corpus; every phase reads and overwrites it.
    public static void inPlace(Path database, GeneratorDefaults generatorDefaults)
            throws Exception {
        // Phase A — prepare the source: two write transactions, in order.
        InPlace.runInPlace(EpipToDb::deembedServiceCalendarChildren,
                database, "epip-deembed-calendars", /*resolve=*/false);
        InPlace.runInPlace(EpipToDb::backfillPsaStopPlaceRefs,
                database, "epip-psa-backfill", /*resolve=*/false);
        // Phase B — the six generators, ONE write transaction.
        InPlace.runInPlace((db, txn) -> runGenerators(db, txn, generatorDefaults),
                database, "epip-generators", /*resolve=*/false);
        // Phase C — interchange repair + the ensemble, ONE write transaction.
        InPlace.runInPlace((db, txn) -> repairAndEnsemble(db, txn, generatorDefaults),
                database, "epip-repair-ensemble", /*resolve=*/false);
        // Phase C2 — clear every LevelRef whose target is not a Level its own Site defines. LAST of
        // the unconditional phases, because it is a conformance repair over the FINAL site
        // population: every stage that can drop a quay or a <levels> container has already run. It
        // carries its own G1-G6 guard; see EpipSanitize.stripOrphanLevelRefs.
        InPlace.runInPlace((db, txn) -> EpipSanitize.stripOrphanLevelRefs(db, txn),
                database, "epip-strip-levelrefs", /*resolve=*/false);
        // Phase D — OPT-IN, DEFAULT OFF. Rewrite the illegal after-midnight xs:time encoding
        // (24:20:00) into the only one NeTEx defines (00:20:00 + <*DayOffset>1</>). Applying it
        // CHANGES the encoding the feed used, so it runs only under
        // -Dtoolkit.epip.dayOffsetRewrite=on; with the flag unset this transaction is never opened
        // and the store is byte-identical. LAST, because it is a data repair over the final journey
        // population. See toolkit.transform.feedfix.DayOffsetRewrite.
        if (DayOffsetRewrite.ENABLED) {
            Log.info("[epip] %s=on -- running the day-offset rewrite (re-encodes the feed's "
                    + "after-midnight times; default is off)", DayOffsetRewrite.PROPERTY);
            InPlace.runInPlace(DayOffsetRewrite::apply,
                    database, "epip-day-offset-rewrite", /*resolve=*/false);
        }
    }

    // ------------------------------------------------------------- phase A1: de-embed calendars

    /// Every id-carrying registrable entity embedded in a ServiceCalendar is re-inserted
    /// first-class. Lazy: the calendar cursor and each calendar's embedded-entity walk advance
    /// only as the insert drains.
    public static void deembedServiceCalendarChildren(Store db, Txn txn) {
        Iterator<Object> embedded = new Iterator<>() {
            Iterator<Object> calendars;
            Iterator<EntityStructure> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                if (calendars == null) calendars = db.iterOnlyObjects(txn, ServiceCalendar.class).iterator();
                while (!current.hasNext()) {
                    if (!calendars.hasNext()) return false;
                    current = RecursiveAttributes.embeddedEntities(
                            calendars.next(), c -> ModelRegistry.isRegistered(c.getSimpleName())).iterator();
                }
                return true;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
        db.insertAnyObjects(txn, embedded);
    }

    // ------------------------------------------------------------- phase A2: PSA StopPlaceRef

    /// Map every entity embedded in a StopPlace (in practice its Quays) to a ref of that StopPlace,
    /// then re-emit only the PSAs whose stop-place union is EMPTY, so a PSA already carrying a
    /// TaxiRankRef/StopPlaceRef/StopPlace keeps its stored fragment untouched. A yielded PSA whose
    /// quay union holds a plain QuayRef (not a TaxiStandRef) gains its quay's parent StopPlaceRef;
    /// the rest are re-inserted unchanged.
    public static void backfillPsaStopPlaceRefs(Store db, Txn txn) {
        Iterator<Object> psas = new Iterator<>() {
            Map<String, StopPlaceRefStructure> quaySp;
            Iterator<Object> source;
            PassengerStopAssignment pending;

            private void init() {
                // embedded quay id -> its StopPlace's ref; drained fully before the PSA cursor
                // opens (one live cursor at a time).
                quaySp = new HashMap<>();
                for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
                    StopPlace sp = (StopPlace) o;
                    // One ref per StopPlace: it is re-marshalled per referencing PSA on insert.
                    StopPlaceRefStructure ref = NetexUtils.getRef(sp, StopPlaceRefStructure.class);
                    for (EntityStructure emb : RecursiveAttributes.embeddedEntities(
                            sp, c -> ModelRegistry.isRegistered(c.getSimpleName()))) {
                        quaySp.put(emb.getId(), ref);
                    }
                }
                source = db.iterOnlyObjects(txn, PassengerStopAssignment.class).iterator();
            }

            @Override
            public boolean hasNext() {
                if (source == null) init();
                while (pending == null && source.hasNext()) {
                    PassengerStopAssignment psa = (PassengerStopAssignment) source.next();
                    // Already carries a rank/stop-place ref or an embedded StopPlace -> NOT
                    // re-inserted. The binding splits the union into stopPlaceRef + stopPlace, and
                    // a TaxiRankRef arrives through the same stopPlaceRef slot
                    // (TaxiRankRefStructure extends StopPlaceRefStructure), so these two getters
                    // cover all three union arms.
                    if (psa.getStopPlaceRef() != null || psa.getStopPlace() != null) continue;
                    JAXBElement<? extends QuayRefStructure> quayEl = psa.getQuayRef();
                    if (quayEl != null && !(quayEl.getValue() instanceof TaxiStandRefStructure)) {
                        StopPlaceRefStructure spRef = quaySp.get(quayEl.getValue().getRef());
                        if (spRef != null) {
                            psa.setStopPlaceRef(FACTORY.createStopPlaceRef(spRef));
                        }
                    }
                    pending = psa; // emitted changed or not; the stop-place-less ones only
                }
                return pending != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                PassengerStopAssignment psa = pending;
                pending = null;
                return psa;
            }
        };
        db.insertAnyObjects(txn, psas);
    }

    // ---------------------------------------------------------------- phase B: the generators

    /// The six EPIP generators, in order, each piped to insertAnyObjects.
    public static void runGenerators(Store db, Txn txn, GeneratorDefaults gd) {
        db.insertAnyObjects(txn, EpipSanitize.epipSanitizeStopAssignments(db, txn, gd));
        db.insertAnyObjects(txn, EpipCalendar.epipServiceCalendar(db, txn, gd));
        db.insertAnyObjects(txn, EpipInterchange.epipServiceJourneyInterchange(db, txn, gd));
        db.insertAnyObjects(txn, EpipLines.epipLineGenerator(db, txn, gd));
        db.insertAnyObjects(txn, EpipLocations.inferLocationsFromQuayOrStopplaceAndApply(db, txn, gd));
        // The journeys generator carries the stage. With -Dtoolkit.epip.parallelJourneys=N its JAXB
        // MARSHAL moves onto N threads and each object arrives here as a WithFragment carrying
        // exactly the bytes insertOne would have produced inline. Default OFF: marshalAhead is the
        // identity, and this is the serial arm. Only the journeys generator is wrapped.
        db.insertAnyObjects(txn, toolkit.harness.GenParallel.marshalAhead(
                EpipJourneys.epipServiceJourneyGenerator(db, txn, gd), "journeys"));
    }

    // ------------------------------------------------------- phase C: repair + the ensemble

    /// Interchange-point repair, then the fused ensemble pass — one transaction.
    public static void repairAndEnsemble(Store db, Txn txn, GeneratorDefaults gd) {
        db.insertAnyObjects(txn, EpipInterchange.epipRepairInterchangePoints(db, txn, gd));
        runEnsemble(db, txn, gd);
    }

    /// ONE streaming pass over the target for every per-object touch-up —
    /// reprojection to WGS84 for every geo-carrying class present in the db, direction inference
    /// on ServiceJourneyPatterns, and the profile backfills. Each operation's query phase (index
    /// building) runs before the pass starts; every class is scanned once and a changed object
    /// written once. The synthesized Directions are chained after the ensemble output into one
    /// insert queue; two sequential insertAnyObjects on the same txn produce the identical
    /// insert sequence and honor the drain-after contract (Direction.InferDirections: the values
    /// view must only be iterated once the pass is exhausted).
    public static void runEnsemble(Store db, Txn txn, GeneratorDefaults gd) {
        Map<Class<?>, List<Ensemble.Operation>> ops = new LinkedHashMap<>();
        Ensemble.Operation reprojection = obj -> Projection.reprojectionChanged(obj, Projection.WGS84_URN);
        for (Class<?> geoClazz : db.dbNames(txn)) {
            if (GeoClasses.all().contains(geoClazz)) {
                ops.computeIfAbsent(geoClazz, k -> new ArrayList<>()).add(reprojection);
            }
        }
        Direction.InferDirections directions = Direction.inferDirectionsOp(gd);
        ops.computeIfAbsent(ServiceJourneyPattern.class, k -> new ArrayList<>()).add(directions.op());
        ops.computeIfAbsent(ServiceJourney.class, k -> new ArrayList<>())
                .add(Backfills.serviceJourneyOperatorOp(db, txn));
        ops.computeIfAbsent(VehicleType.class, k -> new ArrayList<>()).add(Backfills::vehicleTypeShortNameOp);
        ops.computeIfAbsent(StopPlace.class, k -> new ArrayList<>()).add(Backfills::quayNameOp);

        db.insertAnyObjects(txn, Ensemble.applyOperations(db, txn, ops));
        // Drained strictly AFTER the pass above: populated lazily during it (see the Javadoc).
        db.insertAnyObjects(txn, directions.inferred().values().iterator());
        // Projection is an Ensemble.Operation body and owns no pass, so this is the only place that
        // knows the reprojection is finished.
        Projection.reportDropped("epip-db-to-db");
    }
}
