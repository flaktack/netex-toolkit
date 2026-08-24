/// **API.** Leaf utilities every other package may depend on, and which depend on nothing in
/// `toolkit` themselves.
///
/// [toolkit.util.Log] is the single logging surface (`String.format` style, slf4j
/// behind it, `--log-file` attach at runtime). [toolkit.util.Strictness] is the policy
/// object deciding, per concern, whether a detected degradation is refused or tolerated-and-counted.
/// [toolkit.util.PeekIterator] probes one element and replays it.
///
/// Deliberately small: the test for admission is "does every other package plausibly need this",
/// not "where else would it go".
package toolkit.util;
