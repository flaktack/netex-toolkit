package toolkit.transform.common;

// The Line authority backfill, on the two rules it resolves by: the sole Authority of the id's
// codespace, and an Authority minted from the Line's Operator where the codespace has none.
//
// The fixtures are shaped after the feeds that drove the rules. A Mentz export writes ids that end
// in ':' and one Authority per codespace, several codespaces to a merged store; a Swiss export
// writes no Authority at all and lines whose codespace differs from their operators'; an Italian
// export spells the class token in a case the canonical id does not use.

import noi.netex.model.Authority;
import noi.netex.model.FlexibleLine;
import noi.netex.model.Line;
import noi.netex.text.Mls;
import org.junit.jupiter.api.Test;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.TestStore;
import toolkit.transform.epip.GeneratorDefaults;

import java.util.LinkedHashMap;
import java.util.Map;

public class TestLineAuthorities {

    /// Two Verbünde in one store, each with one Authority, ids ending in ':' throughout. Their last
    /// id segments are identical ('25'), which is what a match on the last segment would confuse.
    private static final String TWO_CODESPACES = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="at:ktn:Line:08120_.j26:" version="5"><Name>120</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="at:ktn:Operator:25:"/></Line>
            <Line id="at:vor:Line:41:" version="5"><Name>41</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="at:vor:Operator:25:"/></Line>
            <Line id="at:vor:Line:noop:" version="5"><Name>N</Name><TransportMode>bus</TransportMode></Line>
          </lines>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Authority id="at:ktn:Authority:ktn:" version="7"><Name>Kaernten</Name></Authority>
          <Authority id="at:vor:Authority:vor:" version="9"><Name>Ost-Region</Name></Authority>
          <Operator id="at:ktn:Operator:25:" version="1"><Name>Retter</Name></Operator>
          <Operator id="at:vor:Operator:25:" version="1"><Name>Dr Richard</Name></Operator>
        </organisations></ResourceFrame>
        """;

    /// No Authority anywhere, and the lines' codespace is not their operators'.
    private static final String NO_AUTHORITY = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="ch:2:Line:81_VVV.B.640" version="any"><Name>640</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="ch:1:Operator:234"/></Line>
            <Line id="ch:2:Line:81_VVV.B.641" version="any"><Name>641</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="ch:1:Operator:234"/></Line>
            <Line id="ch:2:Line:81_VVV.B.642" version="any"><Name>642</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="ch:1:Operator:827"/></Line>
          </lines>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Operator id="ch:1:Operator:234" version="3"><Name>Saastal Bergbahnen AG</Name></Operator>
          <Operator id="ch:1:Operator:827" version="3"><ShortName>SVB</ShortName></Operator>
        </organisations></ResourceFrame>
        """;

    /// What a feed looks like when it publishes each transport undertaking twice, once under each
    /// element name: the derived id is not free to mint into, it already names a published object.
    /// The lines' codespace is not their organisations'.
    private static final String AUTHORITY_TWINS_THE_OPERATORS = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="ch:2:Line:A" version="any"><Name>A</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="ch:1:Operator:234"/></Line>
            <Line id="ch:2:Line:B" version="any"><Name>B</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="ch:1:Operator:827"/></Line>
          </lines>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Authority id="ch:1:Authority:234" version="3"><Name>Saastal Bergbahnen AG</Name></Authority>
          <Authority id="ch:1:Authority:999" version="3"><Name>Ein Dritter</Name></Authority>
          <Operator id="ch:1:Operator:234" version="3"><Name>Saastal Bergbahnen AG</Name></Operator>
          <Operator id="ch:1:Operator:827" version="3"><Name>Staedtische Verkehrsbetriebe Bern</Name></Operator>
        </organisations></ResourceFrame>
        """;

    /// One Line and one FlexibleLine of the same Operator, neither with an authorityRef.
    private static final String FLEXIBLE_LINE = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="ch:2:Line:plain" version="any"><Name>P</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="ch:1:Operator:234"/></Line>
            <FlexibleLine id="ch:2:FlexibleLine:onDemand" version="any"><Name>F</Name>
              <TransportMode>bus</TransportMode>
              <OperatorRef ref="ch:1:Operator:234"/>
              <FlexibleLineType>hailAndRideSections</FlexibleLineType></FlexibleLine>
          </lines>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Operator id="ch:1:Operator:234" version="3"><Name>Saastal Bergbahnen AG</Name></Operator>
        </organisations></ResourceFrame>
        """;

