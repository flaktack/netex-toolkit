/// **API.** The shared leaves — transformer helpers more than one caller needs, and which reference
/// no other `toolkit.transform` package themselves. The surface a pipeline builds its own drivers
/// on, reached through the jar, including from outside this repository.
///
/// [toolkit.transform.common.ScheduledStopPoints] (ScheduledStopPoint synthesis, centroid backfill
/// and the same-name stop-merge plan), [toolkit.transform.common.TransportModes] (which mode a
/// station takes when a merge folds several publishers' copies of it into one, for the merge above
/// and for a pipeline's own), [toolkit.transform.common.LineAuthorities] (the Line authority
/// backfill, which synthesises an Authority from a Line's Operator where the feed publishes none),
/// [toolkit.transform.common.SiteLevels] (the union, the one-floor fold and the `LevelRef` walk
/// over a Site's embedded `<levels>`, shared by every merge that folds two copies of one station
/// into one) and [toolkit.transform.common.MpLoadRules] (the zip-loader dedup rules, shaped as
/// `Store.MergeFn` policies: the `MergeFn` machinery is toolkit mechanism, and the rules themselves
/// are policy that no single loader owns).
///
/// [toolkit.transform.common.TrainNumbers] is the exception: it encodes one country's policy, which
/// by the rule above belongs with the pipeline, and is compiled anyway because a source-launched
/// driver can reach nothing but the jar. Its generic half, `synthesizeTrainNumbers`, takes the id
/// and number functions and has no country in it.
package toolkit.transform.common;
