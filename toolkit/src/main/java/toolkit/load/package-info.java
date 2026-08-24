/// **API is [toolkit.load.NetexLoader], [toolkit.load.ZipLoad] and
/// [toolkit.load.LoadStats]; the rest is internal.** Feed to store.
///
/// A StAX cursor loop over a NeTEx document, unmarshalling only the elements the interesting-set
/// names, with `CopyingCapture` teeing every event into a byte-faithful copy of the source
/// subtree so the fragment stored is the fragment that arrived. `CopyingCapture` also injects
/// the denormalised attributes (`dataSourceRef`, `responsibilitySetRef`, `srsName`,
/// and an inherited `id`/`version`) into the READER before the unmarshaller sees the
/// start tag, so bean and fragment carry the same decision.
///
/// [toolkit.load.ZipLoad] walks a zip's members and stops there. The post pass and the mandatory
/// `resolve()` + `resolveEmbeddings()` pair belong to [toolkit.harness.FeedToDb]: they are
/// properties of a finished LOAD, not of a container format.
///
/// [toolkit.load.LoadStats] carries what a load skipped as DATA, not just as log lines.
package toolkit.load;
