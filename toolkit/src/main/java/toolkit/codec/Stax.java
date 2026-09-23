package toolkit.codec;

import javax.xml.stream.XMLInputFactory;

/// The toolkit's StAX reader, built in one place.
///
/// Every consumer of a stored fragment must come through here — `FragmentCodec.decode`,
/// `FragmentPeek`, `RefPatcher.scan` and `RefPatcher.patch` — because those byte-reads are
/// compared against unmarshalled bean fields, so they must share the reader's attribute-value
/// normalisation, entity handling and namespace view.
///
/// `RefPatcher.patch` additionally needs per-event input offsets to find the start tags it
/// splices. Over an `InputStream` this reader reports byte offsets, which index the fragment's
/// raw bytes.
public final class Stax {

    private Stax() {}

    /// DTD support off. A factory is not thread-safe: each owner keeps its own.
    public static XMLInputFactory factory() {
        XMLInputFactory f = new com.fasterxml.aalto.stax.InputFactoryImpl();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
