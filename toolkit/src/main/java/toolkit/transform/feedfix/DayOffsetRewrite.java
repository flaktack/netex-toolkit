package toolkit.transform.feedfix;

// Rewrites the ILLEGAL after-midnight xs:time encoding
// (`24:20:00`, `25:10:00`, `30:05:00`, and the legal-but-day-losing `24:00:00`) into the ONLY
// encoding NeTEx actually defines: an hour in 0..23 plus the sibling `*DayOffset` element.
//
//   <DepartureTime>24:20:00</DepartureTime>
//     ->  <DepartureTime>00:20:00</DepartureTime><DepartureDayOffset>1</DepartureDayOffset>
//
// WHY:
//
//   * NeTEx defines no time type of its own — every declaration is `xsd:time` with no
//     restriction — so the W3C lexical space governs verbatim and hour 24+ is not a NeTEx
//     encoding at all. The intended form is the sibling `*DayOffset`.
//   * A consumer does NOT tolerate the illegal form: its adapter parses under a strict resolver
//     and throws, the JAXB RI downgrades that to a recoverable event, no handler is installed, so
//     the field is silently null with NO LOG. A passing time with both times null then trips a
//     completeness rule whose action is "the journey will be skipped".
//   * ANY BYTE-PRESERVING PIPELINE DROPS THE SAME JOURNEYS, so no before/after comparison of
//     stored bytes can see this.
//   * The VALID form is read correctly: a consumer adds 86400 per day offset.
//
// Default OFF: applying this CHANGES the encoding the feed used. The chain hook runs only under
// -Dtoolkit.epip.dayOffsetRewrite=on; unset, the chain does not even open the extra transaction
// and the output is byte-identical. Running THIS FILE as a driver is itself the opt-in.
//
// PLACEMENT is transform-time, never load-time:
//   * at load time the stored fragment would stop being source bytes, so the round-trip harness
//     would report a loss;
//   * and it would land UPSTREAM of coupling, moving the hour-24 scoring the coupling depends on.
//
// IT COMPOSES WITH the monotonicity repair, which is a DIFFERENT concept: its guard requires the
// departure offset to be at least the arrival's, and a rewritten value fails that conjunct, so
// there is no double count.
//
// THE FIVE SILENT FAILURES this is written against, and the answer to each:
//   1. losing the day        -> the PER-VALUE INVARIANT below, asserted on every rewrite;
//   2. multi-day             -> floorDiv(hour, 24), never `hour == 24 ? 1 : 0`;
//   3. a pre-existing offset -> newOffset = oldOffset + d, ACCUMULATE; and a NON-ZERO
//                               pre-existing offset THROWS, because the corpus has none and a
//                               violation means that precondition died;
//   4. `24:00:00` + explicit `DayOffset=0`  -> see the decision note on `rewriteTime`;
//   5. the carriers' payload -> the rewrite is LEXICAL SURGERY on the hour field only, so the
//                               fraction and the timezone offset travel verbatim
//                               (`24:20:00+01:00` -> `00:20:00+01:00`, `.000` kept as `.000`).

