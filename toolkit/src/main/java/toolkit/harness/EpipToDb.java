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
//       synthesized Directions drain AFTER the pass), the profile backfills and — only under
//       -Dtoolkit.epip.dayOffsetRewrite=on — the after-midnight re-encoding, which is an operation
//       of this pass rather than a phase because it reads journeys this pass already decodes.
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
import noi.netex.model.FlexibleLine;
import noi.netex.model.Line;
import noi.netex.model.ServiceCalendar;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceJourney_VersionStructure;
import noi.netex.model.ServiceLink;
import noi.netex.model.StopPlace;
import noi.netex.model.VehicleType;
import toolkit.geo.Projection;
import toolkit.model.GeoClasses;
import toolkit.model.ModelRegistry;
import toolkit.model.NetexUtils;
import toolkit.model.RecursiveAttributes;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.transform.common.LineAuthorities;
import toolkit.transform.common.ProductCategories;
import toolkit.transform.epip.Backfills;
import toolkit.transform.epip.DestinationDisplays;
import toolkit.transform.epip.Direction;
import toolkit.transform.feedfix.AxisOrder;
import toolkit.transform.epip.EpipCalendar;
import toolkit.transform.epip.EpipInterchange;
import toolkit.transform.epip.EpipJourneys;
import toolkit.transform.epip.EpipLines;
import toolkit.transform.epip.EpipLocations;
import toolkit.transform.epip.EpipSanitize;
import toolkit.transform.epip.GeneratorDefaults;
import toolkit.transform.epip.TerminusNames;
import toolkit.transform.feedfix.DayOffsetRewrite;
import toolkit.util.Conf;
import toolkit.util.Log;
import toolkit.util.Strictness;

public final class EpipToDb {

    private EpipToDb() {}

    /// Raw-clone `source` into `target` (source opened READ-ONLY, one ro + one rw txn, commit, no
    /// resolve: the clone is byte-faithful, resolved state included), then run the in-place
    /// transformation there.
    public static void cloneAndTransform(Path source, Path target, String format) throws Exception {
        cloneAndTransform(source, target, format, GeneratorDefaults.PRODUCTION);
    }

