package toolkit.test;

// What toolkit.test.Runner used to be, expressed as a JUnit extension: a fresh TestStore per test
// METHOD, closed afterwards, and the one piece of suite-wide setup the runner did.
//
// WHY IT IS HERE AND NOT IN toolkit.test IN MAIN. The fixture kit ships in the production jar so
// that somebody can test a driver they wrote themselves, with no JUnit and no build step. This
// class is the only part that needs JUnit, so it is the only part that stays test-scoped; adding it
// to main would put junit-jupiter on the shipped jar's compile classpath. It is in the same PACKAGE
// so that a fixture change is read beside its runner.
//
// REGISTERED BY SERVICE LOADER, not by @ExtendWith on every file:
// src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension names it and the
// pom turns extension autodetection on, so a test class declares nothing.
//
// ONE FORMAT. The seam a second format would re-enter through is TestStore.create's own parameter.

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import toolkit.store.Stores;
import toolkit.util.Conf;

public final class TestStoreExtension implements ParameterResolver {

    /// Small v2 edge blocks, so the dirty-block machinery crosses block boundaries at test scale
    /// (production default is 65,536 pairs). Suite-wide, as `Runner.runReporting` sets it: a test
    /// that never reaches a second block cannot tell a correct block reader from a
    /// cursor-ordered one. Tests that need another chunking set and restore it themselves.
    ///
    /// In a static initialiser because this class is instantiated once per test class and the
    /// override is idempotent; there is no earlier hook that runs for pure tests as well.
    static {
        Conf.override(Conf.V2_BLOCK_PAIRS, 128);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == TestStore.class;
    }

    /// One store per test method, closed by the store JUnit keeps for the method's context.
    ///
    /// `CloseableResource` is what ties the close to the method rather than to this call: a test
    /// that fails mid-way still gets its temp directory removed, which is what the old
    /// try-with-resources around `m.invoke` did.
    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        try {
            TestStore ts = TestStore.create(Stores.V2);
            context.getStore(ExtensionContext.Namespace.create(TestStoreExtension.class))
                    .put(context.getUniqueId(), (ExtensionContext.Store.CloseableResource) ts::close);
            return ts;
        } catch (Exception e) {
            throw new ParameterResolutionException("could not create a TestStore", e);
        }
    }
}
