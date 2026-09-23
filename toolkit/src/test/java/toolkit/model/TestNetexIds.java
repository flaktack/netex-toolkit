package toolkit.model;

// Deriving a generated object's id from the object it was generated from: the class-token swap, the
// two overloads, and the fallback that makes the miss path uniform.
//
// The ids here are the shapes real feeds publish. IT:ITF2::Line:busREGIONEMOLISE:A101 is the §8.5.3
// object structure an Italian NAP feed writes; the lowercase-token case is what Italian feeds do to
// :operator: and :authority:.

import noi.netex.model.Authority;
import noi.netex.model.Codespace;
import noi.netex.model.DayType;
import noi.netex.model.Line;
import noi.netex.model.Operator;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.UicOperatingPeriod;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;

public class TestNetexIds {

    private static Codespace codespace(String xmlns) {
        Codespace cs = new Codespace();
        cs.setId("cs");
        cs.setXmlns(xmlns);
        return cs;
    }

    private static final Codespace FALLBACK = codespace("epd");

    /// The technical-id overload keeps everything ahead of the class token and replaces everything
    /// after it: which of the trailing segments is the technical id and which the id-provider
    /// cannot be read off the id, so they are not preserved.
    @Test
    void testDeriveIdTakesThePrefixAndReplacesTheTail() {
        Check.equals("IT:ITF2::ServiceJourneyPattern:abc123",
                NetexUtils.deriveId("IT:ITF2::VehicleJourney:busREGIONEMOLISE:A101_0_1_1",
                        ServiceJourneyPattern.class, "abc123", FALLBACK),
                "the country and local codes survive, the class token is swapped, the tail is new");
    }

    /// The no-technical-id overload swaps the token and keeps the tail, for an object that stands
    /// one-to-one for its source and so wants its identity.
    @Test
    void testDeriveIdWithoutATechnicalIdKeepsEverySegment() {
        Check.equals("IT:ITF2::UicOperatingPeriod:REGIONEMOLISE:DOM",
                NetexUtils.deriveId("IT:ITF2::DayType:REGIONEMOLISE:DOM",
                        UicOperatingPeriod.class, FALLBACK),
                "only the class token changes");
    }

    /// Case-insensitive, because a publisher may spell the token in any case.
    @Test
    void testALowercaseClassTokenStillMatches() {
        Check.equals("IT:ITF2:Authority:REGIONEMOLISE:1",
                NetexUtils.deriveId("IT:ITF2:operator:REGIONEMOLISE:1", Authority.class, FALLBACK),
                "an id writing :operator: derives an Authority, and the swap is canonical-case");
    }

    /// The FIRST token naming a class wins: in an EPIP id the object type precedes the technical
    /// identifier, so a technical id that happens to spell a class name cannot claim the slot.
    @Test
    void testTheFirstClassTokenWinsOverALaterOne() {
        Check.equals("IT:ITF2::DayType:x",
                NetexUtils.deriveId("IT:ITF2::Line:Route:A101", DayType.class, "x", FALLBACK),
                "Line is the class token; the Route in the technical position is not");
    }

    /// An id opening with its class token carries no codespace. Deriving from it would spread an id
    /// the profile does not allow, so it takes the fallback instead.
    @Test
    void testAnIdOpeningWithItsClassTokenIsNotDerivedFrom() {
        Check.isNull(NetexUtils.swapClassToken("Line:A101", DayType.class),
                "no codespace ahead of the token, so there is nothing to inherit");
        NetexUtils.reportIdFallbacks("test-reset");
        Check.equals("epd:DayType:Line-A101",
                NetexUtils.deriveId("Line:A101", DayType.class, FALLBACK),
                "the fallback composes over the configured codespace");
        Check.equals(1L, NetexUtils.reportIdFallbacks("test"), "and the fallback is counted");
    }

    /// swapClassToken is the primitive and answers null, for the one caller that needs to know the
    /// id carried no token. deriveId never does.
    @Test
    void testSwapClassTokenAnswersNullWhereDeriveIdFallsBack() {
        Check.isNull(NetexUtils.swapClassToken("plain-string-no-colons", Operator.class),
                "no colon at all");
        Check.isNull(NetexUtils.swapClassToken("a:b:c", Operator.class),
                "colons, but no token naming a NeTEx class");
        Check.isNull(NetexUtils.swapClassToken(null, Operator.class), "null id");

        NetexUtils.reportIdFallbacks("test-reset");
        Check.equals("epd:Operator:a-b-c",
                NetexUtils.deriveId("a:b:c", Operator.class, FALLBACK),
                "deriveId answers anyway");
        Check.equals(1L, NetexUtils.reportIdFallbacks("test"), "having counted the miss");
    }

    /// The count is read-and-reset, so a second report is silent rather than doubling.
    @Test
    void testReportIdFallbacksResets() {
        NetexUtils.reportIdFallbacks("test-reset");
        NetexUtils.deriveId("no-token", Line.class, "x", FALLBACK);
        NetexUtils.deriveId("also-none", Line.class, "y", FALLBACK);
        Check.equals(2L, NetexUtils.reportIdFallbacks("test"), "both misses counted");
        Check.equals(0L, NetexUtils.reportIdFallbacks("test"), "and the counter is back to zero");
    }

    /// The codespace prefix is a JOIN KEY — what a Line, its Operator and its Authority share when
    /// one publisher issued all three, which is how `LineAuthorities` finds the Authority of a
    /// codespace. It INCLUDES the separator, so every side of the join has to take it from here
    /// rather than cutting before the class token itself.
    @Test
    void testCodespacePrefixIsTheJoinKeyAcrossClasses() {
        Check.equals("at:ktn:", NetexUtils.codespacePrefix("at:ktn:Operator:12:"), "operator");
        Check.equals("at:ktn:", NetexUtils.codespacePrefix("at:ktn:Authority:ktn:"), "authority");
        Check.equals("at:ktn:", NetexUtils.codespacePrefix("at:ktn:Line:5:"), "line");
        Check.equals("IT:ITF2::", NetexUtils.codespacePrefix("IT:ITF2::Line:busX:A101"),
                "a structured id keeps its empty local-code placeholder");
    }

    /// No class token, no codespace to share — the lookup has to decline rather than key on a
    /// prefix it invented. This is the one caller that still wants the primitive's null.
    @Test
    void testCodespacePrefixDeclinesWhereThereIsNoToken() {
        Check.isNull(NetexUtils.codespacePrefix("auth1"), "a bare id shares no codespace");
        Check.isNull(NetexUtils.codespacePrefix("Operator:12"),
                "an id opening with its class token has no codespace ahead of it");
        Check.isNull(NetexUtils.codespacePrefix(null), "null id");
    }

    /// A derived id keeps the source's codespace, which is the whole point: the generated object
    /// stops being the one thing in the document with a different id grammar.
    @Test
    void testTheDerivedIdKeepsTheSourcesCodespace() {
        Check.equals("epd:NO::ServiceJourneyPattern:9f",
                NetexUtils.deriveId("epd:NO::Route:985a:ENTUR", ServiceJourneyPattern.class, "9f",
                        codespace("other")),
                "the source's epd codespace is inherited, not the fallback's");
    }
}