    /// [#cloneAndTransform] over a caller-chosen codespace, which is the only part of the defaults
    /// a driver has reason to set: it decides the prefix of every id no source could supply.
    public static void cloneAndTransform(Path source, Path target, String format,
                                         GeneratorDefaults gd) throws Exception {
        long t0 = System.nanoTime();
        try (Store src = Stores.open(source, true);
             Store dst = Stores.create(target, format)) {
            try (Txn stx = src.roTxn(); Txn dtx = dst.rwTxn()) {
                dst.cloneFrom(src, stx, dtx, null, 0);
                dtx.commit();
            }
        }
        Log.info("[epip] clone %s -> %s done (%.1f s)", source, target, Log.secs(t0));
        inPlace(target, gd);
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

    /// The authority backfill, then the five EPIP generators, in order, each piped to
    /// insertAnyObjects.
    ///
    /// The backfill has to stay ahead of the line generator: it gives a Line an authorityRef, and
    /// the generator is what then demotes whichever organisation reference is not primary into
    /// additionalOperators. Reversed, a backfilled Line reaches the export carrying both.
    ///
    /// The pass skips a Line that already has an authorityRef, so a pipeline that also runs it per
    /// feed gets the same store either way.
    public static void runGenerators(Store db, Txn txn, GeneratorDefaults gd) {
        // The codespace the fallback ids carry, as a row, so the export declares it the way it
        // declares a feed's own. Its id is fixed, so a second run replaces this rather than adding
        // a second: an id-carrying object is keyed on (id, version, class).
        db.insertAnyObjects(txn, List.<Object>of(gd.codespace()).iterator());
        db.insertAnyObjects(txn, LineAuthorities.backfillLineAuthorities(db, txn, gd.codespace()));
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
        // A journey's headsign is the display its pattern's first stop point names, and most of the
        // corpus names none. Here, not in phase B: the display feeds the frozen pattern-id hash.
        DestinationDisplays.SynthesizeDisplays displays =
                DestinationDisplays.synthesizeDisplaysOp(db, txn, gd);
        ops.computeIfAbsent(ServiceJourneyPattern.class, k -> new ArrayList<>()).add(displays.op());
        ops.computeIfAbsent(ServiceJourney.class, k -> new ArrayList<>())
                .add(Backfills.serviceJourneyOperatorOp(db, txn));
        ops.computeIfAbsent(VehicleType.class, k -> new ArrayList<>()).add(Backfills::vehicleTypeShortNameOp);
        // Both Line operations are in the second pass below, which is the only place that can see
        // the displays this one mints.
        ops.computeIfAbsent(StopPlace.class, k -> new ArrayList<>()).add(Backfills::quayNameOp);
        ops.computeIfAbsent(StopPlace.class, k -> new ArrayList<>())
                .add(Backfills.stopPlaceTypeOfPlaceOp(db, txn));

        if (DayOffsetRewrite.ENABLED) {
            DayOffsetRewrite.Stats stats = new DayOffsetRewrite.Stats();
            try (DayOffsetRewrite.Sink sink =
                         DayOffsetRewrite.Sink.open(Conf.pathOf(Conf.EPIP_DAY_OFFSET_REWRITE_DUMP))) {
                registerDayOffsetRewrite(db, txn, ops, stats, sink);
                db.insertAnyObjects(txn, Ensemble.applyOperations(db, txn, ops));
            }
            Log.info("[day-offset-rewrite] %s", stats);
        } else {
            db.insertAnyObjects(txn, Ensemble.applyOperations(db, txn, ops));
        }
        // Drained strictly after the pass above: populated lazily during it.
        db.insertAnyObjects(txn, directions.inferred().values().iterator());
        db.insertAnyObjects(txn, displays.minted().values().iterator());
        Log.info("[destination-displays] %s", displays.counts());

        // A second pass, over the two line classes only, because naming a Line after its termini
        // reads what the first pass wrote: the display a pattern's first stop point names, and the
        // Directions inferred for it. Both are in the store only now -- the minted values views are
        // populated lazily during the pass above and drained after it -- so an operation registered
        // alongside them would have read an empty index.
        //
        // Order within the pass is load-bearing. The terminus pass declines a Line that already
        // carries a real name, and the backstop's last resort is the identifier part of the id,
        // which is such a name; registering the backstop first would lock every Line it touched out
        // of a terminus name.
        TerminusNames.NameTermini termini = TerminusNames.nameTerminiOp(db, txn);
        Map<Class<?>, List<Ensemble.Operation>> lineOps = new LinkedHashMap<>();
        for (Class<?> lineClazz : List.of(Line.class, FlexibleLine.class)) {
            // The profile requires Line/Name, and OTP dereferences it without a null guard, so the
            // backstop closes whatever the terminus pass left.
            lineOps.put(lineClazz, List.of(termini.op(), ProductCategories::lineNameOp));
        }
        db.insertAnyObjects(txn, Ensemble.applyOperations(db, txn, lineOps));
        Log.info("[terminus-names] %s", termini.counts());
        // Projection is an Ensemble.Operation body and owns no pass, so this is the only place that
        // knows the reprojection is finished.
        Projection.reportDropped("epip-db-to-db");
        AxisOrder.reportSwapped("epip-db-to-db");
        // Phase B's generators derive most of their ids; this is the first point after all of them
        // and the Direction inference above, so it is where the count is whole.
        NetexUtils.reportIdFallbacks("epip-db-to-db");
    }

    /// Add the after-midnight re-encoding to an ensemble's operations, one per journey class.
    ///
    /// `journeyClasses`, not `ServiceJourney.class`: TemplateServiceJourney carries the same time
    /// carriers. The operator backfill writes the same class, and the two set disjoint fields, so a
    /// journey both touch is written once and the order between them does not matter.
    ///
    /// Public so a test drives the registration the chain uses, rather than a copy of it: the flag
    /// that gates it is read once at class init, so no in-process test can turn it on.
    public static void registerDayOffsetRewrite(Store db, Txn txn,
            Map<Class<?>, List<Ensemble.Operation>> ops,
            DayOffsetRewrite.Stats stats, DayOffsetRewrite.Sink sink) {
        for (Class<?> journeyClazz : DayOffsetRewrite.journeyClasses(db, txn)) {
            ops.computeIfAbsent(journeyClazz, k -> new ArrayList<>()).add(obj -> {
                stats.objectsScanned++;
                boolean rewritten = DayOffsetRewrite.rewriteJourney(
                        (ServiceJourney_VersionStructure) obj, stats, sink);
                if (rewritten) stats.objectsChanged++;
                return rewritten;
            });
        }
    }
}
