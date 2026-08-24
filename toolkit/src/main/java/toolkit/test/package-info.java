/// **API, and shipped in the production jar on purpose.** The fixture kit the uncompiled tiers
/// test against.
///
/// [toolkit.test.TestStore] gives a real temp LMDB store, a NeTEx skeleton and a loader in
/// one call; [toolkit.test.Gen] drives a transformer; [toolkit.test.Xml] compares XML
/// canonically (prefix-normalised, attributes sorted); [toolkit.test.Runner] discovers
/// `test*` methods reflectively and prints a transcript; [toolkit.test.Check] is four
/// assertions and no JUnit.
///
/// It ships because it is what makes the JEP-458 tier work: a script author writes a test with no
/// build step, dependency or runner.
///
/// The test SUITE is NOT here — it is `src/test/java`, run by surefire, in the package of whatever
/// each file tests.
package toolkit.test;
