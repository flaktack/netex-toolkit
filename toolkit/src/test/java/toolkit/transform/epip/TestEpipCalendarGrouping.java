package toolkit.transform.epip;

// EPIP calendar: DayTypeAssignment grouping and dangling-DayTypeRef tolerance.
//
// 1. The EPIP service calendar groups assignments per DayType with a run-length grouping, which
//    only merges ADJACENT equal keys, so the input is sorted by the grouping key first. Unsorted,
//    interleaved assignments (dt1, dt2, dt1 in document order) split dt1 into two groups.
// 2. An assignment whose DayTypeRef targets a DayType that is not loaded is skipped with a warning,
//    matching the tolerance applied to dangling operating-period refs.

import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.EntityStructure;
import noi.netex.model.UicOperatingPeriod;
import org.junit.jupiter.api.Test;
import toolkit.test.Check;
import toolkit.test.Gen;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.List;

public class TestEpipCalendarGrouping {

    // Assignments in document order dta1(dt1), dta2(dt2), dta3(dt1), dta4(dt_missing): dt1's two
    // assignments are NOT adjacent, and dta4 references a DayType that does not exist.
    private static final String INPUT = """
        <ServiceCalendarFrame id="scf" version="1">
          <ServiceCalendar id="sc" version="1">
            <dayTypes>
              <DayType id="dt1" version="1"/>
              <DayType id="dt2" version="1"/>
            </dayTypes>
            <operatingPeriods>
              <UicOperatingPeriod id="op1" version="1">
                <FromDate>2026-01-05T00:00:00</FromDate>
                <ToDate>2026-01-11T00:00:00</ToDate>
                <ValidDayBits>1000001</ValidDayBits>
              </UicOperatingPeriod>
              <UicOperatingPeriod id="op2" version="1">
                <FromDate>2026-01-12T00:00:00</FromDate>
                <ToDate>2026-01-18T00:00:00</ToDate>
                <ValidDayBits>0100000</ValidDayBits>
              </UicOperatingPeriod>
            </operatingPeriods>
            <dayTypeAssignments>
              <DayTypeAssignment id="dta1" version="1" order="1">
                <OperatingPeriodRef ref="op1" version="1"/>
                <DayTypeRef ref="dt1" version="1"/>
              </DayTypeAssignment>
              <DayTypeAssignment id="dta2" version="1" order="1">
                <OperatingPeriodRef ref="op1" version="1"/>
                <DayTypeRef ref="dt2" version="1"/>
              </DayTypeAssignment>
              <DayTypeAssignment id="dta3" version="1" order="1">
                <OperatingPeriodRef ref="op2" version="1"/>
                <DayTypeRef ref="dt1" version="1"/>
              </DayTypeAssignment>
              <DayTypeAssignment id="dta4" version="1" order="1">
                <OperatingPeriodRef ref="op1" version="1"/>
                <DayTypeRef ref="dt_missing" version="1"/>
              </DayTypeAssignment>
            </dayTypeAssignments>
          </ServiceCalendar>
        </ServiceCalendarFrame>
        """;

    /// Non-adjacent assignments form one group, and a dangling DayTypeRef is skipped.
    @Test
    void testNonAdjacentAssignmentsFormOneGroupAndDanglingDayTypeIsSkipped(TestStore db)
            throws Exception {
        db.loadNetex(INPUT);

        List<Object> result = Gen.run(db,
                (s, txn) -> EpipCalendar.epipServiceCalendar(s, txn, GeneratorDefaults.TEST));

        // Each DayType is yielded exactly once (unsorted: dt1 twice, from two grouping runs),
        // and dta4's dangling dt_missing is skipped instead of throwing.
        Check.equals(List.of("dt1", "dt2"), ids(result, DayType.class),
                "each DayType yielded exactly once");
        // Groups are emitted in sorted-by-ref order, each with all of its assignments.
        Check.equals(List.of("dta1", "dta3", "dta2"), ids(result, DayTypeAssignment.class),
                "assignments grouped per DayType in sorted-by-ref order");
        // Every assignment's operating period rides along with its group.
        Check.equals(List.of("op1", "op2", "op1"), ids(result, UicOperatingPeriod.class),
                "operating periods ride along with their group");
    }

    /// The ids of every `clazz` instance in `result`, in EMISSION ORDER.
    private static List<String> ids(List<Object> result, Class<?> clazz) {
        List<String> out = new ArrayList<>();
        for (Object o : result) {
            if (clazz.isInstance(o)) out.add(((EntityStructure) o).getId());
        }
        return out;
    }
}
