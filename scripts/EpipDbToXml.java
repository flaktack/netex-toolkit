// Streams every object of a store into an EPIP-shaped NeTEx zip:
//
//   shared-01-resource.xml   ResourceFrame COMMON (dataSources, responsibilitySets, typesOfValue,
//                            organisations, vehicleTypes, zones — the XSD sequence)
//   shared-02-sites.xml      SiteFrame EU_PI_STOP (topographicPlaces, stopPlaces)
//   shared-03-network.xml    ServiceFrame EU_PI_NETWORK (Network, directions, lines, ...,
//                            stopAssignments — no routes and no journey patterns)
//   shared-05-calendar.xml   ServiceCalendarFrame EU_PI_CALENDAR, UicOperatingPeriods only
//   shared-06-other.xml      GeneralFrame OTHER_REFERENCED — the reference-graph closure over the
//                            29 listed classes; TrainNumbers excluded; omitted when empty
//   gSS-NNNN.xml             ServiceFrame(journeyPatterns) + TimetableFrame(their journeys),
//                            patterns colocated with their journeys (pattern index % shards,
//                            pattern-id-sorted within a shard, ref-less orphans last in shard 0)
//   z-links.xml              TimetableFrame EU_PI_TIMETABLE_LINKS (trainNumbers,
//                            journeyInterchanges); sorts last, omitted when empty
//
// Entry order is g* < shared-* < z-links. Entry metadata is pinned to the 1980 DOS epoch by
// toolkit.harness.ZipWriter; the CONTENT is not reproducible, because every entry's
// <PublicationTimestamp> is a wall clock — compare two exports with tools/diff/export_zip.py,
// which normalises exactly that field.
//
// This file is the EPIP PROFILE: which class goes in which frame, in which container, in which
// order, and what the closure is. The mechanism it runs on is the export sandwich —
// toolkit.harness.DbToZip (CLI, the read-only open, the atomic .tmp/rename, timing, the common
// tail), toolkit.harness.ZipWriter (entries, byte/object totals), toolkit.harness.NetexEnvelope
// (the per-entry PublicationDelivery wrapper) and toolkit.harness.FrameWriter (stored rows ->
// emitted fragment bytes). Nothing below knows how a zip entry is opened; nothing up there knows
// what EPIP is.
//
// Stored values are already LZ4'd XML fragments, so emission is decompress + byte copy between
// literal frame wrappers — no JAXB on the hot path. Journey-to-pattern routing reads the stored
// edge index rather than decoding. shared-06 is the one exception: the closure decodes each
// member once, then still copies the stored fragment bytes.
//
// Every emitted fragment passes through toolkit.codec.FragmentOrder. That is a byte scan, not a
// decode: it reorders a fragment's top-level children into the class's declared XSD sequence when
// the source feed emitted them out of order, and returns the same array when it did not, which is
// the common case. Without it the export would carry the source's schema violations verbatim. It
// is passed to FrameWriter as its Fragments hook, in ONE place, so it holds for every emitted
// fragment; a profile that wants the stored bytes verbatim omits it.
//
// Emission is single-threaded: most of the entry-emission time is zlib inside the one Deflater a
// ZipOutputStream owns, capping any gain at ~6%. The prologue's four index reads do run on four
// threads (device-I/O bound, no deflate); the joins, and so the emitted order, stay
// single-threaded.

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import toolkit.codec.FragmentCodec;
import toolkit.codec.FragmentOrder;
import toolkit.harness.DbToZip;
import toolkit.harness.FrameWriter;
import toolkit.harness.NetexEnvelope;
import toolkit.harness.ZipWriter;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.ModelRegistry;
import toolkit.store.IdRow;
import toolkit.store.RawRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;
import toolkit.util.Log;
import toolkit.util.PeekIterator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class EpipDbToXml {

    // ------------------------------------------------------------ class routing (simple names)
    // Container order is the XSD sequence: dataSources, responsibilitySets, typesOfValue,
    // organisations, vehicleTypes, zones.
    static final Map<String, List<String>> RESOURCE = orderedMap(
            "dataSources", "DataSource",
            "responsibilitySets", "ResponsibilitySet",
            "typesOfValue", "ValueSet",
            "organisations", "Authority,Operator",
            "vehicleTypes", "VehicleType",
            "zones", "TransportAdministrativeZone");
    static final Map<String, List<String>> SITES = orderedMap(
            "topographicPlaces", "TopographicPlace",
            "stopPlaces", "StopPlace");
    // NO "routes": the EPIP service frame has no routes block — Route objects reach the export
    // only through the OTHER_REFERENCED closure. ServiceFrame sequence: network precedes
    // directions, then lines < destinationDisplays < scheduledStopPoints < serviceLinks <
    // connections < tariffZones < stopAssignments < notices.
    static final Map<String, List<String>> NETWORK = orderedMap(
            "directions", "Direction",
            "lines", "Line,FlexibleLine",
            "destinationDisplays", "DestinationDisplay",
            "scheduledStopPoints", "ScheduledStopPoint",
            "serviceLinks", "ServiceLink",
            "connections", "Connection,SiteConnection,DefaultConnection",
            "tariffZones", "TariffZone",
            "stopAssignments", "PassengerStopAssignment",
            "notices", "Notice");
    // operatingPeriods = UicOperatingPeriod ONLY; plain OperatingPeriods reach shared-06 via the
    // closure.
    static final Map<String, List<String>> CALENDAR = orderedMap(
            "dayTypes", "DayType",
            "operatingPeriods", "UicOperatingPeriod",
            "dayTypeAssignments", "DayTypeAssignment");

    static Map<String, List<String>> orderedMap(String... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], List.of(kv[i + 1].split(",")));
        return m;
    }

    /// OTHER_REFERENCED_CLASSES: the classes whose OUTWARD
    /// references define the OTHER_REFERENCED general frame. The closure yields what these
    /// reference (directly + transitively) but never the classes themselves.
    static final List<String> OTHER_REFERENCED_CLASSES = List.of(
            "Authority",
            "Connection",
            "DayType",
            "DayTypeAssignment",
            "DataSource",
            "DefaultConnection",
            "DestinationDisplay",
            "Direction",
            "FlexibleLine",
            "Line",
            "Network",
            "Notice",
            "Operator",
            "PassengerStopAssignment",
            "ResponsibilitySet",
            "ScheduledStopPoint",
            "ServiceCalendar",
            "ServiceJourney",
            "ServiceJourneyInterchange",
            "ServiceJourneyPattern",
            "ServiceLink",
            "SiteConnection",
            "StopPlace",
            "TariffZone",
            "TopographicPlace",
            "TransportAdministrativeZone",
            "UicOperatingPeriod",
            "ValueSet",
            "VehicleType");

    /// The two strings the EPIP profile puts in the per-entry envelope. Everything else in the
    /// wrapper is NeTEx-generic and lives in [NetexEnvelope].
    static final NetexEnvelope.Options ENVELOPE = new NetexEnvelope.Options(
            "NDOV", "Huge XML Serializer test", "EU_NETWORK_OFFER", "epip:EU_PI_NETWORK_OFFER");

    // ------------------------------------------------------------ state
    final Store store;
    final Txn tx;
    final ZipWriter out;
    final FragmentCodec codec = new FragmentCodec();
    NetexEnvelope env; // built in export(), once the codespaces and the locale are known
    FrameWriter fw;    // built with env, and for the same reason: it repeats the envelope per entry
    final List<byte[]> codespaces = new ArrayList<>();
    byte[] defaultLocale; // the frame defaults' locale block, or null

    EpipDbToXml(Store store, Txn tx, ZipWriter out) {
        this.store = store;
        this.tx = tx;
        this.out = out;
    }

    static final String USAGE = "EpipDbToXml <db> <out.zip> [--shards N (>= 1)]"
            + " [--journeys-per-file N] [--level N (0..9)] [--log-file F]";

    public static void main(String[] args) throws Exception {
        // CLI via toolkit.harness.Args, the common half owned by DbToZip: unknown --x, a
        // value-taking flag with no value (also as the LAST token) or a wrong positional count ->
        // usage + exit 2; a missing input db -> exit 1. No --pretty: pretty-printing would conflict
        // with the fragment byte-copy design. The two flags declared here are the ones the harness
        // cannot know about; --level and --log-file come from DbToZip.
        DbToZip.driverMain("epip-db-to-xml", args, USAGE,
                new String[] {"--shards=", "--journeys-per-file="},
                a -> {
                    // The shard count is PINNED, not host-derived: it decides the ARTEFACT, where
                    // thread counts decide only wall time. Deriving it from the core count would
                    // ship a different zip per host.
                    //
                    // The four prologue readers are fixed at four, one per index: they exist to
                    // fill a device that one scan leaves idle.
                    int shards = a.intFlag("--shards", 8);
                    int journeysPerFile = a.intFlag("--journeys-per-file", 50_000);
                    if (shards < 1) {
                        System.err.println("usage: " + USAGE);
                        System.exit(2);
                    }
                    return (db, tx, zip) -> new EpipDbToXml(db, tx, zip).export(shards, journeysPerFile);
                });
    }

    // ------------------------------------------------------------ export driver

    void export(int shards, int journeysPerFile) throws IOException {
        Class<?> cs = store.classForName("Codespace");
        if (cs != null) {
            for (RawRow r : store.iterRawObjects(tx, cs)) {
                codespaces.add(FragmentOrder.normalise(cs, codec.decompress(r.blob())));
            }
        }
        defaultLocale = deriveDefaultLocale(); // one envelope-level pass, shared by every entry
        env = new NetexEnvelope(ENVELOPE, codespaces, defaultLocale);
        // The profile hook this export exists to apply. Passing it here, once, is what makes it
        // true of EVERY emitted fragment rather than of the call sites someone remembered.
        fw = new FrameWriter(store, tx, out, env, FragmentOrder::normalise);

        // Entry ORDER inside the zip is a plain name sort:
        // sorted names put the g* journey groups first, then shared-*, then z-links. The groups
        // are streamed in that sorted order by construction (see journeyGroups: shards emitted
        // in sorted-name order, seqs zero-padded), so the zip is written g* -> shared -> z-links
        // without buffering.
        journeyGroups(shards, journeysPerFile);

        // shared entries
        fw.entry("shared-01-resource.xml");
        env.frameOpen(out, "ResourceFrame", "COMMON", "epip:COMMON");
        fw.containers(RESOURCE);
        out.write("</ResourceFrame>");
        fw.end();

        fw.entry("shared-02-sites.xml");
        env.frameOpen(out, "SiteFrame", "EU_PI_STOP", "epip:EU_PI_STOP");
        fw.containers(SITES);
        out.write("</SiteFrame>");
        fw.end();

        fw.entry("shared-03-network.xml");
        env.frameOpen(out, "ServiceFrame", "EU_PI_NETWORK", "epip:EU_PI_NETWORK");
        // Exactly ONE Network, a direct child with no container element — every extra
        // Network row is dropped. Deterministic "first" = the store's scan (key) order, which is
        // insertion order for the toolkit's local keys.
        fw.first("Network");
        fw.containers(NETWORK);
        out.write("</ServiceFrame>");
        fw.end();

        fw.entry("shared-05-calendar.xml");
        env.frameOpen(out, "ServiceCalendarFrame", "EU_PI_CALENDAR", "epip:EU_PI_CALENDAR");
        out.write("<ServiceCalendar id=\"OPENOV:ServiceCalendar:ServiceCalendar\" version=\""
                + env.version() + "\">");
        calendarValidity();
        fw.containers(CALENDAR);
        out.write("</ServiceCalendar></ServiceCalendarFrame>");
        fw.end();

        otherFrame();
        zLinks();

        // How much of the export the source feeds had out of sequence, and how much of it
        // FragmentOrder declined to rank (unknown child element / unrankable class). Logged from
        // here rather than from the harness tail: it is a profile decision, like the hook itself.
        Log.info("[epip-db-to-xml] child order: %d fragments seen, %d reordered, %d left verbatim (unrankable)",
                FragmentOrder.seen(), FragmentOrder.reordered(), FragmentOrder.skipped());
    }

    /// The frame defaults' locale block: the union of the Authority+Operator
    /// locales (null locales dropped, content-deduplicated), first one wins; when its languages
    /// block holds exactly ONE LanguageUsage, its Language becomes the DefaultLanguage. Done as a
    /// byte-scan of the organisation fragments (no JAXB): the root element is renamed Locale ->
    /// DefaultLocale, which is the element name the frame defaults declare it under.
    ///
    /// The pick is deterministic: Authority-then-Operator scan order, first one wins, deduplicated
    /// by fragment bytes. A feed carrying more than one distinct locale has no single right answer,
    /// so the export takes the first — two runs over the same store must produce the same zip.
    byte[] deriveDefaultLocale() {
        LinkedHashSet<String> locales = new LinkedHashSet<>();
        for (String cn : List.of("Authority", "Operator")) {
            Class<?> c = store.classForName(cn);
            if (c == null) continue;
            for (RawRow r : store.iterRawObjects(tx, c)) {
                String s = new String(codec.decompress(r.blob()), StandardCharsets.UTF_8);
                int i = s.indexOf("<Locale>");
                if (i < 0) continue;
                int j = s.indexOf("</Locale>", i);
                if (j < 0) continue;
                locales.add(s.substring(i + "<Locale>".length(), j)); // inner content
            }
        }
        if (locales.isEmpty()) return null;
        if (locales.size() > 1) {
            Log.info("[epip-db-to-xml] %d distinct Authority/Operator locales -- the first in "
                    + "Authority-then-Operator scan order becomes the DefaultLocale", locales.size());
        }
        String body = locales.iterator().next();
        // a single languageUsage -> defaultLanguage = languageUsage[0].language
        int usages = countOccurrences(body, "<LanguageUsage");
        if (usages == 1) {
            String lang = between(body, "<Language>", "</Language>");
            if (lang != null) {
                String dl = "<DefaultLanguage>" + lang + "</DefaultLanguage>";
                int existing = body.indexOf("<DefaultLanguage>");
                if (existing >= 0) {
                    int end = body.indexOf("</DefaultLanguage>", existing);
                    body = body.substring(0, existing) + dl + body.substring(end + "</DefaultLanguage>".length());
                } else {
                    // LocaleStructure field order: default_language BEFORE languages
                    int langs = body.indexOf("<languages");
                    body = langs >= 0 ? body.substring(0, langs) + dl + body.substring(langs) : body + dl;
                }
            }
        }
        return ("<DefaultLocale>" + body + "</DefaultLocale>").getBytes(StandardCharsets.UTF_8);
    }

    static int countOccurrences(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /// ServiceCalendar FromDate/ToDate from a decode-free byte scan of the UicOperatingPeriods.
    /// With ZERO periods the max/min sentinels survive the scan and are still emitted:
    /// XmlDate.from_date(datetime.max.date()) serializes as "9999-12-31" and
    /// the minimum date as "0001-01-01" — the
    /// degenerate inverted range is emitted verbatim, per side (each sentinel survives
    /// independently when its bound never updates).
    void calendarValidity() throws IOException {
        Class<?> c = store.classForName("UicOperatingPeriod");
        String from = null, to = null;
        if (c != null) {
            for (RawRow r : store.iterRawObjects(tx, c)) {
                String s = new String(codec.decompress(r.blob()), StandardCharsets.UTF_8);
                String f = between(s, "<FromDate>", "</FromDate>");
                String t = between(s, "<ToDate>", "</ToDate>");
                if (f != null && (from == null || f.compareTo(from) < 0)) from = f;
                if (t != null && (to == null || t.compareTo(to) > 0)) to = t;
            }
        }
        if (from == null) from = "9999-12-31"; // the max-date sentinel
        if (to == null) to = "0001-01-01";     // the min-date sentinel
        out.write("<FromDate>" + from.substring(0, Math.min(10, from.length())) + "</FromDate>"
                + "<ToDate>" + to.substring(0, Math.min(10, to.length())) + "</ToDate>");
    }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        int j = s.indexOf(b, i);
        return j < 0 ? null : s.substring(i + a.length(), j).trim();
    }

    /// The OTHER_REFERENCED frame: members = the reference-graph
    /// closure over \_OTHER_REFERENCED_CLASSES via fetch_all_references_by_class(skip_existing),
    /// TrainNumbers excluded (they are emitted explicitly in z-links' TimetableFrame); the whole
    /// entry is omitted when the filtered closure is empty (the builder returns null and
    /// \_entry_worker then produces no file). PeekIterator = the GeneratorTester probe.
    void otherFrame() throws IOException {
        Set<Class<?>> classes = new HashSet<>();
        for (String name : OTHER_REFERENCED_CLASSES) {
            Class<?> c = store.classForName(name);
            if (c == null) c = ModelRegistry.classForName(name); // class absent from this db: no rows/edges, harmless
            if (c != null) classes.add(c);
        }
        Iterator<Object> closure = store.fetchAllReferencesByClass(tx, classes, true);
        PeekIterator<Object> members = new PeekIterator<>(new Iterator<Object>() {
            Object next;

            @Override public boolean hasNext() {
                while (next == null && closure.hasNext()) {
                    Object o = closure.next();
                    // TrainNumbers are excluded from the closure
                    if (!(o instanceof noi.netex.model.TrainNumber)) next = o;
                }
                return next != null;
            }

            @Override public Object next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                Object o = next;
                next = null;
                return o;
            }
        });
        if (!members.hasAny()) return; // frame is None -> entry omitted
        fw.entry("shared-06-other.xml");
        out.write("<GeneralFrame id=\"OTHER_REFERENCED\" version=\"" + env.version() + "\"><members>");
        for (Object o : members) writeMember(o);
        out.write("</members></GeneralFrame>");
        fw.end();
    }

    final Map<Class<?>, Boolean> classHasOrder = new HashMap<>();

    /// One closure member: the stored fragment bytes of the decoded object's row, F2-adjusted.
    void writeMember(Object o) throws IOException {
        Class<?> clazz = o.getClass();
        String id = ((noi.netex.model.EntityStructure) o).getId();
        String version = o instanceof noi.netex.model.EntityInVersionStructure eiv ? eiv.getVersion() : null;
        long fk = store.lookupFullKey(tx, id, version, clazz);
        if (fk == -1 || NulKeyCodec.fullKeyClassIdx(fk) != store.classIdxInt(clazz)) {
            Log.info("[epip-db-to-xml] shared-06 member %s %s has no id-index row — skipped", clazz.getSimpleName(), id);
            return;
        }
        // Not FrameWriter.byFullKey: this member takes a SECOND profile step after the child-order
        // normalisation — the order="1" injection below — and one that only shared-06 wants.
        byte[] frag = FragmentOrder.normalise(clazz,
                codec.decompress(store.loadRawObject(tx, clazz, NulKeyCodec.u32le(NulKeyCodec.fullKeyLocal(fk)))));
        out.write(withDefaultOrder(clazz, frag));
        out.countObject();
    }

    /// The order="N" attribute on members is not serializer numbering — it is the object's own
    /// `order` field, a NeTEx attribute on order-bearing classes, which the loader defaults to 1
    /// when the source omits it. Fragments carry a source-present order verbatim; this injects
    /// order="1" into the root start tag when the class is order-bearing (`getOrder` exists) and
    /// the fragment lacks the attribute.
    byte[] withDefaultOrder(Class<?> clazz, byte[] frag) {
        Boolean has = classHasOrder.computeIfAbsent(clazz, c -> {
            try {
                c.getMethod("getOrder");
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
        if (!has) return frag;
        String s = new String(frag, StandardCharsets.UTF_8);
        // root start tag = up to the first '>' outside quotes
        int end = -1;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == '>' && !q) {
                end = i;
                break;
            }
        }
        if (end < 0) return frag;
        String tag = s.substring(0, end);
        if (rootTagHasOrder(tag)) return frag;
        int insert = tag.endsWith("/") ? end - 1 : end;
        return (s.substring(0, insert) + " order=\"1\"" + s.substring(insert)).getBytes(StandardCharsets.UTF_8);
    }

    /// quote-aware probe for an `order` attribute NAME in a start tag (not inside a value).
    static boolean rootTagHasOrder(String tag) {
        boolean q = false;
        for (int i = 0; i + 6 <= tag.length(); i++) {
            char ch = tag.charAt(i);
            if (ch == '"') q = !q;
            else if (!q && Character.isWhitespace(ch) && tag.startsWith("order=", i + 1)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ journey groups

    /// The partition/entry semantics, decode-free:
    ///
    /// - a pattern's shard is its position in the ServiceJourneyPattern enumeration mod the shard
    ///    count, keyed by pattern id — a duplicate id's LAST enumeration wins for both the shard
    ///    and the emitted row;
    /// - journeys are grouped by pattern ref (== the resolved target pattern's id) in scan
    ///    order; a shard emits its patterns in sorted-id order, and only patterns that HAVE
    ///    journeys, so journey-less patterns are never emitted;
    /// - journeys whose pattern ref exists but resolves to no known pattern are DROPPED
    ///    silently — only a journey with NO pattern ref at all spills to the orphan group;
    ///    those are emitted plain (no ServiceFrame) by shard 0 only, AFTER its pattern groups,
    ///    with the seq continuing.
    ///
    /// The unresolvable-vs-ref-less distinction is read off the stored indexes: a resolved
    /// SJ->pattern edge routes; an unresolved (or resolved-to-a-non-SJP-pattern-class) pattern
    /// ref drops; neither means the journey carried no pattern ref at all -> orphan.
    void journeyGroups(int shards, int journeysPerFile) throws IOException {
        Class<?> sjp = store.classForName("ServiceJourneyPattern");
        Class<?> sj = store.classForName("ServiceJourney");
        if (sjp == null && sj == null) return;
        int sjpIdx = sjp == null ? -1 : store.classIdxInt(sjp);
        int sjIdx = sj == null ? -1 : store.classIdxInt(sj);
        Set<Integer> otherPatternIdx = new HashSet<>();
        for (String n : List.of("JourneyPattern", "DeadRunJourneyPattern")) {
            Class<?> c = store.classForName(n);
            if (c != null) otherPatternIdx.add(store.classIdxInt(c));
        }

        // READ the four independent indexes, one per thread, then JOIN them below on this
        // thread. The join cannot see that the readers were split — see Scans.
        Scans scans = readParallel(sjp, sj, sjpIdx, sjIdx, otherPatternIdx);
        // Calling env.openDbi from the scan threads makes them collect each other's handles, and
        // the id-index scan then reports a fraction of the rows at a convincing wall time. The
        // counts are printed on EVERY run because they are the only thing that would notice it.
        Log.info("[epip-db-to-xml] prologue (4 readers): id-index %d rows, SJP %d, edges %d, "
                        + "unresolved %d, SJ %d, %.2f s",
                scans.idRows, scans.sjpRows, scans.edgeRows, scans.unresRows, scans.sjRows, scans.wall);

        // ---- the joins. Single-threaded, in scan order, byte-for-byte what they were before the
        // readers were split: nothing below can see which arm produced `scans`.

        // pattern shard assignment: enumeration index
        // % n_shards, keyed by id; journey-less patterns still count in the index.
        Map<String, Integer> patternShard = new HashMap<>();
        Map<String, Long> patternKey = new HashMap<>();
        for (int i = 0; i < scans.sjpKeys.size(); i++) {
            long fk = scans.sjpKeys.getLong(i);
            String pid = scans.patternId.get(fk);
            patternShard.put(pid, i % shards);
            patternKey.put(pid, fk);
        }

        // by_pattern: pattern ref -> journey keys in scan order (_entry_worker:219-222)
        Map<String, LongArrayList> byPattern = new HashMap<>();
        LongArrayList orphans = new LongArrayList();
        for (int i = 0; i < scans.sjKeys.size(); i++) {
            long fk = scans.sjKeys.getLong(i);
            long pat = scans.sjToPattern.get(fk);
            if (pat != -1) {
                byPattern.computeIfAbsent(scans.patternId.get(pat), k -> new LongArrayList(4)).add(fk);
            } else if (!scans.refDrop.contains(fk)) {
                orphans.add(fk); // ref is None -> orphan spill
            }
            // else: unresolvable pattern ref -> dropped silently
        }

        // Shards stream in sorted-NAME order so the zip's entry sequence equals the
        // entries.sort() even at >= 100 shards ("g100-…" sorts before "g99-…"); within a shard
        // the zero-padded 4-digit seq makes generation order == sorted order (a seq beyond 9999
        // would need buffering — out of scope: that is > 500M journeys at the default
        // journeys-per-file).
        List<Integer> shardOrder = new ArrayList<>();
        for (int s = 0; s < shards; s++) shardOrder.add(s);
        shardOrder.sort(java.util.Comparator.comparing(s -> String.format("g%02d-", s)));
        for (int s : shardOrder) {
            // patterns sorted by id: code points,
            List<String> pids = new ArrayList<>();
            for (String pid : byPattern.keySet()) {
                if (patternShard.get(pid) == s) pids.add(pid);
            }
            pids.sort(EpipDbToXml::compareCodePoints);
            int seq = 0;
            LongArrayList chunkPatterns = new LongArrayList();
            LongArrayList chunkJourneys = new LongArrayList();
            for (String pid : pids) {
                chunkPatterns.add(patternKey.get(pid).longValue());
                chunkJourneys.addAll(byPattern.get(pid));
                if (chunkJourneys.size() >= journeysPerFile) {
                    emitGroup(s, ++seq, chunkPatterns, chunkJourneys, sjp, sj);
                    chunkPatterns = new LongArrayList();
                    chunkJourneys = new LongArrayList();
                }
            }
            if (!chunkPatterns.isEmpty() || !chunkJourneys.isEmpty()) {
                emitGroup(s, ++seq, chunkPatterns, chunkJourneys, sjp, sj);
            }
            if (s == 0 && !orphans.isEmpty()) {
                emitGroup(s, ++seq, new LongArrayList(), orphans, sjp, sj);
            }
        }
    }

    // ------------------------------------------------------------ the prologue's four readers

    /// The prologue's four index reads and nothing else — no join, no emitted byte.
    ///
    /// The block is dominated by cold device I/O. The four reads are independent, and one thread
    /// under-uses the device because an LMDB index scan is not sequential — its leaf pages are
    /// scattered through the file — so four concurrent scans fill it. The win is latency-hiding
    /// rather than bandwidth, which is why it is the opposite verdict from the per-entry
    /// parallelism (deflate-bound, capped at ~6 %; this block contains no deflate at all). Warm, it
    /// still speeds up, so the four readers also parallelise real CPU work (decode, hash into the
    /// fastutil maps, copy out of page cache).
    ///
    /// Cost is three extra read txns and a wider simultaneous mmap working set. Over a whole export
    /// the peak RSS is set by emission, not by the prologue.
    ///
    /// Splitting the READS cannot move a byte: each field below is filled by exactly one reader, in
    /// that reader's own scan order, and every reader is internally sequential — the SJP/SJ key
    /// lists are cursor order, `sjToPattern`'s first-wins is one thread walking `iterEdges` once.
    /// The joins in `journeyGroups` then run single-threaded over those lists in index order, so
    /// the emitted order is untouched.
    ///
    /// Fields are written by four threads and read by one. Each writer owns a disjoint set of
    /// fields and the `Thread.join()` barrier in [#runAll] is the happens-before edge that
    /// publishes all of them; nothing is read before that barrier.
    static final class Scans {
        /// A — `_id_idx`: pattern full_key -> id (decode-free; ids drive sort + grouping).
        final Long2ObjectOpenHashMap<String> patternId = new Long2ObjectOpenHashMap<>();
        /// B — the ServiceJourneyPattern class map, in cursor order. The order IS the shard rule.
        final LongArrayList sjpKeys = new LongArrayList();
        /// C — journey -> pattern, off the stored edge index.
        final Long2LongOpenHashMap sjToPattern = new Long2LongOpenHashMap();
        /// C — journeys whose pattern ref exists but is unresolvable; these are dropped.
        final LongOpenHashSet refDrop = new LongOpenHashSet();
        /// D — the ServiceJourney class map, in cursor order. The order IS the grouping order.
        final LongArrayList sjKeys = new LongArrayList();

        long idRows, sjpRows, edgeRows, unresRows, sjRows;
        double wall;

        Scans() {
            sjToPattern.defaultReturnValue(-1);
        }
    }

    /// Reader A: the id index.
    void readIdIndex(Iterable<IdRow> rows, int sjpIdx, Scans s) {
        long n = 0;
        for (IdRow r : rows) {
            n++;
            if (NulKeyCodec.classTrailer(r.encodedKey()) == sjpIdx) {
                s.patternId.put(r.fullKey(), NulKeyCodec.idPart(r.encodedKey()));
            }
        }
        s.idRows = n;
    }

    /// Reader B / D: a class map's local keys, widened to full_keys in cursor order.
    void readClassKeys(Iterable<byte[]> rows, Class<?> clazz, LongArrayList keys) {
        for (byte[] localKey : rows) keys.add(store.fullKeyOf(clazz, localKey));
    }

    /// Reader C: the edge + unresolved indexes.
    ///
    /// This one takes the txn rather than a ready-made `Iterable`: `iterEdges` calls
    /// `ensureEdgeState()` eagerly, so building the iterable would run `V2Store.loadEdgeState` on
    /// the CALLER's thread. Deferring it for a read-only store is what makes this reader
    /// schedulable.
    void readEdgeState(Txn txn, int sjIdx, int sjpIdx, Set<Integer> otherPatternIdx, Scans s) {
        long edges = 0, unres = 0;
        for (long[] e : store.iterEdges(txn)) {
            edges++;
            if (NulKeyCodec.fullKeyClassIdx(e[0]) != sjIdx) continue;
            int tc = NulKeyCodec.fullKeyClassIdx(e[1]);
            if (tc == sjpIdx) s.sjToPattern.putIfAbsent(e[0], e[1]);
            else if (otherPatternIdx.contains(tc)) s.refDrop.add(e[0]); // ref present, not an SJP id
        }
        for (UnresolvedRow r : store.iterUnresolved(txn)) {
            unres++;
            if (NulKeyCodec.fullKeyClassIdx(r.referrerFullKey()) != sjIdx) continue;
            Class<?> declared = store.classForIdx(NulKeyCodec.classTrailer(r.encodedTarget()));
            String n = declared == null ? "" : declared.getSimpleName();
            if (n.equals("ServiceJourneyPattern") || n.equals("JourneyPattern") || n.equals("DeadRunJourneyPattern")) {
                s.refDrop.add(r.referrerFullKey());
            }
        }
        s.edgeRows = edges;
        s.unresRows = unres;
    }

    /// The four readers on four threads, each with its OWN read txn (the env carries MDB_NOTLS, so
    /// a read txn is not thread-bound, and 4 + 1 readers sit well inside the 126-slot table).
    ///
    /// Every `env.openDbi` happens on THIS thread, before any reader starts: `mdb_dbi_open` must be
    /// serialised, and calling it from the scan threads makes them collect each other's handles.
    /// The mechanism is structural — `iterIdIndex`/`scanObjects` resolve their `Dbi` eagerly and
    /// capture it in the returned `Iterable`, so constructing the three iterables below is what
    /// opens the handles and the threads only step cursors. Reader C opens no handle at all
    /// (`_edge_blocks`/`_unres_keys` are opened in `V2Store`'s constructor). The row counts logged
    /// by the caller are what prove it.
    Scans readParallel(Class<?> sjp, Class<?> sj, int sjpIdx, int sjIdx, Set<Integer> otherPatternIdx)
            throws IOException {
        long t0 = System.nanoTime();
        Scans s = new Scans();
        try (Txn ta = store.roTxn(); Txn tb = store.roTxn();
             Txn tc = store.roTxn(); Txn td = store.roTxn()) {
            Iterable<IdRow> idRows = sjpIdx == -1 ? List.of() : store.iterIdIndex(ta);
            Iterable<byte[]> sjpRows = sjp == null ? List.of() : store.scanObjects(tb, sjp);
            Iterable<byte[]> sjRows = sj == null ? List.of() : store.scanObjects(td, sj);
            runAll(List.of(
                    () -> readIdIndex(idRows, sjpIdx, s),
                    () -> readClassKeys(sjpRows, sjp, s.sjpKeys),
                    () -> {
                        if (sjIdx != -1) readEdgeState(tc, sjIdx, sjpIdx, otherPatternIdx, s);
                    },
                    () -> readClassKeys(sjRows, sj, s.sjKeys)));
        }
        s.sjpRows = s.sjpKeys.size();
        s.sjRows = s.sjKeys.size();
        s.wall = (System.nanoTime() - t0) / 1e9;
        return s;
    }

    /// Run each task on its own thread and join them all; the first failure aborts the export.
    ///
    /// Not promoted to the harness with the rest of the plumbing: this fan-out is safe only because
    /// of properties of the four readers above — each opens its `Dbi` on the calling thread before
    /// its task starts, reader C opens none, each writes a disjoint set of `Scans` fields — and
    /// none of those is enforceable by a generic "run these Runnables" helper.
    static void runAll(List<Runnable> tasks) throws IOException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Runnable task = tasks.get(i);
            Thread t = new Thread(() -> {
                try {
                    task.run();
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "epip-prologue-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for prologue reader " + t.getName(), e);
            }
        }
        Throwable e = failure.get();
        if (e != null) throw new IOException("prologue reader failed: " + e, e);
    }

    /// Entry names sort by CODE POINTS; String.compareTo is UTF-16 code-unit order and disagrees
    /// on supplementary-plane characters (same fixup as scratch/GraphDump.java).
    static int compareCodePoints(String a, String b) {
        int la = a.length(), lb = b.length(), n = Math.min(la, lb);
        for (int i = 0; i < n; i++) {
            char ca = a.charAt(i), cb = b.charAt(i);
            if (ca != cb) {
                int fa = ca >= 0xE000 ? ca - 0x800 : (ca >= 0xD800 ? ca + 0x2000 : ca);
                int fb = cb >= 0xE000 ? cb - 0x800 : (cb >= 0xD800 ? cb + 0x2000 : cb);
                return Integer.compare(fa, fb);
            }
        }
        return Integer.compare(la, lb);
    }

    void emitGroup(int shard, int seq, LongArrayList patterns, LongArrayList journeys,
                   Class<?> sjp, Class<?> sj) throws IOException {
        String name = String.format("g%02d-%04d.xml", shard, seq);
        String suffix = String.format("%02d_%04d", shard, seq);
        fw.entry(name);
        if (!patterns.isEmpty()) {
            env.frameOpen(out, "ServiceFrame", "EU_PI_NETWORK_" + suffix, "epip:EU_PI_NETWORK");
            out.write("<journeyPatterns>");
            fw.byFullKey(patterns, sjp);
            out.write("</journeyPatterns></ServiceFrame>");
        }
        if (!journeys.isEmpty()) {
            env.frameOpen(out, "TimetableFrame", "EU_PI_TIMETABLE_" + suffix, "epip:EU_PI_TIMETABLE");
            out.write("<vehicleJourneys>");
            fw.byFullKey(journeys, sj);
            out.write("</vehicleJourneys></TimetableFrame>");
        }
        fw.end();
    }

    // ------------------------------------------------------------ z-links

    void zLinks() throws IOException {
        boolean anyI = fw.hasRows("ServiceJourneyInterchange");
        boolean anyT = fw.hasRows("TrainNumber");
        if (!anyI && !anyT) return;
        fw.entry("z-links.xml");
        env.frameOpen(out, "TimetableFrame", "EU_PI_TIMETABLE_LINKS", "epip:EU_PI_TIMETABLE");
        // Container order = TimetableFrameGroup's xsd:sequence of groups
        // (netex_part_2/part2_frames/netex_timetableFrame_version.xsd:39-49):
        //   TimetablePropertiesGroup -> TimetableDefaultsGroup -> TimeDemandTypeInFrameGroup
        //   -> TimetableJourneyInFrameGroup (trainNumbers) -> NoticesInFrameGroup
        //   -> InterchangeInFrameGroup     (journeyInterchanges)
        // so trainNumbers MUST precede journeyInterchanges, and the EPIP profile XSD agrees
        // (_content_NeTEx_EPIP.xsd:3337/:3339).
        if (anyT) {
            out.write("<trainNumbers>");
            fw.all("TrainNumber");
            out.write("</trainNumbers>");
        }
        if (anyI) {
            out.write("<journeyInterchanges>");
            fw.all("ServiceJourneyInterchange");
            out.write("</journeyInterchanges>");
        }
        out.write("</TimetableFrame>");
        fw.end();
    }

}
