package toolkit.load;

// Regression test for the loader's attribute denormalisation. TestLoaderSrsName covers the srsName
// arm; this file covers the other four.
//
// The rule, per arm:
//
//   1. id      every element whose local name is in all_with_id and that has no no-namespace `id`,
//              at any depth, gets the nearest id-bearing ancestor's id with that ancestor's element
//              name textually replaced by ours. Not gated on FrameDefaults.
//   2. version every element in all_with_version with no `version`, at any depth: the nearest
//              enclosing version if the element is also in all_with_id, otherwise the literal
//              "any" — which is the case for every *Ref. Not gated on FrameDefaults.
//   3. dataSourceRef        every element in all_datasource_refs (= classes derived from
//              DataManagedObjectStructure) with none of its own, at any depth, when the enclosing
//              frame's <FrameDefaults><DefaultDataSourceRef> is set.
//   4. responsibilitySetRef same, from <DefaultResponsibilitySetRef>.

import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPlace;
import noi.netex.model.ValidBetween;
import org.junit.jupiter.api.Test;
import toolkit.codec.FragmentCodec;
import toolkit.store.RawRow;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;

public class TestLoaderDenormalisation {

    /// FrameDefaults declaring both ref arms; \__DEFAULTS__ is substituted with it or with "".
    static final String DEFAULTS =
            "<FrameDefaults>"
                    + "<DefaultDataSourceRef ref=\"test:DataSource:ds\"/>"
                    + "<DefaultResponsibilitySetRef ref=\"test:Authority:rs\" version=\"any\"/>"
                    + "</FrameDefaults>";

    /// Everything nested: the journey pattern's RouteView carries neither id nor version, its
    /// StopPointInJourneyPattern carries neither, the refs carry no version, and the StopPlace's
    /// Quay/Level carry no dataSourceRef/responsibilitySetRef. The frame version is 7 and the two
    /// objects' versions are 3 and 5, so an arm that reads the wrong stack level is visible.
    static final String NESTED = """
        <ServiceFrame id="test:ServiceFrame:1" version="7">
          __DEFAULTS__
          <journeyPatterns>
            <ServiceJourneyPattern id="test:ServiceJourneyPattern:1" version="3">
              <RouteView/>
              <pointsInSequence>
                <StopPointInJourneyPattern order="1">
                  <ScheduledStopPointRef ref="test:ScheduledStopPoint:1"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <SiteFrame id="test:SiteFrame:1" version="7">
          __DEFAULTS__
          <stopPlaces>
            <StopPlace id="test:StopPlace:1" version="5">
              <Name>Bolzano</Name>
              <Centroid><Location><Latitude>46.5</Latitude><Longitude>11.35</Longitude></Location></Centroid>
              <levels><Level id="test:Level:1" version="5"><Name>Ground</Name></Level></levels>
              <quays><Quay id="test:Quay:1" version="5"><Name>1</Name></Quay></quays>
            </StopPlace>
          </stopPlaces>
        </SiteFrame>
        """;

    /// The same shapes, but every element already declares every attribute an arm could add.
    static final String PREDECLARED = """
        <ServiceFrame id="test:ServiceFrame:1" version="7">
          __DEFAULTS__
          <journeyPatterns>
            <ServiceJourneyPattern id="test:ServiceJourneyPattern:1" version="3"
                                   dataSourceRef="own:ds" responsibilitySetRef="own:rs">
              <RouteView id="own:RouteView:9"/>
              <pointsInSequence>
                <StopPointInJourneyPattern id="own:StopPointInJourneyPattern:9" version="99" order="1">
                  <ScheduledStopPointRef ref="test:ScheduledStopPoint:1" version="42"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <SiteFrame id="test:SiteFrame:1" version="7">
          __DEFAULTS__
          <stopPlaces>
            <StopPlace id="test:StopPlace:1" version="5">
              <Name>Bolzano</Name>
              <quays><Quay id="test:Quay:1" version="5" dataSourceRef="own:qds"
                           responsibilitySetRef="own:qrs"><Name>1</Name></Quay></quays>
            </StopPlace>
          </stopPlaces>
        </SiteFrame>
        """;

