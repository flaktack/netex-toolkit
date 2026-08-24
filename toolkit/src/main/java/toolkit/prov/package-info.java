/// **API.** What a store can be trusted to contain, recorded beside it.
///
/// Two files, two different questions, deliberately not merged:
///
/// `.provenance.json` ([toolkit.prov.Provenance]) records what the writing JAR was
/// capable of — a [capability][toolkit.prov.Capabilities] is "a fix whose absence changes
/// stored or exported bytes", so it is a property of the CODE. It INTERSECTS across a merge (a
/// merged store is only as capable as its least capable input) and `Provenance.check` refuses
/// an input on it. Absence means UNKNOWN and refuses; it never means fine.
///
/// `.loadreport.json` ([toolkit.prov.LoadReport]) records what one build TOLERATED — the
/// degradations [toolkit.util.Strictness] carried on past. That is a property of a RUN over an
/// INPUT: it does not intersect, and it must not be able to move a capability verdict.
package toolkit.prov;
