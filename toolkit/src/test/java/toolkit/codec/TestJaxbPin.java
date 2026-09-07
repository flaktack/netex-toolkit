package toolkit.codec;

// Regression test for toolkit.codec.JaxbPin, the sentinel Coordinator that stops jaxb-runtime
// re-creating a `ThreadLocalMap.Entry` on every XML event.
//
// NEUTER PROOF: run with -Dtoolkit.jaxbPin=false and testSentinelSurvivesADecode /
// testSentinelSurvivesAMarshal / testPinIsIdempotent fail; the two value tests
// (testDecodeIsUnchangedByThePin, testMarshalIsUnchangedByThePin) keep passing.

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

    /// ... and so must the re-marshalled bytes. The literal is JAXB's own output shape — its
    /// namespace declarations, its attribute order — not the source spelling.
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
