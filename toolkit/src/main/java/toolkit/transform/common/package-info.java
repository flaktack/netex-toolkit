/// The shared leaves — transformer helpers more than one caller needs, and which reference no other
/// `toolkit.transform` package themselves.
///
/// [toolkit.transform.common.ScheduledStopPoints] (ScheduledStopPoint synthesis, centroid and
/// authority backfill, and the same-name stop-merge plan) and
/// [toolkit.transform.common.MpLoadRules] (the zip-loader dedup rules, shaped as `Store.MergeFn`
/// policies).
///
/// Depends on nothing here; depended on from outside, including across the repository boundary —
/// `../noi-pipeline` reaches these classes through the jar. They are the API surface a pipeline
/// builds its own drivers on.
///
/// [toolkit.transform.common.TrainNumbers] is here on an exception it states in its own header: it
/// encodes one country's policy, which by the rule above belongs with the pipeline, and is compiled
/// anyway because it is the worked example for the two transform sandwiches — a source-launched
/// driver can reach nothing but the jar. Its generic half, `synthesizeTrainNumbers`, takes the id
/// and number functions and has no country in it.
///
/// `MpLoadRules` states the tier rule the whole split follows, in its own header: the
/// `Store.MergeFn` machinery is toolkit mechanism, and the dedup RULES are policy. The rules stay
/// on this side because two independent loaders need them and neither owns them.
package toolkit.transform.common;
