package toolkit.model;

// ModelRegistry.scan() enumerates every .class resource of the model package out of the model's OWN
// code source and calls Class.forName on each. The name came from a file that is demonstrably
// there, so a failure is never "no such class" -- it is a classpath problem: a missing transitive
// dependency, a truncated jar, a version skew. Swallowed, it reappears in NetexLoader as an
// `unmapped` element on a feed that is perfectly valid, with nothing connecting the two.
//
// classes() is where the model jar BECOMES the store's class table: it is the sole feeder of
// V2Store.populateClassTable -> assignIdx(), and a fresh database writes that name->idx map once
// for every later reader. A dropped class does not go missing, it SHIFTS every idx assigned after
// it, so the store stays internally consistent, is silently smaller than the model, and is not
// comparable with any store built on a healthy classpath. There is no useful degraded mode, so
// this one gets no Strictness flag.
//
// The refusal is exercised directly: its precondition is a broken classpath, which a test running
// inside a healthy one cannot manufacture.

import org.junit.jupiter.api.Test;
import toolkit.test.Check;

import java.util.List;

public class TestModelRegistryScan {

    /// An unloadable class file in the model's code source refuses the scan, and says which.
    @Test
    void testAnUnloadableModelClassRefusesTheScan() {
        IllegalStateException e = null;
        try {
            ModelRegistry.refuseUnloadable(List.of(
                    "noi.netex.model.StopPlace: java.lang.NoClassDefFoundError: some/Missing",
                    "noi.netex.model.Quay: java.lang.NoClassDefFoundError: some/Missing"));
        } catch (IllegalStateException ex) {
            e = ex;
        }
        Check.that(e != null, "a class file that will not load is not a class that does not exist");
        String m = e.getMessage();
        Check.that(m.contains("StopPlace") && m.contains("Quay"),
                "…and the message names EVERY offender, not the first one the walk reached: " + m);
        Check.that(m.contains("NoClassDefFoundError"),
                "…with the throwable, which is the only thing that says why: " + m);
        Check.that(m.contains("class idx"),
                "…and what it costs: a partial scan shifts the class table a fresh database "
                + "writes once. " + m);
    }

    /// Nothing to report is silent: the refusal must not fire on an empty list.
    @Test
    void testAWholeScanRefusesNothing() {
        ModelRegistry.refuseUnloadable(List.of());
        Check.that(true, "an empty offender list is the normal case and returns");
    }

    /// The healthy-path control: the registry is not quietly dropping half the model on this
    /// classpath.
    @Test
    void testTheScanIsWholeOnThisClasspath() {
        int registrable = ModelRegistry.classes().size();
        Check.that(registrable > 1400,
                "the registrable model is ~1,544 classes at schema 2.0 and 1,421 at 1.15.2; "
                + "got " + registrable + ", which means the scan dropped some");
        Check.that(ModelRegistry.classForName("StopPlace") != null, "a concrete type resolves");
        Check.that(ModelRegistry.classForName("DataManagedObject") != null,
                "and so does the aliased ref fallback, which has no JAXB twin");
    }
}
