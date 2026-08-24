package toolkit.codec;

// JAXB bind events: the "raised and discarded" shape.
//
// FragmentCodec.decode and NetexLoader.loadStream both bound values with NO
// ValidationEventHandler installed. Every source value that failed to parse into the type its
// field is bound to was therefore raised by the JAXB RI and dropped: the field was left empty and
// nothing said so.
//
// WHY THIS TEST EXISTS RATHER THAN A REPORT. The fix is a COUNTING handler, and the argument that
// it is safe to install one is that it does not change any verdict. That argument rests on six
// numbers — what the RI's own default handler returns for each severity on each side — which a
// jaxb-runtime bump could change. So they are measured here, at every build, instead of quoted:
//
//     unmarshal default:  WARNING -> true    ERROR -> true    FATAL_ERROR -> false
//     marshal   default:  WARNING -> false   ERROR -> false   FATAL_ERROR -> false
//
// The unmarshal ERROR row IS the defect: the RI decided to continue, and with no handler nobody
// was told. The marshal row is why the marshal side was never silent — an event there is already
// an exception — and therefore why this is a decode-and-load fix that instruments marshal only
// for attribution.
//
// WHAT THIS FILE PINS:
//   1. those six verdicts, read off the running jaxb-runtime;
//   2. that BindEvents returns EXACTLY what the handler it wrapped returns, severity by severity
//      — a property of the construction, not a claim;
//   3. that decoding <gml:posList>None</gml:posList> still yields the same bean it yielded before
//      (empty list, no throw) AND now yields a count attributed to the class, the element and the
//      cause;
//   4. that the tally is BOUNDED — 500 bad decodes produce 500 counted events and ONE site, which
//      is why this is not a ValidationEventCollector;
//   5. that the loader arm counts too, labelled with the stream it was reading — the "per feed"
//      axis;
//   6. that clean data counts nothing at all.

