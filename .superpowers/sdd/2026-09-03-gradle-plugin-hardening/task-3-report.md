# Task 3 Report: Lazy plugin configuration and JDK shim removal

## Implementation

- Added `ElideRuntimeResolver` and `ElideRuntimeResolution`. The resolver maps extension inputs to the Task 2 pure selection model, reads `PATH` through `ProviderFactory.environmentVariable`, honors explicit `elideBin`, maps an explicitly set legacy `resolveElideFromPath`, and computes the deterministic managed cache executable under Gradle User Home. Managed selections intentionally carry no preparation task until Task 4.
- Added `ElideExecTask`, with declared executable, arguments, and working-directory inputs. It injects `ExecOperations` and is the only implementation path that starts Elide.
- Reworked the plugin to configure Java compilation with the resolved Elide executable and the literal `javac --` JVM-argument prefix. It wires managed preparation only when the runtime source is managed and converts `elideInstall` into `ElideExecTask` with declared manifest, dev-root, lockfile, and dependency-repository inputs/outputs.
- Removed version subprocess execution, all JDK-shim writes, shim state, the obsolete shim functional test, the workflow shim installation step, and `.github/workflows/shim.sh`.
- Added TestKit coverage for explicit and `PATH` runtime configurations. Each runs `help --configuration-cache` twice against a fake executable and fake Java Home, asserting no invocation, no `elide-javac` creation, initial cache storage, and subsequent reuse.

## TDD evidence

### RED

Before production changes, I added the configuration-purity TestKit fixture and ran:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.RuntimeSelectionFunctionalTest
```

The test failed at `RuntimeSelectionFunctionalTest.java:54` with:

```text
expected: <false> but was: <true>
```

The observed file was the legacy plugin-created `fake-java-home/bin/elide-javac` shim. The prior implementation suppresses its `--version` subprocess specifically when configuration cache is requested, so the failure directly demonstrated the remaining configuration-time JDK mutation instead.

### GREEN

After replacing the shim and execution path, the focused suite passed:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.RuntimeSelectionFunctionalTest
```

```text
BUILD SUCCESSFUL
```

The functional XML report records 2 tests, 0 failures, and 0 errors: explicit and `PATH` runtime configurations both stored and reused configuration-cache entries without invoking the fake executable or creating the shim.

## Verification commands and results

```text
./gradlew :elide-gradle-plugin:validatePlugins
```

```text
BUILD SUCCESSFUL
```

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

First final run:

```text
BUILD SUCCESSFUL
Configuration cache entry stored.
```

Second final run, followed by the requested shim and whitespace checks:

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache && test ! -e .github/workflows/shim.sh && git diff --check
```

```text
BUILD SUCCESSFUL
Configuration cache entry reused.
```

## Files changed

- `.github/workflows/job.build.yml`
- `.github/workflows/shim.sh` (deleted)
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtension.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolution.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolver.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideTaskName.java`
- `elide-gradle-plugin/src/functionalTest/java/com/example/plugin/ElidePluginFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/RuntimeSelectionFunctionalTest.java`

## Self-review

- The plugin does not call `ProcessBuilder`, `System.getenv("PATH")`, or `java.home`; only `ElideExecTask` performs execution.
- `PATH` is provided through Gradle's provider API; explicit, PATH, AUTO, and managed selection preserve the Task 2 locator semantics, while the legacy switch changes mode only when explicitly set.
- `JavaCompile` uses the resolved Elide executable directly and prefixes its existing fork arguments with `javac`, `--`; it neither reads nor writes Java Home.
- Managed selections expose an empty preparation provider in this task and dependency wiring is explicitly source-gated, leaving Task 4's managed task implementation isolated.
- `ElideExecTask` passed Gradle plugin validation with explicit path normalization and disabled task-output caching, because external Elide commands can modify state beyond the current declared outputs.
- `git diff --check` and the shim absence check passed.

## Concerns

- The repository-level test wiring still downloads and checks the pinned real Elide runtime when the plugin test tasks run; that is pre-existing repository build behavior, not consumer plugin configuration behavior.
- Full builds continue to emit pre-existing Gradle warnings about restricted native access, deprecated Gradle features, and a nonexistent configured Java-installation path. These warnings did not affect the passing test or configuration-cache checks.
