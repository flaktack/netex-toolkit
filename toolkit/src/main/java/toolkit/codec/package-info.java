/// **API.** The value codec and the JAXB/NeTEx defect repairs — how bytes become beans and back.
///
/// [toolkit.codec.FragmentCodec] is the storage format itself: an LZ4-compressed
/// `[4B LE len][block]` of the object's SOURCE XML fragment. Objects are kept as the bytes the feed
/// shipped, not as re-serialised beans, so a reference repair patches start tags in place
/// ([toolkit.codec.RefPatcher]) and no round-trip loss is baked in. [toolkit.codec.FragmentPeek]
/// reads one attribute without unmarshalling; [toolkit.codec.FragmentOrder] reorders a fragment's
/// children into the declared XSD sequence when a feed emitted them wrong.
///
/// The rest are repairs for defects that are not ours: [toolkit.codec.EnumWhitespace] (a
/// padded NMTOKEN misses JAXB's enum map and returns null SILENTLY — no exception, no event),
/// [toolkit.codec.SchemaDefaults] (omit-if-default on marshal), [toolkit.codec.JaxbPin]
/// (a per-event allocation in the RI), and [toolkit.codec.Stax] — the single point where the
/// StAX implementation is chosen, Woodstox for byte offsets, Aalto for decode.
///
/// [toolkit.codec.BindEvents] counts what failed to bind. Read its class note before
/// trusting a zero: it cannot see a value whose ADAPTER returned an object, which is what the
/// date/time carriers do with an unparseable lexeme. That half is counted in
/// `noi.netex.time.UnparsedTally` instead.
package toolkit.codec;
