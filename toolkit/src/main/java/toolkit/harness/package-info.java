/// **API.** The driver sandwiches — the shape every driver in `scripts/` is a configuration of.
///
/// One per direction data moves. Each owns the CLI, the checks that must happen before anything
/// destructive, the transaction sandwich, the mandatory resolve pair, and a common tail —
/// post-conditions, what the stage wrote by class, and the effective strictness policy. A driver
/// supplies the middle and nothing else. That tail is `Harness`, package-private.
///
/// `TestHarnessExamples` holds the list below to the package: every public type here with a
/// `driverMain` must be named in it, and must have a worked example under `scripts/`.
///
/// - [toolkit.harness.FeedToDb] — a feed (`.xml`, `.xml.gz` or `.zip`)
///    into a fresh store;
/// - [toolkit.harness.DbToDb] — one store into a fresh store;
/// - [toolkit.harness.MergeToDb] — N stores into a fresh store, first source winning any
///    `(id, version, class)` collision;
/// - [toolkit.harness.InPlace] — one store, rewritten where it stands;
/// - [toolkit.harness.DbToZip] — one store out to a NeTEx zip.
///
/// `DbToZip` opens read-only, so it has no `resolve()`/`resolveEmbeddings()` pair and cannot have
/// one: resolve needs a write transaction. Its companions are the generic half of an export
/// driver — [toolkit.harness.ZipWriter], which pins entry timestamps to the DOS epoch,
/// [toolkit.harness.NetexEnvelope], the PublicationDelivery/CompositeFrame preamble, and
/// [toolkit.harness.FrameWriter], which decompresses a stored row and hands the fragment to a
/// caller-supplied hook that decides what is emitted.
///
/// Pinned entry timestamps make export metadata comparable; they do not make the archive
/// reproducible, because the exporter writes a wall clock into `<PublicationTimestamp>`. Compare
/// exports with `tools/diff/export_zip.py`, which normalises exactly that field.
///
/// [toolkit.harness.Args] is the only CLI parser in the tree: known flags, loud on anything else,
/// exit 2.
package toolkit.harness;
