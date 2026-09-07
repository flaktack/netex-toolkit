/// The v2 on-disk format, and the only implementation of it.
///
/// One class, [toolkit.store.v2.V2Store], extending [toolkit.store.AbstractLmdbStore]: the abstract
/// half owns transactions, the class table and the resolve passes, and this half owns the LMDB
/// sub-databases and their byte layouts. [toolkit.store.v2.V2Store]'s header carries the layout
/// itself — sub-database names, key widths, the block encoding and the reserved values.
///
/// Not reached from `scripts/` or `tools/`: a caller opens a store through
/// [toolkit.store.Stores#open], which sniffs the format and picks the implementation. Naming this
/// package is how a caller ends up pinned to one format.
///
/// Changing anything here changes every stored byte, so a store written by one generation is not a
/// clone source for another. The format string in `_meta` is what a reader checks before it trusts
/// the rest.
package toolkit.store.v2;