import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.ValidationEvent;
import jakarta.xml.bind.ValidationEventHandler;
import jakarta.xml.bind.helpers.ValidationEventImpl;
import noi.netex.model.ServiceLink;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class TestBindEvents {

    private static final String NS = " xmlns=\"http://www.netex.org.uk/netex\""
            + " xmlns:gml=\"http://www.opengis.net/gml/3.2\"";

    /// The shape, reduced to one fragment: a literal "None" where a list of doubles is bound.
    private static final String SL_NONE =
            "<ServiceLink" + NS + " id=\"X:ServiceLink:1\" version=\"2\">"
            + "<Distance>210</Distance>"
            + "<gml:LineString gml:id=\"g1\"><gml:posList>None</gml:posList></gml:LineString>"
            + "</ServiceLink>";

    private static final String SL_OK =
            "<ServiceLink" + NS + " id=\"X:ServiceLink:2\" version=\"2\">"
            + "<Distance>210</Distance>"
            + "<gml:LineString gml:id=\"g2\"><gml:posList>45.75 7.31 45.76 7.32</gml:posList></gml:LineString>"
            + "</ServiceLink>";

    /// The same value inside a frame the loader will walk, for the load-side arm.
    private static final String FRAME_NONE = """
        <ServiceFrame id="svf" version="1">
          <serviceLinks>
            <ServiceLink id="sl-none" version="1"><Distance>210</Distance>
              <gml:LineString gml:id="ls1"><gml:posList>None</gml:posList></gml:LineString>
            </ServiceLink>
            <ServiceLink id="sl-ok" version="1"><Distance>11</Distance>
              <gml:LineString gml:id="ls2"><gml:posList>45.75 7.31 45.76 7.32</gml:posList></gml:LineString>
            </ServiceLink>
          </serviceLinks>
        </ServiceFrame>
        """;

    // ------------------------------------------------------------------ 1. the six verdicts

    /// The RI's own default handlers, measured. If a jaxb-runtime bump moves any of these six, the
    /// argument moves with it and this is the only thing that would say so.
    @Test
    void testRiDefaultVerdictsAreWhatTheFixAssumes() throws Exception {
        Unmarshaller u = FragmentCodec.packageContext().createUnmarshaller();
        ValidationEventHandler du = u.getEventHandler();
        Check.equals(true, du.handleEvent(ev(ValidationEvent.WARNING)),
                "unmarshal default CONTINUES on WARNING");
        Check.equals(true, du.handleEvent(ev(ValidationEvent.ERROR)),
                "unmarshal default CONTINUES on ERROR — the defect, stated as a number");
        Check.equals(false, du.handleEvent(ev(ValidationEvent.FATAL_ERROR)),
                "unmarshal default ABORTS on FATAL_ERROR");

        Marshaller m = FragmentCodec.packageContext().createMarshaller();
        ValidationEventHandler dm = m.getEventHandler();
        Check.equals(false, dm.handleEvent(ev(ValidationEvent.WARNING)),
                "marshal default ABORTS on WARNING — the marshal side was never silent");
        Check.equals(false, dm.handleEvent(ev(ValidationEvent.ERROR)), "marshal default ABORTS on ERROR");
        Check.equals(false, dm.handleEvent(ev(ValidationEvent.FATAL_ERROR)),
                "marshal default ABORTS on FATAL_ERROR");
    }

    /// Delegation by construction: whatever the wrapped handler decides, BindEvents decides. A
    /// handler that answered "true" on its own would be MORE tolerant than the RI on a fatal error.
    @Test
    void testHandlerDelegatesEveryVerdictAndCountsEveryEvent() {
        BindEvents.reset();
        for (boolean verdict : new boolean[] {true, false}) {
            BindEvents h = BindEvents.unmarshalling(e -> verdict);
            for (int sev : new int[] {ValidationEvent.WARNING, ValidationEvent.ERROR,
                    ValidationEvent.FATAL_ERROR}) {
                Check.equals(verdict, h.handleEvent(ev(sev)),
                        "BindEvents returns the wrapped verdict (severity " + sev + ")");
            }
        }
        Check.equals(6L, BindEvents.total(), "and counted all six on the way through");
        BindEvents.reset();
    }

    // ------------------------------------------------------------------ 2. the decode arm

    /// The fragment through the real codec. The BEAN must be exactly what it was before —
    /// that is the unchanged half — and the COUNT must be exactly one, attributed to the class, the
    /// offending element and the cause. That attribution is not inferred: the element name is read
    /// off the StAX reader the RI is standing on when the event fires.
    @Test
    void testDecodeCountsTheLostPosListWithoutChangingTheBean() {
        BindEvents.reset();
        FragmentCodec codec = new FragmentCodec().scope("fixture");
        ServiceLink sl = codec.decode(codec.encodeRaw(bytes(SL_NONE)), ServiceLink.class);

        // The loader tolerated this before and still does, to the field.
        Check.that(sl != null, "the fragment still binds — a failing handler would have thrown here");
        Check.that(sl.getLineString() != null, "the LineString is still there");
        Check.equals(List.of(), sl.getLineString().getPosList().getValue(),
                "and the coordinates are still lost, exactly as before — this counts, it does not repair");

        Check.equals(1L, BindEvents.total(), "one event, no longer discarded");
        List<Map.Entry<BindEvents.Site, Long>> sites = BindEvents.sites();
        Check.equals(1, sites.size(), "one site");
        BindEvents.Site s = sites.get(0).getKey();
        Check.equals("unmarshal", s.side(), "attributed to the decode side");
        Check.equals("fixture", s.scope(), "attributed to the feed/store label the caller set");
        Check.equals("ServiceLink", s.clazz(), "attributed to the fragment's root class");
        Check.equals("{http://www.opengis.net/gml/3.2}posList", s.element(),
                "attributed to the OFFENDING ELEMENT, read off the reader at event time");
        Check.equals("NumberFormatException", s.cause(), "and to what actually failed");
        Check.equals(List.of("None"), BindEvents.samples(s),
                "with the source lexical kept as a sample — the one thing a count alone cannot say");
        BindEvents.reset();
    }

    /// Clean data must count nothing.
    @Test
    void testCleanDataCountsNothing() {
        BindEvents.reset();
        FragmentCodec codec = new FragmentCodec();
        ServiceLink sl = codec.decode(codec.encodeRaw(bytes(SL_OK)), ServiceLink.class);
        Check.equals(4, sl.getLineString().getPosList().getValue().size(), "four coordinates bound");
        Check.equals(0L, BindEvents.total(), "and nothing counted");
        Check.equals(0, BindEvents.sites().size(), "and no site created");
        BindEvents.reset();
    }

    /// THE reason this is not a ValidationEventCollector: the collector retains one event object
    /// per event. This retains a bounded tally, so 500 identical failures cost one map entry and
    /// three sample strings.
    @Test
    void testTheTallyIsBoundedNotAccumulated() {
        BindEvents.reset();
        FragmentCodec codec = new FragmentCodec().scope("fixture");
        byte[] blob = codec.encodeRaw(bytes(SL_NONE));
        for (int i = 0; i < 500; i++) codec.decode(blob, ServiceLink.class);
        Check.equals(500L, BindEvents.total(), "every one of the 500 is counted");
        Check.equals(1, BindEvents.sites().size(), "and they share ONE retained site");
        Check.equals(1, BindEvents.samples(BindEvents.sites().get(0).getKey()).size(),
                "holding one distinct sample lexical, not 500 event objects");
        Check.equals(0L, BindEvents.dropped(), "nothing was dropped at this cardinality");
        BindEvents.reset();
    }

    // ------------------------------------------------------------------ 3. the load arm

    /// The loader is the FIRST place a source value is bound, and it had no handler either. The
    /// scope here is the stream label, which is what makes the report per-feed.
    @Test
    void testLoaderCountsAndLabelsWithTheStream(TestStore db) throws Exception {
        BindEvents.reset();
        db.loadNetex(FRAME_NONE);
        // Snapshot BEFORE anything reads the store back: the load and the read-back are two
        // separate binding sites and the whole point of the scope axis is that they are told apart.
        List<Map.Entry<BindEvents.Site, Long>> afterLoad = BindEvents.sites();
        long loadTotal = BindEvents.total();

        Map<String, ServiceLink> byId = Gen.byId(db, ServiceLink.class);
        Check.equals(2, byId.size(), "both links loaded — the load was not aborted");
        Check.equals(List.of(), byId.get("sl-none").getLineString().getPosList().getValue(),
                "the None link lost its coordinates, as it always did");
        Check.equals(4, byId.get("sl-ok").getLineString().getPosList().getValue().size(),
                "and the good one kept its four");

        Check.equals(1L, loadTotal, "the LOAD raised exactly one event and now counts it");
        Check.equals(1, afterLoad.size(), "over one site");
        BindEvents.Site s = afterLoad.get(0).getKey();
        Check.equals("test", s.scope(), "the stream label is the per-feed axis of the report");
        Check.equals("ServiceLink", s.clazz(), "attributed to the loaded class");
        Check.that(s.element().endsWith("posList"), "and to the offending element: " + s.element());

        // Reading the same object back through the codec raises it a SECOND time, under its own
        // scope: the same value costs one event per bind.
        Check.equals(2L, BindEvents.total(), "load + read-back = two binds, two events");
        Check.equals(2, BindEvents.sites().size(), "under two distinguishable sites");
        BindEvents.reset();
    }

    // ------------------------------------------------------------------ 4. reporting

    /// report() must never be the thing that fails a run, and reset() must actually reset.
    @Test
    void testReportAndResetAreSafe() {
        BindEvents.reset();
        BindEvents.report("test-zero");              // the "0 events" line — the positive statement
        FragmentCodec codec = new FragmentCodec().scope("fixture");
        codec.decode(codec.encodeRaw(bytes(SL_NONE)), ServiceLink.class);
        Check.equals(1L, BindEvents.total(), "counted");
        BindEvents.report("test-one");               // the WARNING line plus one site line
        BindEvents.reset();
        Check.equals(0L, BindEvents.total(), "reset clears the total");
        Check.equals(0, BindEvents.sites().size(), "and the sites");
        Check.equals(0L, BindEvents.dropped(), "and the drop counter");
    }

    // ------------------------------------------------------------------ helpers

    private static ValidationEvent ev(int severity) {
        return new ValidationEventImpl(severity, "probe", null, new RuntimeException("probe"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
