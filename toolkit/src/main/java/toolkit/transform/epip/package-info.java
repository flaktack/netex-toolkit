/// **API.** The EPIP export profile: the generators that turn a loaded store into the shape the
/// EU Passenger Information Profile wants — journeys and patterns, calendars, interchanges, lines,
/// locations, the sanitising passes that make stop assignments legal, and the backfills in
/// [toolkit.transform.epip.Backfills].
///
/// Compiled and shipped, so a generator change reaches a downstream run only after `mvn install`.
///
/// Self-contained: no class here references any other `toolkit.transform` subpackage.
///
/// Every generator has the shape `(Store, Txn, GeneratorDefaults) -> Iterator<Object>`. Several
/// read nothing from the third argument and accept it only to keep the shape.
///
/// The profile requires an internal reference to resolve within the same document: it declares
/// `Level_KeyRef` with selector `.//netex:LevelRef`, so a LevelRef whose Level is not in the
/// document is a violation while its absence is conformant. The export's document split therefore
/// decides what a reference may point at.
package toolkit.transform.epip;