    /// One codespace, two Authorities: ambiguous, so neither answers for the codespace.
    private static final String AMBIGUOUS_CODESPACE = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="IT:ITI1:Line:1" version="1"><Name>L</Name><TransportMode>bus</TransportMode>
              <OperatorRef ref="IT:ITI1:operator:AT"/></Line>
          </lines>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Authority id="IT:ITI1:authority:RT" version="1"><Name>Regione Toscana</Name></Authority>
          <Authority id="IT:ITI1:authority:XX" version="1"><Name>Altro</Name></Authority>
          <Operator id="IT:ITI1:operator:AT" version="1"><Name>Op AT</Name></Operator>
        </organisations></ResourceFrame>
        """;

    /// What a driver does with the pass: raw-clone the source, then overlay what it repaired.
    private static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, LineAuthorities.backfillLineAuthorities(src, stx,
                GeneratorDefaults.TEST.codespace()));
    }

    /// Load the fixture, then run the pass the way a driver would.
    private static TestStore run(TestStore db, String netex) throws Exception {
        db.loadNetex(netex);
        return db.runDbToDb(TestLineAuthorities::apply);
    }

    private static <T> Map<String, T> byId(TestStore db, Class<T> clazz,
            java.util.function.Function<T, String> id) {
        Map<String, T> out = new LinkedHashMap<>();
        try (Txn txn = db.store.roTxn()) {
            for (Object o : db.store.iterOnlyObjects(txn, clazz)) {
                T obj = clazz.cast(o);
                out.put(id.apply(obj), obj);
            }
        }
        return out;
    }

    private static Map<String, Line> linesOf(TestStore db) {
        return byId(db, Line.class, Line::getId);
    }

    private static Map<String, Authority> authoritiesOf(TestStore db) {
        return byId(db, Authority.class, Authority::getId);
    }

    private static Map<String, FlexibleLine> flexibleLinesOf(TestStore db) {
        return byId(db, FlexibleLine.class, FlexibleLine::getId);
    }

    /// A FlexibleLine is a Line with flexible-service fields added, so it carries the same
    /// authorityRef and wants the same repair. The pass read only the Line class map, so every
    /// FlexibleLine reached a consumer with no agency to resolve -- the defect this pass exists to
    /// fix, left standing for one of the two classes.
    @Test
    void testAFlexibleLineIsAttributedLikeALine(TestStore db) throws Exception {
        TestStore target = run(db, FLEXIBLE_LINE);

        Check.equals("ch:1:Authority:234",
                flexibleLinesOf(target).get("ch:2:FlexibleLine:onDemand").getAuthorityRef().getRef(),
                "the FlexibleLine is attributed from its Operator, like a Line");
        Check.equals("ch:1:Authority:234",
                linesOf(target).get("ch:2:Line:plain").getAuthorityRef().getRef(),
                "and the Line beside it is unaffected");
        Check.equals(1, authoritiesOf(target).size(),
                "both share the one Authority minted for their common Operator");
    }

    @Test
    void testCodespaceResolvesEachAuthoritySeparately(TestStore db) throws Exception {
        TestStore target = run(db, TWO_CODESPACES);
        Map<String, Line> lines = linesOf(target);

        Check.equals("at:ktn:Authority:ktn:",
                lines.get("at:ktn:Line:08120_.j26:").getAuthorityRef().getRef(),
                "the Line's own codespace picks its Verbund, not the other one");
        Check.equals("at:vor:Authority:vor:",
                lines.get("at:vor:Line:41:").getAuthorityRef().getRef(),
                "a second codespace in the same store resolves independently");
        Check.equals(2, authoritiesOf(target).size(), "no Authority is minted where one already answers");
    }

    @Test
    void testCodespaceAnswersALineWithoutAnOperator(TestStore db) throws Exception {
        TestStore target = run(db, TWO_CODESPACES);

        Check.equals("at:vor:Authority:vor:",
                linesOf(target).get("at:vor:Line:noop:").getAuthorityRef().getRef(),
                "a Line with no operatorRef still resolves through its own codespace");
    }

    @Test
    void testRefCarriesTheAuthoritysOwnVersion(TestStore db) throws Exception {
        TestStore target = run(db, TWO_CODESPACES);

        Check.equals("7", linesOf(target).get("at:ktn:Line:08120_.j26:").getAuthorityRef().getVersion(),
                "the ref is versioned as its target, not as the Line that holds it");
    }

    @Test
    void testMintsOneAuthorityPerOperatorWhereTheCodespaceHasNone(TestStore db) throws Exception {
        TestStore target = run(db, NO_AUTHORITY);
        Map<String, Line> lines = linesOf(target);

        Check.equals("ch:1:Authority:234", lines.get("ch:2:Line:81_VVV.B.640").getAuthorityRef().getRef(),
                "minted from the Operator, with the canonical class token");
        Check.equals("ch:1:Authority:234", lines.get("ch:2:Line:81_VVV.B.641").getAuthorityRef().getRef(),
                "two Lines of one Operator share the one minted Authority");
        Check.equals("ch:1:Authority:827", lines.get("ch:2:Line:81_VVV.B.642").getAuthorityRef().getRef(),
                "a second Operator of the same codespace gets its own");

        Map<String, Authority> authorities = authoritiesOf(target);
        Check.equals(2, authorities.size(), "one Authority per Operator, not per Line");
        Authority minted = authorities.get("ch:1:Authority:234");
        Check.equals("Saastal Bergbahnen AG", Mls.text(minted.getName()), "the Operator's name carries over");
        Check.equals("ch:1:Address:234", minted.getAddress().getId(),
                "an AUTHORITY takes a mandatory Address, id-derived from its owner");
    }

    @Test
    void testLinksToAPublishedAuthorityRatherThanMintingOverIt(TestStore db) throws Exception {
        TestStore target = run(db, AUTHORITY_TWINS_THE_OPERATORS);
        Map<String, Line> lines = linesOf(target);

        Check.equals("ch:1:Authority:234", lines.get("ch:2:Line:A").getAuthorityRef().getRef(),
                "the derived id already names a published Authority, so it is looked up");
        Check.equals("3", lines.get("ch:2:Line:A").getAuthorityRef().getVersion(),
                "and the ref carries that published Authority's version");

        Map<String, Authority> authorities = authoritiesOf(target);
        Check.equals(3, authorities.size(),
                "two published Authorities survive and exactly one is minted, for the Operator "
                        + "the feed left without a twin");
        Check.equals("Saastal Bergbahnen AG", Mls.text(authorities.get("ch:1:Authority:234").getName()),
                "the published Authority is untouched -- not overwritten by a mint");
        Check.equals("ch:1:Authority:827", lines.get("ch:2:Line:B").getAuthorityRef().getRef(),
                "the Operator without a twin still mints one");
    }

    @Test
    void testAmbiguousCodespaceMintsRatherThanGuessing(TestStore db) throws Exception {
        TestStore target = run(db, AMBIGUOUS_CODESPACE);

        Check.equals("IT:ITI1:Authority:AT", linesOf(target).get("IT:ITI1:Line:1").getAuthorityRef().getRef(),
                "two Authorities in one codespace answer for none of it, so the Operator does");
    }
}
