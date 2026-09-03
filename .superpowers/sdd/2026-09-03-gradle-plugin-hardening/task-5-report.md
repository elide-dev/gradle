# Task 5 Report: Dependency installation and compiler integration

## Implementation

- Added deterministic TestKit coverage for dependency installation. The fixture records invocations, creates `.dev/dependencies/m2` only for `install`, and proves `install` precedes the compiler's literal `javac --` invocation. It also covers Maven repository registration enabled/disabled and confirms disabling installation does not invoke `install`.
- Added compiler integration coverage for preservation of a user-supplied compiler argument after the `javac --` prefix. The test uses an absent `fake-java-home` sentinel and proves no path is created beneath it.
- `ElideExecTask` now declares install-role manifest and dev-root inputs plus lockfile and generated Maven-repository outputs through typed annotated properties. Manifest and dev-root use `@InputFiles`, so the default paths are still tracked when present but an initial install may create the `.dev` tree itself.
- Elide executions now capture standard output and standard error. A nonzero result throws `GradleException` naming the executable, working directory, and exit code. Captured output replaces every inherited non-empty environment value with `[redacted]`; no environment map is emitted.
- The local Maven repository uses the generated repository path's URI and remains conditional on Maven integration.
- Removed normal functional-test dependence on a pre-created Java Home directory and the ambient developer `PATH`. The baseline plugin fixture uses an explicit fake runtime, and PATH-mode tests supply only their fake executable directory.
- Renamed the isolated pinned-release smoke task to `realRuntimeSmoke`; it remains detached from ordinary tests and builds.

## TDD evidence

### RED

1. Before changing execution handling, the focused failure fixture was run:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests 'dev.elide.gradle.CompilerIntegrationFunctionalTest.reportsElideFailuresWithoutLeakingEnvironmentValues'
```

The fake executable exited 23 after printing `not-for-build-output`. The build failed with Gradle's generic `Process ... finished with non-zero exit value 23`; its output exposed the secret and did not name the working directory.

2. The install fixture was then made intentionally free of a pre-created manifest or `.dev` directory and run:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests 'dev.elide.gradle.DependencyInstallFunctionalTest'
```

It failed task validation because the optional single-file/directory declarations required the absent default manifest and dev root. This demonstrated that an initial `elide install` could not create its own generated repository.

### GREEN

After typed execution I/O, captured/redacted diagnostics, and `@InputFiles` install inputs were implemented:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests 'dev.elide.gradle.DependencyInstallFunctionalTest' --tests 'dev.elide.gradle.CompilerIntegrationFunctionalTest' --tests 'dev.elide.gradle.RuntimeSelectionFunctionalTest' --tests 'com.example.plugin.ElidePluginFunctionalTest'
```

Result: `BUILD SUCCESSFUL in 15s`; the four focused classes completed with zero failures. The diagnostics fixture confirms that both captured standard streams can contain the test secret without it appearing in the Gradle failure output.

The install/compiler topology itself was already present from Task 3, so the corresponding new behavior tests passed once their deterministic runtime-shaped fixture was in place. They now protect the install ordering, Maven registration condition, disabled-install branch, literal prefix, and user compiler argument against regressions.

## Verification

```text
./gradlew :elide-gradle-plugin:tasks --all | rg 'realRuntimeSmoke|checkElide'
```

Result: `realRuntimeSmoke`; no legacy `checkElide` task remained.

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 23s`; unit and functional suites passed and a configuration-cache entry was stored.

```text
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 472ms`; this first standalone functional graph stored its own cache entry, as expected because its requested task graph differs from the preceding combined test/functional invocation.

```text
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 308ms`; `Configuration cache entry reused.`

```text
git diff --check
```

Result: exit 0.

## Files changed

- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/DependencyInstallFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/CompilerIntegrationFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/RuntimeSelectionFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/com/example/plugin/ElidePluginFunctionalTest.java`
- `elide-gradle-plugin/build.gradle.kts`

## Self-review

- `elideInstall` has explicit executable, arguments, working-directory, manifest, dev-root, lockfile, and generated-repository declarations. Missing optional project files are represented as empty `@InputFiles` rather than causing an initial install validation failure.
- The fake compiler runtime is explicit and does not resolve Elide through `PATH`; its Java probe launcher uses the test JVM's absolute executable only to satisfy Gradle's Java-compiler toolchain probe. It does not create an `elide-javac` or other Java Home shim.
- The failure message contains only the executable, working directory, exit status, and redacted child output. The code does not inspect or print an environment map.
- The generated Maven repository URL is a `Path.toUri()` value, avoiding malformed `file://` construction for platform-specific paths.
- Production code uses public Gradle task/property/exec APIs available to Gradle 7.6.4 and Java 17 APIs only; repository compilation retains release 17 bytecode policy.

## Concerns

