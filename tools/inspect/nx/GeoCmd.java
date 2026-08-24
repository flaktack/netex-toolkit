package inspect.nx;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import toolkit.codec.FragmentCodec;
import toolkit.model.GeoClasses;
import toolkit.model.ModelRegistry;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// `nx geo` — geometry extract and self-contained map.
///
/// ## Why a map
///
/// Geometry is the one defect class where a census is weak. A `ServiceLink` that keeps only its
/// first link's geometry moves no element count and no attribute count: it shortens a polyline,
/// which is visible on a map and invisible in a TSV.
///
/// The second customer is routing fidelity: how far the true path deviates from the straight line
/// a consumer draws between two stops.
///
/// ## What it does
///
/// - `census` — the scale statement. Per (class, fragment path): how many objects carry geometry,
///    of what kind, the point-count histogram, the `srsName` buckets, and for lines the
///    straight-line delta and endpoint-gap distributions. Run it BEFORE extracting: it says what a
///    scope will cost.
/// - `extract` — GeoJSON, scoped by class / bbox / radius / metric threshold. GeoJSON because QGIS
///    and geojson.io already read it; the HTML is a convenience, not a lock-in.
/// - `html` — one self-contained file. No network, no basemap, no tiles, no CDN: it opens from
///    disk with the machine offline.
/// - `compare` — two stores joined on (class, id, version), with a displacement histogram, and
///    with a tolerance: reprojection moves coordinates by ~1e-6° while a truncated link moves
///    kilometres, and those are not one finding.
///
/// ## Coordinates come from the stored fragment bytes, not from the bean
///
/// What will be emitted, not what the binding reconstructs. So a `<gml:posList>None</gml:posList>`
/// is visible here — the bean binds that to an empty list — and gets its own bucket rather than
/// counting as "no geometry".
///
/// ## `srsName` buckets
///
/// Every geometry carries its declared `srsName` verbatim, bucketed three ways:
///
/// - `declared` — a name that resolves to a CRS.
/// - `absent` — none in scope. WGS84 is assumed, as the rest of the stack assumes it, and the map
///    says so rather than implying the source did.
/// - `not-a-CRS` — present and unresolvable. Feeds put timezones where a CRS belongs, the loader
///    stamping the frame default verbatim with no validation. A map that assumed WGS84 would render
///    those and show nothing; the bucket is the finding.
///
/// Resolution is not re-implemented: the same lexical rules feed the same `CRSFactory` the
/// reprojection uses, so a `geo` verdict and a transform's CRS error cannot disagree. A resolvable
/// non-WGS84 CRS is reprojected into WGS84 for output, GeoJSON being defined in WGS84, and flagged
/// `reprojected`; `absent` and `not-a-CRS` pass through flagged `assumed_wgs84`.
///
/// ## Scale
///
/// This is a drill-down from an object, not a whole-corpus viewer: `extract` has a hard `--max`
/// that says so when it truncates and never silently samples, and every scope is printed back with
/// what it does and does not cover.
///
/// ## What it cannot reach
///
/// - The EXPORT. It reads stored fragments; an export census is a different number.
/// - `V2Store`'s edge graph. [RawStore] is used instead — the SPI store's constructor materialises
///    the whole edge state — so a `ServiceLink`'s endpoints resolve through `_id_idx` by id, not
///    through resolved edges. An endpoint whose stop point is not in the same store is reported
///    `unresolved`, never as a gap of zero.
/// - Correctness of a shape. It can say a polyline is 6 km long where the straight line is 800 m,
///    or that it ends 4 km from the point it claims to end at. It cannot say which is the road.
///
/// ## What `AXIS_SWAPPED` does and does not claim
///
/// The `--endpoints` arm measures ONE thing: the distance from a polyline's first and last vertex
/// to the location of the `From`/`ToPoint` it names. Those endpoints come from NAMED
/// `<Longitude>`/`<Latitude>` elements and are unambiguous; a `gml:posList` is a bare pair
/// sequence and is not. When swapping the polyline's two ordinates collapses a continent-scale gap
/// to under a metre, the posList is `(lon lat)` relative to its own endpoints. That is a fact
/// about the data, and all this measures.
///
/// It is not a claim that any reader gets it wrong. The reprojection path takes authority order and
/// swaps on the geographic side, so both arms are faithful; and where `srsName` is absent it
/// defaults to the target CRS, the transform is skipped and the posList is exported byte for byte.
/// The exposure is downstream, in whatever reads the export.
///
/// With one exception, which the `VERDICT x srsName` cross-tab exists to count: a geometry whose
/// `srsName` is present and merely DIFFERS from the target — including an unresolvable one, where
/// the fallback builds an identity transform — has the target CRS stamped onto it. Its numbers and
/// their order survive; its declaration does not. That converts an ambiguous encoding into one
/// this chain positively asserts to be EPSG:4326, whose authority order is the opposite. For
/// those, a conformant GML reader placing the line thousands of kilometres away is not a reader
/// defect.
///
/// A diagnostic, not a gate: no pass/fail. A zero here is a statement about the store you pointed
/// at, not about the corpus.
public final class GeoCmd {

    private static final String RULE = "=".repeat(100);
    /// IUGG mean Earth radius, metres — the constant every haversine in this file uses.
    private static final double R_EARTH = 6_371_008.8;

    private GeoCmd() {}

    // ================================================================== CLI

    public static int run(String[] args) throws Exception {
        if (args.length == 0) { usage(System.err); return 2; }
        String sub = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (sub) {
            case "census" -> census(rest);
            case "extract" -> extract(rest);
            case "html" -> html(rest);
            case "compare" -> compare(rest);
            case "selftest" -> selftest();
            case "-h", "--help", "help" -> { usage(System.out); yield 0; }
            default -> {
                System.err.println("nx geo: unknown subcommand '" + sub + "'");
                usage(System.err);
                yield 2;
            }
        };
    }

    static void usage(PrintStream o) {
        o.println("""
                nx geo — geometry extract and self-contained map

                  WHY.  Geometry is the one defect class where a census is structurally weak.
                  THE TRUNCATED-LINK DEFECT — a multi-RouteLink ServiceLink keeping only the
                  FIRST link's geometry — moves no element count and no attribute count.  It shortens a
                  polyline: unmissable on a map, invisible in a TSV.

                  A DIAGNOSTIC, NEVER A GATE.  No pass/fail, and it must not gate a chain.
                  A ZERO IS A STATEMENT ABOUT THE STORE YOU POINTED AT, not about the corpus.

                nx geo census --db <store> [--db <store>...]
                      what geometry this store holds, before you extract any of it.
                      --class C[,C..] / --skip-class C[,..] / --all-classes
                      --sample N        census about N objects per class map (every Nth ROW of a
                                        cursor walk -- saves the parse, not the read)
                      --workers N       CPU workers (default availableProcessors-1)
                      --endpoints       resolve ServiceLink From/ToPointRef against the point
                                        index built during the same scan, and NAME what it finds:
                                          ENDPOINTS_OK      starts and ends where the link says
                                          SHORT_AT_TO       starts right, STOPS SHORT of the
                                                            ToPoint -- the truncated-link signature
                                          SHORT_AT_FROM     the mirror of it
                                          AXIS_SWAPPED      swapping the polyline's two axes lands
                                                            it ON its endpoints, i.e. the posList is
                                                            (lon lat) relative to the SAME object's
                                                            NAMED <Longitude>/<Latitude> endpoints.
                                                            That is a fact about the DATA. What any
                                                            reader assumes is a separate question --
                                                            see the VERDICT x srsName table, and
                                                            --crs-to.
                                          BOTH_ENDS_ADRIFT  not this link's shape at all
                                          UNRESOLVED        the ref is not in this store; never 0
                      --gap-min M       the threshold above which a gap counts (default 250 m).
                                        On `extract` it is also a FILTER.
                      --max-index N     cap the point index (default 3,000,000; then gaps go
                                        'unresolved' rather than silently wrong)
                      --crs-to URN      the crs_to the EPIP reprojection pass runs with (default
                                        urn:ogc:def:crs:EPSG::4326, what conv/epip_db_to_db
                                        registers).  It decides the EPIP FATE column: whether a
                                        geometry leaves this chain still UNDECLARED, or STAMPED
                                        with crs_to by the identity transform.
                      --progress SEC    progress line every SEC seconds (default 30, 0=off)
                      --out f.tsv       the per-(store,class,path) ledger

                nx geo extract --db <store> [--db <store>...] --out extract.geojson[.gz]
                      the same scan, emitting GeoJSON (RFC 7946: WGS84 lon,lat).
                      SCOPES -- combine freely; every one is printed back with what it covers:
                      --class C[,C..]   only these class maps
                      --id SUBSTR       only objects whose @id contains this -- the drill-down
                                        from one object §2.5 says this tool is FOR
                      --kind point|line|polygon
                      --bbox W,S,E,N    keep geometries INTERSECTING this box (degrees)
                      --around <id> --radius 2km    box of that radius around that object's
                                        first coordinate.  Resolved through _id_idx, not edges.
                      --dev-min M       lines whose max deviation from their own straight line
                                        exceeds M metres.  This is OTP_SERVICELINK_GEOMETRY's
                                        57.2%-over-50m figure, per link.
                      --ratio-min R     lines whose path/straight-line ratio exceeds R
                      --gap-min M       links whose endpoint gap exceeds M metres (--endpoints)
                      --points-min N    lines with at least N coordinates
                      --srs declared|absent|not-a-crs|out-of-range[,..]
                      --max N           HARD CAP, default 50,000 features.  It TRUNCATES AND SAYS
                                        SO; it never silently samples.  38,253,940 coordinate
                                        points exist nationally and no single page should try.
                      --precision N     coordinate decimals in the FILE (default 6 ~= 0.1 m).
                                        `compare` computes from full doubles, not from the file.
                      --lens            bake the stored fragment into each feature so the map's
                                        side panel can show it offline.  Costs file size.
                      --html f.html     also write the map (same as piping through `nx geo html`)

                nx geo html --extract extract.geojson[.gz] --out map.html [--title T]
                      ONE SELF-CONTAINED FILE.  No basemap, no tiles, no CDN, no fetch: it opens
                      from disk with the machine offline.  There is deliberately NO COASTLINE --
                      a graticule with labelled degrees, a scale bar and a coordinate readout are
                      the reference, and a decorative outline would only look like a basemap.
                      Colour modes: srsName bucket / max deviation / point count / path ratio /
                      endpoint gap / store / displacement.  Click a feature for its properties.

                nx geo compare --a <store> --b <store> --out cmp.geojson[.gz]
                      join on (class,id,version), per-vertex displacement, WITH A TOLERANCE.
                      EXPORT_AB §5.10 corrected EPIP_TRANSFORM dev. 6 twice: reprojected
                      coordinates are NOT bit-identical, delta ~1e-6 deg ~ 0.1 m.  So the output
                      is a HISTOGRAM plus features carrying disp_max/disp_mean, and
                      "0.1 m everywhere" and "one link truncated" are visibly different findings
                      rather than both being "geometry differs".
                      --class/--kind/--bbox/--max as extract, plus:
                      --disp-min M      only emit features displaced more than M metres

                THE srsName BUCKETS, which are the third reason this exists:
                  declared     resolves through the SAME proj4j CRSFactory toolkit.geo.Projection
                               uses, so this tool and a CRSError cannot disagree
                  absent       no srsName in scope; WGS84 ASSUMED, and said rather than implied
                  not-a-CRS    present and unresolvable -- where CopyingCapture.srsNameFor
                               stamps <FrameDefaults><DefaultLocationSystem> verbatim with no
                               validation and three Abruzzo feeds put the TIMEZONE 'Europe/Rome'
                               there.  Those coordinates are lat/lon and render perfectly; a map
                               that assumed WGS84 silently would show NOTHING.  The bucket is the
                               finding.
                  out-of-range a coordinate outside WGS84 after all of the above; excluded from
                               the map bounds so one bad point cannot zoom the world out

                nx geo selftest
                      the geometry reader against synthetic fragments, NO STORE NEEDED.  It exists
                      because two of the five GEO_FIELD_TYPES -- Polygon and MultiSurface -- occur
                      ZERO times in this corpus, so those arms have no positive control in any
                      store you can point this tool at.  An arm with no control is an arm nobody
                      has checked; this is the control.  Exit 1 on any failure.

                EXAMPLES   (from the repository root; see tools/nx/acceptance/geo_acceptance.sh)
                  nx geo census --db /home/zsombor/Projects/NOI/data-java/it-rap-abruzzo-IT-ITF1-0083.lmdb
                  nx geo extract --db <store> --class ServiceLink --dev-min 50 \\
                                 --out sl.geojson --html sl.html --lens
                  nx geo compare --a before.lmdb --b after.lmdb --out cmp.geojson""");
    }

    // ================================================================== srsName resolution

    /// The reprojection's own rules, reused rather than re-derived: an OGC URN collapses to
    /// `AUTH:CODE`, the authority is upper-cased, and the result goes to the same `CRSFactory` the
    /// transformer would use. A name this class calls `declared` is one that would not take the
    /// fallback.
    static final class Crs {
        static final String WGS84_URN = "urn:ogc:def:crs:EPSG::4326";
        private static final Pattern URN =
                Pattern.compile("urn:ogc:def:crs:([^:]+):[^:]*:(.+)", Pattern.CASE_INSENSITIVE);
        private static final Pattern AUTH_CODE = Pattern.compile("([A-Za-z]+):(\\d+)");
        private static final CRSFactory FACTORY = new CRSFactory();
        private static final CoordinateTransformFactory TF = new CoordinateTransformFactory();
        private static final Map<String, Object> RESOLVED = new ConcurrentHashMap<>();
        private static final Object UNRESOLVABLE = new Object();
        private static final Map<String, Object> TRANSFORMS = new ConcurrentHashMap<>();

        static final String DECLARED = "declared";
        static final String ABSENT = "absent";
        static final String NOT_A_CRS = "not-a-CRS";

        private Crs() {}

        static String normalise(String name) {
            String lookup = name;
            Matcher urn = URN.matcher(lookup);
            if (urn.matches()) lookup = urn.group(1) + ":" + urn.group(2);
            Matcher ac = AUTH_CODE.matcher(lookup);
            if (ac.matches()) lookup = ac.group(1).toUpperCase(Locale.ROOT) + ":" + ac.group(2);
            return lookup;
        }

        static CoordinateReferenceSystem resolve(String name) {
            Object o = RESOLVED.computeIfAbsent(name, n -> {
                try {
                    CoordinateReferenceSystem c = FACTORY.createFromName(normalise(n));
                    return c == null ? UNRESOLVABLE : c;
                } catch (RuntimeException e) {
                    return UNRESOLVABLE;
                }
            });
            return o == UNRESOLVABLE ? null : (CoordinateReferenceSystem) o;
        }

