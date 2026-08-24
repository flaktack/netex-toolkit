/// The EPIP export profile: the generators that turn a loaded store into the shape the EU Passenger
/// Information Profile wants — journeys and patterns, calendars, interchanges, lines, locations,
/// the sanitising passes that make stop assignments legal, and the backfills in
/// [toolkit.transform.epip.Backfills].
///
/// Compiled and shipped, because EPIP is a published profile rather than one deployment's policy,
/// and because a source-launched program sees one source root and cannot import across a checkout.
///
/// The cost: a generator change is no longer edit-and-run, and a downstream run does not see it
/// until `mvn install`. See OPEN.md item 14 for the stale-jar failure mode.
///
/// Self-contained: no class here references any other `toolkit.transform` subpackage.
package toolkit.transform.epip;
