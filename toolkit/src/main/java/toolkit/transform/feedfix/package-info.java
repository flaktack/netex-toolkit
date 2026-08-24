/// Repairs for what a feed encodes wrongly, as opposed to what a profile asks for.
///
/// One class is left here. [toolkit.transform.feedfix.DayOffsetRewrite] re-encodes hour-24 times
/// into the valid NeTEx `DayOffset` form. It deliberately changes the encoding the feed used, so it
/// is off by default. It is here and not in `epip` because it repairs an input; it is compiled and
/// not a script because it is a general NeTEx correctness rewrite that any feed can need.
///
/// The vendor-specific repairs are NOT here. `MentzLineVersions` and `DedupDefinitionDays` resolve
/// one exporter's overlapping line versions, and they live with the deployment that has that
/// exporter, in `../noi-pipeline/scripts/transformers/feedfix/`.
package toolkit.transform.feedfix;
