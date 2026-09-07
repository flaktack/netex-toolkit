package toolkit.codec;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import toolkit.model.ModelRegistry;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/// The value codec: the object's raw NeTEx XML fragment, LZ4-block-compressed, stored as
/// `[4-byte LE uncompressed length][LZ4 block]`.
///
/// [#encodeRaw] takes fragment bytes a caller already holds; [#encode(Object)] marshals first, for
/// objects a transformer created or mutated. Decode is LZ4 + StAX + unmarshal by declared type.
///
/// One lazily-built full-package JAXBContext, shared by every codec instance and costing seconds on
/// first use. It must stay full-package: a per-class context misses value classes hidden behind
/// wildcarded `List<JAXBElement<?>>` element-ref fields, and marshal/unmarshal of container classes
/// then fails. Not thread-safe — one codec per thread.
public final class FragmentCodec {
    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();
    private static volatile JAXBContext PACKAGE_CTX;

    private final LZ4Compressor comp = LZ4.fastCompressor();
    private final LZ4FastDecompressor decomp = LZ4.fastDecompressor();
    private final XMLInputFactory xif = Stax.decodeFactory();
    private Unmarshaller unmarshaller;
    private Marshaller marshaller;
    private BindEvents decodeEvents;
    private BindEvents marshalEvents;
    /// The feed or store this codec is reading, for [BindEvents]' per-feed axis.
    private String scope;
    /// The thread this codec last pinned [JaxbPin] on.
    private Thread pinnedOn;

    public FragmentCodec() {
    }

    /// Label this codec's binding events with the feed or store they came from. Optional —
    /// unlabelled events are reported under `-`.
    public FragmentCodec scope(String label) {
        this.scope = label;
        if (decodeEvents != null) decodeEvents.scope(label);
        if (marshalEvents != null) marshalEvents.scope(label);
        return this;
    }

    private void pin() {
        Thread t = Thread.currentThread();
        if (pinnedOn != t) {
            JaxbPin.pin();
            pinnedOn = t;
        }
    }

    /// The shared full-model JAXBContext, built once per JVM on first use.
    ///
    /// A plain `newInstance(pkg, loader)`, with no `ANNOTATION_READER` hook: the date/time and
    /// duration bindings are declared in the model's own bindings file.
    public static JAXBContext packageContext() {
        JAXBContext ctx = PACKAGE_CTX;
        if (ctx == null) {
            synchronized (FragmentCodec.class) {
                ctx = PACKAGE_CTX;
                if (ctx == null) {
                    try {
                        ctx = JAXBContext.newInstance(ModelRegistry.MODEL_PKG,
                                FragmentCodec.class.getClassLoader());
                    } catch (Exception e) {
                        throw new IllegalStateException("JAXBContext init failed", e);
                    }
                    PACKAGE_CTX = ctx;
                }
            }
        }
        return ctx;
    }

    /// Compress an already-captured fragment.
    public byte[] encodeRaw(byte[] fragmentXml) {
        byte[] compressed = comp.compress(fragmentXml);
        byte[] out = new byte[4 + compressed.length];
        int n = fragmentXml.length;
        out[0] = (byte) n;
        out[1] = (byte) (n >>> 8);
        out[2] = (byte) (n >>> 16);
        out[3] = (byte) (n >>> 24);
        System.arraycopy(compressed, 0, out, 4, compressed.length);
        return out;
    }

    /// Marshal the object to its NeTEx fragment, then compress (the mutated-object path).
    public byte[] encode(Object obj) {
        return encodeRaw(marshalFragment(obj));
    }

    /// The object's canonical fragment bytes (element name = class simple name, netex namespace).
    @SuppressWarnings({"unchecked", "rawtypes"})
    public byte[] marshalFragment(Object obj) {
        pin();
        try {
            if (marshaller == null) {
                marshaller = packageContext().createMarshaller();
                // The RI's marshal default returns false for every severity, so an event here is
                // already an exception; this is for attribution.
                marshalEvents = BindEvents.marshalling(marshaller.getEventHandler());
                marshalEvents.scope(scope);
                marshaller.setEventHandler(marshalEvents);
                marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
                marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE); // no XML declaration
                marshaller.setListener(SchemaDefaults.LISTENER);
            }
            Class<?> clazz = obj.getClass();
            marshalEvents.context(clazz, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
            QName qn = new QName(ModelRegistry.NETEX_NS, clazz.getSimpleName());
            marshaller.marshal(new JAXBElement(qn, clazz, obj), bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("marshal failed for " + obj.getClass().getName(), e);
        }
    }

    /// Decompress a stored blob back to the raw fragment bytes.
    public byte[] decompress(byte[] blob) {
        int n = (blob[0] & 0xFF) | ((blob[1] & 0xFF) << 8) | ((blob[2] & 0xFF) << 16) | ((blob[3] & 0xFF) << 24);
        byte[] out = new byte[n];
        decomp.decompress(blob, 4, out, 0, n);
        return out;
    }

    /// Full decode: LZ4 + StAX + JAXB unmarshal into the NeTEx model class.
    public <T> T decode(byte[] blob, Class<T> clazz) {
        byte[] xml = decompress(blob);
        pin();
        try {
            if (unmarshaller == null) {
                unmarshaller = packageContext().createUnmarshaller();
                // Without a handler here, every value that fails to parse into its bound type is
                // raised by the RI and dropped, leaving an empty field and no signal.
                decodeEvents = BindEvents.unmarshalling(unmarshaller.getEventHandler());
                decodeEvents.scope(scope);
                unmarshaller.setEventHandler(decodeEvents);
            }
            // EnumWhitespace: the xs:collapse the RI skips for enum-valued elements. This is where
            // the loss materialises — the loader stores source bytes verbatim, so a padded enum
            // survives storage and disappears only when a transformer decodes and re-marshals.
            XMLStreamReader r = EnumWhitespace.wrap(
                    xif.createXMLStreamReader(new ByteArrayInputStream(xml)));
            r.nextTag();
            // The reader names the offending element when an event fires; the class is the root.
            decodeEvents.context(clazz, r);
            JAXBElement<T> el = unmarshaller.unmarshal(r, clazz);
            r.close();
            return el.getValue();
        } catch (Exception e) {
            throw new IllegalStateException("unmarshal failed for " + clazz.getName(), e);
        }
    }
}
