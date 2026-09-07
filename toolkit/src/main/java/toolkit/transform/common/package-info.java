/// **API.** The shared leaves — transformer helpers more than one caller needs, and which reference
/// no other `toolkit.transform` package themselves. The surface a pipeline builds its own drivers
/// on, reached through the jar, including from outside this repository.
///
/// [toolkit.transform.common.ScheduledStopPoints] (ScheduledStopPoint synthesis, centroid and
/// authority backfill, and the same-name stop-merge plan) and
/// [toolkit.transform.common.MpLoadRules] (the zip-loader dedup rules, shaped as `Store.MergeFn`
/// policies: the `MergeFn` machinery is toolkit mechanism, and the rules themselves are policy that
/// no single loader owns).
///
/// [toolkit.transform.common.TrainNumbers] is the exception: it encodes one country's policy, which
/// by the rule above belongs with the pipeline, and is compiled anyway because a source-launched
/// driver can reach nothing but the jar. Its generic half, `synthesizeTrainNumbers`, takes the id
/// and number functions and has no country in it.
package toolkit.transform.common;
