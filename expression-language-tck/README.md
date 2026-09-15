# Jakarta Expression Language

## Prerequisites
Download and install the TCK from the tck-downloads module. From the top-level directory:

`mvn clean install -pl . -pl tck-download -pl tck-download/jakarta-expression-language-tck -Dpayara.version=...`

## Test Executions
**(Make sure the Payara server up and running)**

Run maven test from the module directory using remote arquillian profile, and provide the path to payara and its version

```
cd expression-language-tck
mvn clean verify -Ppayara-server-remote -Dpayara.version=... -Dpayara.home=...
```

To run against a managed Payara Micro instead:

`mvn clean verify -Ppayara-micro-managed -pl . -pl expression-language-tck`

Under `payara-micro-managed` the build starts a real Payara Micro process (via
Arquillian) and runs the functional EL TCK tests **inside the container**:

- The Payara Micro uber-jar is copied to `target/payara-micro-<version>.jar`.
- `fish.payara.tck.el.ELTckIT` (compiled from `src/test/micro/java`) builds an
  `el-tck.war` containing the EL TCK jar plus the JUnit Platform engine/launcher,
  deploys it to Payara Micro, and runs every `com.sun.ts.tests.el` test in-container
  using the EL implementation shipped in Payara Micro.
- Each EL test method is driven as a separate `@ParameterizedTest` invocation, so all
  ~360 tests are reported individually in the Failsafe output. `@ParameterizedTest` is
  used deliberately: Arquillian's JUnit 5 extension intercepts it and runs the
  invocations **inside** Payara Micro. A `@TestFactory` would instead execute in the
  client JVM (Arquillian does not intercept test factories), defeating the purpose.
- The signature test (`ELSigTestIT`) still runs in-JVM, because it needs the JDK
  `jimage` tool and real jar files on disk and cannot run inside a WAR. Its EL API and
  implementation jars are resolved from the Payara BOM (the same artifacts Payara Micro
  bundles), since Payara Micro is not unpacked.