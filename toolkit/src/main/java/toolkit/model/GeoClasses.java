package toolkit.model;

import noi.netex.gml.LineStringType;
import noi.netex.gml.MultiSurfaceType;
import noi.netex.gml.PolygonType;
import noi.netex.model.LocationStructure;
import noi.netex.model.SimplePoint_VersionStructure;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/// The model classes that CARRY geographic data: those with a direct — possibly inherited,
/// never collection — attribute whose type is one of the five geo types (`LocationStructure`,
/// `SimplePoint_VersionStructure`, and GML's `LineStringType`, `PolygonType`,
/// `MultiSurfaceType`).
///
/// Derived by reflecting over [ModelRegistry#classes()] and testing each declared or inherited
/// non-static field's declared type for EXACT membership: no subtype matching, no
/// List/JAXBElement unwrapping. Every geo field in the model is a plain singular field.
///
/// The geo types themselves are excluded explicitly — `SimplePoint_VersionStructure` is an
/// EntityStructure carrying a `location` field, so it would otherwise qualify. KNOWN LIMIT
/// (OPEN.md §16): abstract intermediate structures may appear here, harmless because stored
/// objects are concrete and membership is tested against `obj.getClass()` and `dbNames()`.
public final class GeoClasses {

    /// The field types that make a class "geo".
    public static final Set<Class<?>> GEO_FIELD_TYPES = Set.of(
            LocationStructure.class,
            SimplePoint_VersionStructure.class,
            LineStringType.class,
            PolygonType.class,
            MultiSurfaceType.class);

    private static volatile Set<Class<?>> all;

    private GeoClasses() {}

    /// Every registrable model class carrying a direct geo attribute.
    public static Set<Class<?>> all() {
        Set<Class<?>> result = all;
        if (result == null) {
            synchronized (GeoClasses.class) {
                result = all;
                if (result == null) {
                    Set<Class<?>> out = new LinkedHashSet<>();
                    for (Class<?> c : ModelRegistry.classes()) {
                        if (GEO_FIELD_TYPES.contains(c)) continue;
                        if (carriesGeoField(c)) out.add(c);
                    }
                    all = result = Collections.unmodifiableSet(out);
                }
            }
        }
        return result;
    }

    private static boolean carriesGeoField(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (GEO_FIELD_TYPES.contains(f.getType())) return true;
            }
        }
        return false;
    }
}