        /// `declared` / `absent` / `not-a-CRS` — the three §2.5 asks for.
        static String bucket(String srsName) {
            if (srsName == null || srsName.isEmpty()) return ABSENT;
            return resolve(srsName) == null ? NOT_A_CRS : DECLARED;
        }

        static boolean isWgs84(String srsName) {
            if (srsName == null || srsName.isEmpty()) return true;
            String n = normalise(srsName);
            return n.equals("EPSG:4326") || n.equals("CRS:84") || n.equals("OGC:CRS84");
        }

        /// A transform into WGS84, or null when none is needed or possible. Axis handling mirrors
        /// `Projection.Transform`: a geographic side is in authority order (lat, lon), a
        /// projected side in (easting, northing).
        static Object[] toWgs84(String srsName) {
            if (srsName == null || srsName.isEmpty() || isWgs84(srsName)) return null;
            Object o = TRANSFORMS.computeIfAbsent(srsName, n -> {
                CoordinateReferenceSystem src = resolve(n);
                if (src == null) return UNRESOLVABLE;
                try {
                    CoordinateReferenceSystem dst = FACTORY.createFromName("EPSG:4326");
                    return new Object[] {TF.createTransform(src, dst),
                            Boolean.TRUE.equals(src.isGeographic())};
                } catch (RuntimeException e) {
                    return UNRESOLVABLE;
                }
            });
            return o == UNRESOLVABLE ? null : (Object[]) o;
        }

        /// (a, b) in the source's authority order -> {lat, lon} in WGS84.
        static double[] project(Object[] t, double a, double b) {
            CoordinateTransform ct = (CoordinateTransform) t[0];
            boolean srcLatLon = (Boolean) t[1];
            ProjCoordinate out = ct.transform(
                    srcLatLon ? new ProjCoordinate(b, a) : new ProjCoordinate(a, b),
                    new ProjCoordinate());
            return new double[] {out.y, out.x};   // EPSG:4326 is geographic: authority order back
        }
    }

    // ================================================================== geometry model

    static final String POINT = "point", LINE = "line", POLYGON = "polygon";

    static final String FATE_UNDECLARED = "PASSES_THROUGH_UNDECLARED";
    static final String FATE_STAMPED = "STAMPED_WITH_CRS_TO";
    static final String FATE_ALREADY = "ALREADY_THE_TARGET";

    /// What the reprojection pass does with a geometry carrying this `srsName`:
    ///
    /// - `srsName` equals the target as a RAW STRING — untouched.
    /// - `srsName` absent — defaulted to WGS84, which equals the target, so no transform is built.
    ///    The coordinates are never read and the geometry is exported still undeclared.
    /// - anything else, unresolvable names included, where the fallback supplies an identity
    ///    transform — the geometry is projected and its `srsName` is OVERWRITTEN with the target.
    ///    Numbers and their order survive; the declaration does not.
    ///
    /// The third case matters for axis order: a geometry whose axis convention was merely ambiguous
    /// ships declared as the CRS whose authority order is the opposite one.
    static String fate(String srsRaw, String crsTo) {
        if (srsRaw == null || srsRaw.isEmpty()) return FATE_UNDECLARED;
        if (srsRaw.equals(crsTo)) return FATE_ALREADY;
        return FATE_STAMPED;
    }

    /// One geometry lifted out of one stored fragment. Coordinates are WGS84 `lon,lat` pairs.
    static final class Geom {
        String kind;
        String path;                 // where in the fragment it sat, e.g. ServiceLink/gml:LineString
        String srsRaw;               // verbatim, or null
        String bucket;
        boolean reprojected;
        boolean outOfRange;
        boolean noneLiteral;         // the literal string "None" written where a posList belongs
        boolean unparseable;
        String fate;                 // what the EPIP reprojection pass would do with this srsName
        double[] xy = new double[0]; // flat lon,lat
        List<double[]> rings;        // polygons only; ring 0 is the exterior

        int points() { return xy.length / 2; }
    }

    /// One object's worth: the address plus every geometry in it plus the derived metrics.
    static final class Feat {
        String store, cls, id, version;
        Geom g;
        double pathLen, straightLen, ratio, maxDev;
        double gapFrom = -1, gapTo = -1;   // -1 = not computed; -2 = ref present, unresolved
        double gapSwapped = -1;            // the same gap with the polyline's axes exchanged
        String gapVerdict;
        String fromRef, toRef;
        String declaredDistance;
        String frag;
        double dispMax = -1, dispMean = -1;
        String dispVerdict;
    }

    // ================================================================== fragment -> geometry

    /// Walk the parsed fragment in document order and lift every geometry out of it. The
    /// `srsName` in scope is inherited downward exactly as `Projection`'s
    /// `get_transformer_by_srs_name` inherits it: a `gml:pos`'s own name beats its
    /// `Location`'s, which beats anything above.
    static void collect(XmlDoc.Node n, String prefix, String inherited, List<Geom> out) {
        String name = n.name;
        String path = prefix.isEmpty() ? name : prefix + "/" + name;
        String srs = attr(n, "srsName");
        String here = srs != null && !srs.isEmpty() ? srs : inherited;

        switch (name) {
            case "LineString", "LinearRing" -> { lineOf(n, path, here, out); return; }
            case "Location" -> { locationOf(n, path, here, out); return; }
            case "Polygon" -> { polygonOf(n, path, here, out); return; }
            default -> { }
        }
        for (XmlDoc.Node k : n.kids) collect(k, path, here, out);
    }

    private static String attr(XmlDoc.Node n, String name) {
        for (String[] a : n.attrs) {
            String an = a[0];
            int c = an.indexOf(':');
            if (an.equals(name) || (c >= 0 && an.substring(c + 1).equals(name))) return a[1];
        }
        return null;
    }

    private static XmlDoc.Node kid(XmlDoc.Node n, String name) {
        for (XmlDoc.Node k : n.kids) if (k.name.equals(name)) return k;
        return null;
    }

    /// `<Location>`: either a `gml:pos`, or the `Longitude`/`Latitude` pair.
    private static void locationOf(XmlDoc.Node n, String path, String inherited, List<Geom> out) {
        Geom g = new Geom();
        g.kind = POINT;
        g.path = path;
        XmlDoc.Node pos = kid(n, "pos");
        String srs = attr(n, "srsName");
        if (pos != null) {
            String ps = attr(pos, "srsName");
            if (ps != null && !ps.isEmpty()) srs = ps;
        }
        g.srsRaw = srs != null && !srs.isEmpty() ? srs
                : (inherited != null && !inherited.isEmpty() ? inherited : null);
        double lat, lon;
        if (pos != null) {
            double[] v = doubles(pos.text);
            if (v == null || v.length < 2) { g.unparseable = true; finish(g, out); return; }
            lat = v[0];
            lon = v[1];
        } else {
            XmlDoc.Node lonN = kid(n, "Longitude"), latN = kid(n, "Latitude");
            if (lonN == null || latN == null) return;   // a Location with neither is not geometry
            Double la = num(latN.text), lo = num(lonN.text);
            if (la == null || lo == null) { g.unparseable = true; finish(g, out); return; }
            lat = la;
            lon = lo;
        }
        g.xy = new double[] {lon, lat};
        finish(g, out);
    }

    /// `gml:LineString` / `gml:LinearRing`: a `posList`, or repeated `pos`.
    private static void lineOf(XmlDoc.Node n, String path, String inherited, List<Geom> out) {
        Geom g = new Geom();
        g.kind = LINE;
        g.path = path;
        String srs = attr(n, "srsName");
        g.srsRaw = srs != null && !srs.isEmpty() ? srs
                : (inherited != null && !inherited.isEmpty() ? inherited : null);
        int dim = 2;
        String sd = attr(n, "srsDimension");
        XmlDoc.Node pl = kid(n, "posList");
        if (pl != null) {
            String sd2 = attr(pl, "srsDimension");
            if (sd2 != null) sd = sd2;
        }
        if (sd != null) try { dim = Math.max(2, Integer.parseInt(sd.trim())); } catch (RuntimeException ignored) { }

        List<Double> flat = new ArrayList<>();
        if (pl != null) {
            String t = pl.text;
            if (t != null && t.trim().equals("None")) {
                // The bean binds this to an empty list and the defect vanishes; the fragment still
                // says None, so this arm can still see it.
                g.noneLiteral = true;
                finish(g, out);
                return;
            }
            double[] v = doubles(t);
            if (v == null) { g.unparseable = true; finish(g, out); return; }
            for (double d : v) flat.add(d);
        } else {
            for (XmlDoc.Node k : n.kids) {
                if (!k.name.equals("pos")) continue;
                double[] v = doubles(k.text);
                if (v == null || v.length < 2) continue;
                flat.add(v[0]);
                flat.add(v[1]);
            }
            dim = 2;
        }
        int npts = flat.size() / dim;
        double[] xy = new double[npts * 2];
        for (int i = 0; i < npts; i++) {
            double lat = flat.get(i * dim), lon = flat.get(i * dim + 1);
            xy[i * 2] = lon;
            xy[i * 2 + 1] = lat;
        }
        g.xy = xy;
        finish(g, out);
    }

    private static void polygonOf(XmlDoc.Node n, String path, String inherited, List<Geom> out) {
        Geom g = new Geom();
        g.kind = POLYGON;
        g.path = path;
        String srs = attr(n, "srsName");
        g.srsRaw = srs != null && !srs.isEmpty() ? srs
                : (inherited != null && !inherited.isEmpty() ? inherited : null);
        g.rings = new ArrayList<>();
        List<Geom> tmp = new ArrayList<>();
        for (XmlDoc.Node side : n.kids) {              // exterior / interior
            for (XmlDoc.Node ring : side.kids) {
                if (!ring.name.equals("LinearRing")) continue;
                tmp.clear();
                lineOf(ring, path + "/" + side.name + "/LinearRing", g.srsRaw, tmp);
                for (Geom r : tmp) if (r.points() > 0) g.rings.add(r.xy);
            }
        }
        if (g.rings.isEmpty()) return;
        g.xy = g.rings.get(0);
        // finish() has already normalised each ring; re-derive the polygon's own flags from ring 0.
        g.bucket = Crs.bucket(g.srsRaw);
        g.reprojected = !Crs.isWgs84(g.srsRaw) && g.bucket.equals(Crs.DECLARED);
        g.outOfRange = anyOutOfRange(g.xy);
        out.add(g);
    }

    /// Bucket, reproject if the declared CRS resolves and is not WGS84, range-check, emit.
    private static void finish(Geom g, List<Geom> out) {
        g.bucket = Crs.bucket(g.srsRaw);
        if (g.bucket.equals(Crs.DECLARED) && !Crs.isWgs84(g.srsRaw)) {
            Object[] t = Crs.toWgs84(g.srsRaw);
            if (t != null) {
                for (int i = 0; i + 1 < g.xy.length; i += 2) {
                    try {
                        double[] ll = Crs.project(t, g.xy[i + 1], g.xy[i]);  // authority order in
                        g.xy[i] = ll[1];
                        g.xy[i + 1] = ll[0];
                    } catch (RuntimeException e) {
                        g.outOfRange = true;
                    }
                }
                g.reprojected = true;
            }
        }
        if (anyOutOfRange(g.xy)) g.outOfRange = true;
        out.add(g);
    }

    private static boolean anyOutOfRange(double[] xy) {
        for (int i = 0; i + 1 < xy.length; i += 2) {
            double lon = xy[i], lat = xy[i + 1];
            if (!(lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90)) return true;
            if (Double.isNaN(lon) || Double.isNaN(lat)) return true;
        }
        return false;
    }

