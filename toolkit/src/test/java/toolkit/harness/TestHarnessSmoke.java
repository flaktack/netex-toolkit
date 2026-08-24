package toolkit.harness;

// The trivial loader round-trip: load one ScheduledStopPoint through the real loader, read it
// back under a ro transaction, canonical-XML compare.

import noi.netex.model.ScheduledStopPoint;
import org.junit.jupiter.api.Test;
import toolkit.store.Txn;
import toolkit.test.TestStore;
import toolkit.test.Xml;

import java.util.ArrayList;
import java.util.List;

public class TestHarnessSmoke {

    /// One entity survives a load and a read-back unchanged.
    @Test
    void testLoadNetexRoundTripsAnEntity(TestStore db) throws Exception {
        db.loadNetex("""
            <ServiceFrame id="sf" version="1">
              <scheduledStopPoints>
                <ScheduledStopPoint id="ssp1" version="1"><Name>Test</Name></ScheduledStopPoint>
              </scheduledStopPoints>
            </ServiceFrame>
            """);

        List<Object> ssps = new ArrayList<>();
        try (Txn txn = db.store.roTxn()) {
            for (Object o : db.store.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
                ssps.add(o);
            }
        }

        Xml.assertXmlEqual(
                Xml.toXmlAll(ssps),
                """
                <ScheduledStopPoint xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="ssp1" version="1">
                  <Name>Test</Name>
                </ScheduledStopPoint>""");
    }
}
