/// **API.** The driver sandwiches — the shape every driver in `scripts/` is a
/// configuration of.
///
/// One per direction data moves. `TestHarnessExamples` holds the list below to the package: every
/// public type here with a `driverMain` must be named in it, and must have a worked example under
/// `scripts/`.
///
/// - [toolkit.harness.FeedToDb] — a feed (`.xml`, `.xml.gz` or `.zip`)
///    into a fresh store;
/// - [toolkit.harness.DbToDb] — one store into a fresh store;
/// - [toolkit.harness.MergeToDb] — N stores into a fresh store, first source winning any
///    `(id, version, class)` collision;
/// - [toolkit.harness.InPlace] — one store, rewritten where it stands;
/// - [toolkit.harness.DbToZip] — one store out to a NeTEx zip.
///
/// `DbToZip` is the odd one: it opens READ-ONLY, so it has no `resolve()`/`resolveEmbeddings()`
/// pair and cannot have one — resolve needs a write transaction. Its companions
/// [toolkit.harness.ZipWriter], which pins entry
/// timestamps to the DOS epoch, [toolkit.harness.NetexEnvelope], the
/// PublicationDelivery/CompositeFrame preamble, and [toolkit.harness.FrameWriter], which turns
/// stored rows into emitted fragment bytes, are the generic half of an export driver. `FrameWriter`
/// decompresses (the store's format, so mechanism) and hands the fragment to a caller-supplied hook
/// (what to do with it, so profile) — which is why `EpipDbToXml` can normalise child order and
/// `DbToZipRaw` can decline to.
///
/// Pinned entry timestamps buy comparable METADATA, not a reproducible archive: the exporter
/// writes a wall clock into `<PublicationTimestamp>`. Compare exports with
/// `tools/diff/export_zip.py`, which normalises exactly that field.
///
/// Each owns the CLI, the checks that must happen BEFORE anything destructive, the transaction
/// sandwich, the mandatory resolve pair, and a common tail — post-conditions, what the stage wrote
/// by class, and the effective strictness policy. A driver supplies the middle and nothing else.
///
/// That tail is `Harness`, package-private, and a CALL rather than a superclass: the sandwiches have
/// genuinely different middles and no shared state, and what they share is a suffix.
///
/// [toolkit.harness.Args] is the only CLI parser in the tree: known flags, loud on anything
/// else, exit 2. There is no picocli and no other argument handling anywhere.
package toolkit.harness;