    private static double[] doubles(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return new double[0];
        String[] parts = t.split("\\s+");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Double.parseDouble(parts[i]); }
            catch (NumberFormatException e) { return null; }
        }
        return out;
    }

    private static Double num(String s) {
        if (s == null) return null;
        try { return Double.valueOf(s.trim()); } catch (RuntimeException e) { return null; }
    }

    // ================================================================== metrics

    static double haversine(double lon1, double lat1, double lon2, double lat2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R_EARTH * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    static double pathLength(double[] xy) {
        double s = 0;
        for (int i = 0; i + 3 < xy.length; i += 2) {
            s += haversine(xy[i], xy[i + 1], xy[i + 2], xy[i + 3]);
        }
        return s;
    }

    /// The greatest distance from any interior vertex to the straight chord first→last, in metres.
    /// That chord is what a consumer draws when a link has no geometry, so this is "how wrong the
    /// straight line is" for this link. Computed in a local equirectangular frame, whose error is
    /// well under a metre at link scale.
    static double maxDeviation(double[] xy) {
        int n = xy.length / 2;
        if (n < 3) return 0;
        double lat0 = Math.toRadians(xy[1]);
        double kx = R_EARTH * Math.cos(lat0), ky = R_EARTH;
        double ax = Math.toRadians(xy[0]) * kx, ay = Math.toRadians(xy[1]) * ky;
        double bx = Math.toRadians(xy[(n - 1) * 2]) * kx, by = Math.toRadians(xy[(n - 1) * 2 + 1]) * ky;
        double dx = bx - ax, dy = by - ay;
        double dd = dx * dx + dy * dy;
        double best = 0;
        for (int i = 1; i < n - 1; i++) {
            double px = Math.toRadians(xy[i * 2]) * kx, py = Math.toRadians(xy[i * 2 + 1]) * ky;
            double d;
            if (dd == 0) {
                d = Math.hypot(px - ax, py - ay);
            } else {
                double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / dd));
                d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
            }
            if (d > best) best = d;
        }
        return best;
    }

    // ================================================================== the scan

    /// Everything a scan needs to know; shared by census / extract / compare.
    static final class Scope {
        List<Path> dbs = new ArrayList<>();
        Set<String> only = new LinkedHashSet<>(), skip = new LinkedHashSet<>();
        boolean allClasses = false;
        Set<String> kinds = new LinkedHashSet<>();
        Set<String> srsWanted = new LinkedHashSet<>();
        double[] bbox;                 // W,S,E,N
        String around;
        double radiusM = -1;
        double devMin = -1, ratioMin = -1, gapMin = 250, dispMin = -1;
        boolean gapMinSet = false;
        String idLike;
        int pointsMin = -1;
        long sample = 0;
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int progressSec = 30;
        boolean endpoints = false;
        boolean lens = false;
        long max = 50_000;
        long maxIndex = 3_000_000;
        int precision = 6;
        /// The target CRS the reprojection pass runs with, which decides the FATE column below.
        /// An option because it is a chain configuration, not a law.
        String crsTo = Crs.WGS84_URN;

        boolean wantsKind(String k) { return kinds.isEmpty() || kinds.contains(k); }

        String describe() {
            List<String> s = new ArrayList<>();
            s.add(only.isEmpty() ? (allClasses ? "classes=ALL POPULATED CLASS MAPS"
                    : "classes=GeoClasses.all() (" + GeoClasses.all().size() + " model classes carrying a direct geo field)")
                    : "classes=" + only);
            if (!skip.isEmpty()) s.add("skip=" + skip);
            if (idLike != null) s.add("id~" + idLike);
            if (!kinds.isEmpty()) s.add("kind=" + kinds);
            if (bbox != null) s.add(String.format("bbox=%.5f,%.5f,%.5f,%.5f", bbox[0], bbox[1], bbox[2], bbox[3]));
            if (around != null) s.add("around=" + around + " r=" + (long) radiusM + "m");
            if (devMin >= 0) s.add("dev>=" + (long) devMin + "m");
            if (ratioMin >= 0) s.add("ratio>=" + ratioMin);
            if (pointsMin >= 0) s.add("points>=" + pointsMin);
            if (!srsWanted.isEmpty()) s.add("srs=" + srsWanted);
            if (sample > 0) s.add("sample~" + sample + "/class");
            return String.join("  ", s);
        }
    }

    /// Per-store totals. Everything printed by `census` comes out of one of these.
    static final class Acc {
        long objects, fragErrors, geoms, points;
        final Map<String, long[]> byClassPath = new TreeMap<>();      // -> {objects, geoms, points}
        final Map<String, Long> byKind = new TreeMap<>();
        final Map<String, Long> bySrsBucket = new TreeMap<>();
        final Map<String, Long> bySrsValue = new TreeMap<>();
        final Map<String, Long> byFate = new TreeMap<>();
        final Map<String, Long> verdictBySrs = new TreeMap<>();
        final Map<String, Long> pointHist = new LinkedHashMap<>();
        long noneLiteral, unparseable, outOfRange, reprojected;
        final List<Double> devSample = new ArrayList<>();
        final List<Double> ratioSample = new ArrayList<>();
        final List<Double> gapSample = new ArrayList<>();
        boolean sampleCapped;
        long devOver50, devTotal, straightAlready, singlePoint;
        long gapResolved, gapUnresolved, truncated;
        final Map<String, Long> gapVerdicts = new TreeMap<>();
        /// Examples are the LOWEST `@id`s, not the first ones a worker happened to see, so a
        /// re-run diffs to zero. A thread-order-dependent example list makes that uncheckable.
        final Map<String, TreeMap<String, Feat>> examples = new TreeMap<>();
        final TreeMap<String, Feat> notACrsExamples = new TreeMap<>();

        static final int EX_CAP = 8;
        static final int SAMPLE_CAP = 1_000_000;

        static void keep(TreeMap<String, Feat> m, Feat f) {
            m.put(f.g.path + "\0" + f.id + "\0" + f.version, f);
            while (m.size() > EX_CAP) m.remove(m.lastKey());
        }

        void merge(Acc o) {
            objects += o.objects; fragErrors += o.fragErrors; geoms += o.geoms; points += o.points;
            noneLiteral += o.noneLiteral; unparseable += o.unparseable;
            outOfRange += o.outOfRange; reprojected += o.reprojected;
            devOver50 += o.devOver50; devTotal += o.devTotal;
            straightAlready += o.straightAlready; singlePoint += o.singlePoint;
            gapResolved += o.gapResolved; gapUnresolved += o.gapUnresolved; truncated += o.truncated;
            o.byClassPath.forEach((k, v) -> byClassPath.merge(k, v.clone(),
                    (a, b) -> new long[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]}));
            o.byKind.forEach((k, v) -> byKind.merge(k, v, Long::sum));
            o.gapVerdicts.forEach((k, v) -> gapVerdicts.merge(k, v, Long::sum));
            o.examples.forEach((k, v) -> {
                TreeMap<String, Feat> l = examples.computeIfAbsent(k, x -> new TreeMap<>());
                for (Feat f : v.values()) keep(l, f);
            });
            sampleCapped |= o.sampleCapped;
            o.bySrsBucket.forEach((k, v) -> bySrsBucket.merge(k, v, Long::sum));
            o.bySrsValue.forEach((k, v) -> bySrsValue.merge(k, v, Long::sum));
            o.byFate.forEach((k, v) -> byFate.merge(k, v, Long::sum));
            o.verdictBySrs.forEach((k, v) -> verdictBySrs.merge(k, v, Long::sum));
            o.pointHist.forEach((k, v) -> pointHist.merge(k, v, Long::sum));
            devSample.addAll(o.devSample);
            ratioSample.addAll(o.ratioSample);
            gapSample.addAll(o.gapSample);
            for (Feat f : o.notACrsExamples.values()) keep(notACrsExamples, f);
        }
    }

    private static final String[] PT_BUCKETS =
            {"0 (none literal)", "1", "2 (already straight)", "3-4", "5-9", "10-24", "25-49",
             "50-99", "100-249", "250-999", "1000+"};

    private static String ptBucket(int n) {
        if (n == 0) return PT_BUCKETS[0];
        if (n == 1) return PT_BUCKETS[1];
        if (n == 2) return PT_BUCKETS[2];
        if (n <= 4) return PT_BUCKETS[3];
        if (n <= 9) return PT_BUCKETS[4];
        if (n <= 24) return PT_BUCKETS[5];
        if (n <= 49) return PT_BUCKETS[6];
        if (n <= 99) return PT_BUCKETS[7];
        if (n <= 249) return PT_BUCKETS[8];
        if (n <= 999) return PT_BUCKETS[9];
        return PT_BUCKETS[10];
    }

    /// What a scan hands back: the accumulator, and the features that passed the scope filters.
    record ScanResult(Acc acc, List<Feat> feats, boolean truncatedByMax, long considered,
                      Map<String, float[]> pointIndex, long ms) {}

    static ScanResult scan(Path db, Scope sc, boolean keepFeatures, PrintStream o) throws Exception {
        long t0 = System.nanoTime();
        RawStore store;
        try {
            store = new RawStore(db);
        } catch (RuntimeException e) {
            o.println("SKIP " + db + ": " + e.getMessage());
            return null;
        }
        String storeName = db.getFileName().toString();
        try (store) {
            Set<String> geoNames = new LinkedHashSet<>();
            for (Class<?> c : GeoClasses.all()) geoNames.add(c.getSimpleName());

            record Plan(int idx, String name, long entries, long stride, boolean emit) {}
            List<Plan> plan = new ArrayList<>();
            long present = 0, planned = 0, forIndex = 0, skippedNonGeo = 0, skippedRows = 0;
            for (int idx : store.populatedClassIdxs()) {
                String name = store.className(idx);
                boolean wanted = sc.only.isEmpty() || sc.only.contains(name);
                // --endpoints resolves From/ToPointRef against a point index built during THIS scan,
                // so a --class filter that excludes the points would make every gap UNRESOLVED and
                // the tool would report an absence it manufactured. Read them anyway; emit nothing.
                boolean forIdxOnly = !wanted && sc.endpoints && geoNames.contains(name);
                if (!wanted && !forIdxOnly) continue;
                if (sc.skip.contains(name)) continue;
                long n = store.entries(idx);
                if (n == 0) continue;
                present += n;
                if (wanted && sc.only.isEmpty() && !sc.allClasses && !geoNames.contains(name)) {
                    skippedNonGeo++;
                    skippedRows += n;
                    continue;
                }
                long stride = sc.sample > 0 && n > sc.sample ? Math.max(1, n / sc.sample) : 1;
                plan.add(new Plan(idx, name, n, stride, wanted));
                long r = stride == 1 ? n : n / stride;
                if (wanted) planned += r; else forIndex += r;
            }
            o.printf("%-44s format=%-12s %d geo class map(s) of %,d row(s) present; %,d to read%s%n",
                    storeName, store.formatMarker(), plan.size(), present, planned,
                    forIndex > 0 ? String.format(" (+%,d read only to build the endpoint index)", forIndex) : "");
            if (skippedNonGeo > 0) {
                o.printf("%-44s   [%d class map(s) / %,d rows carry NO direct geo field per"
                        + " GeoClasses.all(); pass --all-classes to read them anyway]%n",
                        "", skippedNonGeo, skippedRows);
            }
            if (plan.isEmpty()) {
                o.printf("%-44s   NOTHING IN SCOPE. A zero here is a statement about this store.%n", "");
            }

            // ---- around: resolve the anchor first, through _id_idx, never through edges
            double[] bbox = sc.bbox;
            if (sc.around != null) {
                double[] anchor = anchorOf(store, sc.around, o);
                if (anchor == null) return null;
                double dLat = Math.toDegrees(sc.radiusM / R_EARTH);
                double dLon = dLat / Math.max(0.01, Math.cos(Math.toRadians(anchor[1])));
                bbox = new double[] {anchor[0] - dLon, anchor[1] - dLat,
                                     anchor[0] + dLon, anchor[1] + dLat};
                o.printf("%-44s   --around %s -> %.6f,%.6f  bbox %.5f,%.5f,%.5f,%.5f%n",
                        "", sc.around, anchor[0], anchor[1], bbox[0], bbox[1], bbox[2], bbox[3]);
            }
            final double[] box = bbox;

            Acc acc = new Acc();
            List<Feat> feats = Collections.synchronizedList(new ArrayList<>());
            Map<String, float[]> pointIx = sc.endpoints ? new ConcurrentHashMap<>() : null;
            List<Feat> needEnds = sc.endpoints ? Collections.synchronizedList(new ArrayList<>()) : null;
            AtomicLong considered = new AtomicLong();
            AtomicLong done = new AtomicLong();

            record Task(String cls, byte[] blob, boolean emit) {}
            BlockingQueue<Object> q = new ArrayBlockingQueue<>(Math.max(64, sc.workers * 32));
            Object POISON = new Object();
            List<Acc> locals = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int w = 0; w < sc.workers; w++) {
                Acc local = new Acc();
                locals.add(local);
                Thread t = new Thread(() -> {
                    FragmentCodec codec = new FragmentCodec();
                    try {
                        while (true) {
                            Object it = q.take();
                            if (it == POISON) break;
                            Task tk = (Task) it;
                            process(storeName, tk.cls(), tk.blob(), tk.emit(), codec, sc, box,
                                    local, feats, keepFeatures, considered, pointIx, needEnds);
                            done.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "nx-geo-" + w);
                t.start();
                threads.add(t);
            }

            Thread prog = null;
            if (sc.progressSec > 0) {
                final long target = planned;
                prog = new Thread(() -> {
                    long start = System.nanoTime();
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(sc.progressSec * 1000L);
                            long d = done.get();
                            long el = Math.max(1, (System.nanoTime() - start) / 1_000_000_000L);
                            o.printf("  … %,d / %,d objects  (%,d/s, %d%%)  %,d geometries%n",
                                    d, target, d / el, target == 0 ? 100 : (int) (100 * d / target),
                                    feats.size());
                        }
                    } catch (InterruptedException ignored) { }
                }, "nx-geo-progress");
                prog.setDaemon(true);
                prog.start();
            }

            for (Plan p : plan) {
                long[] seen = {0};
                store.scanClass(p.idx(), (k, blob) -> {
                    if (seen[0]++ % p.stride() != 0) return true;
                    try { q.put(new Task(p.name(), blob, p.emit())); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                    return true;
                });
            }
            for (int w = 0; w < sc.workers; w++) q.put(POISON);
            for (Thread t : threads) t.join();
            if (prog != null) prog.interrupt();
            for (Acc l : locals) acc.merge(l);

            // ---- endpoint gaps, resolved from the point index built during the same scan
            if (sc.endpoints) {
                for (Feat f : needEnds) {
                    f.gapFrom = gap(pointIx, f.fromRef, f.g.xy, true, false);
                    f.gapTo = gap(pointIx, f.toRef, f.g.xy, false, false);
                    if (f.gapFrom < 0 || f.gapTo < 0) {
                        acc.gapUnresolved++;
                        f.gapVerdict = UNRESOLVED;
                        continue;
                    }
                    acc.gapResolved++;
                    double worst = Math.max(f.gapFrom, f.gapTo);
                    if (acc.gapSample.size() < Acc.SAMPLE_CAP) acc.gapSample.add(worst);
                    else acc.sampleCapped = true;
                    f.gapSwapped = Math.max(gap(pointIx, f.fromRef, f.g.xy, true, true),
                                            gap(pointIx, f.toRef, f.g.xy, false, true));
                    f.gapVerdict = verdict(f, worst, sc.gapMin);
                    acc.gapVerdicts.merge(f.gapVerdict, 1L, Long::sum);
                    acc.verdictBySrs.merge(f.gapVerdict + "\t" + f.g.fate, 1L, Long::sum);
                    if (!f.gapVerdict.equals(ENDPOINTS_OK)) {
                        acc.truncated++;
                        Acc.keep(acc.examples.computeIfAbsent(f.gapVerdict, k -> new TreeMap<>()), f);
                    }
                }
            }

            boolean trunc = false;
            List<Feat> kept = feats;
            if (keepFeatures) {
                List<Feat> pass = new ArrayList<>();
                for (Feat f : feats) {
                    if (sc.gapMinSet && sc.endpoints
                            && !(Math.max(f.gapFrom, f.gapTo) >= sc.gapMin)) continue;
                    pass.add(f);
                }
                pass.sort(Comparator.comparing((Feat f) -> f.cls).thenComparing(f -> f.id));
                trunc = feats.size() >= sc.max;
                kept = pass;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            o.printf("%-44s %,d objects read, %,d geometries, %,d coordinate points, %,d ms%n",
                    "", acc.objects, acc.geoms, acc.points, ms);
            return new ScanResult(acc, keepFeatures ? kept : List.of(), trunc, considered.get(),
                    pointIx, ms);
        }
    }

    private static double gap(Map<String, float[]> ix, String ref, double[] xy, boolean head,
                              boolean swapAxes) {
        if (ref == null || ix == null) return -1;
        float[] p = ix.get(ref);
        if (p == null) return -2;
        if (xy.length < 2) return -2;
        int i = head ? 0 : xy.length - 2;
        double lon = swapAxes ? xy[i + 1] : xy[i], lat = swapAxes ? xy[i] : xy[i + 1];
        if (swapAxes && (Math.abs(lat) > 90 || Math.abs(lon) > 180)) return Double.MAX_VALUE;
        return haversine(lon, lat, p[1], p[0]);
    }

    static final String ENDPOINTS_OK = "ENDPOINTS_OK";
    static final String AXIS_SWAPPED = "AXIS_SWAPPED";
    static final String SHORT_AT_TO = "SHORT_AT_TO";
    static final String SHORT_AT_FROM = "SHORT_AT_FROM";
    static final String BOTH_ENDS_ADRIFT = "BOTH_ENDS_ADRIFT";
    static final String UNRESOLVED = "UNRESOLVED";

    /// Turn two distances into a NAMED finding — the difference between a tool and a column of
    /// numbers. `SHORT_AT_TO` is the signature this section exists for: the polyline starts where
    /// it should and stops short of the `ToPoint`.
    private static String verdict(Feat f, double worst, double gapMin) {
        if (worst <= gapMin) return ENDPOINTS_OK;
        if (f.gapSwapped >= 0 && f.gapSwapped <= gapMin && f.gapSwapped * 20 < worst) return AXIS_SWAPPED;
        boolean fromOk = f.gapFrom <= gapMin, toOk = f.gapTo <= gapMin;
        if (fromOk && !toOk) return SHORT_AT_TO;
        if (toOk && !fromOk) return SHORT_AT_FROM;
        return BOTH_ENDS_ADRIFT;
    }

    /// The first coordinate of the named object, resolved through `_id_idx`.
    private static double[] anchorOf(RawStore store, String id, PrintStream o) {
        String bare = id, ver = null;
        int at = id.lastIndexOf('@');
        if (at > 0) { bare = id.substring(0, at); ver = id.substring(at + 1); }
        List<RawStore.Hit> hits = store.lookup(bare, ver, null);
        if (hits.isEmpty()) {
            o.println("nx geo: --around " + id + ": no _id_idx row in this store.");
            return null;
        }
        FragmentCodec codec = new FragmentCodec();
        for (RawStore.Hit h : hits) {
            byte[] blob = store.rawBlob(h);
            if (blob == null) continue;
            try {
                XmlDoc doc = XmlDoc.parse(codec.decompress(blob));
                List<Geom> gs = new ArrayList<>();
                collect(doc.root, "", null, gs);
                for (Geom g : gs) if (g.points() > 0 && !g.outOfRange) return new double[] {g.xy[0], g.xy[1]};
            } catch (Exception ignored) { }
        }
        o.println("nx geo: --around " + id + ": found " + hits.size()
                + " row(s), none carrying a usable coordinate.");
        return null;
    }

    /// One stored object: decompress, parse, lift geometry, measure, filter, keep.
    private static void process(String storeName, String cls, byte[] blob, boolean emit,
                                FragmentCodec codec, Scope sc, double[] box, Acc acc, List<Feat> feats,
                                boolean keep, AtomicLong considered,
                                Map<String, float[]> pointIx, List<Feat> needEnds) {
        if (emit) acc.objects++;
        byte[] frag;
        XmlDoc doc;
        try {
            frag = codec.decompress(blob);
            doc = XmlDoc.parse(frag);
        } catch (Exception e) {
            if (emit) acc.fragErrors++;
            return;
        }
        List<Geom> gs = new ArrayList<>();
        try {
            collect(doc.root, "", null, gs);
        } catch (RuntimeException e) {
            if (emit) acc.fragErrors++;
            return;
        }
        if (gs.isEmpty()) return;

        String id = null, version = null, fromRef = null, toRef = null, distance = null;
        for (String[] a : doc.root.attrs) {
            if (a[0].equals("id")) id = a[1];
            else if (a[0].equals("version")) version = a[1];
        }
        XmlDoc.Node fp = kid(doc.root, "FromPointRef"), tp = kid(doc.root, "ToPointRef");
        if (fp != null) fromRef = attr(fp, "ref");
        if (tp != null) toRef = attr(tp, "ref");
        XmlDoc.Node dn = kid(doc.root, "Distance");
        if (dn != null) distance = dn.text.trim();

        for (Geom g : gs) {
            if (!emit) {
                // index-only class: contribute the point and nothing else -- no count, no feature.
                if (g.kind.equals(POINT) && g.points() == 1 && pointIx != null && id != null
                        && pointIx.size() < sc.maxIndex) {
                    pointIx.putIfAbsent(id, new float[] {(float) g.xy[1], (float) g.xy[0]});
                }
                continue;
            }
            acc.geoms++;
            acc.points += g.points();
            long[] cp = acc.byClassPath.computeIfAbsent(cls + "\t" + g.path, k -> new long[3]);
            cp[0]++; cp[1]++; cp[2] += g.points();
            acc.byKind.merge(g.kind, 1L, Long::sum);
            acc.bySrsBucket.merge(g.bucket, 1L, Long::sum);
            if (g.srsRaw != null) acc.bySrsValue.merge(g.srsRaw, 1L, Long::sum);
            g.fate = fate(g.srsRaw, sc.crsTo);
            acc.byFate.merge(g.fate, 1L, Long::sum);
            if (g.noneLiteral) acc.noneLiteral++;
            if (g.unparseable) acc.unparseable++;
            if (g.outOfRange) acc.outOfRange++;
            if (g.reprojected) acc.reprojected++;
            if (g.kind.equals(LINE)) acc.pointHist.merge(ptBucket(g.points()), 1L, Long::sum);

            Feat f = new Feat();
            f.store = storeName;
            f.cls = cls;
            f.id = id == null ? "(no @id)" : id;
            f.version = version == null ? "" : version;
            f.g = g;
            f.declaredDistance = distance;

            if (g.kind.equals(POINT) && g.points() == 1 && pointIx != null
                    && id != null && pointIx.size() < sc.maxIndex) {
                pointIx.putIfAbsent(id, new float[] {(float) g.xy[1], (float) g.xy[0]});
            }

            if (g.kind.equals(LINE) && g.points() >= 2) {
                f.pathLen = pathLength(g.xy);
                f.straightLen = haversine(g.xy[0], g.xy[1],
                        g.xy[g.xy.length - 2], g.xy[g.xy.length - 1]);
                f.ratio = f.straightLen > 0 ? f.pathLen / f.straightLen : 0;
                f.maxDev = maxDeviation(g.xy);
                if (g.points() == 2) acc.straightAlready++;
                if (g.points() >= 3) {
                    acc.devTotal++;
                    if (f.maxDev > 50) acc.devOver50++;
                    if (acc.devSample.size() < Acc.SAMPLE_CAP) acc.devSample.add(f.maxDev);
                    else acc.sampleCapped = true;
                    if (f.ratio > 0 && acc.ratioSample.size() < Acc.SAMPLE_CAP) acc.ratioSample.add(f.ratio);
                }
            } else if (g.kind.equals(LINE) && g.points() == 1) {
                acc.singlePoint++;
            }
            // Keeping a Feat alive keeps its coordinate array alive, so an extract must not pin
            // every link in the store just to resolve endpoints: only what the scope kept.
            boolean interesting = !keep || passes(f, sc, box);
            if (g.kind.equals(LINE) && fromRef != null && toRef != null && needEnds != null
                    && g.points() >= 2 && interesting) {
                f.fromRef = fromRef;
                f.toRef = toRef;
                needEnds.add(f);
            }
            if (g.bucket.equals(Crs.NOT_A_CRS)) Acc.keep(acc.notACrsExamples, f);
            if (sc.lens) {
                String x = new String(frag, StandardCharsets.UTF_8);
                f.frag = x.length() > 20_000 ? x.substring(0, 20_000) + "\n… truncated at 20 kB" : x;
            }

            considered.incrementAndGet();
            if (keep && interesting) {
                // Bounded DURING the scan, not after: holding every passing geometry just to drop
                // most of them would OOM at scale. What survives is the first --max the scan
                // reached, in class-map order, and it is printed back as such -- "the first N" and
                // "a sample of N" are different claims.
                synchronized (feats) {
                    if (feats.size() < sc.max) feats.add(f);
                }
            }
        }
    }

    private static boolean passes(Feat f, Scope sc, double[] box) {
        Geom g = f.g;
        if (!sc.wantsKind(g.kind)) return false;
        if (g.points() == 0) return false;
        if (sc.idLike != null && !f.id.contains(sc.idLike)) return false;
        if (!sc.srsWanted.isEmpty()) {
            String b = g.outOfRange ? "out-of-range" : g.bucket;
            if (!sc.srsWanted.contains(b.toLowerCase(Locale.ROOT))) return false;
        }
        if (sc.pointsMin >= 0 && g.points() < sc.pointsMin) return false;
        if (sc.devMin >= 0 && !(f.maxDev >= sc.devMin)) return false;
        if (sc.ratioMin >= 0 && !(f.ratio >= sc.ratioMin)) return false;
        if (box != null && !g.outOfRange) {
            boolean hit = false;
            for (int i = 0; i + 1 < g.xy.length && !hit; i += 2) {
                hit = g.xy[i] >= box[0] && g.xy[i] <= box[2]
                        && g.xy[i + 1] >= box[1] && g.xy[i + 1] <= box[3];
            }
            if (!hit) return false;
        }
        return true;
    }

    // ================================================================== census

    private static int census(String[] args) throws Exception {
        Scope sc = new Scope();
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            int c = common(args, i, sc);
            if (c > 0) { i += c - 1; continue; }
            switch (a) {
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    if (a.startsWith("-")) { System.err.println("nx geo census: unknown option " + a); return 2; }
                    sc.dbs.add(Path.of(a));
                }
            }
        }
        if (sc.dbs.isEmpty()) { System.err.println("nx geo census: need --db <store>"); return 2; }

        PrintStream o = System.out;
        header(o, "nx geo census — what geometry this store holds", sc);
        Acc total = new Acc();
        long ms = 0;
        for (Path db : sc.dbs) {
            ScanResult r = scan(db, sc, false, o);
            if (r != null) { total.merge(r.acc()); ms += r.ms(); }
        }
        o.println();
        report(o, total, sc, ms);
        if (out != null) writeCensusTsv(out, total, o);
        o.println();
        o.println("nx geo is a DIAGNOSTIC, not a gate: no pass/fail, and it must not gate a chain.");
        o.println("A zero above is a statement about the store(s) named, not about the corpus.");
        return 0;
    }

    private static void report(PrintStream o, Acc a, Scope sc, long ms) {
        o.println(RULE);
        o.printf("1. TOTALS%n");
        o.println(RULE);
        o.printf("  objects read              %,d%n", a.objects);
        o.printf("  fragments unparseable     %,d%n", a.fragErrors);
        o.printf("  geometries                %,d%n", a.geoms);
        o.printf("  coordinate points         %,d%n", a.points);
        o.printf("  wall                      %,d ms%n", ms);
        if (a.geoms == 0) {
            o.println();
            o.println("  NO GEOMETRY IN SCOPE. That is a statement about this store, not the corpus.");
            return;
        }
        o.println();
        o.println("  by kind");
        a.byKind.forEach((k, v) -> o.printf("    %-12s %,12d%n", k, v));

        o.println();
        o.println(RULE);
        o.println("2. srsName — declared / absent / not-a-CRS");
        o.println(RULE);
        o.println("   Resolution is Projection.resolveCrs's: OGC URN -> AUTH:CODE, authority upper-cased,");
        o.println("   then the SAME proj4j CRSFactory the transformer uses. 'not-a-CRS' here is exactly a");
        o.println("   name that would take Projection's CRSError fallback.");
        long tot = a.geoms;
        for (String b : List.of(Crs.DECLARED, Crs.ABSENT, Crs.NOT_A_CRS)) {
            long v = a.bySrsBucket.getOrDefault(b, 0L);
            o.printf("    %-12s %,12d  %5.1f%%%s%n", b, v, 100.0 * v / Math.max(1, tot),
                    b.equals(Crs.ABSENT) ? "   (WGS84 ASSUMED — said, not implied)" : "");
        }
        o.printf("    %-12s %,12d  %5.1f%%   (excluded from map bounds)%n",
                "out-of-range", a.outOfRange, 100.0 * a.outOfRange / Math.max(1, tot));
        o.printf("    %-12s %,12d%n", "reprojected", a.reprojected);
        if (!a.bySrsValue.isEmpty()) {
            o.println();
            o.println("  distinct srsName values, verbatim");
            a.bySrsValue.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(20)
                    .forEach(e -> o.printf("    %-12s %,10d  \"%s\"%n",
                            Crs.bucket(e.getKey()), e.getValue(), e.getKey()));
        }
        o.println();
        o.printf("  what the EPIP reprojection pass does with these, at crs_to = %s%n", sc.crsTo);
        Map<String, String> fates = new LinkedHashMap<>();
        fates.put(FATE_ALREADY, "srsName IS crs_to as a raw string -> skipped, untouched");
        fates.put(FATE_UNDECLARED, "no srsName -> transformer is NULL, coordinates NEVER READ,");
        fates.put(FATE_STAMPED, "a transform runs (identity for a not-a-CRS name) and crs_to is");
        for (Map.Entry<String, String> e : fates.entrySet()) {
            o.printf("    %-26s %,10d   %s%n", e.getKey(), a.byFate.getOrDefault(e.getKey(), 0L),
                    e.getValue());
            if (e.getKey().equals(FATE_UNDECLARED)) {
                o.println("                                             and EXPORTED STILL UNDECLARED");
            } else if (e.getKey().equals(FATE_STAMPED)) {
                o.println("                                             STAMPED ON. Numbers and their order");
                o.println("                                             survive; the declaration does not.");
            }
        }
        if (!a.notACrsExamples.isEmpty()) {
            o.println();
            o.println("  not-a-CRS examples — a timezone stamped here is the classic one");
            for (Feat f : a.notACrsExamples.values()) {
                o.printf("    %-22s %-52s srsName=\"%s\"  %s%n", f.cls, f.id, f.g.srsRaw,
                        f.g.points() == 1
                                ? String.format(Locale.ROOT, "(%.5f, %.5f)", f.g.xy[1], f.g.xy[0])
                                : f.g.points() + " pts");
            }
        }

        long lines = a.byKind.getOrDefault(LINE, 0L);
        if (lines > 0) {
            o.println();
            o.println(RULE);
            o.println("3. LINES — point counts, and how wrong the straight line is");
            o.println(RULE);
            o.printf("   The chord first->last is exactly what OTP draws when it takes the%n");
            o.printf("   MissingProjectionInServiceLink branch (OTP_SERVICELINK_GEOMETRY §2), so 'max%n");
            o.printf("   deviation' below is 'how wrong that straight line is', per link.%n%n");
            for (String b : PT_BUCKETS) {
                Long v = a.pointHist.get(b);
                if (v == null) continue;
                o.printf("    %-20s %,10d  %5.1f%%%n", b, v, 100.0 * v / lines);
            }
            o.printf("%n    literal \"None\" posList  %,10d   (binds to an EMPTY%n",
                    a.noneLiteral);
            o.printf("                                        list, so the BEAN cannot see it — this arm%n");
            o.printf("                                        reads the fragment and can)%n");
            o.printf("    unparseable posList     %,10d%n", a.unparseable);
            if (a.devTotal > 0) {
                o.println();
                o.printf("    lines with >=3 points   %,10d%n", a.devTotal);
                o.printf("    max deviation from the straight line, metres%n");
                pct(o, a.devSample, "      ", a.sampleCapped);
                o.printf("      over 50 m             %,10d   %5.1f%%   <- OTP_SERVICELINK_GEOMETRY's headline%n",
                        a.devOver50, 100.0 * a.devOver50 / a.devTotal);
                o.printf("    true path / straight line%n");
                pctRatio(o, a.ratioSample, "      ", a.sampleCapped);
            }
        }

        if (sc.endpoints) {
            o.println();
            o.println(RULE);
            o.println("4. ENDPOINT GAPS — the truncated-link defect, stated as a predicate");
            o.println(RULE);
            o.println("   A multi-RouteLink ServiceLink that keeps only the FIRST link's");
            o.println("   geometry. It moves no element count and no attribute count; it leaves the polyline");
            o.println("   ending far from the ToPoint it claims to end at. THAT is checkable, and this is it:");
            o.println("   gap = distance from the polyline's first/last vertex to the resolved From/ToPoint.");
            o.println("   Endpoints are resolved through _id_idx, never through V2Store's edges.");
            o.printf("%n    links with both endpoints resolved   %,10d%n", a.gapResolved);
            o.printf("    endpoint ref not in this store       %,10d   (reported unresolved, never 0)%n",
                    a.gapUnresolved);
            if (a.gapResolved > 0) {
                o.printf("    max(fromGap,toGap), metres%n");
                pct(o, a.gapSample, "      ", a.sampleCapped);
            }
            o.printf("%n    VERDICTS at --gap-min %,d m%n", (long) sc.gapMin);
            Map<String, String> gloss = new LinkedHashMap<>();
            gloss.put(ENDPOINTS_OK, "the polyline starts and ends where the link says it does");
            gloss.put(AXIS_SWAPPED, "swapping the polyline's two axes lands it ON its endpoints:");
            gloss.put(SHORT_AT_TO, "starts right, STOPS SHORT of the ToPoint  <- truncated link");
            gloss.put(SHORT_AT_FROM, "ends right, starts away from the FromPoint");
            gloss.put(BOTH_ENDS_ADRIFT, "neither end is near its point: this is not this link's shape");
            for (Map.Entry<String, String> e : gloss.entrySet()) {
                long v = a.gapVerdicts.getOrDefault(e.getKey(), 0L);
                o.printf("      %-18s %,10d   %s%n", e.getKey(), v, e.getValue());
                if (e.getKey().equals(AXIS_SWAPPED) && v > 0) {
                    o.println("                                        the posList is (lon lat), measured"
                            + " against the SAME");
                    o.println("                                        object's From/ToPoint, whose"
                            + " <Longitude>/<Latitude>");
                    o.println("                                        elements are NAMED and therefore"
                            + " unambiguous.");
                    o.println("                                        THAT IS ALL THIS MEASURES. It says"
                            + " nothing about what");
                    o.println("                                        any reader assumes — see the"
                            + " VERDICT x srsName table");
                    o.println("                                        below for the only consumer question"
                            + " that is decided");
                    o.println("                                        by data rather than by a reader's"
                            + " choice. No element");
                    o.println("                                        count and no attribute count moves"
                            + " either way.");
                }
            }
            if (!a.verdictBySrs.isEmpty()) {
                o.println();
                o.println("    VERDICT x WHAT THE EPIP PASS DOES WITH ITS srsName");
                o.println("    Read the AXIS_SWAPPED row and nothing else, if you read one row: an");
                o.println("    axis-swapped polyline that is STAMPED_WITH_CRS_TO leaves this chain");
                o.println("    DECLARED as a CRS whose authority axis order is the opposite one, and a");
                o.println("    conformant GML reader is then RIGHT to place it thousands of km away.");
                o.println("    One that PASSES_THROUGH_UNDECLARED leaves it ambiguous instead.");
                o.printf("      %-18s %14s %14s %14s%n", "", "UNDECLARED", "STAMPED", "ALREADY_TARGET");
                for (String v : List.of(ENDPOINTS_OK, AXIS_SWAPPED, SHORT_AT_TO, SHORT_AT_FROM,
                                        BOTH_ENDS_ADRIFT)) {
                    o.printf("      %-18s %,14d %,14d %,14d%n", v,
                            a.verdictBySrs.getOrDefault(v + "\t" + FATE_UNDECLARED, 0L),
                            a.verdictBySrs.getOrDefault(v + "\t" + FATE_STAMPED, 0L),
                            a.verdictBySrs.getOrDefault(v + "\t" + FATE_ALREADY, 0L));
                }
            }
            for (String v : List.of(AXIS_SWAPPED, SHORT_AT_TO, SHORT_AT_FROM, BOTH_ENDS_ADRIFT)) {
                Map<String, Feat> ex = a.examples.get(v);
                if (ex == null || ex.isEmpty()) continue;
                o.printf("%n    %s — examples (the lowest ids, so a re-run diffs to zero)%n", v);
                for (Feat f : ex.values()) {
                    o.printf("      %-52s pts=%-5d fromGap=%,9.0fm toGap=%,9.0fm swapped=%,9.0fm Distance=%s%n",
                            f.id, f.g.points(), f.gapFrom, f.gapTo,
                            f.gapSwapped == Double.MAX_VALUE ? -1 : f.gapSwapped,
                            f.declaredDistance == null ? "(absent)" : f.declaredDistance);
                }
            }
        }

        o.println();
        o.println(RULE);
        o.println("5. BY CLASS AND FRAGMENT PATH");
        o.println(RULE);
        a.byClassPath.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed())
                .limit(40)
                .forEach(e -> {
                    String[] kp = e.getKey().split("\t", 2);
                    o.printf("    %-26s %-46s %,10d geoms  %,14d pts%n",
                            kp[0], kp.length > 1 ? kp[1] : "", e.getValue()[1], e.getValue()[2]);
                });
    }

    /// Percentiles over the whole population, not a sample, up to [Acc#SAMPLE_CAP]. When that cap
    /// bites it is PRINTED: past it the retained values depend on worker order and the quantiles
    /// stop being reproducible.
    private static void pct(PrintStream o, List<Double> xs, String pad, boolean capped) {
        if (xs.isEmpty()) { o.println(pad + "(none)"); return; }
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        o.printf("%smin %,.1f   median %,.1f   p75 %,.1f   p90 %,.1f   p99 %,.1f   max %,.1f   (n=%,d%s)%n",
                pad, s.get(0), q(s, .5), q(s, .75), q(s, .90), q(s, .99), s.get(s.size() - 1), s.size(),
                capped ? ", CAPPED — order-dependent" : ", complete");
    }

    private static void pctRatio(PrintStream o, List<Double> xs, String pad, boolean capped) {
        if (xs.isEmpty()) { o.println(pad + "(none)"); return; }
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        o.printf("%smedian %.3f   p75 %.3f   p90 %.3f   p99 %.3f   max %.1f   (n=%,d%s)%n",
                pad, q(s, .5), q(s, .75), q(s, .90), q(s, .99), s.get(s.size() - 1), s.size(),
                capped ? ", CAPPED — order-dependent" : ", complete");
    }

    private static double q(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.min(sorted.size() - 1L, Math.round(p * (sorted.size() - 1)));
        return sorted.get(i);
    }

    private static void writeCensusTsv(Path out, Acc a, PrintStream o) throws Exception {
        StringBuilder sb = new StringBuilder("class\tpath\tgeoms\tpoints\n");
        for (Map.Entry<String, long[]> e : a.byClassPath.entrySet()) {
            String[] kp = e.getKey().split("\t", 2);
            sb.append(kp[0]).append('\t').append(kp.length > 1 ? kp[1] : "")
              .append('\t').append(e.getValue()[1]).append('\t').append(e.getValue()[2]).append('\n');
        }
        Files.writeString(out, sb.toString());
        o.printf("%nwrote %s (%,d rows)%n", out, a.byClassPath.size());
    }

    // ================================================================== extract

    private static int extract(String[] args) throws Exception {
        Scope sc = new Scope();
        Path out = null, htmlOut = null;
        String title = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            int c = common(args, i, sc);
            if (c > 0) { i += c - 1; continue; }
            switch (a) {
                case "--out" -> out = Path.of(args[++i]);
                case "--html" -> htmlOut = Path.of(args[++i]);
                case "--title" -> title = args[++i];
                default -> {
                    if (a.startsWith("-")) { System.err.println("nx geo extract: unknown option " + a); return 2; }
                    sc.dbs.add(Path.of(a));
                }
            }
        }
        if (sc.dbs.isEmpty()) { System.err.println("nx geo extract: need --db <store>"); return 2; }
        if (out == null && htmlOut == null) {
            System.err.println("nx geo extract: need --out <file.geojson[.gz]> and/or --html <file.html>");
            return 2;
        }

        PrintStream o = System.out;
        header(o, "nx geo extract — GeoJSON, scoped", sc);
        List<Feat> all = new ArrayList<>();
        Acc total = new Acc();
        boolean trunc = false;
        long considered = 0;
        for (Path db : sc.dbs) {
            ScanResult r = scan(db, sc, true, o);
            if (r == null) continue;
            total.merge(r.acc());
            all.addAll(r.feats());
            trunc |= r.truncatedByMax();
            considered += r.considered();
        }
        if (all.size() > sc.max) {
            trunc = true;
            all = new ArrayList<>(all.subList(0, (int) sc.max));
        }
        o.println();
        o.printf("%,d geometries considered, %,d passed the scope, %,d written%s%n",
                considered, all.size(), all.size(), trunc ? "" : "");
        if (trunc) {
            o.println();
            o.println("  *** TRUNCATED AT --max=" + sc.max + ". This is a HARD CAP, not a sample: the");
            o.println("      features written are the first " + sc.max + " the scan REACHED, in class-map");
            o.println("      order, sorted by (class, id) for output only. The rest are NOT represented");
            o.println("      and are NOT a random draw. Narrow the scope (--bbox / --class / --dev-min)");
            o.println("      or raise --max knowing that 38,253,940 coordinate points exist nationally.");
        }
        String json = geojson(all, sc, total);
        if (out != null) {
            writeMaybeGz(out, json.getBytes(StandardCharsets.UTF_8));
            o.printf("wrote %s (%,d B)%n", out, Files.size(out));
        }
        if (htmlOut != null) {
            byte[] page = htmlPage(json.getBytes(StandardCharsets.UTF_8),
                    title != null ? title : ("nx geo — " + sc.dbs.get(0).getFileName()));
            Files.write(htmlOut, page);
            o.printf("wrote %s (%,d B, self-contained: no network, no basemap)%n",
                    htmlOut, page.length);
        }
        o.println();
        o.println("nx geo is a DIAGNOSTIC, not a gate. A zero is a statement about the store you named.");
        return 0;
    }

    // ================================================================== compare

    private static int compare(String[] args) throws Exception {
        Scope sc = new Scope();
        Path a = null, b = null, out = null, htmlOut = null;
        for (int i = 0; i < args.length; i++) {
            String x = args[i];
            int c = common(args, i, sc);
            if (c > 0) { i += c - 1; continue; }
            switch (x) {
                case "--a" -> a = Path.of(args[++i]);
                case "--b" -> b = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--html" -> htmlOut = Path.of(args[++i]);
                default -> { System.err.println("nx geo compare: unknown option " + x); return 2; }
            }
        }
        if (a == null || b == null) { System.err.println("nx geo compare: need --a and --b"); return 2; }

        PrintStream o = System.out;
        sc.max = Long.MAX_VALUE / 4;
        header(o, "nx geo compare — displacement, WITH A TOLERANCE", sc);
        o.println("EXPORT_AB §5.10 corrected EPIP_TRANSFORM dev. 6 TWICE because reprojected coordinates");
        o.println("are not bit-identical: delta ~1e-6 deg ~ 0.1 m. A comparison without a tolerance calls");
        o.println("that and a truncated link both 'geometry differs'. This one does not.");
        o.println();
        ScanResult ra = scan(a, sc, true, o);
        ScanResult rb = scan(b, sc, true, o);
        if (ra == null || rb == null) return 1;

        Map<String, Feat> ia = new LinkedHashMap<>(), ib = new LinkedHashMap<>();
        for (Feat f : ra.feats()) ia.putIfAbsent(key(f), f);
        for (Feat f : rb.feats()) ib.putIfAbsent(key(f), f);

        long onlyA = 0, onlyB = 0, identical = 0, moved = 0, shapeChanged = 0;
        List<Double> disp = new ArrayList<>();
        List<Feat> outFeats = new ArrayList<>();
        long[] hist = new long[6];      // 0 exact, <=0.1m, <=1m, <=10m, <=100m, >100m
        for (Map.Entry<String, Feat> e : ia.entrySet()) {
            Feat fa = e.getValue(), fb = ib.get(e.getKey());
            if (fb == null) { onlyA++; continue; }
            double[] xa = fa.g.xy, xb = fb.g.xy;
            if (xa.length != xb.length) {
                shapeChanged++;
                fb.dispVerdict = "SHAPE_CHANGED";
                fb.dispMax = Double.NaN;
                outFeats.add(fb);
                continue;
            }
            double max = 0, sum = 0;
            int n = xa.length / 2;
            for (int i = 0; i < n; i++) {
                double d = haversine(xa[i * 2], xa[i * 2 + 1], xb[i * 2], xb[i * 2 + 1]);
                sum += d;
                if (d > max) max = d;
            }
            fb.dispMax = max;
            fb.dispMean = n > 0 ? sum / n : 0;
            if (max == 0) { identical++; hist[0]++; fb.dispVerdict = "IDENTICAL"; continue; }
            moved++;
            if (disp.size() < 400_000) disp.add(max);
            if (max <= 0.15) { hist[1]++; fb.dispVerdict = "QUANTIZATION"; }
            else if (max <= 1) { hist[2]++; fb.dispVerdict = "SUB_METRE"; }
            else if (max <= 10) { hist[3]++; fb.dispVerdict = "MOVED_10M"; }
            else if (max <= 100) { hist[4]++; fb.dispVerdict = "MOVED_100M"; }
            else { hist[5]++; fb.dispVerdict = "MOVED_FAR"; }
            if (sc.dispMin < 0 || max >= sc.dispMin) outFeats.add(fb);
        }
        for (String k : ib.keySet()) if (!ia.containsKey(k)) onlyB++;

        o.println();
        o.println(RULE);
        o.println("DISPLACEMENT");
        o.println(RULE);
        o.printf("  joined on (class,id,version)   A %,d   B %,d%n", ia.size(), ib.size());
        o.printf("  only in A                      %,d%n", onlyA);
        o.printf("  only in B                      %,d%n", onlyB);
        o.printf("  identical to the last bit      %,d%n", identical);
        o.printf("  point count CHANGED            %,d   <- not a displacement; a different SHAPE.%n",
                shapeChanged);
        o.printf("                                        the truncated-link signature is here, not in the%n");
        o.printf("                                        histogram below.%n");
        o.printf("  displaced                      %,d%n", moved);
        String[] labels = {"exact", "<= 0.15 m (quantization, EXPORT_AB §5.10)", "<= 1 m",
                           "<= 10 m", "<= 100 m", "> 100 m"};
        for (int i = 0; i < hist.length; i++) o.printf("    %-42s %,10d%n", labels[i], hist[i]);
        if (!disp.isEmpty()) {
            o.printf("  max per-vertex displacement, metres%n");
            pct(o, disp, "    ", false);
        }

        for (Feat f : outFeats) f.gapFrom = f.gapTo = -1;
        String json = geojson(outFeats, sc, rb.acc());
        if (out != null) {
            writeMaybeGz(out, json.getBytes(StandardCharsets.UTF_8));
            o.printf("%nwrote %s (%,d features, %,d B)%n", out, outFeats.size(), Files.size(out));
        }
        if (htmlOut != null) {
            byte[] page = htmlPage(json.getBytes(StandardCharsets.UTF_8), "nx geo compare");
            Files.write(htmlOut, page);
            o.printf("wrote %s (%,d B)%n", htmlOut, page.length);
        }
        o.println();
        o.println("nx geo is a DIAGNOSTIC, not a gate.");
        return 0;
    }

    private static String key(Feat f) {
        return f.cls + "\0" + f.id + "\0" + f.version + "\0" + f.g.path;
    }

    // ================================================================== html

    private static int html(String[] args) throws Exception {
        Path extract = null, out = null;
        String title = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--extract" -> extract = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--title" -> title = args[++i];
                default -> { System.err.println("nx geo html: unknown option " + args[i]); return 2; }
            }
        }
        if (extract == null || out == null) {
            System.err.println("nx geo html: need --extract <f.geojson[.gz]> --out <f.html>");
            return 2;
        }
        byte[] raw = Files.readAllBytes(extract);
        if (extract.toString().endsWith(".gz")) {
            try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                raw = in.readAllBytes();
            }
        }
        byte[] page = htmlPage(raw, title != null ? title : ("nx geo — " + extract.getFileName()));
        Files.write(out, page);
        System.out.printf("wrote %s (%,d B) from %s (%,d B)%n", out, page.length, extract, raw.length);
        System.out.println("Self-contained: no network, no basemap, no tiles. Open it from disk.");
        return 0;
    }

    // ================================================================== selftest

    /// The geometry reader against synthetic fragments, with no store.
    ///
    /// `PolygonType` and `MultiSurfaceType` occur zero times in this corpus, so those arms have no
    /// positive control on disk. Reprojection is the same: the feeds that declare a resolvable
    /// `srsName` declare the target CRS, so the transform path is never exercised by real data.
    private static int selftest() {
        List<String> fail = new ArrayList<>();
        int n = 0;

        n++; check(fail, "Location Longitude/Latitude -> lon,lat",
                """
                <ScheduledStopPoint id="x"><Location><Longitude>9.5</Longitude>
                <Latitude>44.5</Latitude></Location></ScheduledStopPoint>""",
                gs -> gs.size() == 1 && gs.get(0).kind.equals(POINT)
                        && near(gs.get(0).xy[0], 9.5) && near(gs.get(0).xy[1], 44.5)
                        && gs.get(0).bucket.equals(Crs.ABSENT));

        n++; check(fail, "Location gml:pos is (lat lon), authority order, per Projection",
                """
                <ScheduledStopPoint id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <Location><gml:pos>44.5 9.5</gml:pos></Location></ScheduledStopPoint>""",
                gs -> gs.size() == 1 && near(gs.get(0).xy[0], 9.5) && near(gs.get(0).xy[1], 44.5));

        n++; check(fail, "gml:pos's own srsName beats its Location's",
                """
                <ScheduledStopPoint id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <Location srsName="Europe/Rome"><gml:pos srsName="EPSG:4326">44.5 9.5</gml:pos>
                </Location></ScheduledStopPoint>""",
                gs -> gs.size() == 1 && "EPSG:4326".equals(gs.get(0).srsRaw)
                        && gs.get(0).bucket.equals(Crs.DECLARED));

        n++; check(fail, "LineString posList, 3 points, metrics",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString><gml:posList>44.0 9.0 44.1 9.0 44.0 9.1</gml:posList>
                </gml:LineString></ServiceLink>""",
                gs -> {
                    if (gs.size() != 1 || gs.get(0).points() != 3) return false;
                    double[] xy = gs.get(0).xy;
                    // Chord runs due east; the middle vertex sits 0.1 deg of latitude north of its
                    // western end, so the deviation and the leg lengths are both known by hand.
                    return near(maxDeviation(xy) / 1000.0, 11.12, 0.05)
                            && near(pathLength(xy) / 1000.0, 24.81, 0.05)
                            && near(haversine(xy[0], xy[1], xy[4], xy[5]) / 1000.0, 7.99, 0.05);
                });

        n++; check(fail, "LineString as repeated gml:pos, not a posList",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString><gml:pos>44.0 9.0</gml:pos><gml:pos>44.1 9.1</gml:pos>
                </gml:LineString></ServiceLink>""",
                gs -> gs.size() == 1 && gs.get(0).points() == 2 && near(gs.get(0).xy[0], 9.0));

        n++; check(fail, "srsDimension=3 posList drops the third ordinate",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString srsDimension="3"><gml:posList>44.0 9.0 100 44.1 9.1 120
                </gml:posList></gml:LineString></ServiceLink>""",
                gs -> gs.size() == 1 && gs.get(0).points() == 2
                        && near(gs.get(0).xy[0], 9.0) && near(gs.get(0).xy[3], 44.1));

        n++; check(fail, "a literal None posList is its own bucket, not 'no geometry'",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString><gml:posList>None</gml:posList></gml:LineString></ServiceLink>""",
                gs -> gs.size() == 1 && gs.get(0).noneLiteral && gs.get(0).points() == 0);

        n++; check(fail, "srsName='Europe/Rome' is not-a-CRS and the coordinates are NOT moved",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString srsName="Europe/Rome"><gml:posList>44.0 9.0 44.1 9.1</gml:posList>
                </gml:LineString></ServiceLink>""",
                gs -> gs.size() == 1 && gs.get(0).bucket.equals(Crs.NOT_A_CRS)
                        && !gs.get(0).reprojected && near(gs.get(0).xy[1], 44.0));

        n++; check(fail, "an OGC URN resolves exactly as Projection.resolveCrs collapses it",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString srsName="urn:ogc:def:crs:EPSG::4326">
                <gml:posList>44.0 9.0 44.1 9.1</gml:posList></gml:LineString></ServiceLink>""",
                gs -> gs.size() == 1 && gs.get(0).bucket.equals(Crs.DECLARED)
                        && !gs.get(0).reprojected);

        n++; check(fail, "EPSG:32632 easting/northing reprojects into WGS84 and is flagged",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString srsName="EPSG:32632"><gml:posList>500000 4872000 501000 4873000
                </gml:posList></gml:LineString></ServiceLink>""",
                gs -> gs.size() == 1 && gs.get(0).reprojected
                        && gs.get(0).bucket.equals(Crs.DECLARED)
                        && near(gs.get(0).xy[0], 9.0, 0.05) && near(gs.get(0).xy[1], 44.0, 0.05));

        n++; check(fail, "Polygon: exterior + interior rings, in that order  [NO CORPUS CONTROL]",
                """
                <ScheduledStopPoint id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:Polygon srsName="EPSG:4326">
                  <gml:exterior><gml:LinearRing><gml:posList>44.0 9.0 44.0 9.1 44.1 9.1 44.0 9.0
                  </gml:posList></gml:LinearRing></gml:exterior>
                  <gml:interior><gml:LinearRing><gml:posList>44.02 9.02 44.02 9.04 44.04 9.04 44.02 9.02
                  </gml:posList></gml:LinearRing></gml:interior>
                </gml:Polygon></ScheduledStopPoint>""",
                gs -> gs.size() == 1 && gs.get(0).kind.equals(POLYGON)
                        && gs.get(0).rings.size() == 2
                        && gs.get(0).rings.get(0).length == 8
                        && near(gs.get(0).rings.get(1)[1], 44.02));

        n++; check(fail, "MultiSurface: every surfaceMember Polygon  [NO CORPUS CONTROL]",
                """
                <ScheduledStopPoint id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:MultiSurface><gml:surfaceMember><gml:Polygon><gml:exterior><gml:LinearRing>
                <gml:posList>44.0 9.0 44.0 9.1 44.1 9.1 44.0 9.0</gml:posList>
                </gml:LinearRing></gml:exterior></gml:Polygon></gml:surfaceMember>
                <gml:surfaceMember><gml:Polygon><gml:exterior><gml:LinearRing>
                <gml:posList>45.0 9.0 45.0 9.1 45.1 9.1 45.0 9.0</gml:posList>
                </gml:LinearRing></gml:exterior></gml:Polygon></gml:surfaceMember>
                </gml:MultiSurface></ScheduledStopPoint>""",
                gs -> gs.size() == 2 && gs.stream().allMatch(g -> g.kind.equals(POLYGON)));

        n++; check(fail, "a nested Quay centroid is its own geometry, at its own path",
                """
                <StopPlace id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <Centroid><Location><Longitude>9.5</Longitude><Latitude>44.5</Latitude></Location></Centroid>
                <quays><Quay id="q"><Centroid><Location><Longitude>9.6</Longitude>
                <Latitude>44.6</Latitude></Location></Centroid></Quay></quays></StopPlace>""",
                gs -> gs.size() == 2
                        && gs.get(0).path.equals("StopPlace/Centroid/Location")
                        && gs.get(1).path.equals("StopPlace/quays/Quay/Centroid/Location"));

        n++; check(fail, "a coordinate outside WGS84 is flagged, not silently drawn",
                """
                <ServiceLink id="x" xmlns:gml="http://www.opengis.net/gml/3.2">
                <gml:LineString><gml:posList>4872000 500000 4873000 501000</gml:posList>
                </gml:LineString></ServiceLink>""",
                gs -> gs.size() == 1 && gs.get(0).outOfRange);

        // The axis-swap predicate itself.
        n++; {
            double[] xy = {44.5, 9.5};   // written (lon lat) but read (lat lon) -> lon=44.5
            double straight = haversine(9.5, 44.5, 9.5, 44.5);
            boolean ok = straight == 0 && haversine(xy[0], xy[1], 9.5, 44.5) > 3_000_000;
            if (!ok) fail.add("axis-swap distance predicate");
        }

        PrintStream o = System.out;
        o.println(RULE);
        o.println("nx geo selftest — the geometry reader, no store");
        o.println(RULE);
        o.printf("%d checks, %d failure(s)%n", n, fail.size());
        for (String f : fail) o.println("  FAIL  " + f);
        if (fail.isEmpty()) {
            o.println();
            o.println("Polygon and MultiSurface have NO POSITIVE CONTROL in any store in this");
            o.println("workspace — they occur zero times — and neither does the reprojection path,");
            o.println("because the only two feeds that declare a resolvable srsName declare");
            o.println("EPSG:4326. Those three arms are checked here or nowhere.");
        }
        return fail.isEmpty() ? 0 : 1;
    }

    private static void check(List<String> fail, String name, String xml,
                              java.util.function.Predicate<List<Geom>> p) {
        try {
            XmlDoc doc = XmlDoc.parse(xml.getBytes(StandardCharsets.UTF_8));
            List<Geom> gs = new ArrayList<>();
            collect(doc.root, "", null, gs);
            if (!p.test(gs)) fail.add(name);
        } catch (Exception e) {
            fail.add(name + "  [threw " + e + "]");
        }
    }

    private static boolean near(double a, double b) { return near(a, b, 1e-9); }

    private static boolean near(double a, double b, double tol) { return Math.abs(a - b) <= tol; }

    // ================================================================== shared arg parsing

    /// Options every subcommand accepts. Returns the number of argv slots consumed, or 0.
    private static int common(String[] args, int i, Scope sc) {
        switch (args[i]) {
            case "--db" -> { sc.dbs.add(Path.of(args[i + 1])); return 2; }
            case "--class" -> { sc.only.addAll(List.of(args[i + 1].split(","))); return 2; }
            case "--skip-class" -> { sc.skip.addAll(List.of(args[i + 1].split(","))); return 2; }
            case "--all-classes" -> { sc.allClasses = true; return 1; }
            case "--kind" -> {
                for (String k : args[i + 1].split(",")) sc.kinds.add(k.trim().toLowerCase(Locale.ROOT));
                return 2;
            }
            case "--srs" -> {
                for (String k : args[i + 1].split(",")) sc.srsWanted.add(k.trim().toLowerCase(Locale.ROOT));
                return 2;
            }
            case "--bbox" -> {
                String[] p = args[i + 1].split(",");
                sc.bbox = new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                                        Double.parseDouble(p[2]), Double.parseDouble(p[3])};
                return 2;
            }
            case "--around" -> { sc.around = args[i + 1]; if (sc.radiusM < 0) sc.radiusM = 2000; return 2; }
            case "--radius" -> { sc.radiusM = metres(args[i + 1]); return 2; }
            case "--dev-min" -> { sc.devMin = Double.parseDouble(args[i + 1]); return 2; }
            case "--ratio-min" -> { sc.ratioMin = Double.parseDouble(args[i + 1]); return 2; }
            case "--gap-min" -> { sc.gapMin = Double.parseDouble(args[i + 1]); sc.gapMinSet = true; return 2; }
            case "--id" -> { sc.idLike = args[i + 1]; return 2; }
            case "--disp-min" -> { sc.dispMin = Double.parseDouble(args[i + 1]); return 2; }
            case "--points-min" -> { sc.pointsMin = Integer.parseInt(args[i + 1]); return 2; }
            case "--sample" -> { sc.sample = Long.parseLong(args[i + 1].replace("_", "")); return 2; }
            case "--workers" -> { sc.workers = Integer.parseInt(args[i + 1]); return 2; }
            case "--progress" -> { sc.progressSec = Integer.parseInt(args[i + 1]); return 2; }
            case "--endpoints" -> { sc.endpoints = true; return 1; }
            case "--lens" -> { sc.lens = true; return 1; }
            case "--max" -> { sc.max = Long.parseLong(args[i + 1].replace("_", "")); return 2; }
            case "--max-index" -> { sc.maxIndex = Long.parseLong(args[i + 1].replace("_", "")); return 2; }
            case "--precision" -> { sc.precision = Integer.parseInt(args[i + 1]); return 2; }
            case "--crs-to" -> { sc.crsTo = args[i + 1]; return 2; }
            default -> { return 0; }
        }
    }

    private static double metres(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("km")) return Double.parseDouble(t.substring(0, t.length() - 2)) * 1000;
        if (t.endsWith("m")) return Double.parseDouble(t.substring(0, t.length() - 1));
        return Double.parseDouble(t);
    }

    private static void header(PrintStream o, String what, Scope sc) {
        o.println(RULE);
        o.println(what);
        o.println(RULE);
        o.printf("stores      %s%n", sc.dbs);
        o.printf("scope       %s%n", sc.describe());
        o.printf("workers     %d%n", sc.workers);
        o.printf("model       GeoClasses.all() = %,d classes carrying a direct geo field;"
                + " GEO_FIELD_TYPES = %d%n", GeoClasses.all().size(), GeoClasses.GEO_FIELD_TYPES.size());
        o.printf("registry    %,d registered classes%n", ModelRegistry.classes().size());
        o.println("coordinates READ FROM THE STORED FRAGMENT BYTES, not from the bean — what would be");
        o.println("            emitted, not what the binding reconstructs. Output is WGS84 lon,lat");
        o.println("            (RFC 7946); a resolvable non-WGS84 srsName is reprojected and flagged.");
        o.println();
    }

    // ================================================================== GeoJSON

    private static String geojson(List<Feat> feats, Scope sc, Acc acc) {
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("{\"type\":\"FeatureCollection\",");
        sb.append("\"nx\":{\"tool\":\"nx geo\",");
        sb.append("\"generated\":\"").append(java.time.Instant.now()).append("\",");
        sb.append("\"scope\":").append(jstr(sc.describe())).append(',');
        sb.append("\"stores\":[");
        for (int i = 0; i < sc.dbs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jstr(sc.dbs.get(i).toString()));
        }
        sb.append("],\"features_written\":").append(feats.size());
        sb.append(",\"geometries_seen\":").append(acc.geoms);
        sb.append(",\"points_seen\":").append(acc.points);
        sb.append(",\"note\":").append(jstr(
                "A DIAGNOSTIC, NEVER A GATE. Coordinates come from the stored fragment bytes."
                + " srs_bucket 'absent' means WGS84 was ASSUMED, not declared; 'not-a-CRS'"
                + " covers values like a timezone stamped where a CRS belongs."));
        sb.append("},\"features\":[");
        boolean first = true;
        for (Feat f : feats) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("{\"type\":\"Feature\",\"geometry\":");
            Geom g = f.g;
            if (g.kind.equals(POINT)) {
                sb.append("{\"type\":\"Point\",\"coordinates\":");
                coord(sb, g.xy, 0, sc.precision);
                sb.append('}');
            } else if (g.kind.equals(POLYGON) && g.rings != null) {
                sb.append("{\"type\":\"Polygon\",\"coordinates\":[");
                for (int r = 0; r < g.rings.size(); r++) {
                    if (r > 0) sb.append(',');
                    ring(sb, g.rings.get(r), sc.precision);
                }
                sb.append("]}");
            } else {
                sb.append("{\"type\":\"LineString\",\"coordinates\":");
                ring(sb, g.xy, sc.precision);
                sb.append('}');
            }
            sb.append(",\"properties\":{");
            p(sb, "store", f.store, true);
            p(sb, "class", f.cls, false);
            p(sb, "id", f.id, false);
            p(sb, "version", f.version, false);
            p(sb, "path", g.path, false);
            p(sb, "kind", g.kind, false);
            p(sb, "srs", g.srsRaw == null ? "" : g.srsRaw, false);
            p(sb, "srs_bucket", g.outOfRange ? "out-of-range" : g.bucket, false);
            n(sb, "reprojected", g.reprojected ? 1 : 0);
            n(sb, "assumed_wgs84", g.bucket.equals(Crs.DECLARED) ? 0 : 1);
            p(sb, "epip_fate", g.fate == null ? "" : g.fate, false);
            n(sb, "points", g.points());
            if (g.kind.equals(LINE) && g.points() >= 2) {
                n(sb, "len_m", round(f.pathLen, 1));
                n(sb, "straight_m", round(f.straightLen, 1));
                n(sb, "ratio", round(f.ratio, 4));
                n(sb, "maxdev_m", round(f.maxDev, 1));
            }
            if (f.declaredDistance != null) p(sb, "Distance", f.declaredDistance, false);
            if (f.gapFrom >= 0 || f.gapFrom == -2) {
                p(sb, "from_ref", f.fromRef == null ? "" : f.fromRef, false);
                p(sb, "to_ref", f.toRef == null ? "" : f.toRef, false);
                p(sb, "gap_verdict", f.gapVerdict == null ? UNRESOLVED : f.gapVerdict, false);
                if (f.gapFrom >= 0 && f.gapTo >= 0) {
                    n(sb, "gap_from_m", round(f.gapFrom, 1));
                    n(sb, "gap_to_m", round(f.gapTo, 1));
                    if (f.gapSwapped >= 0 && f.gapSwapped != Double.MAX_VALUE) {
                        n(sb, "gap_axis_swapped_m", round(f.gapSwapped, 1));
                    }
                }
            }
            if (f.dispVerdict != null) {
                p(sb, "disp_verdict", f.dispVerdict, false);
                if (!Double.isNaN(f.dispMax)) {
                    n(sb, "disp_max_m", round(f.dispMax, 3));
                    n(sb, "disp_mean_m", round(f.dispMean, 3));
                }
            }
            if (f.frag != null) p(sb, "fragment", f.frag, false);
            sb.append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static double round(double v, int d) {
        double m = Math.pow(10, d);
        return Math.round(v * m) / m;
    }

    private static void coord(StringBuilder sb, double[] xy, int i, int prec) {
        sb.append('[').append(fmt(xy[i * 2], prec)).append(',').append(fmt(xy[i * 2 + 1], prec)).append(']');
    }

    private static void ring(StringBuilder sb, double[] xy, int prec) {
        sb.append('[');
        for (int i = 0; i * 2 + 1 < xy.length; i++) {
            if (i > 0) sb.append(',');
            coord(sb, xy, i, prec);
        }
        sb.append(']');
    }

    private static String fmt(double v, int prec) {
        String s = String.format(Locale.ROOT, "%." + prec + "f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() || s.equals("-") ? "0" : s;
    }

    private static void p(StringBuilder sb, String k, String v, boolean first) {
        if (!first) sb.append(',');
        sb.append(jstr(k)).append(':').append(jstr(v));
    }

    private static void n(StringBuilder sb, String k, double v) {
        sb.append(',').append(jstr(k)).append(':');
        if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append((long) v);
        else sb.append(v);
    }

    private static String jstr(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                // '<' is escaped so a --lens fragment can never terminate the <script> element the
                // payload is embedded in. \u003c is plain JSON; JSON.parse restores it.
                case '<' -> b.append("\\u003c");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static void writeMaybeGz(Path out, byte[] json) throws Exception {
        if (out.toString().endsWith(".gz")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (OutputStream gz = new GZIPOutputStream(bo)) { gz.write(json); }
            Files.write(out, bo.toByteArray());
        } else {
            Files.write(out, json);
        }
    }

    // ================================================================== the self-contained page

    /// One file, no network. The payload is inlined raw below 4 MB and gzip+base64 above it,
    /// inflated with `DecompressionStream('gzip')` — no library. Rendering is canvas plus a uniform
    /// grid for picking.
    ///
    /// No coastline: the graticule, labelled degrees, scale bar and cursor readout are here, but a
    /// decorative outline is indistinguishable from a basemap that failed to load.
    static byte[] htmlPage(byte[] json, String title) throws Exception {
        String payload;
        String mode;
        if (json.length < 4 << 20) {
            payload = new String(json, StandardCharsets.UTF_8);
            mode = "raw";
        } else {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (OutputStream gz = new GZIPOutputStream(bo)) { gz.write(json); }
            payload = Base64.getEncoder().encodeToString(bo.toByteArray());
            mode = "gzip";
        }
        String tpl = PAGE
                .replace("__TITLE__", esc(title))
                .replace("__MODE__", mode)
                .replace("__RAWLEN__", String.valueOf(json.length));
        int at = tpl.indexOf("__PAYLOAD__");
        String head = tpl.substring(0, at), tail = tpl.substring(at + "__PAYLOAD__".length());
        ByteArrayOutputStream out = new ByteArrayOutputStream(json.length + tpl.length() + 1024);
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.write(tail.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>__TITLE__</title>
            <style>
            :root{--bg:#12151a;--fg:#dfe4ec;--dim:#8a94a6;--line:#2a3140;--panel:#191d25;--accent:#7fb2ff}
            *{box-sizing:border-box}
            html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);
              font:13px/1.45 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
            #app{display:flex;height:100%}
            #main{flex:1;position:relative;min-width:0}
            canvas{display:block;width:100%;height:100%;cursor:crosshair}
            #side{width:420px;max-width:45vw;border-left:1px solid var(--line);background:var(--panel);
              overflow:auto;padding:12px 14px}
            h1{font-size:13px;margin:0 0 8px;letter-spacing:.02em}
            .sub{color:var(--dim);font-size:11px;margin:0 0 12px}
            .warn{border:1px solid #6b4a12;background:#211a0d;color:#e8c97a;padding:8px 10px;
              border-radius:4px;font-size:11px;margin:0 0 12px}
            fieldset{border:1px solid var(--line);border-radius:4px;margin:0 0 12px;padding:8px 10px}
            legend{color:var(--dim);font-size:11px;padding:0 4px}
            label{display:block;margin:2px 0;cursor:pointer}
            select,input{width:100%;background:#0e1116;color:var(--fg);border:1px solid var(--line);
              border-radius:3px;padding:4px 6px;font:inherit}
            .legend div{display:flex;align-items:center;gap:6px;margin:2px 0}
            .sw{width:12px;height:12px;border-radius:2px;flex:0 0 auto}
            table{width:100%;border-collapse:collapse;font-size:11px}
            td{padding:2px 4px;vertical-align:top;border-bottom:1px solid #20252e;word-break:break-all}
            td.k{color:var(--dim);white-space:nowrap;width:34%}
            pre{white-space:pre-wrap;word-break:break-all;background:#0e1116;border:1px solid var(--line);
              border-radius:3px;padding:6px;font-size:10.5px;max-height:340px;overflow:auto}
            #hud{position:absolute;left:8px;bottom:8px;background:rgba(18,21,26,.86);border:1px solid var(--line);
              border-radius:4px;padding:5px 8px;font-size:11px;color:var(--dim);pointer-events:none}
            #scale{position:absolute;right:12px;bottom:10px;color:var(--dim);font-size:11px;text-align:center;
              pointer-events:none}
            #scale .bar{height:3px;background:var(--fg);margin-bottom:2px}
            #err{padding:20px;color:#ff9c9c}
            button{background:#0e1116;color:var(--fg);border:1px solid var(--line);border-radius:3px;
              padding:4px 8px;font:inherit;cursor:pointer}
            </style></head><body>
            <div id="app">
              <div id="main"><canvas id="c"></canvas>
                <div id="hud">—</div>
                <div id="scale"><div class="bar"></div><span>—</span></div>
              </div>
              <div id="side">
                <h1>__TITLE__</h1>
                <p class="sub" id="meta">loading…</p>
                <div class="warn">No basemap. No tiles. No network of any kind — this file opens from
                  disk. The reference is the graticule (labelled degrees), the scale bar and the cursor
                  readout. There is deliberately <b>no coastline</b>: a decorative outline is
                  indistinguishable from a basemap that failed to load.</div>
                <fieldset><legend>colour by</legend><select id="mode"></select>
                  <div class="legend" id="legend"></div></fieldset>
                <fieldset><legend>filter (id / class / srsName substring)</legend>
                  <input id="q" placeholder="type to filter…"><div class="sub" id="qn"></div></fieldset>
                <fieldset><legend>selection</legend><div id="sel">click a feature</div></fieldset>
                <fieldset><legend>view</legend><button id="fit">fit all</button>
                  <div class="sub">drag to pan · wheel to zoom</div></fieldset>
              </div>
            </div>
            <script id="payload" type="application/octet-stream">__PAYLOAD__</script>
            <script>
            (async function(){
            const MODE="__MODE__", RAWLEN=__RAWLEN__;
            const side=document.getElementById('side');
            let text=document.getElementById('payload').textContent;
            if(MODE==='gzip'){
              if(!('DecompressionStream' in window)){
                document.body.innerHTML='<div id="err">This page embeds its data gzip+base64 and this'
                  +' browser has no DecompressionStream. Re-run <code>nx geo html</code> on a smaller'
                  +' extract (under 4 MB is embedded raw), or open the .geojson directly.</div>';
                return;
              }
              const bin=Uint8Array.from(atob(text.trim()),c=>c.charCodeAt(0));
              const ds=new DecompressionStream('gzip');
              const buf=await new Response(new Blob([bin]).stream().pipeThrough(ds)).arrayBuffer();
              text=new TextDecoder().decode(buf);
            }
            const fc=JSON.parse(text);
            const nx=fc.nx||{};
            const feats=fc.features||[];

            // ---- flatten to a draw list -------------------------------------------------
            const F=[];
            for(const f of feats){
              const g=f.geometry, p=f.properties||{};
              let parts=[];
              if(!g) continue;
              if(g.type==='Point') parts=[[g.coordinates]];
              else if(g.type==='LineString') parts=[g.coordinates];
              else if(g.type==='Polygon') parts=g.coordinates;
              else if(g.type==='MultiLineString') parts=g.coordinates;
              else continue;
              let x0=1e9,y0=1e9,x1=-1e9,y1=-1e9;
              for(const part of parts) for(const c of part){
                if(c[0]<x0)x0=c[0]; if(c[0]>x1)x1=c[0];
                if(c[1]<y0)y0=c[1]; if(c[1]>y1)y1=c[1];
              }
              F.push({p:p,parts:parts,type:g.type,bb:[x0,y0,x1,y1],
                      hay:((p.id||'')+' '+(p.class||'')+' '+(p.srs||'')+' '+(p.path||'')).toLowerCase()});
            }

            // ---- colour modes -----------------------------------------------------------
            const SRSCOL={'declared':'#5ec8a0','absent':'#7fb2ff','not-a-CRS':'#ff7676',
                          'out-of-range':'#ffb454'};
            function bands(v,stops,cols){for(let i=0;i<stops.length;i++)if(v<=stops[i])return cols[i];
              return cols[cols.length-1];}
            const RAMP=['#3d5a8a','#5ec8a0','#d8d05a','#ff9a4a','#ff5a5a'];
            const MODES={
              'srsName bucket (declared / absent / not-a-CRS)':{
                f:p=>SRSCOL[p.srs_bucket]||'#888',
                legend:()=>Object.entries(SRSCOL).map(([k,v])=>[v,k+
                  (k==='absent'?'  — WGS84 assumed, not declared':
                   k==='not-a-CRS'?'  — e.g. a timezone where a CRS belongs':'')])},
              'max deviation from the straight line':{
                f:p=>p.maxdev_m==null?'#454b58':bands(p.maxdev_m,[10,50,200,1000],RAMP),
                legend:()=>[[RAMP[0],'<= 10 m'],[RAMP[1],'<= 50 m'],[RAMP[2],'<= 200 m'],
                            [RAMP[3],'<= 1 km'],[RAMP[4],'> 1 km'],['#454b58','n/a (point, or < 3 pts)']]},
              'path / straight-line ratio':{
                f:p=>p.ratio==null?'#454b58':bands(p.ratio,[1.001,1.1,1.3,1.7],RAMP),
                legend:()=>[[RAMP[0],'1.000 (straight)'],[RAMP[1],'<= 1.10'],[RAMP[2],'<= 1.30'],
                            [RAMP[3],'<= 1.70'],[RAMP[4],'> 1.70'],['#454b58','n/a']]},
              'point count':{
                f:p=>bands(p.points||0,[2,9,49,249],RAMP),
                legend:()=>[[RAMP[0],'<= 2 (already a straight line)'],[RAMP[1],'<= 9'],
                            [RAMP[2],'<= 49'],[RAMP[3],'<= 249'],[RAMP[4],'> 249']]},
              'endpoint verdict (truncated-link shape)':{
                f:p=>({'ENDPOINTS_OK':'#3d5a8a','AXIS_SWAPPED':'#ff5ada','SHORT_AT_TO':'#ff5a5a',
                       'SHORT_AT_FROM':'#ff9a4a','BOTH_ENDS_ADRIFT':'#d8d05a'})[p.gap_verdict]||'#454b58',
                legend:()=>[['#3d5a8a','ENDPOINTS_OK'],
                            ['#ff5ada','AXIS_SWAPPED — the posList is (lon lat); every reader here'
                                       +' assumes (lat lon)'],
                            ['#ff5a5a','SHORT_AT_TO — starts right, stops short: a truncated link'],
                            ['#ff9a4a','SHORT_AT_FROM'],
                            ['#d8d05a','BOTH_ENDS_ADRIFT — not this link\\'s shape at all'],
                            ['#454b58','not resolved (run with --endpoints)']]},
              'endpoint gap, metres':{
                f:p=>{const g=Math.max(p.gap_from_m==null?-1:p.gap_from_m,p.gap_to_m==null?-1:p.gap_to_m);
                      return g<0?'#454b58':bands(g,[25,100,250,1000],RAMP);},
                legend:()=>[[RAMP[0],'<= 25 m'],[RAMP[1],'<= 100 m'],[RAMP[2],'<= 250 m'],
                            [RAMP[3],'<= 1 km'],[RAMP[4],'> 1 km'],
                            ['#454b58','not resolved (run with --endpoints)']]},
              'displacement A -> B':{
                f:p=>p.disp_verdict==null?'#454b58':
                     p.disp_verdict==='SHAPE_CHANGED'?'#ff5ada':
                     bands(p.disp_max_m==null?0:p.disp_max_m,[0.0001,0.15,1,10],RAMP),
                legend:()=>[[RAMP[0],'identical'],[RAMP[1],'<= 0.15 m (quantization)'],
                            [RAMP[2],'<= 1 m'],[RAMP[3],'<= 10 m'],[RAMP[4],'> 10 m'],
                            ['#ff5ada','SHAPE_CHANGED — a different shape, not a displacement'],
                            ['#454b58','n/a']]},
              'store':{f:p=>{let h=0;const s=p.store||'';for(let i=0;i<s.length;i++)h=(h*31+s.charCodeAt(i))|0;
                        return 'hsl('+((h>>>0)%360)+',62%,62%)';},
                legend:()=>[...new Set(F.map(f=>f.p.store))].slice(0,12).map(s=>{
                        let h=0;for(let i=0;i<s.length;i++)h=(h*31+s.charCodeAt(i))|0;
                        return ['hsl('+((h>>>0)%360)+',62%,62%)',s];})}
            };
            const sel=document.getElementById('mode');
            for(const k of Object.keys(MODES)){const o=document.createElement('option');o.textContent=k;sel.append(o);}
            let mode=Object.keys(MODES)[0];

            // ---- view -------------------------------------------------------------------
            const cv=document.getElementById('c'), ctx=cv.getContext('2d');
            let V={cx:0,cy:0,s:1}, W=0,H=0, DPR=Math.min(2,window.devicePixelRatio||1);
            function resize(){const r=cv.getBoundingClientRect();W=r.width;H=r.height;
              cv.width=W*DPR;cv.height=H*DPR;ctx.setTransform(DPR,0,0,DPR,0,0);draw();}
            function toPx(x,y){return [(x-V.cx)*V.s+W/2,(V.cy-y)*V.s*aspect()+H/2];}
            function toDeg(px,py){return [(px-W/2)/V.s+V.cx, V.cy-(py-H/2)/(V.s*aspect())];}
            function aspect(){return 1/Math.max(0.05,Math.cos(V.cy*Math.PI/180));}
            function fit(list){
              let x0=1e9,y0=1e9,x1=-1e9,y1=-1e9,n=0;
              for(const f of list){if(f.p.srs_bucket==='out-of-range')continue;
                x0=Math.min(x0,f.bb[0]);y0=Math.min(y0,f.bb[1]);
                x1=Math.max(x1,f.bb[2]);y1=Math.max(y1,f.bb[3]);n++;}
              if(!n){V={cx:12,cy:43,s:60};return;}
              V.cx=(x0+x1)/2;V.cy=(y0+y1)/2;
              const dx=Math.max(1e-5,x1-x0), dy=Math.max(1e-5,y1-y0);
              V.s=Math.min((W-60)/dx,(H-60)/(dy*aspect()));
            }

            let shown=F;
            function draw(){
              ctx.clearRect(0,0,W,H);
              graticule();
              const cf=MODES[mode].f;
              const pts=[];
              ctx.lineWidth=1.4;ctx.lineJoin='round';ctx.lineCap='round';
              for(const f of shown){
                if(f.p.srs_bucket==='out-of-range')continue;
                const col=cf(f.p);
                if(f.type==='Point'){pts.push([f,col]);continue;}
                ctx.strokeStyle=col;
                if(f.type==='Polygon')ctx.fillStyle=col+'33';
                ctx.beginPath();
                for(const part of f.parts){
                  let first=true;
                  for(const c of part){const q=toPx(c[0],c[1]);
                    if(first){ctx.moveTo(q[0],q[1]);first=false;}else ctx.lineTo(q[0],q[1]);}
                  if(f.type==='Polygon')ctx.closePath();
                }
                if(f.type==='Polygon')ctx.fill();
                ctx.stroke();
              }
              const r=V.s>4000?3.2:V.s>400?2.2:1.5;
              for(const [f,col] of pts){const q=toPx(f.parts[0][0][0],f.parts[0][0][1]);
                ctx.fillStyle=col;ctx.beginPath();ctx.arc(q[0],q[1],r,0,6.2832);ctx.fill();}
              if(picked){
                ctx.strokeStyle='#fff';ctx.lineWidth=2.6;ctx.beginPath();
                for(const part of picked.parts){let first=true;
                  for(const c of part){const q=toPx(c[0],c[1]);
                    if(first){ctx.moveTo(q[0],q[1]);first=false;}else ctx.lineTo(q[0],q[1]);}}
                if(picked.type==='Point'){const q=toPx(picked.parts[0][0][0],picked.parts[0][0][1]);
                  ctx.arc(q[0],q[1],6,0,6.2832);}
                ctx.stroke();
              }
              scalebar();
            }
            function niceStep(spanDeg){
              const steps=[10,5,2,1,.5,.2,.1,.05,.02,.01,.005,.002,.001,.0005,.0002,.0001];
              for(const s of steps) if(spanDeg/s>=4) return s;
              return 0.00005;
            }
            function graticule(){
              const a=toDeg(0,H), b=toDeg(W,0);
              const stepX=niceStep(b[0]-a[0]), stepY=niceStep(b[1]-a[1]);
              ctx.strokeStyle='#20252e';ctx.fillStyle='#5b6577';ctx.lineWidth=1;
              ctx.font='10px ui-monospace,monospace';
              for(let x=Math.ceil(a[0]/stepX)*stepX;x<=b[0];x+=stepX){
                const q=toPx(x,0);ctx.beginPath();ctx.moveTo(q[0],0);ctx.lineTo(q[0],H);ctx.stroke();
                ctx.fillText(x.toFixed(stepX<0.01?4:stepX<1?2:0)+'°',q[0]+3,12);
              }
              for(let y=Math.ceil(a[1]/stepY)*stepY;y<=b[1];y+=stepY){
                const q=toPx(0,y);ctx.beginPath();ctx.moveTo(0,q[1]);ctx.lineTo(W,q[1]);ctx.stroke();
                ctx.fillText(y.toFixed(stepY<0.01?4:stepY<1?2:0)+'°',3,q[1]-3);
              }
            }
            function scalebar(){
              const el=document.getElementById('scale');
              const mPerPx=111320*Math.cos(V.cy*Math.PI/180)/V.s;
              let target=140*mPerPx;
              const p=Math.pow(10,Math.floor(Math.log10(target)));
              const n=[1,2,5,10].find(k=>k*p>=target/2)*p;
              el.querySelector('.bar').style.width=(n/mPerPx).toFixed(0)+'px';
              el.querySelector('span').textContent=n>=1000?(n/1000)+' km':n+' m';
            }

            // ---- picking ----------------------------------------------------------------
            let picked=null;
            function pick(px,py){
              const d=toDeg(px,py), tolX=14/V.s, tolY=14/(V.s*aspect());
              let best=null,bestD=1e18;
              for(const f of shown){
                if(f.p.srs_bucket==='out-of-range')continue;
                if(d[0]<f.bb[0]-tolX||d[0]>f.bb[2]+tolX||d[1]<f.bb[1]-tolY||d[1]>f.bb[3]+tolY)continue;
                for(const part of f.parts) for(let i=0;i<part.length;i++){
                  const c=part[i];
                  const dx=(c[0]-d[0])/tolX, dy=(c[1]-d[1])/tolY, dd=dx*dx+dy*dy;
                  if(dd<bestD){bestD=dd;best=f;}
                }
              }
              return bestD<=1.6?best:null;
            }
            const ORDER=['class','id','version','path','kind','points','srs','srs_bucket',
              'assumed_wgs84','reprojected','len_m','straight_m','ratio','maxdev_m','Distance',
              'from_ref','to_ref','gap_verdict','gap_from_m','gap_to_m','gap_axis_swapped_m',
              'disp_verdict','disp_max_m','disp_mean_m','store'];
            function show(f){
              const el=document.getElementById('sel');
              if(!f){el.textContent='click a feature';return;}
              const p=f.p; let h='<table>';
              const keys=[...ORDER.filter(k=>p[k]!==undefined),
                          ...Object.keys(p).filter(k=>!ORDER.includes(k)&&k!=='fragment')];
              for(const k of keys) h+='<tr><td class="k">'+k+'</td><td>'+esc(String(p[k]))+'</td></tr>';
              h+='</table>';
              if(p.srs_bucket==='not-a-CRS')
                h+='<p class="sub" style="color:#ff9c9c">not-a-CRS: this value does not resolve through'
                  +' the CRS factory the transformer uses.</p>';
              if(p.srs_bucket==='absent')
                h+='<p class="sub">absent: WGS84 was ASSUMED here. The source never said so.</p>';
              if(p.gap_verdict==='AXIS_SWAPPED')
                h+='<p class="sub" style="color:#ff9cf0">AXIS_SWAPPED: exchanging this polyline\\'s two'
                  +' axes lands it on its own endpoints, so the posList is (lon lat) where every reader'
                  +' in this stack assumes (lat lon). No element or attribute count moves.</p>';
              if(p.gap_verdict==='SHORT_AT_TO')
                h+='<p class="sub" style="color:#ff9c9c">SHORT_AT_TO: the polyline starts at its'
                  +' FromPoint and stops short of its ToPoint. That is the truncated-link'
                  +' signature.</p>';
              if(p.fragment) h+='<p class="sub">stored fragment (layer F, baked by --lens)</p><pre>'
                  +esc(p.fragment)+'</pre>';
              else h+='<p class="sub">Re-run <code>nx geo extract --lens</code> to bake the stored'
                  +' fragment into each feature, or <code>nx lens '+esc(p.id||'')+'</code> for every layer.</p>';
              el.innerHTML=h;
            }
            function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}

            // ---- interaction ------------------------------------------------------------
            let drag=null;
            cv.addEventListener('mousedown',e=>{drag={x:e.offsetX,y:e.offsetY,cx:V.cx,cy:V.cy,moved:0};});
            window.addEventListener('mouseup',e=>{
              if(drag&&drag.moved<4){picked=pick(e.offsetX,e.offsetY);show(picked);draw();}
              drag=null;});
            cv.addEventListener('mousemove',e=>{
              const d=toDeg(e.offsetX,e.offsetY);
              document.getElementById('hud').textContent=
                d[1].toFixed(5)+'°N  '+d[0].toFixed(5)+'°E   ·   '+shown.length.toLocaleString()+' shown';
              if(!drag)return;
              drag.moved+=Math.abs(e.movementX)+Math.abs(e.movementY);
              V.cx=drag.cx-(e.offsetX-drag.x)/V.s;
              V.cy=drag.cy+(e.offsetY-drag.y)/(V.s*aspect());
              draw();});
            cv.addEventListener('wheel',e=>{e.preventDefault();
              const before=toDeg(e.offsetX,e.offsetY);
              V.s*=Math.exp(-e.deltaY*0.0016);
              const after=toDeg(e.offsetX,e.offsetY);
              V.cx+=before[0]-after[0];V.cy+=before[1]-after[1];draw();},{passive:false});
            sel.addEventListener('change',()=>{mode=sel.value;legend();draw();});
            document.getElementById('fit').addEventListener('click',()=>{fit(shown);draw();});
            document.getElementById('q').addEventListener('input',e=>{
              const q=e.target.value.trim().toLowerCase();
              shown=q?F.filter(f=>f.hay.includes(q)):F;
              document.getElementById('qn').textContent=
                shown.length.toLocaleString()+' of '+F.length.toLocaleString()+' features';
              draw();});
            function legend(){
              const el=document.getElementById('legend');el.innerHTML='';
              for(const [c,t] of MODES[mode].legend()){
                const d=document.createElement('div');
                d.innerHTML='<span class="sw" style="background:'+c+'"></span><span>'+esc(t)+'</span>';
                el.append(d);}
            }
            window.addEventListener('resize',resize);

            document.getElementById('meta').innerHTML=
              esc((nx.tool||'nx geo')+' · '+(nx.spec||'')+' · '+(nx.generated||''))
              +'<br>'+F.length.toLocaleString()+' features drawn of '
              +(nx.geometries_seen||F.length).toLocaleString()+' geometries seen ('
              +(nx.points_seen||0).toLocaleString()+' coordinate points scanned)'
              +'<br>scope: '+esc(nx.scope||'—')
              +'<br>payload '+RAWLEN.toLocaleString()+' B, embedded '+MODE
              +'<br><b>A diagnostic, never a gate.</b> '+esc(nx.note||'');
            document.getElementById('qn').textContent=F.length.toLocaleString()+' features';
            legend();
            resize();
            fit(F);
            draw();
            })();
            </script></body></html>
            """;
}
