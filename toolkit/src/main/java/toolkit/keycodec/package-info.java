/// **API.** The on-disk key layout, in one class.
///
/// `id NUL version NUL [class_idx LE16]` for the id index, and the packing of
/// `(class_idx, local_key)` into the 8-byte `full_key` everything else passes around.
/// Pure byte manipulation: it references no model class and no store.
///
/// Changing anything here changes every stored byte, which is why it is its own package rather than
/// private to the store.
package toolkit.keycodec;
