# Task 6 Report: Consumer compatibility and cross-platform CI

## Implementation

- Added `compatibilityTest`, a JUnit 6.1.3/TestKit source set. Its Java test launcher is a Java 17 toolchain, independently of the JDK that runs the repository wrapper.
- Added `ConsumerCompatibilityTest`, parameterized with the literal consumer Gradle versions `7.6.4`, `8.14.5`, and `9.7.1`. Every fixture applies `dev.elide` and `java`, supplies the existing platform-native explicit fake Elide executable, runs `help` and `compileJava`, and verifies the fake received the `javac --` invocation.
- The fixture environment contains only its Java 17 `JAVA_HOME`, that JDK's `bin` directory on `PATH`, and a fixture `GRADLE_USER_HOME`; it never selects an Elide executable from the developer or runner `PATH`, and it never selects the managed Elide runtime.
- Registered functional and compatibility source sets together with the Gradle plugin metadata provider. This preserves the `plugin-under-test-metadata.properties` classpath entry for both TestKit suites. Added the explicit Gradle TestKit dependency needed when the shared functional fixture output is compiled.
- Replaced the primary workflow with a native OS matrix: Ubuntu, macOS, and Windows run Java 17; Ubuntu adds Java 24 / Gradle 8.14.5 and Java 26 / Gradle 9.7.1 current-toolchain lanes. All use the 9.7.1 repository wrapper and run `build compatibilityTest`; the named Gradle versions are covered by the literal TestKit matrix rather than installing a second system Gradle.
- Added the daily/manual managed-runtime smoke workflow for Ubuntu, macOS, and Windows without installing Elide on `PATH`. It runs `./gradlew realRuntimeSmoke -Pelide.runtime.mode=MANAGED` and its blocking egress allow-list is exactly `elide.zip:443`, `github.com:443`, `release-assets.githubusercontent.com:443`, and `objects.githubusercontent.com:443`.
- Updated all requested setup actions to immutable v2/v5/v6 commit SHAs, removed POSIX `cd` and shim/setup-Elide steps, and added weekly Gradle/GitHub Actions Dependabot updates.

## TDD evidence

### RED

The compatibility test was added before its source set/task wiring. The required command then failed because the verification task did not yet exist:

```text
./gradlew :elide-gradle-plugin:compatibilityTest
Cannot locate tasks that match ':elide-gradle-plugin:compatibilityTest' as task 'compatibilityTest' not found
BUILD FAILED
```

### GREEN

After registering the source set, TestKit classpath, Java 17 launcher, and fixture helper output, the same command ran all three parameterized consumer versions successfully:

```text
./gradlew :elide-gradle-plugin:compatibilityTest
BUILD SUCCESSFUL
```

The compatibility XML result contains three cases, one each for Gradle 7.6.4, 8.14.5, and 9.7.1, with zero failures or errors.

## Diagnostic correction

The first full build found that functional TestKit tests could not locate `plugin-under-test-metadata.properties`. Classpath tracing showed that invoking `gradlePlugin.testSourceSets(...)` separately for the two source sets replaced the functional suite's metadata registration. Registering both source sets in one invocation restored that entry for both suites. The focused functional suite then produced 25 tests, zero failures/errors (three platform-specific skips on macOS).

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :elide-gradle-plugin:compatibilityTest` | Passed; all three literal Gradle-version cases executed under the Java 17 test launcher. |
| `./gradlew :elide-gradle-plugin:functionalTest` | Passed after the metadata correction; 25 tests, 0 failures/errors, 3 expected platform skips. |
| `actionlint .github/workflows/*.yml` | Passed. |
| `ruby -e 'require "yaml"; Dir[".github/**/*.yml"].each { \|f\| YAML.safe_load_file(f, aliases: false); puts f }'` | Passed for all workflow and Dependabot YAML. |
| `git diff --check` | Passed before the final verification pass. |

The required final command is run after this report is added so its result covers the report, source, and workflow changes together.

## Matrix rationale

The published plugin continues to target Java 17 and supports Gradle 7.6.4 as its consumer floor. The local wrapper remains Gradle 9.7.1 and is not a consumer minimum. Java 17 runs on all three hosted operating systems to cover that floor and platform-specific fixture behavior. Java 24 / Gradle 8.14.5 and Java 26 / Gradle 9.7.1 add the current supported toolchain pairs. The TestKit suite always launches from Java 17 so Gradle 7.6.4 is never accidentally started by Java 26; its three literal Gradle distributions cover the consumer versions in every lane.

## Self-review

- The compatibility test invokes the plugin through TestKit's plugin-under-test metadata and asserts a consumer-visible `help`/`compileJava` success plus the fake compiler invocation.
- Explicit fake Elide and the isolated `PATH` prevent an installed Elide executable or managed Elide download from satisfying the test.
- Source-set registration provides TestKit metadata to both functional and compatibility suites.
- Both workflows use `working-directory` for command steps and contain no `cd`, shell shim, or setup-Elide step.
- The scheduled workflow includes the required initial `elide.zip:443` origin and only the three specified redirect origins in its harden-runner allow-list.
- All requested action pins match the task brief exactly.

## Unverified native lanes

The local verification ran on macOS with the POSIX fake executable. GitHub-hosted Ubuntu and Windows matrix jobs, the Java 24 and Java 26 hosted lanes, and the three external-release scheduled smoke jobs have not run from this workspace. In particular, native Windows batch-fixture execution and the pinned real-release download/redirect path remain CI verification responsibilities.
