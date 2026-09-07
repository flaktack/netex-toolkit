package toolkit.harness;

// Raw-clone the source database into the target, then run the EPIP transformation on the target
// in place. inPlace is the composition form the pipelines' fused drivers reuse.
//
// Phases, one runInPlace (= one transaction) each:
//   A.  de-embed the ServiceCalendar children — every id-carrying registrable entity embedded in
//       a ServiceCalendar is re-inserted as a first-class row; the calendar keeps its copy.
//   B.  the five EPIP generators in order: calendar, interchange, lines, locations, journeys.
//       Calendar and interchange must see the pre-transform corpus — the interchange resolves
//       JourneyMeeting points against the journeys' original patterns, and the calendar regroups
//       only the feeds' own calendar objects, never the journey transform's generated DayTypes.
//   C.  repair interchange points against the patterns consumers will see, then one streaming
//       ensemble pass fusing reprojection, the ServiceJourneyPattern direction inference (its
//       synthesized Directions drain AFTER the pass) and the profile backfills.
//   C2. strip orphan LevelRefs — a LevelRef whose target is not a Level its own Site defines is
//       cleared, on both shapes the corpus carries. EPIP's Level_KeyRef requires an emitted
//       LevelRef to resolve in the same document; LevelRef is minOccurs="0", so absence is
//       conformant.
//   E.  resolve() + resolveEmbeddings(), once, over the finished store; inPlace then checks that
//       the unresolved count did not grow across its own run.
//
// Repaired quays are a precondition, not something this stage does: the assignments arrive already
// sanitized per feed, EpipLocations reads them expecting that, and C2 is a conformance pass over
// the sites left behind.
//
// A non-empty target is refused, and --format must equal the source's sniffed format: the clone
// is a raw byte copy, so a cross-format clone would corrupt the target.

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import noi.netex.model.EntityStructure;
import noi.netex.model.ServiceCalendar;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLink;
import noi.netex.model.StopPlace;
import noi.netex.model.VehicleType;
import toolkit.geo.Projection;
import toolkit.model.GeoClasses;
import toolkit.model.ModelRegistry;
import toolkit.model.RecursiveAttributes;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.transform.epip.Backfills;
import toolkit.transform.epip.Direction;
import toolkit.transform.feedfix.AxisOrder;
import toolkit.transform.epip.EpipCalendar;
import toolkit.transform.epip.EpipInterchange;
import toolkit.transform.epip.EpipJourneys;
import toolkit.transform.epip.EpipLines;
import toolkit.transform.epip.EpipLocations;
import toolkit.transform.epip.EpipSanitize;
import toolkit.transform.epip.GeneratorDefaults;
import toolkit.transform.feedfix.DayOffsetRewrite;
import toolkit.util.Log;
import toolkit.util.Strictness;

public final class EpipToDb {

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
        long unresolvedBefore = countUnresolved(database);
        // Phase A — prepare the source.
        InPlace.runInPlace(EpipToDb::deembedServiceCalendarChildren,
                database, "epip-deembed-calendars", /*resolve=*/false);
        // Phase B — the five generators, ONE write transaction.
        InPlace.runInPlace((db, txn) -> runGenerators(db, txn, generatorDefaults),
                database, "epip-generators", /*resolve=*/false);
        // Phase C — interchange repair + the ensemble, ONE write transaction.
        InPlace.runInPlace((db, txn) -> repairAndEnsemble(db, txn, generatorDefaults),
                database, "epip-repair-ensemble", /*resolve=*/false);
        // Phase C2 — clear every LevelRef whose target is not a Level its own Site defines. Last of
        // the unconditional phases, because it is a conformance repair over the final site
        // population: every stage that can drop a quay or a <levels> container has already run. It
        // carries its own G1-G6 guard.
        InPlace.runInPlace((db, txn) -> EpipSanitize.stripOrphanLevelRefs(db, txn),
                database, "epip-strip-levelrefs", /*resolve=*/false);
        // Phase D — opt-in, default off. Rewrite the illegal after-midnight xs:time encoding
        // (24:20:00) into the only one NeTEx defines (00:20:00 + <*DayOffset>1</>). Applying it
        // changes the encoding the feed used, so it runs only under
        // -Dtoolkit.epip.dayOffsetRewrite=on; with the flag unset this transaction is never opened
        // and the store is byte-identical. Last, because it is a data repair over the final journey
        // population.
        if (DayOffsetRewrite.ENABLED) {
            Log.info("[epip] %s=on -- running the day-offset rewrite (re-encodes the feed's "
                    + "after-midnight times; default is off)", DayOffsetRewrite.PROPERTY);
            InPlace.runInPlace(DayOffsetRewrite::apply,
                    database, "epip-day-offset-rewrite", /*resolve=*/false);
        }
        // Phase E — wire what this stage's own generators left dangling, so the store handed on is
        // coherent. No fix function: the pair is the phase, and it runs outside a transaction of
        // its own making (resolveEmbeddings opens and commits one, so it cannot run inside
        // another).
        //
        // resolveEmbeddings and not resolve() alone: the conversion gives every
        // TimetabledPassingTime a PointInJourneyPatternRef naming a StopPointInJourneyPattern
        // embedded in its pattern, and an embedded child has no row in the id index, which is what
        // resolve() probes — so it leaves ~20 unresolved rows per journey for every later stage.
        InPlace.resolveOnly(database, "epip-resolve");

