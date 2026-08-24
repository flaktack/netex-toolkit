/// **API.** Coordinate reference system reprojection, on proj4j.
///
/// Mechanism only: how to move geometry between CRSs, the OGC-URN to `AUTH:CODE` reduction,
/// and the axis-order handling the EPSG database forces (geographic CRSs are (lat, lon), projected
/// ones (easting, northing), proj4j is always (x, y)). WHICH classes get reprojected, and to what,
/// is profile policy, and lives one tier up in [toolkit.transform.epip] — compiled, but still
/// not here: this package knows how to reproject, not what a profile wants reprojected.
///
/// Separate from [toolkit.model] on purpose. That package is uniformly reflection over the
/// generated classes and pulls in nothing beyond the model; this one is arithmetic against a
/// third-party CRS registry. [toolkit.model.GeoClasses] answers "which classes carry geometry";
/// this package answers "how do you move it".
package toolkit.geo;
