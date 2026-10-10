package toolkit.transform.epip;

// A journey's PublicCode comes from the number its trainNumbers advertise, on the products whose
// number the public reads.
//
// OTP maps a journey's PublicCode to a trip's short name and reads TrainNumber nowhere, so this is
// the field that carries a train number into a graph.
//
// A Mentz export populates trainNumbers well beyond rail -- on funiculars, trams and plain buses,
// where the value counts courses within a day. Rail qualifies on its own; anything else has to be
// named by toolkit.epip.journeyCodeCategories, which is how a rail replacement keeps the number of
// the train it stands in for.
//
// sjRail and sjBus carry no TransportMode of their own, which is the shape every OEBB journey
// arrives in: the mode comes from the line each names.

import noi.netex.model.ServiceJourney;
import noi.netex.text.Codes;
import org.junit.jupiter.api.Test;
import toolkit.harness.EpipToDb;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.util.Conf;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class TestEpipJourneyPublicCode {

    private static final String NETEX = """
        <ResourceFrame id="rf" version="1">
          <typesOfValue>
            <ValueSet id="vs1" version="1" nameOfClass="TypeOfProductCategory">
              <values>
                <TypeOfProductCategory id="cat:SEV" version="1"><Name>Rail replacement</Name><ShortName>SEV</ShortName></TypeOfProductCategory>
                <TypeOfProductCategory id="cat:BUS" version="1"><Name>Bus</Name><ShortName>BUS</ShortName></TypeOfProductCategory>
              </values>
            </ValueSet>
          </typesOfValue>
        </ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="lineRail" version="1"><Name>Rail</Name><TransportMode>rail</TransportMode></Line>
            <Line id="lineBus" version="1"><Name>Bus</Name><TransportMode>bus</TransportMode></Line>
            <Line id="lineFun" version="1"><Name>Funicular</Name><TransportMode>funicular</TransportMode></Line>
          </lines>
          <scheduledStopPoints>
            <ScheduledStopPoint id="sspA" version="1"><Name>Alpha</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="sspB" version="1"><Name>Beta</Name></ScheduledStopPoint>
          </scheduledStopPoints>
          <journeyPatterns>
            <ServiceJourneyPattern id="pat1" version="1">
              <pointsInSequence>
                <StopPointInJourneyPattern id="p1" version="1" order="1"><ScheduledStopPointRef ref="sspA" version="1"/></StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="p2" version="1" order="2"><ScheduledStopPointRef ref="sspB" version="1"/></StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <trainNumbers>
            <TrainNumber id="tn:1289" version="1"><ForAdvertisement>1289</ForAdvertisement></TrainNumber>
            <TrainNumber id="tn:714" version="1"><ForAdvertisement>714</ForAdvertisement></TrainNumber>
            <TrainNumber id="tn:2425" version="1"><ForAdvertisement>2425</ForAdvertisement></TrainNumber>
            <TrainNumber id="tn:33" version="1"><ForAdvertisement>33</ForAdvertisement></TrainNumber>
            <TrainNumber id="tn:7744" version="1"><ForAdvertisement>7744</ForAdvertisement></TrainNumber>
          </trainNumbers>
          <vehicleJourneys>
            <ServiceJourney id="sjRail" version="1">
              <LineRef ref="lineRail" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers><TrainNumberRef ref="tn:1289" version="1"/></trainNumbers>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjSev" version="1">
              <LineRef ref="lineBus" version="1"/>
              <TypeOfProductCategoryRef ref="cat:SEV" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers><TrainNumberRef ref="tn:714" version="1"/></trainNumbers>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjBus" version="1">
              <LineRef ref="lineBus" version="1"/>
              <TypeOfProductCategoryRef ref="cat:BUS" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers><TrainNumberRef ref="tn:2425" version="1"/></trainNumbers>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjFun" version="1">
              <LineRef ref="lineFun" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers><TrainNumberRef ref="tn:33" version="1"/></trainNumbers>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjOwnCode" version="1">
              <LineRef ref="lineRail" version="1"/>
              <PublicCode>ICE 71</PublicCode>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers><TrainNumberRef ref="tn:1289" version="1"/></trainNumbers>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjNoNumber" version="1">
              <LineRef ref="lineRail" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjDangling" version="1">
              <LineRef ref="lineRail" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers><TrainNumberRef ref="tn:absent" version="1"/></trainNumbers>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjTwoNumbers" version="1">
              <LineRef ref="lineRail" version="1"/>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers>
                <TrainNumberRef ref="tn:7744" version="1"/>
                <TrainNumberRef ref="tn:1289" version="1"/>
              </trainNumbers>
              %s
            </ServiceJourney>
            <ServiceJourney id="sjOwnMode" version="1">
              <LineRef ref="lineBus" version="1"/>
              <TransportMode>rail</TransportMode>
              <ServiceJourneyPatternRef ref="pat1" version="1"/>
              <trainNumbers><TrainNumberRef ref="tn:7744" version="1"/></trainNumbers>
              %s
            </ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """.formatted((Object[]) times(9));

    /// Every journey needs a timetable to survive the pass, and they all run over one pattern.
    private static String[] times(int n) {
        String block = """
            <passingTimes>
                <TimetabledPassingTime id="tpt-%d-1" version="1"><StopPointInJourneyPatternRef ref="p1" version="1" order="1"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
                <TimetabledPassingTime id="tpt-%d-2" version="1"><StopPointInJourneyPatternRef ref="p2" version="1" order="2"/><ArrivalTime>10:30:00</ArrivalTime></TimetabledPassingTime>
              </passingTimes>""";
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = block.formatted(i, i);
        return out;
    }

    private static Map<String, ServiceJourney> run(TestStore ts, String name) throws Exception {
        Path file = ts.buildDbFile(NETEX, name);
        EpipToDb.inPlace(file, GeneratorDefaults.TEST);
        Map<String, ServiceJourney> out = new LinkedHashMap<>();
        try (Store db = Stores.open(file, true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, ServiceJourney.class)) {
                out.put(((ServiceJourney) o).getId(), (ServiceJourney) o);
            }
        }
        return out;
    }

    private static String code(Map<String, ServiceJourney> js, String id) {
        ServiceJourney sj = js.get(id);
        Check.that(sj != null, id + " survives the pass: " + js.keySet());
        return Codes.text(sj.getPublicCode());
    }

    @Test
    void testRailTakesItsTrainNumberAndOtherModesDoNot(TestStore ts) throws Exception {
        Map<String, ServiceJourney> js = run(ts, "journey-publiccode.lmdb");

        Check.equals("1289", code(js, "sjRail"),
                "a rail journey takes the number its trainNumbers advertise");
        Check.equals("7744", code(js, "sjOwnMode"),
                "the journey's OWN TransportMode decides where it has one, over its line's");
        Check.isNull(code(js, "sjFun"),
                "a funicular journey's number counts courses within a day, so it is left alone");
        Check.isNull(code(js, "sjBus"),
                "likewise a plain bus journey, whose category is not admitted here");
        Check.isNull(code(js, "sjSev"),
                "and a rail replacement, until its category is named");
        Check.equals("ICE 71", code(js, "sjOwnCode"),
                "a journey that published its own PublicCode keeps it");
        Check.isNull(code(js, "sjNoNumber"), "a journey with no trainNumbers gets no code");
        Check.isNull(code(js, "sjDangling"),
                "a TrainNumberRef naming nothing yields no code");
        Check.equals("7744", code(js, "sjTwoNumbers"),
                "a journey naming several numbers takes the first");
    }

    @Test
    void testAnAdmittedCategoryTakesItsTrainNumberOffRail(TestStore ts) throws Exception {
        Conf.override(Conf.EPIP_JOURNEY_CODE_CATEGORIES, "cat:SEV");
        Map<String, ServiceJourney> js;
        try {
            js = run(ts, "journey-publiccode-sev.lmdb");
        } finally {
            Conf.clearOverride(Conf.EPIP_JOURNEY_CODE_CATEGORIES);
        }
        Check.equals("714", code(js, "sjSev"),
                "a rail replacement advertises the number of the train it stands in for");
        Check.isNull(code(js, "sjBus"),
                "a category the flag does not name is still left alone");
        Check.isNull(code(js, "sjFun"), "and so is a funicular, which names no category at all");
        Check.equals("1289", code(js, "sjRail"), "rail still qualifies without being listed");
    }

    /// The journey's own type refs get the test the profile requires of a reference, which the line
    /// pass already applies to a Line's.
    @Test
    void testAJourneyKeepsACategoryRefOnlyWhenItsDefinitionIsPresent(TestStore ts) throws Exception {
        Map<String, ServiceJourney> js = run(ts, "journey-typerefs.lmdb");
        Check.equals("cat:BUS", js.get("sjBus").getTypeOfProductCategoryRef().getRef(),
                "a ref whose definition the ValueSet carries is KEPT");
        Check.isNull(js.get("sjRail").getTypeOfProductCategoryRef(),
                "a journey that named no category still has none");
    }
}
