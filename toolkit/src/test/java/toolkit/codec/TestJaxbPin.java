package toolkit.codec;

// A JVM-level allocation defect in jaxb-runtime.
//
// `java.lang.ThreadLocal$ThreadLocalMap$Entry` is a top allocated class in the large stages, and
// every one of those allocation samples carries `Coordinator.pushCoordinator`: JAXB's per-event
// push/pop leaves `old == null`, so `popCoordinator` calls `ThreadLocal.remove()` and the next
// `get()` has to build a fresh Entry. toolkit.codec.JaxbPin keeps a sentinel Coordinator pushed
// for the life of the thread so `old` is never null and the entry is never removed.
//
// NEUTER PROOF: run with -Dtoolkit.jaxbPin=false and testSentinelSurvivesADecode /
// testSentinelSurvivesAMarshal / testPinIsIdempotent fail; the two value tests
// (testDecodeIsUnchangedByThePin, testMarshalIsUnchangedByThePin) keep passing, because the pin
// is an allocation shape and must never touch a byte.

import noi.netex.model.Line;
import org.glassfish.jaxb.runtime.v2.runtime.Coordinator;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import java.nio.charset.StandardCharsets;

public class TestJaxbPin {

    private static final String LINE_XML =
            "<Line xmlns=\"http://www.netex.org.uk/netex\" id=\"x:Line:1\" version=\"1\">"
                    + "<Name>L1</Name><TransportMode>bus</TransportMode></Line>";

    private static byte[] blob() {
        return new FragmentCodec().encodeRaw(LINE_XML.getBytes(StandardCharsets.UTF_8));
    }

    /// Two calls install exactly one sentinel, and it is the active coordinator afterwards.
    @Test
    void testPinIsIdempotent() {
        JaxbPin.pin();
        Coordinator first = Coordinator._getInstance();
        JaxbPin.pin();
        Check.that(JaxbPin.pinned(), "the thread carries a sentinel after pin()");
        Check.that(first instanceof JaxbPin, "_getInstance() is the sentinel between operations");
        Check.that(first == Coordinator._getInstance(), "pin() is idempotent — one sentinel only");
    }

    /// THE regression test. Without the sentinel, the unmarshal's last popCoordinator() calls
    /// ThreadLocal.remove() and this reads back null — which is exactly the state that makes the
    /// next event allocate an Entry.
    @Test
    void testSentinelSurvivesADecode() {
        FragmentCodec codec = new FragmentCodec();
        codec.decode(blob(), Line.class);
        Check.that(Coordinator._getInstance() instanceof JaxbPin,
                "the sentinel is still the active coordinator after a decode "
                        + "(a null here means popCoordinator took the remove() branch again)");
    }

    /// Same for the marshal side: XMLSerializer pushes and pops the same thread-local.
    @Test
    void testSentinelSurvivesAMarshal() {
        FragmentCodec codec = new FragmentCodec();
        Line line = codec.decode(blob(), Line.class);
        codec.marshalFragment(line);
        Check.that(Coordinator._getInstance() instanceof JaxbPin,
                "the sentinel is still the active coordinator after a marshal");
    }

    /// The pin is an allocation shape: the decoded bean must be exactly what it always was.
    @Test
    void testDecodeIsUnchangedByThePin() {
        Line line = new FragmentCodec().decode(blob(), Line.class);
        Check.equals("x:Line:1", line.getId(), "id survives");
        Check.equals("1", line.getVersion(), "version survives");
        Check.equals("bus", line.getTransportMode().value(), "TransportMode survives");
    }

    /// ... and so must the re-marshalled bytes. The literal is JAXB's own output shape (its
    /// namespace declarations, its attribute order), NOT the source spelling — the point is that
    /// the pin does not move a single byte of it. Run this test with and without
    /// -Dtoolkit.jaxbPin=false: it must pass identically both ways.
    @Test
    void testMarshalIsUnchangedByThePin() {
        FragmentCodec codec = new FragmentCodec();
        Line line = codec.decode(blob(), Line.class);
        Check.equals("<Line xmlns=\"http://www.netex.org.uk/netex\""
                        + " xmlns:ns2=\"http://www.opengis.net/gml/3.2\""
                        + " xmlns:ns3=\"http://www.siri.org.uk/siri\""
                        + " version=\"1\" id=\"x:Line:1\">"
                        + "<Name>L1</Name><TransportMode>bus</TransportMode></Line>",
                new String(codec.marshalFragment(line), StandardCharsets.UTF_8),
                "the re-marshalled fragment is byte-identical with and without the pin");
    }
}
