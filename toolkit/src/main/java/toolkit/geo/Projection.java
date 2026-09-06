package toolkit.geo;

// Reprojection on proj4j and the proj4j-epsg registry.
//
// Nothing here knows a profile: crsTo is a parameter and the geo classes come from the model. The
// policy — send every geo class to WGS84 — is one Ensemble.Operation registration in the driver.
// Package toolkit.geo rather than toolkit.model, which is reflection over the generated classes
// and depends on nothing outside the model.
//
// srsName RESOLUTION RULE:
//   * LocationStructure with a pos: pos.srsName, else location.srsName, else WGS84_URN — null and
//     empty both fall through;
//   * everything else: its own srsName, else WGS84_URN;
//   * the comparison against crsTo is on RAW STRINGS, so "EPSG:4326" != the URN form and an
//     EPSG:4326-labelled geometry still takes an identity transform plus a requantize;
//   * proj4j accepts "AUTH:CODE" only, so the OGC URN form is reduced to AUTH:CODE with the
//     authority uppercased; everything else passes through verbatim;
//   * an unparseable name logs once and falls back to a WGS84->crsTo transformer.
//
// AXIS ORDER: the EPSG database orders geographic CRS axes (lat, lon) and projected ones (easting,
// northing), while proj4j always works in (x = lon/easting, y = lat/northing). Transform swaps the
// pair on the geographic side, keyed off isGeographic(). KNOWN LIMIT (OPEN.md §16): an exotic
// northing-easting projected CRS would deviate.
//
// Precision: outputs are quantized as new BigDecimal(double) — the exact binary expansion — then
// setScale(n, HALF_UP).