- The current repository continues to report pre-existing restricted-native-access, Java-installation-path, and Gradle deprecation warnings. They did not produce test failures or configuration-cache problems.
- This original POSIX-only-fixture concern was resolved in review-fix round 1 below with native Windows batch fixtures and Windows-specific coverage.

---

## Review-fix round 1: repeatability, redaction, and Windows fixtures

### Implementation

- Replaced the install task's broad `@InputDirectory` working-directory snapshot with an internal execution directory plus an `@Input` path value. Project source/build changes therefore no longer invalidate `elideInstall` merely because they are below the working directory.
- Replaced the whole `.dev` directory input with a relative `@InputFiles` collection that excludes `dependencies/**` and `elide.lock.bin`. The generated Maven repository remains the task's declared output, but is no longer also an input. Removed the lockfile output declaration because an install is permitted not to create it; an absent output must not force every install to rerun.
- Failure construction now redacts the complete final diagnostic, including executable and working-directory context. Environment values are deduplicated and sorted longest-first before replacement, preventing a short secret prefix from leaving a suffix exposed. Captured standard streams are bounded to 64 KiB, with a truncation marker.
- Replaced `$*` recording with one `.args` file per invocation and one element per line. The compiler test now asserts the exact initial argument sequence `javac`, `--`, and a quoted value containing a space.
- Added native Windows batch fixture generation. On Windows, `.cmd` fixtures are run by `cmd.exe /d /c`; normal Elide `.exe` execution remains direct. A Windows-only compiler configuration test validates an `elide.exe` fixture and literal compiler arguments without trying to execute a synthetic Java runtime. The Unix compile fixture links the test JVM's real `java` and `javac` tools solely for Gradle's toolchain probe; it neither sets `JAVA_HOME` nor creates a fake Java-home directory.
- Updated runtime-selection fixtures to use `elide.exe` on Windows. `realRuntimeSmoke` now derives its executable name and archive type from the detected platform, using `bin/elide.exe` and ZIP assets on Windows.

### TDD evidence

#### RED

Before the production change, the two new focused regressions failed:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.DependencyInstallFunctionalTest.installIsUpToDateWhenItsInputsHaveNotChanged --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.redactsEnvironmentValuesFromExecutableAndWorkingDirectoryWithLongestValueFirst
```

Result: `2 tests completed, 2 failed`.

- The unchanged second `elideInstall` was not `UP-TO-DATE`, because its input snapshot included generated `.dev` output and it declared an absent lockfile output.
- The secret embedded in the executable/project path appeared in the failure diagnostic; redaction covered only captured streams and could also leave a suffix after a shorter overlapping value was replaced.

During fixture refactoring, a focused compiler test also confirmed that Gradle requires real Java tool metadata for a fork executable. The final fixture uses links to the running test JVM tools rather than a fake `JAVA_HOME` property or directory.

#### GREEN

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.DependencyInstallFunctionalTest --tests dev.elide.gradle.CompilerIntegrationFunctionalTest --tests dev.elide.gradle.RuntimeSelectionFunctionalTest
```

Result: `BUILD SUCCESSFUL in 19s` (10 tests completed; the Windows-only compiler-configuration test is conditionally executed by the Windows matrix).

The repeated-install assertion observed `:elideInstall UP-TO-DATE`; the redaction assertion found neither the full secret, its prefix, nor its suffix in output. Per-element compiler argument assertions passed, including the argument with a space.

```text
./gradlew :elide-gradle-plugin:realRuntimeSmoke --dry-run -Dos.name=Windows -Dos.arch=amd64
```

Result: `BUILD SUCCESSFUL`; the Windows archive/smoke task graph configured without downloading or executing a runtime.

### Final verification

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 28s`; unit and functional tests passed and the configuration cache was reused.

```text
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 322ms`; `Configuration cache entry reused.`

```text
git diff --check
```

Result: exit 0.

### Files added or changed in this round

- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/PlatformFixture.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/DependencyInstallFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/CompilerIntegrationFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/RuntimeSelectionFunctionalTest.java`
- `elide-gradle-plugin/build.gradle.kts`

### Self-review

- No generated repository or optional lockfile is an install input/output overlap. The unchanged-second-build regression protects the result.
- The entire user-facing process failure string is redacted after all context and captured streams are composed; the value order is deterministic longest-first.
- Successful process output remains suppressed but its in-memory capture is bounded. Failure output is useful up to the explicit cap.
- Normal tests use fixture executables, direct links to the already-running test JDK for Gradle metadata only, or the in-process TestKit server; they do not use a real Elide binary, a real network endpoint, developer `PATH`, or a fake `JAVA_HOME` directory.
- Windows-specific behavior is covered by native batch fixture execution and a Windows-only `elide.exe` compiler-wiring test. The current macOS run validates the generated batch content; Windows execution remains for the planned CI matrix rather than being deferred to another task.
