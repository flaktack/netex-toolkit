/// Repairs for what a feed encodes wrongly, as opposed to what a profile asks for. A repair belongs
/// here when it is a general NeTEx correctness rewrite that any feed can need; one that resolves a
/// single exporter's defects belongs with the deployment that has that exporter, and a pass that
/// shapes output belongs in [toolkit.transform.epip].
///
/// [toolkit.transform.feedfix.DayOffsetRewrite] re-encodes hour-24 times into the valid NeTEx
/// `DayOffset` form. It changes the encoding the feed used, so it is off by default.
package toolkit.transform.feedfix;