import jakarta.xml.bind.JAXBElement;
import noi.netex.gml.AbstractRingPropertyType;
import noi.netex.gml.DirectPositionListType;
import noi.netex.gml.DirectPositionType;
import noi.netex.gml.LineStringType;
import noi.netex.gml.LinearRingType;
import noi.netex.gml.MultiSurfaceType;
import noi.netex.gml.PolygonType;
import noi.netex.gml.SurfacePropertyType;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import noi.netex.model.EntityStructure;
import noi.netex.model.LocationStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.util.Log;
import toolkit.util.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Projection {

    public static final String WGS84_URN = "urn:ogc:def:crs:EPSG::4326";

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();
    /// The transformer cache, keyed "{srsName}_{crsTo}".
    private static final Map<String, Transform> TRANSFORMERS = new HashMap<>();
    private static final Set<String> LOGGED_ONCE = new HashSet<>();
    /// [#isResolvableCrs] answers, so a frame default is decided once per distinct name.
    private static final Map<String, Boolean> RESOLVABLE = new HashMap<>();

    private static final Pattern URN_CRS = Pattern.compile("urn:ogc:def:crs:([^:]+):[^:]*:(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_CODE = Pattern.compile("([A-Za-z]+):(\\d+)");

    private Projection() {}

    // --------------------------------------------------------------- what a pass could not project
    //
    // Three counters, because three sites here silently produce coordinates the surrounding
    // document lies about:
    //
    //   * the CRS fallback is CACHED, and the log fires on the BUILD, so one line covers every
    //     later geometry resolving the same unparseable srsName. What is counted here is the USE.
    //   * both projectLocation arms leave coordinates in the SOURCE CRS when they cannot project.
    //     With crsTo = WGS84 the export then carries source-CRS numbers inside a document that
    //     says WGS84 — the one failure here that produces WRONG values rather than missing ones.
    //
    // The message strings are shared between the site, which refuses when strict, and the
    // aggregate, which warns when not.

    private static final String TAG = "projection";

    private static final String WHAT_FALLBACK =
            "geometries were transformed through a FALLBACK WGS84 source transform because their "
            + "srsName did not resolve (their coordinates are wrong unless they were WGS84 already)";
    private static final String WHAT_NO_COORDS =
            "locations carried neither a pos nor a latitude/longitude pair and were left unprojected";
    private static final String WHAT_SOURCE_CRS =
            "locations were left in their SOURCE CRS while the surrounding document claims WGS84";

    /// Cache keys whose transform came from the fallback arm, so a cache HIT can be counted too.
    private static final Set<String> FALLBACK_KEYS = new HashSet<>();

    private static final AtomicLong FALLBACK_USES = new AtomicLong();
    private static final AtomicLong LOCATIONS_NO_COORDS = new AtomicLong();
    private static final AtomicLong LOCATIONS_SOURCE_CRS = new AtomicLong();

    /// What one reprojection pass could not project, as reported by [#reportDropped].
    public record Dropped(long fallbackTransforms, long unprojectedLocations, long leftInSourceCrs) {

        public long total() {
            return fallbackTransforms + unprojectedLocations + leftInSourceCrs;
        }
    }

    /// Report the aggregates under the `droppedOutput` concern and reset them, returning what
    /// was reported. Idempotent: a second call after a first reports zero, which
    /// [Strictness#require] keeps silent.
    ///
    /// Called by the driver, not here: this class owns no pass — it is the body of an
    /// `Ensemble.Operation` registered per object — so only the caller knows when the pass ends.
    public static Dropped reportDropped(String tag) {
        Dropped d = new Dropped(FALLBACK_USES.getAndSet(0), LOCATIONS_NO_COORDS.getAndSet(0),
                LOCATIONS_SOURCE_CRS.getAndSet(0));
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", d.fallbackTransforms(), tag,
                WHAT_FALLBACK);
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", d.unprojectedLocations(), tag,
                WHAT_NO_COORDS);
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", d.leftInSourceCrs(), tag,
                WHAT_SOURCE_CRS);
        return d;
    }

    /// Count one occurrence, and — under `-Dtoolkit.strict.droppedOutput=true` — refuse at the
    /// site. The rest of the policy refuses at the harness boundary instead; this concern cannot,
    /// because the only boundary reachable from here is a shutdown hook, whose exception would
    /// print and leave the exit code 0.
    private static void countDropped(AtomicLong counter, String what) {
        long n = counter.incrementAndGet();
        if (Strictness.DROPPED_OUTPUT) Strictness.require(true, "droppedOutput", n, TAG, what);
    }

    // -------------------------------------------------------------------- Transform (axis-aware)

    /// A proj4j transform plus the axis-order flags that make
    /// [#apply] accept and return coordinates in the authority's (axis-order) convention —
    /// (lat, lon) on a geographic side, (easting, northing) on a projected side.
    public record Transform(CoordinateTransform ct, boolean srcLatLon, boolean dstLatLon) {

        /// transformer.transform(a, b) — authority-order in, authority-order out.
        public double[] apply(double a, double b) {
            ProjCoordinate out = ct.transform(
                    srcLatLon ? new ProjCoordinate(b, a) : new ProjCoordinate(a, b),
                    new ProjCoordinate());
            return dstLatLon ? new double[] {out.y, out.x} : new double[] {out.x, out.y};
        }

        /// transformer.transform(a, b, z) — z rides along untouched by the axis swap.
        public double[] apply(double a, double b, double z) {
            ProjCoordinate out = ct.transform(
                    srcLatLon ? new ProjCoordinate(b, a, z) : new ProjCoordinate(a, b, z),
                    new ProjCoordinate());
            return dstLatLon ? new double[] {out.y, out.x, out.z} : new double[] {out.x, out.y, out.z};
        }
    }

    /// The name→CRS resolution (see the class header for the exact rule).
    private static CoordinateReferenceSystem resolveCrs(String name) {
        String lookup = name;
        Matcher urn = URN_CRS.matcher(lookup);
        if (urn.matches()) lookup = urn.group(1) + ":" + urn.group(2);
        Matcher ac = AUTH_CODE.matcher(lookup);
        if (ac.matches()) lookup = ac.group(1).toUpperCase(Locale.ROOT) + ":" + ac.group(2);
        CoordinateReferenceSystem crs = CRS_FACTORY.createFromName(lookup);
        if (crs == null) throw new IllegalArgumentException("Unparseable CRS: " + name);
        return crs;
    }

    /// Whether `name` resolves to a CRS under the rule in the class header — no logging, no
    /// fallback, nothing counted. For a caller that has to decide whether a name is a CRS at all
    /// BEFORE any geometry is stamped with it; going through [#resolveTransform] instead would
    /// count the answer as a dropped geometry and fire the fallback ERROR.
    ///
    /// Answers from the same proj4j registry that [#resolveCrs] uses, so it cannot reject a name
    /// this class would go on to accept.
    public static boolean isResolvableCrs(String name) {
        if (name == null || name.isEmpty()) return false;
        synchronized (RESOLVABLE) {
            Boolean known = RESOLVABLE.get(name);
            if (known != null) return known;
            boolean ok;
            try {
                resolveCrs(name);
                ok = true;
            } catch (RuntimeException e) {
                ok = false;
            }
            RESOLVABLE.put(name, ok);
            return ok;
        }
    }

    private static Transform buildTransform(String srsName, String crsTo) {
        CoordinateReferenceSystem src = resolveCrs(srsName);
        CoordinateReferenceSystem dst = resolveCrs(crsTo);
        return new Transform(TRANSFORM_FACTORY.createTransform(src, dst),
                Boolean.TRUE.equals(src.isGeographic()), Boolean.TRUE.equals(dst.isGeographic()));
    }

    /// The shared cache + CRSError-fallback body of get_transformer_by_srs_name / project_polygon.
    public static Transform resolveTransform(String srsName, String crsTo) {
        String mapping = srsName + "_" + crsTo;
        Transform transformer = TRANSFORMERS.get(mapping);
        if (transformer == null) {
            try {
                transformer = buildTransform(srsName, crsTo);
            } catch (RuntimeException e) {                          // CRSError
                logOnce("Unknown transformation " + srsName + " for " + crsTo,
                        "Unknown transformation " + srsName + " for " + crsTo
                        + ", we now assume WGS84, and hope the target is available");
                FALLBACK_KEYS.add(mapping);
                transformer = buildTransform(WGS84_URN, crsTo);
            }
            TRANSFORMERS.put(mapping, transformer);
        }
        // Counted on every USE, cache hits included: the assumption is taken once per srsName and
        // paid once per geometry.
        if (!FALLBACK_KEYS.isEmpty() && FALLBACK_KEYS.contains(mapping)) {
            countDropped(FALLBACK_USES, WHAT_FALLBACK);
        }
        return transformer;
    }

    /// Fires once per distinct KEY, emitting `key + ":" + message`.
    private static synchronized void logOnce(String key, String message) {
        if (LOGGED_ONCE.add(key)) Log.error("%s:%s", key, message);
    }

    /// `a` unless it is null or empty, else `b`.
    private static String orDefault(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    // ------------------------------------------------- getTransformerBySrsName

    /// `location` is a LocationStructure or a gml LineString (
    /// `LocationStructure2 | LineString`). Returns null when the resolved source srs IS
    /// `crsTo` (string comparison) — i.e. nothing to do.
    public static Transform getTransformerBySrsName(Object location, String crsTo) {
        String srsName;
        if (location instanceof LocationStructure loc && loc.getPos() != null) {
            srsName = orDefault(loc.getPos().getSrsName(), orDefault(loc.getSrsName(), WGS84_URN));
        } else if (location instanceof LocationStructure loc) {
            srsName = orDefault(loc.getSrsName(), WGS84_URN);
        } else if (location instanceof LineStringType ls) {
            srsName = orDefault(ls.getSrsName(), WGS84_URN);
        } else {
            throw new IllegalArgumentException("Not a LocationStructure/LineString: " + location.getClass());
        }
        if (srsName.equals(crsTo)) return null;
        return resolveTransform(srsName, crsTo);
    }

    // ------------------------------------------------------------ projectLocation

    /// Project a location with the default quantize '0.000001'.
    public static boolean projectLocation(LocationStructure location, String crsTo) {
        return projectLocation(location, crsTo, "0.000001");
    }

    /// Project in place; return true when the location was actually modified (a real CRS
    /// transformation applied). Locations already in / assumed in the target CRS are untouched.
    /// Coordinate order is the srs authority order throughout — (lat, lon) for WGS84, matching
    /// the `<Pos>` convention of the feeds.
    public static boolean projectLocation(LocationStructure location, String crsTo, String quantize) {
        if (Objects.equals(location.getSrsName(), crsTo)) return false;

        int scale = new BigDecimal(quantize).scale();
        if (location.getPos() != null) {
            Transform transformer = getTransformerBySrsName(location, crsTo);
            if (transformer != null) {
                List<Double> v = location.getPos().getValue();
                double[] xy = transformer.apply(v.get(0), v.get(1));
                location.setSrsName(crsTo);
                location.setPos(new DirectPositionType()
                        .withValue(quantizeDouble(xy[0], scale), quantizeDouble(xy[1], scale))
                        .withSrsName(crsTo).withSrsDimension(BigInteger.TWO));
                return true;
            }
        } else if (location.getLatitude() != null && location.getLongitude() != null) {
            Transform transformer = getTransformerBySrsName(location, crsTo);
            if (transformer != null) {
                double[] xy = transformer.apply(
                        location.getLatitude().doubleValue(), location.getLongitude().doubleValue());
                location.setSrsName(crsTo);
                location.setPos(new DirectPositionType()
                        .withValue(quantizeDouble(xy[0], scale), quantizeDouble(xy[1], scale))
                        .withSrsName(crsTo).withSrsDimension(BigInteger.TWO));
                return true;
            }
        } else {
            // Neither a pos nor a lat/lon pair: nothing here to read, so the location keeps its
            // srsName and passes through.
            countDropped(LOCATIONS_NO_COORDS, WHAT_NO_COORDS);
            Log.warn("projection: unsupported CRS, location left unprojected");
        }
        return false;
    }

    // ------------------------------------------------------- projectLocation4326

    /// Project a location to EPSG:4326 with the default quantize '0.000001'.
    public static void projectLocation4326(LocationStructure location) {
        projectLocation4326(location, "0.000001");
    }

    /// Flatten a pos into latitude/longitude attributes in WGS84 (the inverse presentation of
    /// [#projectLocation], used by consumers that want the attribute form).
    public static void projectLocation4326(LocationStructure location, String quantize) {
        String crsTo = WGS84_URN;
        int scale = new BigDecimal(quantize).scale();
        if (location.getPos() != null) {
            Transform transformer = getTransformerBySrsName(location, crsTo);
            double latitude, longitude;
            List<Double> v = location.getPos().getValue();
            if (transformer != null) {
                double[] r = transformer.apply(v.get(0), v.get(1));
                latitude = r[0];
                longitude = r[1];
            } else {
                latitude = v.get(0);
                longitude = v.get(1);
            }
            location.setLongitude(quantizeBig(longitude, scale));
            location.setLatitude(quantizeBig(latitude, scale));
            location.setSrsName(crsTo);
            location.setPos(null);
        } else if (location.getSrsName() != null
                && !location.getSrsName().equals("EPSG:4326")
                && !location.getSrsName().equals(WGS84_URN)) {
            // A NON-WGS84 CRS with no pos to transform: the latitude/longitude attributes stay as
            // they are, in that CRS, from a method whose contract is "the result is WGS84", and
            // every consumer reads them as degrees. This counter measures WRONG coordinates.
            countDropped(LOCATIONS_SOURCE_CRS, WHAT_SOURCE_CRS);
            Log.warn("projection: unsupported CRS, location left unprojected");
        }
    }

    // ------------------------------------------------------- projectLinestring2

    /// Project a gml LineString or LinearRing in place: whatever the source encoding — a posList,
    /// or a sequence of pos elements — the result is a single posList of quantized coordinates at
    /// the same srsDimension. The binding splits that union into a scalar posList plus a
    /// posOrPointProperty list, so this sets the former and clears the latter. srsName is
    /// deliberately NOT stamped here; callers do it.
    public static void projectLinestring2(Transform transformer, Object linestring) {
        int srsDimension = linestring instanceof LineStringType lst && lst.getSrsDimension() != null
                ? lst.getSrsDimension().intValue() : 2;             // LinearRing has no srsDimension

        DirectPositionListType posList;
        List<Object> posOrPointProperty;
        if (linestring instanceof LineStringType ls) {
            posList = ls.getPosList();
            posOrPointProperty = ls.getPosOrPointProperty();
        } else if (linestring instanceof LinearRingType lr) {
            posList = lr.getPosList();
            posOrPointProperty = lr.getPosOrPointProperty();
        } else {
            throw new IllegalArgumentException("Not a LineString/LinearRing: " + linestring.getClass());
        }

        List<Double> xx = new ArrayList<>();
        List<Double> yy = new ArrayList<>();
        List<Double> zz = new ArrayList<>();
        if (posList != null) {
            List<Double> v = posList.getValue();
            for (int i = 0; i + 1 < v.size(); i += srsDimension) {
                xx.add(v.get(i));
                yy.add(v.get(i + 1));
                if (srsDimension >= 3) zz.add(v.get(i + 2));
            }
        } else if (!posOrPointProperty.isEmpty()) {
            for (Object o : posOrPointProperty) {
                Object val = o instanceof JAXBElement<?> je ? je.getValue() : o;
                if (val instanceof DirectPositionType pos) {
                    xx.add(pos.getValue().get(0));
                    yy.add(pos.getValue().get(1));
                    // z only exists for srs_dimension 3 — a 2D Pos has two values
                    if (srsDimension >= 3) zz.add(pos.getValue().get(2));
                }
            }
        } else {
            return; // KNOWN DECISION (OPEN.md §16): guarded, where the bare access would throw
        }

        if (srsDimension == 2) {
            List<Double> out = new ArrayList<>(xx.size() * 2);
            for (int i = 0; i < xx.size(); i++) {
                double[] r = transformer.apply(xx.get(i), yy.get(i));
                out.add(quantizeDouble(r[0], 6));
                out.add(quantizeDouble(r[1], 6));
            }
            replaceWithPosList(linestring, out, srsDimension);
        } else if (srsDimension == 3) {
            List<Double> out = new ArrayList<>(xx.size() * 3);
            for (int i = 0; i < xx.size(); i++) {
                double[] r = transformer.apply(xx.get(i), yy.get(i), zz.get(i));
                out.add(quantizeDouble(r[0], 6));
                out.add(quantizeDouble(r[1], 6));
                out.add(quantizeDouble(r[2], 6));
            }
            replaceWithPosList(linestring, out, srsDimension);
        }
        // any other srsDimension: untouched
    }

    private static void replaceWithPosList(Object linestring, List<Double> values, int srsDimension) {
        DirectPositionListType posList = new DirectPositionListType()
                .withValue(values).withSrsDimension(BigInteger.valueOf(srsDimension));
        if (linestring instanceof LineStringType ls) {
            ls.setPosList(posList);
            ls.getPosOrPointProperty().clear();
        } else {
            LinearRingType lr = (LinearRingType) linestring;
            lr.setPosList(posList);
            lr.getPosOrPointProperty().clear();
        }
    }

    // ----------------------------------------------------------- projectPolygon

    /// Default a missing srsName to WGS84, build the transformer lazily through the shared cache,
    /// and fall back to WGS84 on an unparseable CRS rather than aborting the run.
    public static void projectPolygon(PolygonType polygon, String crsTo) {
        String srsName = orDefault(polygon.getSrsName(), WGS84_URN);
        if (srsName.equals(crsTo)) return;

        Transform transformer = resolveTransform(srsName, crsTo);
        LinearRingType exteriorRing = linearRing(polygon.getExterior());
        if (exteriorRing != null) {
            projectLinestring2(transformer, exteriorRing);
        }
        for (AbstractRingPropertyType interior : polygon.getInterior()) {
            LinearRingType ring = linearRing(interior);
            if (ring != null) projectLinestring2(transformer, ring);
        }
        if (crsTo.equals("EPSG:4326")) {
            // Extra requantize pass for a bare-EPSG:4326 target, which the URN-form crsTo never
            // reaches. Exterior: requantize the posList in place, guarded. Interior: requantized
            // only when the first union element is still a Pos, which post-projection it is not.
            if (exteriorRing != null && exteriorRing.getPosList() != null) {
                List<Double> v = exteriorRing.getPosList().getValue();
                v.replaceAll(d -> quantizeDouble(d, 6));
            }
            for (AbstractRingPropertyType interior : polygon.getInterior()) {
                LinearRingType ring = linearRing(interior);
                if (ring != null && !ring.getPosOrPointProperty().isEmpty()) {
                    Object first = ring.getPosOrPointProperty().get(0);
                    Object val = first instanceof JAXBElement<?> je ? je.getValue() : first;
                    if (val instanceof DirectPositionType pos) {
                        pos.getValue().replaceAll(d -> quantizeDouble(d, 6));
                    }
                }
            }
        }
        polygon.setSrsName(crsTo);
    }

    /// The ring property's LinearRing — `AbstractRingPropertyType` holds its ring as a
    /// `JAXBElement`, the substitution-group binding GML 3.2 generates, so the ring has to be
    /// unwrapped and type-tested rather than read off a typed accessor.
    private static LinearRingType linearRing(AbstractRingPropertyType property) {
        if (property == null || property.getAbstractRing() == null) return null;
        return property.getAbstractRing().getValue() instanceof LinearRingType r ? r : null;
    }

    // ------------------------------------------------------ reprojectionChanged

    /// Project every geometry of the object to `crsTo` in place; return whether anything was
    /// modified. Objects already in the target CRS — explicitly, or srs-less and therefore assumed
    /// to be — pass through untouched, so the caller can skip re-emitting them. This IS the
    /// ensemble operation body: register it for every class in `GeoClasses.all()` the store holds.
    public static boolean reprojectionChanged(Object deserialized, String crsTo) {
        boolean[] changed = {false};
        walkGeo(deserialized, obj -> {
            if (obj instanceof LocationStructure location) {
                changed[0] |= projectLocation(location, crsTo);

            } else if (obj instanceof SimplePoint_VersionStructure point) {
                if (point.getLocation() != null) {
                    changed[0] |= projectLocation(point.getLocation(), crsTo);
                }

            } else if (obj instanceof LineStringType lineString) {
                if (Objects.equals(lineString.getSrsName(), crsTo)) return;

                Transform transformer = getTransformerBySrsName(lineString, crsTo);
                if (transformer != null) {
                    projectLinestring2(transformer, lineString);
                    lineString.setSrsName(crsTo);
                    changed[0] = true;
                }

            } else if (obj instanceof PolygonType polygon) {
                if (!Objects.equals(polygon.getSrsName(), crsTo)) changed[0] = true;
                projectPolygon(polygon, crsTo);

            } else if (obj instanceof MultiSurfaceType multiSurface) {
                if (!Objects.equals(multiSurface.getSrsName(), crsTo)) changed[0] = true;
                if (multiSurface.getSurfaceMembers() != null) {
                    for (SurfacePropertyType member : multiSurface.getSurfaceMember()) {
                        if (member.getAbstractSurface() != null
                                && member.getAbstractSurface().getValue() instanceof PolygonType p) {
                            if (!Objects.equals(p.getSrsName(), crsTo)) changed[0] = true;
                            projectPolygon(p, crsTo);
                        }
                    }
                    for (JAXBElement<?> el : multiSurface.getSurfaceMembers().getAbstractSurface()) {
                        if (el != null && el.getValue() instanceof PolygonType p) {
                            if (!Objects.equals(p.getSrsName(), crsTo)) changed[0] = true;
                            projectPolygon(p, crsTo);
                        }
                    }
                }
                multiSurface.setSrsName(crsTo);
            }
        });
        return changed[0];
    }

    // --------------------------------------------------------------------------- the geo walk

    // RecursiveAttributes prunes the gml package, geometry carrying no ids or refs, so the geo walk
    // lives here instead. Visits every geo sub-object of the tree, root excluded; geo objects and
    // references are leaves.

    // Compared with getPackageName().equals(...), not a prefix test. This walk is the ONE place
    // that deliberately spans BOTH packages, and under the shared noi.netex. root a prefix test
    // could not tell them apart.
    private static final String NETEX_PKG = EntityStructure.class.getPackageName();
    private static final String GML_PKG = PolygonType.class.getPackageName();

    private static boolean inWalkedPackages(Class<?> c) {
        String p = c.getPackageName();
        return p.equals(NETEX_PKG) || p.equals(GML_PKG);
    }

    private static final ClassValue<Field[]> WALK_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> out = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    boolean interesting = List.class.isAssignableFrom(t)
                            || JAXBElement.class.isAssignableFrom(t)
                            || t == Object.class
                            || (inWalkedPackages(t) && !t.isEnum());
                    if (!interesting) continue;
                    f.setAccessible(true);
                    out.add(f);
                }
            }
            return out.toArray(new Field[0]);
        }
    };

    private static boolean isGeo(Object v) {
        return v instanceof LocationStructure || v instanceof SimplePoint_VersionStructure
                || v instanceof LineStringType || v instanceof PolygonType
                || v instanceof MultiSurfaceType;
    }

    private static void walkGeo(Object root, Consumer<Object> visitor) {
        for (Field f : WALK_FIELDS.get(root.getClass())) {
            try {
                descend(f.get(root), visitor);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot read " + f, e);
            }
        }
    }

    private static void descend(Object v, Consumer<Object> visitor) {
        if (v == null) return;
        if (v instanceof JAXBElement<?> je) {
            descend(je.getValue(), visitor);
            return;
        }
        if (v instanceof List<?> list) {
            for (Object x : list) descend(x, visitor);
            return;
        }
        Class<?> c = v.getClass();
        if (!inWalkedPackages(c) || c.isEnum()) return;
        if (v instanceof VersionOfObjectRefStructure) return;       // refs are leaves
        if (isGeo(v)) {
            visitor.accept(v);                                      // geo objects are leaves too
            return;
        }
        walkGeo(v, visitor);
    }

    // ------------------------------------------------------------------------------ quantize

    /// The exact binary expansion of the double, then HALF_UP at the requested scale.
    private static BigDecimal quantizeBig(double value, int scale) {
        return new BigDecimal(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static double quantizeDouble(double value, int scale) {
        return quantizeBig(value, scale).doubleValue();
    }
}
