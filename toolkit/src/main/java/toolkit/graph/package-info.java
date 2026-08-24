/// **API, though only the inspection tools use it.** Immutable forward + inverse CSR adjacency
/// over `full_key` edge pairs.
///
/// Built lazily from a store's live edge slots and invalidated by any edge mutation. Nothing in
/// `scripts/` references it today; `tools/inspect/nx` does.
package toolkit.graph;
