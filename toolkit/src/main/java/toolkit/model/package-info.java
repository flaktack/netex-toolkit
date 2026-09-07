/// **API.** Reflection over the generated JAXB model — everything the rest of the toolkit needs
/// to know about NeTEx classes without hard-coding any of them.
///
/// [toolkit.model.ModelRegistry] discovers the registrable classes once per JVM from the
/// model jar's own code source and assigns the deterministic class indices the store keys on.
/// [toolkit.model.RecursiveAttributes] is the attribute walker on the insert hot path
/// (`VarHandle` accessors, cached per class). [toolkit.model.RefElementIndex] maps an
/// XML element name to its candidate target classes — a set, because some NeTEx element names are
/// polymorphic. [toolkit.model.ContainmentIndex] is the type-level inverse containment index
/// driving embedding resolution. [toolkit.model.GeoClasses] is the census of classes carrying
/// geometry. [toolkit.model.NetexUtils] constructs ids and references and projects beans.
///
/// Everything here is derived from the model at runtime, never enumerated by hand. A
/// hand-maintained list of NeTEx class names is policy and belongs in a profile package such as
/// [toolkit.transform.epip], in a caller's own tree, or in the factory that assembles a store for a
/// profile. The one hand-curated list the toolkit carries lives in [toolkit.store.Stores].
package toolkit.model;
