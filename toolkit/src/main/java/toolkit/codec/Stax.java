package toolkit.codec;

import javax.xml.stream.XMLInputFactory;

/// The StAX implementation choice, in one place. The toolkit ships two implementations; this is
/// where each is chosen, once.
///
/// ## Woodstox is not interchangeable — [#offsetFactory()]
///
/// `RefPatcher.patch` byte-splices attributes into a stored fragment and needs `LocationInfo`
/// per-event input character offsets to find the start tags. Woodstox reports them; Aalto does not.
///
/// ## Aalto is the decode reader — [#decodeFactory()]
///
/// Equivalence is CHECKED, not assumed: decoding a large sample under both parsers and
/// re-marshalling gives zero byte differences, and `RefPatcher.scan` and `FragmentPeek` agree
/// throughout. The behavioural differences are that Woodstox splits a long text node into several
/// CHARACTERS events where Aalto reports one — irrelevant to JAXB, which accumulates, and to
/// [EnumWhitespace], whose lexicals are NMTOKENs — and that the two differ in exception type and
/// column number on malformed input. Both refuse an internal DTD subset. `TestStaxAgreement` pins it.
///
/// The loader is deliberately NOT routed through here. `CopyingCapture` tees its events into a
/// Woodstox `XMLStreamWriter2.copyEventFromReader`, and the stored fragment's byte fidelity depends
/// on that reader/writer PAIR: capture every fragment under both readers and they differ on
/// intra-fragment whitespace.
public final class Stax {

    private Stax() {}

    /// The decode path's reader: Aalto, DTD support off.
    ///
    /// Every consumer of a STORED FRAGMENT must come through here — `FragmentCodec.decode`,
    /// `FragmentPeek` and `RefPatcher.scan` — because those byte-reads are compared against
    /// unmarshalled bean fields, so they must share the decoder's attribute-value normalisation,
    /// entity handling and namespace view. A factory is NOT thread-safe: each owner keeps its own.
    public static XMLInputFactory decodeFactory() {
        XMLInputFactory f = new com.fasterxml.aalto.stax.InputFactoryImpl();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }

    /// The offset-reporting reader: Woodstox, DTD off, coalescing off — for `RefPatcher.patch`
    /// only. See the class javadoc for why this one is not interchangeable.
    public static com.ctc.wstx.stax.WstxInputFactory offsetFactory() {
        com.ctc.wstx.stax.WstxInputFactory f = new com.ctc.wstx.stax.WstxInputFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
        return f;
    }
}
