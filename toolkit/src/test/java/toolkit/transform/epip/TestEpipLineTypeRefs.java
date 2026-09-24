package toolkit.transform.epip;

// A Line's TypeOfServiceRef and TypeOfProductCategoryRef survive the EPIP line pass when the feed
// defines what they name, and are dropped when it does not.
//
// The profile lists both as Line fields, so the data is permitted -- but it also requires an
// internal reference to resolve inside the document it is published in. A publisher that names a
// code list it never publishes therefore gets the field removed rather than a dangling reference.
//
// BrandingRef is not a Line field in the profile and goes either way.

import noi.netex.model.Line;
import org.junit.jupiter.api.Test;
import toolkit.harness.EpipToDb;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class TestEpipLineTypeRefs {

    /// line1 names types the frame defines; line2 names types nothing defines. Both name a
    /// Branding, which is not a profile field for a Line.
    private static final String NETEX = """
        <ResourceFrame id="rf" version="1">
          <typesOfValue>
            <TypeOfService id="tos1" version="1"><Name>Regional</Name></TypeOfService>
            <!-- published the way a code list usually is: a ValueSet with its members inside, so
                 the standalone TypeOfProductCategory class map is empty. -->
            <ValueSet id="vs1" version="1" nameOfClass="TypeOfProductCategory">
              <Name>ProductCategories</Name>
              <values>
                <TypeOfProductCategory id="tpc1" version="1"><Name>InterCity</Name></TypeOfProductCategory>
              </values>
            </ValueSet>
          </typesOfValue>
        </ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="line1" version="1"><Name>Defined</Name><TransportMode>bus</TransportMode>
              <BrandingRef ref="brand1" version="1"/>
              <TypeOfProductCategoryRef ref="tpc1" version="1"/>
              <TypeOfServiceRef ref="tos1" version="1"/>
            </Line>
            <Line id="line2" version="1"><Name>Dangling</Name><TransportMode>bus</TransportMode>
              <TypeOfProductCategoryRef ref="tpc-absent" version="1"/>
              <TypeOfServiceRef ref="tos-absent" version="1"/>
            </Line>
          </lines>
        </ServiceFrame>
        """;

    @Test
    void testALineKeepsATypeRefOnlyWhenItsDefinitionIsPresent(TestStore ts) throws Exception {
        Path file = ts.buildDbFile(NETEX, "line-typerefs.lmdb");
        EpipToDb.inPlace(file, GeneratorDefaults.TEST);
        try (Store db = Stores.open(file, true); Txn txn = db.roTxn()) {
            Map<String, Line> lines = new LinkedHashMap<>();
            for (Object o : db.iterOnlyObjects(txn, Line.class)) {
                lines.put(((Line) o).getId(), (Line) o);
            }

            Line defined = lines.get("line1");
            Check.that(defined != null, "line1 survives: " + lines.keySet());
            Check.equals("tos1", defined.getTypeOfServiceRef().getRef(),
                    "a TypeOfServiceRef whose TypeOfService the frame defines is KEPT");
            Check.equals("tpc1", defined.getTypeOfProductCategoryRef().getRef(),
                    "and so is one whose definition is nested in a ValueSet rather than a row of "
                    + "its own -- which is how a code list is normally published");
            Check.isNull(defined.getBrandingRef(),
                    "BrandingRef goes regardless: the profile has no Line field for it");

            Line dangling = lines.get("line2");
            Check.isNull(dangling.getTypeOfServiceRef(),
                    "a TypeOfServiceRef naming nothing is dropped, not published dangling");
            Check.isNull(dangling.getTypeOfProductCategoryRef(),
                    "likewise a TypeOfProductCategoryRef naming nothing");
        }
    }
}