        long unresolvedAfter = countUnresolved(database);
        Log.info("[epip] unresolved rows %,d -> %,d over the whole transform", unresolvedBefore,
                unresolvedAfter);
        // Checked as "did not grow" rather than "is zero": a corpus references ids outside its own
        // export, and those rows arrive with the feeds.
        Strictness.require(Strictness.UNRESOLVED_REFS, "unresolvedRefs",
                Math.max(0, unresolvedAfter - unresolvedBefore), "epip",
                "unresolved references were ADDED by the EPIP transform and not wired by phase E");
    }

    /// Live unresolved rows, streamed: `scanUnresolved` is the O(1)-heap reader, one block
    /// resident, over `_edge_blocks`.
    private static long countUnresolved(Path database) throws Exception {
        long n = 0;
        try (Store db = Stores.open(database, true); Txn txn = db.roTxn()) {
            for (toolkit.store.UnresolvedRow ignored : db.scanUnresolved(txn)) n++;
        }
        return n;
    }

    // -------------------------------------------------------------- phase A: de-embed calendars

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

    // ---------------------------------------------------------------- phase B: the generators

    /// The five EPIP generators, in order, each piped to insertAnyObjects.
    public static void runGenerators(Store db, Txn txn, GeneratorDefaults gd) {
        db.insertAnyObjects(txn, EpipCalendar.epipServiceCalendar(db, txn, gd));
        db.insertAnyObjects(txn, EpipInterchange.epipServiceJourneyInterchange(db, txn, gd));
        db.insertAnyObjects(txn, EpipLines.epipLineGenerator(db, txn, gd));
        db.insertAnyObjects(txn, EpipLocations.inferLocationsFromQuayOrStopplaceAndApply(db, txn, gd));
        // With -Dtoolkit.epip.parallelJourneys=N the journeys generator's JAXB marshal moves onto N
        // threads and each object arrives here as a WithFragment carrying exactly the bytes
        // insertOne would have produced inline. Default off: marshalAhead is the identity, and this
        // is the serial arm. Only the journeys generator is wrapped.
        db.insertAnyObjects(txn, toolkit.harness.GenParallel.marshalAhead(
                EpipJourneys.epipServiceJourneyGenerator(db, txn, gd), "journeys"));
    }

    // ------------------------------------------------------- phase C: repair + the ensemble

    /// Interchange-point repair, then the fused ensemble pass — one transaction.
    public static void repairAndEnsemble(Store db, Txn txn, GeneratorDefaults gd) {
        db.insertAnyObjects(txn, EpipInterchange.epipRepairInterchangePoints(db, txn, gd));
        runEnsemble(db, txn, gd);
    }

    /// One streaming pass over the target for every per-object touch-up — reprojection to WGS84 for
    /// every geo-carrying class present in the db, direction inference on ServiceJourneyPatterns,
    /// and the profile backfills. Each operation's query phase (index building) runs before the
    /// pass starts; every class is scanned once and a changed object written once. The synthesized
    /// Directions are chained after the ensemble output into one insert queue, which honors
    /// Direction.InferDirections' drain-after contract: the values view must only be iterated once
    /// the pass is exhausted.
    public static void runEnsemble(Store db, Txn txn, GeneratorDefaults gd) {
        Map<Class<?>, List<Ensemble.Operation>> ops = new LinkedHashMap<>();
        Ensemble.Operation reprojection = obj -> Projection.reprojectionChanged(obj, Projection.WGS84_URN);
        for (Class<?> geoClazz : db.dbNames(txn)) {
            if (GeoClasses.all().contains(geoClazz)) {
                ops.computeIfAbsent(geoClazz, k -> new ArrayList<>()).add(reprojection);
            }
        }
        // Ahead of the reprojection on the same class: a link that does take a real transform must
        // be in the authority's own axis convention before Projection swaps it symmetrically.
        ops.computeIfAbsent(ServiceLink.class, k -> new ArrayList<>())
                .add(0, AxisOrder.axisOrderOp(db, txn));
        Direction.InferDirections directions = Direction.inferDirectionsOp(gd);
        ops.computeIfAbsent(ServiceJourneyPattern.class, k -> new ArrayList<>()).add(directions.op());
        ops.computeIfAbsent(ServiceJourney.class, k -> new ArrayList<>())
                .add(Backfills.serviceJourneyOperatorOp(db, txn));
        ops.computeIfAbsent(VehicleType.class, k -> new ArrayList<>()).add(Backfills::vehicleTypeShortNameOp);
        ops.computeIfAbsent(StopPlace.class, k -> new ArrayList<>()).add(Backfills::quayNameOp);

        db.insertAnyObjects(txn, Ensemble.applyOperations(db, txn, ops));
        // Drained strictly after the pass above: populated lazily during it.
        db.insertAnyObjects(txn, directions.inferred().values().iterator());
        // Projection is an Ensemble.Operation body and owns no pass, so this is the only place that
        // knows the reprojection is finished.
        Projection.reportDropped("epip-db-to-db");
        AxisOrder.reportSwapped("epip-db-to-db");
    }
}