    /// A gml geometry whose only id is gml:id, plus a ServiceLink to hang it on.
    static final String GEOMETRY = """
        <ServiceFrame id="test:ServiceFrame:1" version="7">
          __DEFAULTS__
          <serviceLinks>
            <ServiceLink id="test:ServiceLink:1" version="5"><Distance>639</Distance>
              <gml:LineString gml:id="ls1"><gml:posList>45.6 13.7 45.7 13.8</gml:posList></gml:LineString>
            </ServiceLink>
          </serviceLinks>
        </ServiceFrame>
        """;

    static String with(String fixture) {
        return fixture.replace("__DEFAULTS__", DEFAULTS);
    }

    static String without(String fixture) {
        return fixture.replace("__DEFAULTS__", "");
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

    // ------------------------------------------------------------------ arm 1: nested id

    @Test
    void testIdIsSynthesizedAtDepthFromTheEnclosingId(TestStore db) throws Exception {
        db.loadNetex(with(NESTED));

        String sjp = fragmentOf(db, ServiceJourneyPattern.class);
        Check.that(sjp.contains("<RouteView id=\"test:RouteView:1\""),
                "nested RouteView gets the enclosing id with its own element name substituted");
        Check.that(sjp.contains("<StopPointInJourneyPattern order=\"1\" id=\"test:StopPointInJourneyPattern:1\""),
                "nested StopPointInJourneyPattern gets one too, after the source attributes");

        // The attribute is injected into the reader, not patched onto the fragment afterwards.
        ServiceJourneyPattern bean = Gen.byId(db, ServiceJourneyPattern.class).get("test:ServiceJourneyPattern:1");
        Check.equals("test:RouteView:1", bean.getRouteView().getId(),
                "the BOUND BEAN carries the synthesized nested id");
    }

    @Test
    void testNestedIdIsSynthesizedWithoutFrameDefaultsToo(TestStore db) throws Exception {
        db.loadNetex(without(NESTED));

        String sjp = fragmentOf(db, ServiceJourneyPattern.class);
        Check.that(sjp.contains("<RouteView id=\"test:RouteView:1\""),
                "no FrameDefaults: the id arm still fires (it is not gated on them)");
        Check.that(sjp.contains("<StopPointInJourneyPattern order=\"1\" id=\"test:StopPointInJourneyPattern:1\" version=\"3\""),
                "no FrameDefaults: the version arm still fires too");
    }

    /// Arm 1 on an object that becomes its own row. A frame-level ValidBetween declares no id and
    /// is a registered class, so the loader stores it and the synthesized id is the key the row is
    /// filed under — everywhere else in this file the synthesized id is only read back out of the
    /// stored bytes.
    @Test
    void testAnIdlessTopLevelObjectIsKeyedByItsSynthesizedId(TestStore db) throws Exception {
        db.loadNetex("""
            <ServiceFrame id="test:ServiceFrame:1" version="7">
              <ValidBetween>
                <FromDate>2026-01-01T00:00:00</FromDate>
                <ToDate>2026-12-31T23:59:59</ToDate>
              </ValidBetween>
              <scheduledStopPoints>
                <ScheduledStopPoint id="test:ScheduledStopPoint:1" version="5"><Name>X</Name></ScheduledStopPoint>
              </scheduledStopPoints>
            </ServiceFrame>
            """);

        try (Txn tx = db.store.roTxn()) {
            long fk = db.store.lookupFullKey(tx, "test:ValidBetween:1", "7", ValidBetween.class);
            Check.that(fk != -1, "the row is filed under the SYNTHESIZED (id, version)");
            ValidBetween vb = (ValidBetween) db.store.loadObjectByFullKey(tx, fk);
            Check.equals("test:ValidBetween:1", vb.getId(), "and the stored object carries that id");
            Check.equals("7", vb.getVersion(), "with the enclosing frame's version");
        }
        String frag = fragmentOf(db, ValidBetween.class);
        Check.that(frag.contains("id=\"test:ValidBetween:1\"") && frag.contains("version=\"7\""),
                "both attributes are in the captured bytes, not only on the bean: " + frag);
    }

    @Test
    void testGmlGeometryDoesNotGetAPlainId(TestStore db) throws Exception {
        // all_with_id contains LineString/Polygon/MultiSurface/… because they carry gml:id, so the
        // rule writes a no-namespace id onto them, which unmarshal drops: the bound field is
        // {gml}id. Ours must not carry one either.
        db.loadNetex(with(GEOMETRY));

        String link = fragmentOf(db, noi.netex.model.ServiceLink.class);
        Check.that(link.contains("<gml:LineString gml:id=\"ls1\""), "the source gml:id is preserved");
        Check.that(!link.contains("LineString gml:id=\"ls1\" id="),
                "no plain id is synthesized onto a gml geometry");
        Check.that(!link.contains("test:LineString:1"), "…and certainly not a derived one");
    }

    /// Arm 1 against a parent id that names no class. Several Austrian feeds give a StopPlace an id
    /// like `at:46:447`, and the rule used to replace the parent's element name in it — finding
    /// nothing, and handing the child the parent's id unchanged, so one `(id, version)` named both
    /// the StopPlace and its ValidBetween. The token is minted now.
    @Test
    void testAParentIdWithNoClassTokenStillYieldsOne(TestStore db) throws Exception {
        db.loadNetex("""
            <SiteFrame id="test:SiteFrame:1" version="7">
              <stopPlaces>
                <StopPlace id="at:46:447" version="5">
                  <Name>Puch b.Weiz Ort</Name>
                  <ValidBetween><FromDate>1994-02-28T00:00:00</FromDate></ValidBetween>
                </StopPlace>
              </stopPlaces>
            </SiteFrame>
            """);

        String sp = fragmentOf(db, StopPlace.class);
        Check.that(!sp.contains("<ValidBetween responsibilitySetRef=\"\" id=\"at:46:447\"")
                        && !sp.contains("<ValidBetween id=\"at:46:447\""),
                "the child no longer wears the parent's own id: " + sp);
        Check.that(sp.contains("<ValidBetween id=\"at:ValidBetween:"),
                "it carries the parent's codespace and a minted class token: " + sp);
        Check.equals("at:46:447", Gen.byId(db, StopPlace.class).get("at:46:447").getId(),
                "and the parent keeps the id the feed published");
    }

    // ------------------------------------------------------------------ arm 2: nested version

    @Test
    void testVersionIsSynthesizedAtDepth(TestStore db) throws Exception {
        db.loadNetex(with(NESTED));

        String sjp = fragmentOf(db, ServiceJourneyPattern.class);
        Check.that(sjp.contains("<StopPointInJourneyPattern order=\"1\" id=\"test:StopPointInJourneyPattern:1\" version=\"3\""),
                "an id-bearing nested element inherits the enclosing version");
        Check.that(sjp.contains("<ScheduledStopPointRef ref=\"test:ScheduledStopPoint:1\" version=\"any\"/>"),
                "a nested ref with no version gets version=\"any\"");
        // RouteView is in all_with_id but not in all_with_version.
        Check.that(!sjp.contains("<RouteView id=\"test:RouteView:1\" version="),
                "an element outside all_with_version gets no version");
    }

    // ------------------------------------- arms 3 and 4: dataSourceRef / responsibilitySetRef

    @Test
    void testFrameDefaultRefsAreSynthesizedAtDepth(TestStore db) throws Exception {
        db.loadNetex(with(NESTED));

        String sp = fragmentOf(db, StopPlace.class);
        // On the root the hoisted namespace declarations (normalisation N1) sit between the source
        // attributes and the synthesized ones, so match the tail only.
        Check.that(sp.startsWith("<StopPlace id=\"test:StopPlace:1\" version=\"5\" xmlns=")
                        && sp.contains("dataSourceRef=\"test:DataSource:ds\""
                                + " responsibilitySetRef=\"test:Authority:rs\">"),
                "the fragment ROOT gets both ref arms");
        Check.that(sp.contains("<Quay id=\"test:Quay:1\" version=\"5\""
                        + " dataSourceRef=\"test:DataSource:ds\" responsibilitySetRef=\"test:Authority:rs\">"),
                "a NESTED Quay gets both ref arms — this is the 90,209-attribute path");
        Check.that(sp.contains("<Level id=\"test:Level:1\" version=\"5\""
                        + " dataSourceRef=\"test:DataSource:ds\" responsibilitySetRef=\"test:Authority:rs\">"),
                "a nested Level gets them too");

        StopPlace bean = Gen.byId(db, StopPlace.class).get("test:StopPlace:1");
        Check.equals("test:DataSource:ds", bean.getDataSourceRef(), "the bean carries dataSourceRef");
        Check.equals("test:Authority:rs", bean.getResponsibilitySetRef(), "the bean carries responsibilitySetRef");
    }

    @Test
    void testRefArmsUseTheDataManagedObjectSetOnly(TestStore db) throws Exception {
        // The rule tests allResponsibilitySetRefs = classes derived from EntityInVersionStructure,
        // which is wider than DataManagedObjectStructure. responsibilitySetRef is declared on
        // DataManagedObjectStructure, so for the difference (TimetabledPassingTime,
        // StopPointInJourneyPattern, Call, …) unmarshal drops the injected attribute and it never
        // reaches the stored object. What is stored is the DataManagedObject set, for both arms.
        db.loadNetex(with(NESTED));

        String sjp = fragmentOf(db, ServiceJourneyPattern.class);
        Check.equals(1, sjp.split("responsibilitySetRef", -1).length - 1,
                "responsibilitySetRef appears exactly once: on the ServiceJourneyPattern root, "
                        + "and on nothing inside it");
        Check.equals(1, sjp.split("dataSourceRef", -1).length - 1, "and dataSourceRef likewise");
        Check.that(sjp.contains("<StopPointInJourneyPattern order=\"1\" id=\"test:StopPointInJourneyPattern:1\" version=\"3\">"),
                "the nested StopPointInJourneyPattern got id and version and NOTHING else — it is in "
                        + "allResponsibilitySetRefs but the attribute is dropped, so none is stored");
        Check.that(sjp.contains("<ScheduledStopPointRef ref=\"test:ScheduledStopPoint:1\" version=\"any\"/>"),
                "a ref element is not a DataManagedObject and gets neither ref arm");
    }

    @Test
    void testWithoutFrameDefaultsNoRefArmFires(TestStore db) throws Exception {
        db.loadNetex(without(NESTED));

        String sp = fragmentOf(db, StopPlace.class);
        Check.that(!sp.contains("dataSourceRef"), "no FrameDefaults: no dataSourceRef anywhere in the fragment");
        Check.that(!sp.contains("responsibilitySetRef"), "no FrameDefaults: no responsibilitySetRef either");
        StopPlace bean = Gen.byId(db, StopPlace.class).get("test:StopPlace:1");
        Check.isNull(bean.getDataSourceRef(), "no FrameDefaults: the bean's dataSourceRef stays null");
        Check.isNull(bean.getResponsibilitySetRef(), "no FrameDefaults: the bean's responsibilitySetRef stays null");
    }

    // ------------------------------------------------------------------ absent-only, all arms

    @Test
    void testDeclaredAttributesAreNeverOverwritten(TestStore db) throws Exception {
        db.loadNetex(with(PREDECLARED));

        String sjp = fragmentOf(db, ServiceJourneyPattern.class);
        Check.that(sjp.contains("<RouteView id=\"own:RouteView:9\"/>"),
                "a nested element that declares its own id keeps it, and gains nothing");
        Check.that(sjp.contains("<StopPointInJourneyPattern id=\"own:StopPointInJourneyPattern:9\" version=\"99\" order=\"1\">"),
                "a nested element that declares id AND version keeps both");
        Check.that(sjp.contains("<ScheduledStopPointRef ref=\"test:ScheduledStopPoint:1\" version=\"42\"/>"),
                "a ref that declares a version keeps it instead of getting \"any\"");
        Check.that(sjp.contains("dataSourceRef=\"own:ds\"") && !sjp.contains("test:DataSource:ds"),
                "a declared dataSourceRef survives and the frame default never appears");
        Check.that(sjp.contains("responsibilitySetRef=\"own:rs\"") && !sjp.contains("test:Authority:rs"),
                "a declared responsibilitySetRef survives too");

        String sp = fragmentOf(db, StopPlace.class);
        Check.that(sp.contains("<Quay id=\"test:Quay:1\" version=\"5\" dataSourceRef=\"own:qds\""
                        + " responsibilitySetRef=\"own:qrs\">"),
                "a NESTED element that declares both ref attributes keeps its own values");
    }
}
