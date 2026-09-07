package toolkit.codec;

import javax.xml.stream.XMLInputFactory;

/// The StAX implementation choice for the toolkit's two readers.
///
/// [#offsetFactory()] is Woodstox: `RefPatcher.patch` byte-splices attributes into a stored
/// fragment and needs `LocationInfo` per-event input character offsets to find the start tags.
/// Woodstox reports them; Aalto does not. [#decodeFactory()] is Aalto, for every read of a stored
/// fragment.
///
/// The loader is not routed through here. `CopyingCapture` tees its events into a Woodstox
/// `XMLStreamWriter2.copyEventFromReader`, and the stored fragment's byte fidelity depends on that
/// reader/writer pair: captured under the two readers, fragments differ on intra-fragment
/// whitespace.
public final class Stax {

    private Stax() {}

    /// The decode path's reader: Aalto, DTD support off.
    ///
    /// Every consumer of a stored fragment must come through here — `FragmentCodec.decode`,
    /// `FragmentPeek` and `RefPatcher.scan` — because those byte-reads are compared against
    /// unmarshalled bean fields, so they must share the decoder's attribute-value normalisation,
    /// entity handling and namespace view. A factory is not thread-safe: each owner keeps its own.
    public static XMLInputFactory decodeFactory() {
        XMLInputFactory f = new com.fasterxml.aalto.stax.InputFactoryImpl();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }

    /// The offset-reporting reader: Woodstox, DTD off, coalescing off — for `RefPatcher.patch`
    /// only.
    public static com.ctc.wstx.stax.WstxInputFactory offsetFactory() {
        com.ctc.wstx.stax.WstxInputFactory f = new com.ctc.wstx.stax.WstxInputFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
        return f;
    }
}
