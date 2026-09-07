package toolkit.load;

// Regression test for the srsName arm of the loader's FrameDefaults denormalisation, implemented in
// toolkit/load/CopyingCapture (normalisation N7) on top of NetexLoader's tracking of the frame's
// current location system.
//
// While a frame declares a location system:
//   - an element named in srs_name.txt (Point/LineString/pos/…) that declares no srsName gets the
//     enclosing Location's srsName if it has one, otherwise the frame's;
//   - a <Location> that declares no srsName gets the frame's, and it then stops contributing an
//     inherited value to its children.

import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceLink;
import org.junit.jupiter.api.Test;
import toolkit.codec.FragmentCodec;
import toolkit.store.RawRow;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TestLoaderSrsName {

    /// A frame whose geometry declares no srsName anywhere; \__DEFAULTS__ is the FrameDefaults block.
    static final String PLAIN = """
        <ServiceFrame id="svf" version="1">
          __DEFAULTS__
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>Stazione</Name>
              <Location><Latitude>45.65222507</Latitude><Longitude>13.77349440</Longitude></Location>
            </ScheduledStopPoint>
          </scheduledStopPoints>
          <serviceLinks>
            <ServiceLink id="sl1" version="1"><Distance>639</Distance>
              <gml:LineString gml:id="ls1"><gml:posList>45.65368562 13.77593969 45.64829872 13.77877447</gml:posList></gml:LineString>
            </ServiceLink>
          </serviceLinks>
        </ServiceFrame>
        """;

    /// The same shapes, but every geometry root already carries its own srsName.
    static final String PREDECLARED = """
        <ServiceFrame id="svf" version="1">
          <FrameDefaults><DefaultLocationSystem>EPSG:4326</DefaultLocationSystem></FrameDefaults>
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp1" version="1"><Name>Bolzano</Name>
              <Location srsName="EPSG:32632"><gml:pos>680000.0 5140000.0</gml:pos></Location>
            </ScheduledStopPoint>
          </scheduledStopPoints>
          <serviceLinks>
            <ServiceLink id="sl1" version="1"><Distance>12</Distance>
              <gml:LineString gml:id="ls1" srsName="EPSG:25832"><gml:posList>680000.0 5140000.0 680100.0 5140100.0</gml:posList></gml:LineString>
            </ServiceLink>
          </serviceLinks>
        </ServiceFrame>
        """;

    /// Two sibling frames, only the first of which declares a DefaultLocationSystem.
    static final String TWO_FRAMES = """
        <ServiceFrame id="svf-with" version="1">
          <FrameDefaults><DefaultLocationSystem>EPSG:4326</DefaultLocationSystem></FrameDefaults>
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp-inside" version="1">
              <Location><Latitude>45.65</Latitude><Longitude>13.77</Longitude></Location>
            </ScheduledStopPoint>
          </scheduledStopPoints>
        </ServiceFrame>
        <ServiceFrame id="svf-without" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="ssp-outside" version="1">
              <Location><Latitude>45.66</Latitude><Longitude>13.78</Longitude></Location>
            </ScheduledStopPoint>
          </scheduledStopPoints>
        </ServiceFrame>
        """;

    static String withDefaults(String locationSystem) {
        return PLAIN.replace("__DEFAULTS__", locationSystem == null ? ""
                : "<FrameDefaults><DefaultLocationSystem>" + locationSystem + "</DefaultLocationSystem></FrameDefaults>");
    }

    /// The stored fragment bytes of the single object of `clazz` (not a bean re-marshal).
    private static String fragmentOf(TestStore db, Class<?> clazz) {
        FragmentCodec codec = new FragmentCodec();
        try (Txn tx = db.store.roTxn()) {
            for (RawRow row : db.store.iterRawObjects(tx, clazz)) {
                return new String(codec.decompress(row.blob()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no stored " + clazz.getSimpleName());
    }

    @Test
    void testDefaultLocationSystemStampsLocationAndGeometry(TestStore db) throws Exception {
        db.loadNetex(withDefaults("EPSG:4326"));

        ScheduledStopPoint ssp = Gen.byId(db, ScheduledStopPoint.class).get("ssp1");
        Check.equals("EPSG:4326", ssp.getLocation().getSrsName(), "Location gets the frame default");

        ServiceLink sl = Gen.byId(db, ServiceLink.class).get("sl1");
        Check.equals("EPSG:4326", sl.getLineString().getSrsName(),
                "srs_name.txt element (gml:LineString) gets the frame default");
        Check.equals("EPSG:4326", sl.getLineString().getPosList().getSrsName(),
                "nested srs_name.txt element (gml:posList) gets it too");

        Check.that(fragmentOf(db, ScheduledStopPoint.class).contains("<Location srsName=\"EPSG:4326\""),
                "stored ScheduledStopPoint fragment carries the stamped Location srsName");
        String link = fragmentOf(db, ServiceLink.class);
        Check.that(link.contains("srsName=\"EPSG:4326\""), "stored ServiceLink fragment carries srsName");
        Check.equals(2, link.split("srsName=\"EPSG:4326\"", -1).length - 1,
                "stored ServiceLink fragment carries it on BOTH gml:LineString and gml:posList");
    }

    @Test
    void testExistingSrsNameIsNeverOverwritten(TestStore db) throws Exception {
        db.loadNetex(PREDECLARED);

        ScheduledStopPoint ssp = Gen.byId(db, ScheduledStopPoint.class).get("ssp1");
        Check.equals("EPSG:32632", ssp.getLocation().getSrsName(),
                "a Location that declares srsName keeps it");
        Check.equals("EPSG:32632", ssp.getLocation().getPos().getSrsName(),
                "gml:pos inside it inherits the Location's srsName, not the frame default");

        ServiceLink sl = Gen.byId(db, ServiceLink.class).get("sl1");
        Check.equals("EPSG:25832", sl.getLineString().getSrsName(),
                "a gml:LineString that declares srsName keeps it");
        // Only Location is special-cased; a plain srs_name.txt parent does not propagate.
        Check.equals("EPSG:4326", sl.getLineString().getPosList().getSrsName(),
                "gml:posList inside it still takes the frame default");
        Check.that(!fragmentOf(db, ScheduledStopPoint.class).contains("EPSG:4326"),
                "the frame default never reaches a pre-declared Location's fragment");
    }

    @Test
    void testFrameDefaultThatIsNotACrsIsNotStamped(TestStore db) throws Exception {
        // Three of the four Italian gz feeds that declare DefaultLocationSystem declare a timezone
        // there, copied from the DefaultLocale/TimeZone two lines above it. The frame default is
        // stamped only when it resolves to a CRS, because it is the one value that multiplies
        // across a whole frame; a non-CRS default is dropped and the geometries under it keep no
        // srsName, which Projection's resolution rule reads as WGS84.
        //
        // An srsName the source declares on an element is stamped verbatim whatever it says; only
        // the frame default is judged. See testDeclaredNonCrsSrsNameSurvives.
        db.loadNetex(withDefaults("Europe/Rome"));

        ScheduledStopPoint ssp = Gen.byId(db, ScheduledStopPoint.class).get("ssp1");
        Check.isNull(ssp.getLocation().getSrsName(), "non-CRS frame default: Location srsName stays absent");
        ServiceLink sl = Gen.byId(db, ServiceLink.class).get("sl1");
        Check.isNull(sl.getLineString().getSrsName(), "non-CRS frame default: gml:LineString stays absent");
        Check.isNull(sl.getLineString().getPosList().getSrsName(), "…and gml:posList stays absent");
    }

    /// The other half of the rule: `srsName="ITA"` is what 48 Emilia-Romagna and Lombardia RAP
    /// feeds declare on the element itself, and the loader is not the layer that judges it.
    @Test
    void testDeclaredNonCrsSrsNameSurvives(TestStore db) throws Exception {
        db.loadNetex(withDefaults(null).replace("<gml:posList>", "<gml:posList srsName=\"ITA\">"));

        ServiceLink sl = Gen.byId(db, ServiceLink.class).get("sl1");
        Check.equals("ITA", sl.getLineString().getPosList().getSrsName(),
                "a source-declared srsName is kept whatever it says");
    }

    /// The majority shape: 298 of the 302 gz feeds and 14 of the 14 zips declare no
    /// DefaultLocationSystem.
    @Test
    void testWithoutFrameDefaultsNothingIsStamped(TestStore db) throws Exception {
        db.loadNetex(withDefaults(null));

        ScheduledStopPoint ssp = Gen.byId(db, ScheduledStopPoint.class).get("ssp1");
        Check.isNull(ssp.getLocation().getSrsName(), "no FrameDefaults: Location srsName stays absent");
        ServiceLink sl = Gen.byId(db, ServiceLink.class).get("sl1");
        Check.isNull(sl.getLineString().getSrsName(), "no FrameDefaults: gml:LineString srsName stays absent");
        Check.isNull(sl.getLineString().getPosList().getSrsName(), "no FrameDefaults: gml:posList stays absent");
        Check.that(!fragmentOf(db, ScheduledStopPoint.class).contains("srsName"),
                "no FrameDefaults: nothing is added to the stored fragment either");
        Check.that(!fragmentOf(db, ServiceLink.class).contains("srsName"),
                "no FrameDefaults: the geometry fragment is untouched");
    }

    @Test
    void testDefaultLocationSystemDiesWithItsFrame(TestStore db) throws Exception {
        db.loadNetex(TWO_FRAMES);

        Map<String, ScheduledStopPoint> ssps = Gen.byId(db, ScheduledStopPoint.class);
        Check.equals("EPSG:4326", ssps.get("ssp-inside").getLocation().getSrsName(),
                "the declaring frame's members are stamped");
        Check.isNull(ssps.get("ssp-outside").getLocation().getSrsName(),
                "a sibling frame that declares none is not stamped");
    }
}
