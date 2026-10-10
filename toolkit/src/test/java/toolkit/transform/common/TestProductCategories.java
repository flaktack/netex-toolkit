package toolkit.transform.common;

// A Line takes its public code and its name from the product its service runs under, where the feed
// gave it none of its own.
//
// The two shapes a feed states a product in are both read: on the Line (the Swiss export) and on
// every journey (the OEBB one, where no Line carries a category at all).
//
// coded keeps its own code. uncoded has none and runs one product. mixed has none and runs three,
// so it splits. product already codes the abbreviation and wants only the written-out name.

import noi.netex.model.Codespace;
import noi.netex.model.Line;
import noi.netex.model.ServiceJourney;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TestProductCategories {

    private static final String NETEX = """
        <ResourceFrame id="rf" version="1">
          <typesOfValue>
            <ValueSet id="vs1" version="1" nameOfClass="TypeOfProductCategory">
              <values>
                <TypeOfProductCategory id="e:TypeOfProductCategory:EC" version="1"><Name>EuroCity</Name><ShortName>EC</ShortName></TypeOfProductCategory>
                <TypeOfProductCategory id="e:TypeOfProductCategory:IC" version="1"><Name>InterCity</Name><ShortName>IC</ShortName></TypeOfProductCategory>
                <TypeOfProductCategory id="e:TypeOfProductCategory:IR" version="1"><Name>InterRegio</Name><ShortName>IR</ShortName></TypeOfProductCategory>
                <TypeOfProductCategory id="e:TypeOfProductCategory:REX" version="1"><Name>Regional-Express</Name><ShortName>REX</ShortName></TypeOfProductCategory>
                <!-- no written-out form to offer: the Name repeats the abbreviation -->
                <TypeOfProductCategory id="e:TypeOfProductCategory:ZUG" version="1"><Name>ZUG</Name><ShortName>ZUG</ShortName></TypeOfProductCategory>
                <!-- and this one publishes no Name at all -->
                <TypeOfProductCategory id="e:TypeOfProductCategory:BUS" version="1"><ShortName>BUS</ShortName></TypeOfProductCategory>
              </values>
            </ValueSet>
          </typesOfValue>
        </ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="e:Line:coded" version="1"><Name>REX6</Name><ShortName>REX6</ShortName><TransportMode>rail</TransportMode><PublicCode>REX6</PublicCode></Line>
            <Line id="e:Line:uncoded" version="1"><Name>A11</Name><TransportMode>rail</TransportMode></Line>
            <Line id="e:Line:mixed" version="1"><Name>A12</Name><TransportMode>rail</TransportMode></Line>
            <Line id="e:Line:product" version="1"><Name>EC</Name><ShortName>2A</ShortName><TransportMode>rail</TransportMode><PublicCode>EC</PublicCode><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:EC" version="1"/></Line>
            <Line id="e:Line:noname" version="1"><TransportMode>rail</TransportMode><PublicCode>S1</PublicCode></Line>
            <Line id="e:Line:nameless" version="1"><Name>ZUG1</Name><TransportMode>rail</TransportMode></Line>
            <Line id="e:Line:busnoname" version="1"><Name>B7</Name><TransportMode>bus</TransportMode></Line>
          </lines>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="e:ServiceJourney:u1" version="1"><LineRef ref="e:Line:uncoded" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:REX" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:c1" version="1"><LineRef ref="e:Line:coded" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:REX" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:m1" version="1"><LineRef ref="e:Line:mixed" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:EC" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:m2" version="1"><LineRef ref="e:Line:mixed" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:EC" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:m3" version="1"><LineRef ref="e:Line:mixed" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:EC" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:m4" version="1"><LineRef ref="e:Line:mixed" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:IC" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:m5" version="1"><LineRef ref="e:Line:mixed" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:IC" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:m6" version="1"><LineRef ref="e:Line:mixed" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:IR" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:z1" version="1"><LineRef ref="e:Line:nameless" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:ZUG" version="1"/></ServiceJourney>
            <ServiceJourney id="e:ServiceJourney:b1" version="1"><LineRef ref="e:Line:busnoname" version="1"/><TypeOfProductCategoryRef ref="e:TypeOfProductCategory:BUS" version="1"/></ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    private static final Codespace FALLBACK = codespace();

    private static Codespace codespace() {
        Codespace cs = new Codespace();
        cs.setId("epip_data");
        cs.setXmlns("epd");
        cs.setXmlnsUrl("http://netex-cen.eu/epip_data");
        return cs;
    }

    private record Result(Map<String, Line> lines, Map<String, ServiceJourney> journeys) {}

    private static Result run(TestStore ts) throws Exception {
        ts.loadNetex(NETEX);
        try (Txn txn = ts.store.roTxn()) {
            List<Object> changed = ProductCategories.deriveLineLabels(ts.store, txn, FALLBACK);
            Map<String, Line> lines = new LinkedHashMap<>();
            Map<String, ServiceJourney> journeys = new LinkedHashMap<>();
            for (Object o : changed) {
                if (o instanceof Line l) lines.put(l.getId(), l);
                else if (o instanceof ServiceJourney sj) journeys.put(sj.getId(), sj);
            }
            return new Result(lines, journeys);
        }
    }

    @Test
    void testAnUncodedLineTakesTheProductItRuns(TestStore ts) throws Exception {
        Line l = run(ts).lines().get("e:Line:uncoded");
        Check.that(l != null, "the uncoded line is relabelled");
        Check.equals("REX", Codes.text(l.getPublicCode()),
                "the category's ShortName becomes the line's public code");
        Check.equals("Regional-Express", Mls.text(l.getName()),
                "and its Name the written-out form, over the internal corridor code A11");
    }

    @Test
    void testACodedLineKeepsItsCodeAndItsName(TestStore ts) throws Exception {
        Check.isNull(run(ts).lines().get("e:Line:coded"),
                "a line carrying its own PublicCode is left alone entirely: REX6 is the number a "
                        + "rider reads, and REX would lose it");
    }

    @Test
    void testALineThatAlreadyCodesTheProductTakesOnlyTheName(TestStore ts) throws Exception {
        Line l = run(ts).lines().get("e:Line:product");
        Check.that(l != null, "the line that codes EC is named");
        Check.equals("EC", Codes.text(l.getPublicCode()), "its PublicCode is untouched");
        Check.equals("EuroCity", Mls.text(l.getName()),
                "and its Name becomes the written-out form, over the abbreviation it repeated");
    }

    @Test
    void testACategoryWithNoWrittenOutFormEmptiesTheName(TestStore ts) throws Exception {
        Result r = run(ts);
        Line zug = r.lines().get("e:Line:nameless");
        Check.equals("ZUG", Codes.text(zug.getPublicCode()), "the abbreviation still lands");
        Check.equals("", Mls.textOrEmpty(zug.getName()),
                "a Name repeating the abbreviation offers nothing, so the Name is emptied rather "
                        + "than left holding the internal code ZUG1");
        Check.that(zug.getName() != null,
                "the element stays: OTP dereferences a Line's Name with no null guard");

        Line bus = r.lines().get("e:Line:busnoname");
        Check.equals("BUS", Codes.text(bus.getPublicCode()), "a category with no Name still codes");
        Check.equals("", Mls.textOrEmpty(bus.getName()), "and leaves the Name empty");
    }

    @Test
    void testAMixedLineSplitsPerProduct(TestStore ts) throws Exception {
        Result r = run(ts);

        Line source = r.lines().get("e:Line:mixed");
        Check.that(source != null, "the source line is relabelled");
        Check.equals("EC", Codes.text(source.getPublicCode()),
                "the heaviest category keeps the source line, so most journeys keep their id");
        Check.equals("EuroCity", Mls.text(source.getName()), "and its written-out form");

        Line ic = r.lines().get("e:Line:mixed-IC");
        Line ir = r.lines().get("e:Line:mixed-IR");
        Check.that(ic != null && ir != null,
                "one line minted per other category: " + r.lines().keySet());
        Check.equals("IC", Codes.text(ic.getPublicCode()), "the minted IC line codes IC");
        Check.equals("InterCity", Mls.text(ic.getName()), "and is named InterCity");
        Check.equals("rail", ic.getTransportMode().value(),
                "a minted line carries the source's mode, so its journeys keep their vehicle");

        Check.equals("e:Line:mixed-IC", r.journeys().get("e:ServiceJourney:m4").getLineRef()
                .getValue().getRef(), "an IC journey moves onto the IC line");
        Check.equals("e:Line:mixed-IR", r.journeys().get("e:ServiceJourney:m6").getLineRef()
                .getValue().getRef(), "and an IR journey onto the IR line");
        Check.isNull(r.journeys().get("e:ServiceJourney:m1"),
                "an EC journey is not touched: its line is the one that kept the category");
    }

    /// The backstop the EPIP ensemble registers, which is the profile's requirement rather than a
    /// policy: every Line reaches an export with a Name.
    @Test
    void testALineWithNoNameTakesItsCode(TestStore ts) throws Exception {
        ts.loadNetex(NETEX);
        try (Txn txn = ts.store.roTxn()) {
            Line noname = null;
            for (Object o : ts.store.iterOnlyObjects(txn, Line.class)) {
                if ("e:Line:noname".equals(((Line) o).getId())) noname = (Line) o;
            }
            Check.that(noname != null, "the nameless line is in the store");
            Check.isNull(Mls.text(noname.getName()), "precondition: it carries no Name");
            Check.that(ProductCategories.lineNameOp(noname), "the backstop fires");
            Check.equals("S1", Mls.text(noname.getName()), "it takes its PublicCode as its Name");
            Check.that(!ProductCategories.lineNameOp(noname),
                    "and does not fire a second time on a Line that now has one");
        }
    }
}
