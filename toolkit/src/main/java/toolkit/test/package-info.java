/// **API, and shipped in the production jar on purpose.** The fixture kit the uncompiled tiers
/// test against: a script author writes a test with no build step, dependency or runner.
///
/// [toolkit.test.TestStore] gives a real temp LMDB store, a NeTEx skeleton and a loader in
/// one call; [toolkit.test.Gen] drives a transformer; [toolkit.test.Xml] compares XML
/// canonically (prefix-normalised, attributes sorted); [toolkit.test.Runner] discovers
/// `test*` methods reflectively and prints a transcript; [toolkit.test.Check] is four
/// assertions and no JUnit.
///
/// The test suite is not here: it is `src/test/java`, run by surefire, in the package of whatever
/// each file tests.
package toolkit.test;
