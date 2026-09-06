// Streams every object of a store into an EPIP-shaped NeTEx zip:
//
//   shared-01-resource.xml   ResourceFrame COMMON (dataSources, responsibilitySets, typesOfValue,
//                            organisations, vehicleTypes, zones — the XSD sequence)
//   shared-02-sites.xml      SiteFrame EU_PI_STOP (topographicPlaces, stopPlaces)
//   shared-03-network.xml    ServiceFrame EU_PI_NETWORK (Network, directions, lines, ...,
//                            stopAssignments — no routes, no journey patterns, no serviceLinks)
//   shared-04-links.xml      TimetableFrame EU_PI_TIMETABLE_LINKS (trainNumbers); omitted when
//                            empty. OTP does not map TrainNumbers at all, but every group
//                            references them, so they cannot live in one group's file.
//   shared-05-calendar.xml   ServiceCalendarFrame EU_PI_CALENDAR, UicOperatingPeriods only
//   shared-06-other.xml      GeneralFrame OTHER_REFERENCED — the reference-graph closure over the
//                            29 listed classes; TrainNumbers excluded; omitted when empty
//   gSS-shared.xml           ServiceFrame(serviceLinks) + TimetableFrame(journeyInterchanges) —
//                            the SHARD's shared group file; omitted when the shard has neither
//   gSS-NNNN.xml             ServiceFrame(journeyPatterns) + TimetableFrame(their journeys)
//
// The two-level split is the consumer's memory model, not a taste. OTP's
// NetexBundle.loadFileEntries walks three levels: the shared files, then per group the SHARED
// GROUP files, then the group's individual files. A shared-group file's entities stay indexed for
// the whole group; an individual file's index is popped the moment that file is mapped. So a
// shard's ServiceLinks — referenced from every one of its files, and resolved through
// AbstractHierarchicalMap.lookup's parent fallback — belong at the group level, and the journeys,
// which are 84 % of the corpus, belong at the file level where they are dropped one file at a
// time. Its interchanges go there too: NetexMapper collects them at every level except 0 and maps
// them at the GROUP pop, so the shared entry is where they read most naturally.
//
// A journey pattern is the one thing that CANNOT move up: NetexMapper.mapTripPatterns iterates
// getJourneyPatternsById().localValues() and TripPatternMapper indexes journeys from
// serviceJourneyById.localValues(), so a pattern and all of its journeys must be in ONE file or
// the journeys are silently dropped. Patterns are also emitted exactly once already, so moving
// them would save no bytes and would only make a shard's worth of them resident for the whole
// group.
//
// Entry order is g* < shared-*, and within a shard the numbered files precede gSS-shared.xml
// ('0' is 0x30, 's' is 0x73). Entry metadata is pinned to the 1980 DOS epoch by
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
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
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
    //
    // NO "serviceLinks" either: they are 77 % of this frame's bytes and every one of them belongs
    // to the journey patterns that reference it, so they ride along in the group files instead —
    // see journeyGroups. A link no pattern references is not emitted at all.
    static final Map<String, List<String>> NETWORK = orderedMap(
            "directions", "Direction",
            "lines", "Line,FlexibleLine",
            "destinationDisplays", "DestinationDisplay",
            "scheduledStopPoints", "ScheduledStopPoint",
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
    // Resolved once in journeyGroups and read by emitGroup; null when this store has no such
    // class map, which is also when the corresponding key lists are always empty.
    Class<?> serviceLinkClass;
    Class<?> interchangeClass;

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
                    //
                    // journeys-per-file is a BOUND, not a target: nothing but a single pattern
                    // bigger than it can overshoot it, because the shard's ServiceLinks live in
                    // gSS-shared.xml and no longer tie a file to whole components.
                    int shards = a.intFlag("--shards", 8);
                    int journeysPerFile = a.intFlag("--journeys-per-file", 12_500);
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
        // sorted names put the g* journey groups first, then shared-*. The groups are streamed in
        // that sorted order by construction (see journeyGroups: shards emitted in sorted-name
        // order, seqs zero-padded, and a shard's gSS-shared.xml written after its numbered files
        // because '0' < 's'), so the zip is written g* -> shared-* without buffering.
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

        trainNumbers();

        fw.entry("shared-05-calendar.xml");
        env.frameOpen(out, "ServiceCalendarFrame", "EU_PI_CALENDAR", "epip:EU_PI_CALENDAR");
        out.write("<ServiceCalendar id=\"OPENOV:ServiceCalendar:ServiceCalendar\" version=\""
                + env.version() + "\">");
        calendarValidity();
        fw.containers(CALENDAR);
        out.write("</ServiceCalendar></ServiceCalendarFrame>");
        fw.end();

        otherFrame();

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
                    // LocaleStructure's XSD sequence: DefaultLanguage BEFORE Languages
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
    /// "9999-12-31" for the from-bound and "0001-01-01" for the to-bound. The degenerate inverted
    /// range is emitted as-is, per side — each sentinel survives independently when its own bound
    /// never updates.
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

    /// The OTHER_REFERENCED frame: members are the reference-graph closure over
    /// [#OTHER_REFERENCED_CLASSES], skipping what other frames already emit, with TrainNumbers
    /// excluded because shared-04's TimetableFrame emits them explicitly. The whole entry is omitted
    /// when the filtered closure is empty — [PeekIterator] answers that without draining the
    /// stream, so an empty closure costs no entry rather than an empty one.
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
        if (!members.hasAny()) return; // empty closure -> no entry at all
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
    /// `order` field, a NeTEx attribute on order-bearing classes, and the loader defaults it to 1
    /// when the feed omits it. Fragments carry a feed-present order verbatim; this injects
    /// order="1" into the root start tag when the class is order-bearing (a `getOrder` accessor
    /// exists) and the fragment lacks the attribute.
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
    /// - a pattern's shard is the shard of its LINE, and a line's shard is the shard of its
    ///    INTERCHANGE COMPONENT — the set of lines transitively joined by a ServiceJourneyInterchange.
    ///    Components are bin-packed largest-first by journey count, so a component is never split
    ///    across shards and the shards still come out even. Keyed by pattern id; a duplicate id's
    ///    LAST enumeration wins for both the shard and the emitted row;
    /// - journeys are grouped by pattern ref (== the resolved target pattern's id) in scan
    ///    order; a shard emits its patterns sorted by (link component, line, id), and only
    ///    patterns that HAVE journeys, so journey-less patterns are never emitted;
    /// - journeys whose pattern ref exists but resolves to no known pattern are DROPPED
    ///    silently — only a journey with NO pattern ref at all spills to the orphan group;
    ///    those are emitted plain (no ServiceFrame) by shard 0 only, AFTER its pattern groups,
    ///    with the seq continuing;
    /// - a shard's ServiceLinks and ServiceJourneyInterchanges do not ride in the numbered files
    ///    at all: they go to gSS-shared.xml, written LAST for the shard.
    ///
    /// The unresolvable-vs-ref-less distinction is read off the stored indexes: a resolved
    /// SJ->pattern edge routes; an unresolved (or resolved-to-a-non-SJP-pattern-class) pattern
    /// ref drops; neither means the journey carried no pattern ref at all -> orphan.
    ///
    /// WHY THE ORDER IS NOT ARBITRARY. A shard's gSS-shared.xml carries the union of its patterns'
    /// ServiceLinks, so a link used from two SHARDS is written twice. Under the old
    /// `index % shards` rule every line's patterns landed in a DIFFERENT shard, which measured
    /// 1.82x duplication on the production store — 65k links written eight times, once per shard.
    /// Keeping a whole link component in one shard collapses that to exactly one copy each, which
    /// is why the union-find still joins lines that share a link even though the FILE-level
    /// argument for it is gone. Raising the shard count under the old rule made it worse (2.6x at
    /// 20 shards), which is why the rule and not the knob is what changed.
    ///
    /// WHY THE COMPONENT AND NOT JUST THE LINE. OTP resolves an interchange's two journeys at the
    /// END OF ITS GROUP against the trips mapped so far (NetexMapper.performGroupMapping, at the
    /// pop from LEVEL_GROUP). A journey in a later group is not mapped yet, so the transfer would
    /// be dropped with an `ObjectNotFound` and nothing else. Both sides therefore have to be in
    /// the same group, and with `groupFilePattern` keyed on the shard that means the same shard.
    /// It costs nothing: the largest component is 2.6 % of the journey corpus and the bin-packing
    /// comes out even. WHERE in the shard the interchange sits does not matter — gSS-shared.xml is
    /// mapped first, and NetexMapper collects interchanges at every level except 0 — so it is the
    /// shared entry that holds them and no numbered file has to be kept back for them.
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
        serviceLinkClass = store.classForName("ServiceLink");
        interchangeClass = store.classForName("ServiceJourneyInterchange");
        int slIdx = serviceLinkClass == null ? -1 : store.classIdxInt(serviceLinkClass);
        int sjiIdx = interchangeClass == null ? -1 : store.classIdxInt(interchangeClass);
        // A pattern reaches its line through RouteView/LineRef, and the ref is declared on the
        // Line/FlexibleLine substitution pair, so both class maps count as "the line".
        Set<Integer> lineIdx = new HashSet<>();
        for (String n : List.of("Line", "FlexibleLine")) {
            Class<?> c = store.classForName(n);
            if (c != null) lineIdx.add(store.classIdxInt(c));
        }

        // READ the four independent indexes, one per thread, then JOIN them below on this
        // thread. The join cannot see that the readers were split — see Scans.
        Scans scans = readParallel(sjp, sj, sjpIdx, sjIdx, otherPatternIdx, lineIdx, slIdx, sjiIdx);
        // Calling env.openDbi from the scan threads makes them collect each other's handles, and
        // the id-index scan then reports a fraction of the rows at a convincing wall time. The
        // counts are printed on EVERY run because they are the only thing that would notice it.
        Log.info("[epip-db-to-xml] prologue (4 readers): id-index %d rows, SJP %d, edges %d, "
                        + "unresolved %d, SJ %d, %.2f s",
                scans.idRows, scans.sjpRows, scans.edgeRows, scans.unresRows, scans.sjRows, scans.wall);
        Log.info("[epip-db-to-xml] partition inputs: %d patterns with a line, %d pattern->link "
                        + "edges, %d interchanges",
                scans.patternLine.size(), scans.linkEdges, scans.interchangeJourneys.size());

        // ---- the joins. Single-threaded, in scan order: nothing below can see which arm
        // produced `scans`.

        Map<String, Long> patternKey = new HashMap<>();
        for (int i = 0; i < scans.sjpKeys.size(); i++) {
            long fk = scans.sjpKeys.getLong(i);
            patternKey.put(scans.patternId.get(fk), fk); // last enumeration of a duplicate id wins
        }

        // Pattern ref -> journey keys, in scan order.
        Map<String, LongArrayList> byPattern = new HashMap<>();
        LongArrayList orphans = new LongArrayList();
        for (int i = 0; i < scans.sjKeys.size(); i++) {
            long fk = scans.sjKeys.getLong(i);
            long pat = scans.sjToPattern.get(fk);
            if (pat != -1) {
                byPattern.computeIfAbsent(scans.patternId.get(pat), k -> new LongArrayList(4)).add(fk);
            } else if (!scans.refDrop.contains(fk)) {
                orphans.add(fk); // no pattern ref at all -> orphan spill
            }
            // else: unresolvable pattern ref -> dropped silently
        }

        // ---- the line partition: ONE union-find over LINE full_keys, joined by BOTH relations.
        //
        // An interchange edge is a CORRECTNESS constraint (see the method doc). A shared-ServiceLink
        // edge is an efficiency one: a link is written once per group file that uses it, so lines
        // sharing a link want the same shard. Keeping them in separate partitions does not work —
        // shard membership would follow the interchange components only, and link-sharing lines
        // would scatter across all shards. Measured on the production store, that is the difference
        // between 1.2531x and 1.00442x duplication.
        //
        // Merging them only makes components COARSER, which cannot violate the interchange
        // constraint. It costs balance nothing that matters: the largest combined component is
        // ~3 % of the journey corpus against a 1/8 shard.
        //
        // Nothing survives: 1,310,468 links emitted for 1,310,468 distinct, exactly one copy each.
        // Getting there needed the chunker to respect component boundaries as well — see the
        // bin-packing below. While it cut on a running journey count instead, 5,786 links were
        // written twice because the cut fell inside a component; none of them ever crossed a
        // SHARD, which is what said the partition itself was already exact and the remainder was
        // chunking.
        Union lines = new Union();
        for (LongArrayList js : scans.interchangeJourneys.values()) {
            long first = -1;
            for (long jk : js) {
                long line = lineOfJourney(scans, jk);
                if (line == -1) continue;
                if (first == -1) first = line;
                else lines.union(first, line);
            }
        }
        Long2LongOpenHashMap firstLineOfLink = new Long2LongOpenHashMap();
        firstLineOfLink.defaultReturnValue(-1);
        for (Long2ObjectMap.Entry<LongArrayList> e : scans.patternLinks.long2ObjectEntrySet()) {
            long line = scans.patternLine.get(e.getLongKey());
            if (line == -1) continue;
            for (long link : e.getValue()) {
                long prev = firstLineOfLink.putIfAbsent(link, line);
                if (prev != -1) lines.union(prev, line);
            }
        }

        // ---- shard assignment: bin-pack whole components by journey count.
        Long2LongOpenHashMap compJourneys = new Long2LongOpenHashMap();
        for (Map.Entry<String, LongArrayList> e : byPattern.entrySet()) {
            long comp = lines.find(lineOfPattern(scans, patternKey.get(e.getKey())));
            compJourneys.addTo(comp, e.getValue().size());
        }
        List<Long> comps = new ArrayList<>(compJourneys.keySet());
        // Largest first, ties broken by the component key: the packing must not depend on hash order.
        comps.sort((a, b) -> {
            int c = Long.compare(compJourneys.get((long) b), compJourneys.get((long) a));
            return c != 0 ? c : Long.compareUnsigned(a, b);
        });
        long[] binJourneys = new long[shards];
        Long2LongOpenHashMap compShard = new Long2LongOpenHashMap();
        for (long comp : comps) {
            int bin = 0;
            for (int i = 1; i < shards; i++) {
                if (binJourneys[i] < binJourneys[bin]) bin = i;
            }
            compShard.put(comp, bin);
            binJourneys[bin] += compJourneys.get(comp);
        }
        long lo = Long.MAX_VALUE, hi = 0;
        for (long b : binJourneys) {
            lo = Math.min(lo, b);
            hi = Math.max(hi, b);
        }
        Log.info("[epip-db-to-xml] %d line components over %d shards: largest %,d journeys, "
                        + "shard spread %,d..%,d",
                comps.size(), shards, comps.isEmpty() ? 0 : compJourneys.get((long) comps.get(0)), lo, hi);

        // Interchanges ride with their component's shard. Enumerated from the CLASS MAP, not from
        // the edge index, so an interchange whose journey refs are all unresolved still gets
        // emitted: it goes to shard 0, exactly as unplaceable rows did when they all sat in
        // z-links. Every stored row is placed somewhere, which is what makes the tally below a
        // real check rather than a restatement.
        List<LongArrayList> shardInterchanges = new ArrayList<>();
        for (int s = 0; s < shards; s++) shardInterchanges.add(new LongArrayList());
        long interchangeRows = 0;
        if (interchangeClass != null) {
            for (byte[] localKey : store.scanObjects(tx, interchangeClass)) {
                long fk = store.fullKeyOf(interchangeClass, localKey);
                interchangeRows++;
                int s = 0;
                LongArrayList js = scans.interchangeJourneys.get(fk);
                if (js != null) {
                    for (long jk : js) {
                        long line = lineOfJourney(scans, jk);
                        if (line != -1) {
                            s = (int) compShard.get(lines.find(line));
                            break;
                        }
                    }
                }
                shardInterchanges.get(s).add(fk);
            }
        }

        // Shards stream in sorted-NAME order so the zip's entry sequence equals the
        // entries.sort() even at >= 100 shards ("g100-…" sorts before "g99-…"); within a shard
        // the zero-padded 4-digit seq makes generation order == sorted order (a seq beyond 9999
        // would need buffering — out of scope: that is 125M journeys per shard at the default
        // journeys-per-file). gSS-shared.xml is written after the shard's numbered files and still
        // sorts after them, because '0' is 0x30 and 's' is 0x73.
        List<Integer> shardOrder = new ArrayList<>();
        for (int s = 0; s < shards; s++) shardOrder.add(s);
        shardOrder.sort(java.util.Comparator.comparing(s -> String.format("g%02d-", s)));
        long emittedInterchanges = 0;
        for (int s : shardOrder) {
            // The sort keys are resolved ONCE per pattern, not inside the comparator: `find` walks
            // and rewrites the union-find, and the id -> key lookup is a string hash, neither of
            // which belongs on a path taken n log n times.
            record Pat(String id, long comp, long line) {}
            List<Pat> pats = new ArrayList<>();
            for (String pid : byPattern.keySet()) {
                long line = lineOfPattern(scans, patternKey.get(pid));
                if (compShard.get(lines.find(line)) != s) continue;
                pats.add(new Pat(pid, lines.find(line), line));
            }
            // Component, then line, then id — each level a total order, so the result is one too,
            // and a component's lines stay adjacent, so a file is a contiguous run of one or two
            // components rather than a scatter. It no longer has to be (the links have moved up a
            // level); it keeps the emitted files readable and their patterns related.
            pats.sort((a, b) -> {
                int c = Long.compareUnsigned(a.comp(), b.comp());
                if (c != 0) return c;
                c = Long.compareUnsigned(a.line(), b.line());
                return c != 0 ? c : compareCodePoints(a.id(), b.id());
            });
            // The shard's own ServiceLinks: the union over ALL its patterns, resolved once, before
            // any chunk is cut. Only a LongArrayList of keys is held while the numbered files
            // stream — the fragments themselves are read in emitSharedGroup.
            LongArrayList shardPatterns = new LongArrayList(pats.size());
            for (Pat p : pats) shardPatterns.add(patternKey.get(p.id()).longValue());
            LongArrayList shardLinks = linksOf(scans, shardPatterns);

            int seq = 0;
            List<LongArrayList[]> chunks = new ArrayList<>();
            // The cut is a running total over the sorted pattern list, and journeysPerFile is a
            // BOUND. It could not be while a file carried its patterns' ServiceLinks: a link used
            // from two files was written twice, so a file had to hold whole components, and a
            // component bigger than the budget formed a file by itself (the largest measured
            // 130,429 journeys / 1412 MB). With the links in gSS-shared.xml nothing ties a file to
            // a component boundary, and the only thing that can still overshoot is a SINGLE
            // pattern with more journeys than the bound — which cannot be split, because
            // TripPatternMapper indexes a pattern's journeys from one file's localValues(). The
            // corpus mean is 8.3 journeys per pattern, so that is a guard, not a live path.
            //
            // The budget is `bound`, not journeysPerFile: dividing the shard evenly over the file
            // count journeysPerFile implies keeps every file the same size instead of leaving a
            // runt at the end, and bound <= journeysPerFile so it is still a bound.
            long shardJourneys = 0;
            for (Pat p : pats) shardJourneys += byPattern.get(p.id()).size();
            int files = (int) Math.max(1, (shardJourneys + journeysPerFile - 1) / journeysPerFile);
            long bound = Math.max(1, (shardJourneys + files - 1) / files);
            LongArrayList chunkPatterns = new LongArrayList();
            LongArrayList chunkJourneys = new LongArrayList();
            for (Pat p : pats) { // pats is sorted, so the cut is deterministic and contiguous
                LongArrayList js = byPattern.get(p.id());
                if (!chunkJourneys.isEmpty() && chunkJourneys.size() + js.size() > bound) {
                    chunks.add(new LongArrayList[] {chunkPatterns, chunkJourneys});
                    chunkPatterns = new LongArrayList();
                    chunkJourneys = new LongArrayList();
                }
                chunkPatterns.add(patternKey.get(p.id()).longValue());
                chunkJourneys.addAll(js);
            }
            if (!chunkJourneys.isEmpty()) {
                chunks.add(new LongArrayList[] {chunkPatterns, chunkJourneys});
            }
            // Orphans carry no pattern, so nothing constrains them but the bound — slice them the
            // same way, or they would be the one hole left in it.
            if (s == 0) {
                for (int i = 0; i < orphans.size(); i += journeysPerFile) {
                    int end = Math.min(i + journeysPerFile, orphans.size());
                    LongArrayList slice = new LongArrayList(end - i);
                    for (int j = i; j < end; j++) slice.add(orphans.getLong(j));
                    chunks.add(new LongArrayList[] {new LongArrayList(), slice});
                }
            }
            for (LongArrayList[] chunk : chunks) {
                emitGroup(s, ++seq, chunk[0], chunk[1], sjp, sj);
            }
            // LAST for the shard: 'g07-0011.xml' < 'g07-shared.xml' < 'g08-0001.xml', so
            // generation order is still the name sort and nothing is buffered.
            LongArrayList shardIcs = shardInterchanges.get(s);
            emittedInterchanges += shardIcs.size();
            emitSharedGroup(s, shardLinks, shardIcs);
            Log.info("[epip-db-to-xml] shard %d: %,d patterns, %,d journeys over %d files, "
                            + "%,d links, %,d interchanges",
                    s, pats.size(), shardJourneys, chunks.size(), shardLinks.size(), shardIcs.size());
        }
        // Every stored interchange must have been placed. A shortfall is a transfer that silently
        // stopped existing, which no downstream count would attribute back to here.
        if (emittedInterchanges != interchangeRows) {
            throw new IOException("journey groups emitted " + emittedInterchanges + " of "
                    + interchangeRows + " ServiceJourneyInterchanges");
        }
    }

    /// A pattern's line full_key, or -1 — the pattern id may name no row (`patternKey.get` null)
    /// and the pattern may carry no line reference at all. Both collapse to the same "no line"
    /// bucket, which sorts last and forms one component of its own.
    ///
    /// The second case is a FEED property, not a broken reference: some Austrian patterns arrive
    /// already formed, carrying the line only as text (`Name`/`ShortName`/`PrivateCode`) with no
    /// RouteView, LineRef or RouteRef. EPIP normally has one because the toolkit synthesises
    /// RouteView/LineRef from the JOURNEY's LineRef when it builds a pattern from calls. On the
    /// production store that is 44,192 of 565,807 patterns.
    ///
    /// NONE of them is emitted, so the sentinel bucket is empty in practice and the "-1" branches
    /// below are a guard rather than a live path. 44,103 have no journey at all, and a pattern with
    /// no journeys is never emitted. The remaining 89 ARE the target of a ServiceJourney edge, but
    /// [Scans#sjToPattern] is first-edge-wins, and none of the 89 is any journey's first pattern
    /// edge — so no journey is ever grouped under them either. Measured: `byPattern` holds 514,243
    /// patterns and exactly 0 of them lack a line.
    ///
    /// Two fallbacks were measured and both rejected. Following the pattern's RouteRef to the
    /// Route's own LineRef resolves none of the 89 — every Route-only pattern in the store is
    /// journey-less. Taking the line from one of the pattern's journeys resolves all 89 in
    /// full_key space, but fires 0 times here for the reason above, and costs a 3.5M-entry
    /// journey -> line map in the hot edge loop to do it.
    static long lineOfPattern(Scans scans, Long patternFullKey) {
        return patternFullKey == null ? -1 : scans.patternLine.get(patternFullKey.longValue());
    }

    /// A journey's line, through its pattern. -1 when either hop is missing.
    static long lineOfJourney(Scans scans, long journeyFullKey) {
        long pat = scans.sjToPattern.get(journeyFullKey);
        return pat == -1 ? -1 : scans.patternLine.get(pat);
    }

    /// The ServiceLinks a SHARD's patterns reference, deduplicated, in ascending full_key order.
    ///
    /// Ascending key order rather than pattern order: the key IS the store's scan order, so the
    /// emitted sequence is the one `fw.containers` would have produced from `shared-03`, and two
    /// runs over the same store emit the same bytes.
    static LongArrayList linksOf(Scans scans, LongArrayList patterns) {
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long pk : patterns) {
            LongArrayList links = scans.patternLinks.get(pk);
            if (links != null) seen.addAll(links);
        }
        LongArrayList out = new LongArrayList(seen);
        // The cast picks fastutil's primitive overload: `sort(LongComparator)` and the inherited
        // `sort(Comparator<? super Long>)` are both applicable to an implicitly typed lambda.
        out.sort((LongComparator) (x, y) -> Long.compareUnsigned(x, y));
        return out;
    }

    /// Union-find over line full_keys, with the component named by its SMALLEST member.
    ///
    /// The root rule is by VALUE, not by rank or by union order: two runs over the same store see
    /// the same edges but not necessarily in an order that a rank rule would resolve identically,
    /// and the root leaks into the emitted order through the sort. Path compression is safe — it
    /// changes the path, never the root.
    static final class Union {
        private final Long2LongOpenHashMap parent = new Long2LongOpenHashMap();

        Union() {
            parent.defaultReturnValue(Long.MIN_VALUE);
        }

        long find(long x) {
            long p = parent.get(x);
            if (p == Long.MIN_VALUE) return x; // singleton: never unioned
            long root = p;
            while (true) {
                long up = parent.get(root);
                if (up == Long.MIN_VALUE) break;
                root = up;
            }
            for (long cur = x; cur != root; ) {
                long next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        void union(long a, long b) {
            long ra = find(a), rb = find(b);
            if (ra == rb) return;
            if (Long.compareUnsigned(ra, rb) < 0) parent.put(rb, ra);
            else parent.put(ra, rb);
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
        /// C — pattern -> line, off the same edge scan. The partition key.
        final Long2LongOpenHashMap patternLine = new Long2LongOpenHashMap();
        /// C — pattern -> the ServiceLinks it references. The pattern's own row owns these edges:
        /// the ref lives on a StopPointInJourneyPattern, which is an EMBEDDED entity with no row
        /// of its own, and the insert walk attributes a nested ref to the containing row.
        final Long2ObjectOpenHashMap<LongArrayList> patternLinks = new Long2ObjectOpenHashMap<>();
        /// C — interchange -> the journeys it names. Two, unless a ref was unresolvable.
        final Long2ObjectOpenHashMap<LongArrayList> interchangeJourneys = new Long2ObjectOpenHashMap<>();
        /// D — the ServiceJourney class map, in cursor order. The order IS the grouping order.
        final LongArrayList sjKeys = new LongArrayList();

        long idRows, sjpRows, edgeRows, unresRows, sjRows, linkEdges;
        double wall;

        Scans() {
            sjToPattern.defaultReturnValue(-1);
            patternLine.defaultReturnValue(-1);
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
    void readEdgeState(Txn txn, int sjIdx, int sjpIdx, Set<Integer> otherPatternIdx,
                       Set<Integer> lineIdx, int slIdx, int sjiIdx, Scans s) {
        long edges = 0, unres = 0;
        for (long[] e : store.iterEdges(txn)) {
            edges++;
            int sc = NulKeyCodec.fullKeyClassIdx(e[0]);
            int tc = NulKeyCodec.fullKeyClassIdx(e[1]);
            if (sc == sjIdx) {
                if (tc == sjpIdx) s.sjToPattern.putIfAbsent(e[0], e[1]);
                else if (otherPatternIdx.contains(tc)) s.refDrop.add(e[0]); // ref present, not an SJP id
            } else if (sc == sjpIdx) {
                // A pattern names ONE line; first edge wins, as with the journey's pattern ref.
                if (lineIdx.contains(tc)) s.patternLine.putIfAbsent(e[0], e[1]);
                else if (tc == slIdx) {
                    s.patternLinks.computeIfAbsent(e[0], (long k) -> new LongArrayList(4)).add(e[1]);
                    s.linkEdges++;
                }
            } else if (sc == sjiIdx && tc == sjIdx) {
                s.interchangeJourneys.computeIfAbsent(e[0], (long k) -> new LongArrayList(2)).add(e[1]);
            }
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
    Scans readParallel(Class<?> sjp, Class<?> sj, int sjpIdx, int sjIdx, Set<Integer> otherPatternIdx,
                       Set<Integer> lineIdx, int slIdx, int sjiIdx) throws IOException {
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
                    () -> readEdgeState(tc, sjIdx, sjpIdx, otherPatternIdx, lineIdx, slIdx, sjiIdx, s),
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

    /// One numbered group file: the patterns and their journeys, nothing else. Its shard's
    /// ServiceLinks resolve out of gSS-shared.xml, one index level up.
    void emitGroup(int shard, int seq, LongArrayList patterns, LongArrayList journeys,
                   Class<?> sjp, Class<?> sj) throws IOException {
        String name = String.format("g%02d-%04d.xml", shard, seq);
        String suffix = String.format("%02d_%04d", shard, seq);
        fw.entry(name);
        if (!patterns.isEmpty()) {
            env.frameOpen(out, "ServiceFrame", "EU_PI_NETWORK_" + suffix, "epip:EU_PI_NETWORK");
            out.write("<journeyPatterns>");
            fw.byFullKey(patterns, sjp);
            out.write("</journeyPatterns>");
            out.write("</ServiceFrame>");
        }
        if (!journeys.isEmpty()) {
            env.frameOpen(out, "TimetableFrame", "EU_PI_TIMETABLE_" + suffix, "epip:EU_PI_TIMETABLE");
            out.write("<vehicleJourneys>");
            fw.byFullKey(journeys, sj);
            out.write("</vehicleJourneys>");
            out.write("</TimetableFrame>");
        }
        fw.end();
    }

    /// One shard's SHARED GROUP file: the ServiceLinks its patterns reference and the
    /// ServiceJourneyInterchanges its journeys take part in. OTP loads this before the shard's
    /// numbered files and keeps its index for the whole group, so a link resolves from any of them
    /// (`serviceLinkById.lookup` falls back to the parent index) and an interchange is still mapped
    /// at the group pop, by which point every one of the shard's journeys is a mapped trip.
    ///
    /// Skipped entirely when the shard has neither -- an empty shard, or one whose patterns
    /// reference no link. `GroupEntries.sharedEntries()` is then just empty and OTP loads the
    /// group's numbered files as it always did; emitting the entry anyway would put a
    /// PublicationDelivery with no frame in it into the zip.
    ///
    /// Container order inside each frame is the XSD sequence: ServiceFrame's `serviceLinks` is
    /// ServiceInFrameGroup, TimetableFrame's `journeyInterchanges` is InterchangeInFrameGroup, last
    /// of that frame's groups.
    void emitSharedGroup(int shard, LongArrayList links, LongArrayList interchanges)
            throws IOException {
        if (links.isEmpty() && interchanges.isEmpty()) return;
        String suffix = String.format("%02d_shared", shard);
        fw.entry(String.format("g%02d-shared.xml", shard));
        if (!links.isEmpty()) {
            env.frameOpen(out, "ServiceFrame", "EU_PI_NETWORK_" + suffix, "epip:EU_PI_NETWORK");
            out.write("<serviceLinks>");
            fw.byFullKey(links, serviceLinkClass);
            out.write("</serviceLinks>");
            out.write("</ServiceFrame>");
        }
        if (!interchanges.isEmpty()) {
            env.frameOpen(out, "TimetableFrame", "EU_PI_TIMETABLE_" + suffix, "epip:EU_PI_TIMETABLE");
            out.write("<journeyInterchanges>");
            fw.byFullKey(interchanges, interchangeClass);
            out.write("</journeyInterchanges>");
            out.write("</TimetableFrame>");
        }
        fw.end();
    }

    // ------------------------------------------------------------ shared train numbers

    /// TrainNumbers, and nothing else — the interchanges that used to share this entry now sit in
    /// their shard's gSS-shared.xml.
    ///
    /// GLOBALLY shared rather than per-shard, because journeys in EVERY group reference them and a
    /// group's index is dropped when that group is done. OTP does not map TrainNumbers at all
    /// (`TimeTableFrameParser` only warns about them), so this entry costs the parent index some
    /// bytes and buys the artefact its completeness.
    void trainNumbers() throws IOException {
        if (!fw.hasRows("TrainNumber")) return;
        fw.entry("shared-04-links.xml");
        env.frameOpen(out, "TimetableFrame", "EU_PI_TIMETABLE_LINKS", "epip:EU_PI_TIMETABLE");
        // Container order = TimetableFrameGroup's xsd:sequence of groups
        // (netex_part_2/part2_frames/netex_timetableFrame_version.xsd:39-49):
        //   TimetablePropertiesGroup -> TimetableDefaultsGroup -> TimeDemandTypeInFrameGroup
        //   -> TimetableJourneyInFrameGroup (vehicleJourneys, then trainNumbers)
        //   -> NoticesInFrameGroup -> InterchangeInFrameGroup (journeyInterchanges)
        // which is also the order emitGroup writes its two containers in.
        out.write("<trainNumbers>");
        fw.all("TrainNumber");
        out.write("</trainNumbers>");
        out.write("</TimetableFrame>");
        fw.end();
    }

}
