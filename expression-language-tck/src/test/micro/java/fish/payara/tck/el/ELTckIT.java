package fish.payara.tck.el;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.PackageNameFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Runs the Expression Language TCK functional tests inside Payara Micro.
 *
 * The @Deployment packages the EL TCK jar plus the JUnit Platform engine/launcher jars into a
 * WAR that Arquillian deploys to a managed Payara Micro instance. Each EL test method is run as
 * a separate @ParameterizedTest invocation. Arquillian's JUnit 5 extension intercepts
 * @ParameterizedTest (interceptTestTemplateMethod) and runs the invocations INSIDE the container
 * (servlet-jakarta protocol), so the EL implementation exercised is the one shipped in Payara
 * Micro, while every EL test still shows up individually in the Maven/Failsafe report.
 *
 * Note: a plain @TestFactory would NOT work here — Arquillian does not intercept
 * interceptTestFactoryMethod, so a factory (and anything it launches) would run in the client
 * JVM instead of in Payara Micro. @ParameterizedTest is intercepted and therefore runs
 * in-container.
 *
 * The signature test (com.sun.ts.tests.signaturetest.el.ELSigTestIT) is intentionally not
 * selected here: it needs the JDK jimage tool as an external process and is run in-JVM by the
 * module's payara-tests execution instead.
 */
@ExtendWith(ArquillianExtension.class)
public class ELTckIT {

    private static final String SAMPLE_TEST_CLASS_ENTRY =
            "com/sun/ts/tests/el/api/jakarta_el/methodreference/ELClientIT.class";
    private static final String SAMPLE_TEST_CLASS_NAME =
            "com.sun.ts.tests.el.api.jakarta_el.methodreference.ELClientIT";
    private static final String EL_TCK_PACKAGE = "com.sun.ts.tests.el";

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "el-tck.war")
                .addAsLibraries(
                        findJarContaining(SAMPLE_TEST_CLASS_ENTRY),
                        findJar("junit-platform-launcher"),
                        findJar("junit-platform-engine"),
                        findJar("junit-platform-commons"),
                        findJar("junit-jupiter-engine"),
                        findJar("junit-jupiter-api"),
                        findJar("junit-jupiter-params"),
                        findJar("opentest4j"),
                        findJar("apiguardian-api"));
    }

    /**
     * Enumerates every EL TCK test method. This runs both on the client (to build the reported
     * test tree) and in-container (Arquillian re-derives the invocations there), so it must work
     * in both environments. Results are sorted so both environments agree on invocation order.
     */
    static Stream<Arguments> elTckTests() throws Exception {
        Path tckRoot = classpathRootOf(SAMPLE_TEST_CLASS_NAME);

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(tckRoot)))
                .filters(PackageNameFilter.includePackageNames(EL_TCK_PACKAGE))
                .build();

        TestPlan plan = LauncherFactory.create().discover(request);

        List<Arguments> tests = new ArrayList<>();
        collectTests(plan, plan.getRoots(), tests);
        tests.sort(Comparator.comparing(a -> (String) a.get()[2]));
        return tests.stream();
    }

    private static void collectTests(TestPlan plan, Set<TestIdentifier> ids, List<Arguments> out) {
        for (TestIdentifier id : ids) {
            if (id.isTest() && id.getSource().isPresent()
                    && id.getSource().get() instanceof org.junit.platform.engine.support.descriptor.MethodSource) {
                org.junit.platform.engine.support.descriptor.MethodSource source =
                        (org.junit.platform.engine.support.descriptor.MethodSource) id.getSource().get();
                String className = source.getClassName();
                String methodName = source.getMethodName();
                String display = simpleName(className) + "." + id.getDisplayName();
                out.add(Arguments.of(className, methodName, display));
            }
            collectTests(plan, plan.getChildren(id), out);
        }
    }

    /**
     * Runs a single EL TCK test method. Arquillian runs this invocation inside Payara Micro, so
     * the JUnit Platform launcher below executes the EL test against the container's EL runtime.
     */
    @ParameterizedTest(name = "{2}")
    @MethodSource("elTckTests")
    void elTckTest(String className, String methodName, String display) {
        System.setProperty("variable.mapper",
                System.getProperty("variable.mapper", "org.glassfish.expressly.lang.VariableMapperImpl"));

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectMethod(className, methodName))
                .build();

        AtomicReference<TestExecutionResult> outcome = new AtomicReference<>();
        TestExecutionListener capturing = new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                if (id.isTest()) {
                    outcome.set(result);
                }
            }
        };
        LauncherFactory.create().execute(request, capturing);

        TestExecutionResult result = outcome.get();
        if (result == null) {
            throw new AssertionError("EL TCK test was not discovered in-container: " + display);
        }
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            Throwable cause = result.getThrowable().orElse(null);
            throw new AssertionError("EL TCK test failed: " + display
                    + (cause != null ? " -> " + cause : ""), cause);
        }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static Path classpathRootOf(String className) throws Exception {
        Class<?> sample = Class.forName(className);
        URI location = sample.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Paths.get(location);
    }

    private static File findJar(String nameFragment) {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            File jar = new File(entry);
            if (jar.getName().contains(nameFragment) && jar.isFile()) {
                return jar;
            }
        }
        throw new IllegalStateException("Jar containing '" + nameFragment + "' not found on the test classpath");
    }

    // The EL TCK jar shares the "expression-language-tck" name prefix with this module's own
    // artifact, so locate it unambiguously by an entry only the TCK jar contains.
    private static File findJarContaining(String entryName) {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            File jar = new File(entry);
            if (!jar.isFile()) {
                continue;
            }
            try (JarFile jarFile = new JarFile(jar)) {
                if (jarFile.getEntry(entryName) != null) {
                    return jar;
                }
            } catch (Exception ignored) {
                // not a readable jar; skip
            }
        }
        throw new IllegalStateException("No jar on the test classpath contains '" + entryName + "'");
    }
}