import noi.netex.calls.Calls;
import noi.netex.model.ArrivalStructure;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.DepartureStructure;
import noi.netex.model.ServiceJourney_VersionStructure;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.time.XmlTime;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Conf;
import toolkit.util.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public final class DayOffsetRewrite {

    /// The flag. Absent or anything but on/true/1/yes = OFF, which is the default.
    public static final String PROPERTY = Conf.EPIP_DAY_OFFSET_REWRITE.property();

    /// Where [#PROPERTY]-driven runs dump every rewritten value; unset = no dump.
    public static final String DUMP_PROPERTY = Conf.EPIP_DAY_OFFSET_REWRITE_DUMP.property();

    /// Whether the EPIP chain hook runs. **Default false** — see the file header.
    public static final boolean ENABLED = Conf.on(Conf.EPIP_DAY_OFFSET_REWRITE);

    private DayOffsetRewrite() {}

    // ------------------------------------------------------------------ the counters

    /// What a pass did — the numbers the gate reads.
    public static final class Stats {
        public long objectsScanned;
        public long objectsChanged;
        public long valuesRewritten;
        public long valuesExactlyMidnight;    // hour == 24, minute == second == nano == 0
        public long valuesPastBoundary;       // everything later — what OTP silently drops
        public long valuesWithZeroOffsetPresent;   // an EXPLICIT `0` companion, overwritten
        public int  maxDayOffsetWritten;

        @Override
        public String toString() {
            return String.format(
                    "scanned=%d changed=%d values=%d (24:00:00=%d past-boundary=%d) "
                            + "explicit-zero-offset-overwritten=%d maxDayOffset=%d",
                    objectsScanned, objectsChanged, valuesRewritten, valuesExactlyMidnight,
                    valuesPastBoundary, valuesWithZeroOffsetPresent, maxDayOffsetWritten);
        }
    }

    // ------------------------------------------------------------------ the driver body

    /// The pass body, named so tests exercise the exact production path.
    public static void apply(Store db, Txn txn) {
        Stats stats = new Stats();
        Path dump = Conf.pathOf(Conf.EPIP_DAY_OFFSET_REWRITE_DUMP);
        try (Sink sink = Sink.open(dump)) {
            db.insertAnyObjects(txn, rewrittenJourneys(db, txn, stats, sink));
            Log.info("[day-offset-rewrite] %s", stats);
        }
    }

    /// Every store class holding a `ServiceJourney_VersionStructure` — ServiceJourney and, where
    /// present, TemplateServiceJourney. Read off `dbNames`.
    public static List<Class<?>> journeyClasses(Store db, Txn txn) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c : db.dbNames(txn)) {
            if (ServiceJourney_VersionStructure.class.isAssignableFrom(c)) out.add(c);
        }
        out.sort(java.util.Comparator.comparing(Class::getName));
        return out;
    }

    /// Lazy pull-iterator over the journeys, yielding only the ones a rewrite changed.
    public static Iterator<Object> rewrittenJourneys(Store db, Txn txn, Stats stats, Sink sink) {
        List<Class<?>> classes = journeyClasses(db, txn);
        return new Iterator<>() {
            int at = -1;
            Iterator<Object> cur = java.util.Collections.emptyIterator();
            final ArrayDeque<Object> ready = new ArrayDeque<>();

            @Override
            public boolean hasNext() {
                while (ready.isEmpty()) {
                    while (!cur.hasNext()) {
                        if (++at >= classes.size()) return false;
                        cur = db.iterOnlyObjects(txn, classes.get(at)).iterator();
                    }
                    ServiceJourney_VersionStructure sj = (ServiceJourney_VersionStructure) cur.next();
                    stats.objectsScanned++;
                    if (rewriteJourney(sj, stats, sink)) {
                        stats.objectsChanged++;
                        ready.add(sj);
                    }
                }
                return true;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return ready.poll();
            }
        };
    }

    // ------------------------------------------------------------------ the rewrite

    /// One journey, every xs:time carrier NeTEx pairs with a `*DayOffset`:
    /// the journey's own `DepartureTime`, each TimetabledPassingTime's four
    /// (arrival / departure / latest-arrival / earliest-departure), and each Call's
    /// arrival and departure structures.
    ///
    /// @return whether anything moved.
    public static boolean rewriteJourney(ServiceJourney_VersionStructure sj, Stats stats, Sink sink) {
        String jid = sj.getId();
        boolean changed = false;

        // ServiceTimeGroup — the journey's own departure.
        changed |= rewritePair(jid, jid, "DepartureTime",
                sj::getDepartureTime, sj::setDepartureTime,
                sj::getDepartureDayOffset, sj::setDepartureDayOffset, stats, sink);

        if (sj.getPassingTimes() != null) {
            for (TimetabledPassingTime t : sj.getPassingTimes().getTimetabledPassingTime()) {
                String tid = t.getId();
                changed |= rewritePair(jid, tid, "ArrivalTime",
                        t::getArrivalTime, t::setArrivalTime,
                        t::getArrivalDayOffset, t::setArrivalDayOffset, stats, sink);
                changed |= rewritePair(jid, tid, "DepartureTime",
                        t::getDepartureTime, t::setDepartureTime,
                        t::getDepartureDayOffset, t::setDepartureDayOffset, stats, sink);
                changed |= rewritePair(jid, tid, "LatestArrivalTime",
                        t::getLatestArrivalTime, t::setLatestArrivalTime,
                        t::getLatestArrivalDayOffset, t::setLatestArrivalDayOffset, stats, sink);
                changed |= rewritePair(jid, tid, "EarliestDepartureTime",
                        t::getEarliestDepartureTime, t::setEarliestDepartureTime,
                        t::getEarliestDepartureDayOffset, t::setEarliestDepartureDayOffset, stats, sink);
            }
        }

        if (sj.getCalls() != null) {
            for (Call_VersionedChildStructure call : Calls.of(sj.getCalls())) {
                String cid = call.getId();
                ArrivalStructure arr = call.getArrival();
                if (arr != null) {
                    changed |= rewritePair(jid, cid, "Arrival/Time",
                            arr::getTime, arr::setTime, arr::getDayOffset, arr::setDayOffset, stats, sink);
                }
                DepartureStructure dep = call.getDeparture();
                if (dep != null) {
                    changed |= rewritePair(jid, cid, "Departure/Time",
                            dep::getTime, dep::setTime, dep::getDayOffset, dep::setDayOffset, stats, sink);
                }
            }
        }
        return changed;
    }

    /// The whole transform, for one (time, companion offset) pair.
    ///
    /// DECISION — `24:00:00` paired with an EXPLICIT `DayOffset=0`. That pair is
    /// self-contradictory: the producer means midnight at the END of day 0, while the schema-valid
    /// reading of `00:00:00` with offset 0 is the FIRST instant of day 0, twenty-four hours
    /// earlier. This pass treats it uniformly, exactly like every later hour — the TIME wins and
    /// the offset accumulates. Overwritten explicit zeros are counted separately.
    ///
    /// A NON-ZERO pre-existing offset THROWS. The two encodings are disjoint by feed — no element
    /// carries both a past-boundary hour and a non-zero offset — and the transform must fail loudly
    /// rather than accumulate silently into an unmeasured population.
    private static boolean rewritePair(String journeyId, String ownerId, String field,
                                       java.util.function.Supplier<XmlTime> getTime,
                                       java.util.function.Consumer<XmlTime> setTime,
                                       java.util.function.Supplier<BigInteger> getOffset,
                                       java.util.function.Consumer<BigInteger> setOffset,
                                       Stats stats, Sink sink) {
        XmlTime old = getTime.get();
        if (old == null || old.isUnparsed() || old.getHour() < 24) return false;

        int days = Math.floorDiv(old.getHour(), 24);          // NEVER `hour == 24 ? 1 : 0`
        XmlTime now = rewriteTime(old);
        BigInteger oldOffset = getOffset.get();

        if (oldOffset != null && oldOffset.signum() != 0) {
            throw new IllegalStateException("[day-offset-rewrite] PRECONDITION VIOLATED: "
                    + journeyId + " " + ownerId + " " + field + " = " + old.toXmlFormat()
                    + " already carries " + field + "DayOffset=" + oldOffset
                    + " -- the corpus disjointness this was measured against no longer holds, so "
                    + "accumulating here would move an unmeasured population. Re-measure first.");
        }
        BigInteger newOffset = (oldOffset == null ? BigInteger.ZERO : oldOffset)
                .add(BigInteger.valueOf(days));               // ACCUMULATE, never assign

        checkInvariant(journeyId, ownerId, field, old, oldOffset, now, newOffset);

        setTime.accept(now);
        setOffset.accept(newOffset);

        stats.valuesRewritten++;
        if (old.isXsdValid()) stats.valuesExactlyMidnight++; else stats.valuesPastBoundary++;
        if (oldOffset != null) stats.valuesWithZeroOffsetPresent++;
        stats.maxDayOffsetWritten = Math.max(stats.maxDayOffsetWritten, newOffset.intValue());
        sink.write(journeyId, ownerId, field, old, oldOffset, now, newOffset);
        return true;
    }

    /// Hour reduction by **lexical surgery**: everything after the first `':'` — minute, second,
    /// the fraction and the timezone offset — is copied through verbatim, and the result is
    /// re-parsed so the new carrier RETAINS it as its lexical form and re-marshals byte-exactly.
    ///
    /// `XmlTime.of(h, m, s)` is deliberately not used: it drops both the nano and the zone offset,
    /// turning `24:20:00+01:00` into `00:20:00` and `24:00:00.000` into `00:00:00`.
    public static XmlTime rewriteTime(XmlTime old) {
        String lexical = old.toXmlFormat();                   // retained source form, or canonical
        int colon = lexical.indexOf(':');
        int hour = Math.floorMod(old.getHour(), 24);
        String rebuilt = (hour < 10 ? "0" + hour : Integer.toString(hour)) + lexical.substring(colon);
        XmlTime out = XmlTime.parse(rebuilt);
        if (out == null || out.isUnparsed()) {
            throw new IllegalStateException("[day-offset-rewrite] rewrite of '" + lexical
                    + "' produced the unparsable '" + rebuilt + "'");
        }
        return out;
    }

    /// THE CHECK that separates "converted correctly" from "converted and lost the day", which no
    /// before/after A/B can see. Asserted on EVERY rewritten value:
    ///
    /// 1. total seconds are conserved: {@code oldOffset*86400 + rawHour*3600 + m*60 + s ==
    ///       newOffset*86400 + newTime.toSecondOfDay()}. Under the zero-offset precondition this
    ///    reduces to the literal form the design names,
    ///    `rawHour*3600 + m*60 + s == newOffset*86400 + newTime.toSecondOfDay()`;
    /// 1. the new hour is a legal one, 0..23, and the value is now legal `xs:time`;
    /// 1. minute, second, fraction and timezone offset are UNMOVED;
    /// 4. the recovered instant is at least a full day, it being an after-midnight value by
    ///    construction.
    public static void checkInvariant(String journeyId, String ownerId, String field,
                                      XmlTime old, BigInteger oldOffset,
                                      XmlTime now, BigInteger newOffset) {
        long before = (oldOffset == null ? 0L : oldOffset.longValueExact()) * 86_400L
                + old.getHour() * 3600L + old.getMinute() * 60L + old.getSecond();
        long after = newOffset.longValueExact() * 86_400L
                + now.getHour() * 3600L + now.getMinute() * 60L + now.getSecond();
        String where = journeyId + " " + ownerId + " " + field + " "
                + old.toXmlFormat() + "/" + oldOffset + " -> " + now.toXmlFormat() + "/" + newOffset;
        require(before == after, "SECONDS NOT CONSERVED (" + before + " != " + after + "): " + where);
        require(now.getHour() >= 0 && now.getHour() <= 23, "hour not in 0..23: " + where);
        require(now.isXsdValid(), "rewrite is still not legal xs:time: " + where);
        require(now.getMinute() == old.getMinute() && now.getSecond() == old.getSecond(),
                "minute/second moved: " + where);
        require(now.getNano() == old.getNano(), "fraction lost: " + where);
        require(java.util.Objects.equals(now.getOffset(), old.getOffset()), "zone offset lost: " + where);
        require(after >= 86_400L, "recovered value is not after midnight: " + where);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("[day-offset-rewrite] INVARIANT: " + message);
    }

    // ------------------------------------------------------------------ the dump (the gate feed)

    /// Optional TSV of every rewritten value, so the gate can compare the recovered time against
    /// the SOURCE LEXICAL. Columns:
    /// `journey  owner  field  srcLexical  srcOffset  newLexical  newOffset  seconds`.
    public static final class Sink implements AutoCloseable {
        private final Writer out;

        private Sink(Writer out) { this.out = out; }

        public static Sink open(Path path) {
            if (path == null) return new Sink(null);
            try {
                Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
                w.write("journey\towner\tfield\tsrc_lexical\tsrc_offset\tnew_lexical\tnew_offset\tseconds\n");
                return new Sink(w);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void write(String journeyId, String ownerId, String field, XmlTime old,
                   BigInteger oldOffset, XmlTime now, BigInteger newOffset) {
            if (out == null) return;
            long seconds = newOffset.longValueExact() * 86_400L
                    + now.getHour() * 3600L + now.getMinute() * 60L + now.getSecond();
            try {
                out.write(journeyId + "\t" + ownerId + "\t" + field + "\t" + old.toXmlFormat()
                        + "\t" + (oldOffset == null ? "-" : oldOffset) + "\t" + now.toXmlFormat()
                        + "\t" + newOffset + "\t" + seconds + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            if (out == null) return;
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
