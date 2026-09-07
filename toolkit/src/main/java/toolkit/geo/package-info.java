/// Internal. Coordinate reference system reprojection, on proj4j. Reached through
/// `toolkit.transform.epip`, not directly by a driver.
///
/// Mechanism only: how to move geometry between CRSs, the OGC-URN to `AUTH:CODE` reduction, and the
/// axis-order handling the EPSG database forces (geographic CRSs are (lat, lon), projected ones
/// (easting, northing), proj4j is always (x, y)). Which classes get reprojected, and to what, is
/// profile policy and lives in [toolkit.transform.epip].
///
/// Separate from [toolkit.model], which must stay reflection over the generated classes and pull in
/// nothing beyond the model; the third-party CRS registry is a dependency of this package alone.
/// [toolkit.model.GeoClasses] names the classes carrying geometry.
package toolkit.geo;
