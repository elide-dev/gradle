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

---

## Review-fix round 2: launch failures, capture boundaries, and fixture fidelity

### Implementation

- Normalized the smoke build's operating-system value once and accept all names beginning with `windows`, including standard JDK values such as `Windows 10`. The platform continues to select the Windows ZIP archive and `elide.exe` smoke executable.
- Made bounded process capture redaction-safe. Each stream retains the normal 64-KiB visible prefix plus only a bounded suffix equal to the largest inherited environment value. While rendering the visible prefix, matching environment values are replaced byte-for-byte before output is decoded. A value starting before 64 KiB is therefore fully recognized and redacted even if it ends after the visible boundary; bytes after the visible prefix are never emitted. The truncation marker remains.
- Wrapped process-start failures from `ExecOperations` in the same structured `GradleException` used for a nonzero exit. These failures use exit code `-1`, include the executable and working-directory labels, retain the original exception as the cause, and redact its message. The exit code is protected during redaction so incidental environment values such as `1` cannot obscure `-1`.
- Restored the install fixture's responsibility for creating `.dev/dependencies/m2`: fixture setup creates at most `.dev`, and Unix/Windows fake installers explicitly make the generated-repository directory before writing their marker.
- Corrected Windows batch argument iteration to test raw `%1` for end-of-arguments and use `%~1` only for recording. This distinguishes no argument from an empty quoted argument, so a later argument is not dropped. A Windows-only functional task exercises `record`, empty, `after`; macOS additionally checks the generated native batch control flow without claiming to execute it.

### TDD evidence

#### RED

The standard native OS spelling first failed exactly as reported:

```text
./gradlew :elide-gradle-plugin:realRuntimeSmoke --dry-run -Dos.name='Windows 10' -Dos.arch=amd64
```

Result: `Unsupported OS: Windows 10` at `build.gradle.kts:45`.

Focused regression tests were then added before production changes:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.DependencyInstallFunctionalTest.installCreatesTheGeneratedRepositoryWhenItIsInitiallyAbsent --tests dev.elide.gradle.DependencyInstallFunctionalTest.windowsFixtureUsesABatchExecutableAndRecordsArgumentsIndividually --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.reportsAnUnstartableExecutableAsAStructuredRedactedFailure
```

Result: `3 tests completed, 3 failed`.

- The fixture had already created the repository before the install task.
- The batch script used `%~1` for both empty and absent arguments.
- A missing interpreter bypassed the required process-failure structure and did not report an exit code.

The boundary test was separately made red after aligning the secret's start with the 64-KiB capture edge:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.redactsAnEnvironmentValueThatCrossesTheCaptureBoundary
```

Result: failed because the captured prefix `cross-boundary-secret` appeared in the diagnostic.

#### GREEN

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.DependencyInstallFunctionalTest.installCreatesTheGeneratedRepositoryWhenItIsInitiallyAbsent --tests dev.elide.gradle.DependencyInstallFunctionalTest.windowsFixtureUsesABatchExecutableAndRecordsArgumentsIndividually --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.redactsAnEnvironmentValueThatCrossesTheCaptureBoundary --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.reportsAnUnstartableExecutableAsAStructuredRedactedFailure
```

Result: `BUILD SUCCESSFUL in 6s`. The initial repository is absent before the task and present after it; the boundary secret prefix is absent; and the unstartable executable produces labeled, redacted context with `exit code -1`.

```text
./gradlew :elide-gradle-plugin:realRuntimeSmoke --dry-run -Dos.name='Windows 10' -Dos.arch=amd64
```

Result: `BUILD SUCCESSFUL`; the Windows smoke graph configured without network or runtime execution.

```text
./gradlew :elide-gradle-plugin:functionalTest
```

Result: `BUILD SUCCESSFUL in 32s`.

### Final verification

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 1s`; configuration-cache entry stored.

```text
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 285ms`; `Configuration cache entry reused.`

```text
git diff --check
```

Result: exit 0.

### Files changed in this round

- `elide-gradle-plugin/build.gradle.kts`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/PlatformFixture.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/DependencyInstallFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/CompilerIntegrationFunctionalTest.java`

### Self-review

- Capture remains bounded: its retained raw suffix is limited to the largest environment value needed to complete a match, and only the redacted first 64 KiB can enter a diagnostic.
- Process-start exceptions cannot escape the structured/redacted error path. A numeric exit-code marker avoids a one-character inherited environment value masking the required status.
- The native batch script now distinguishes an empty `""` argument from the exhausted argument list. The Windows-only functional assertion is deliberately conditional; the macOS test asserts the batch syntax rather than pretending it was executed.

---

## Review-fix round 3: literal diagnostics, fixed capture bounds, and direct Windows execution

### Implementation

- Built process diagnostics from literal labels plus individually redacted untrusted values. The numeric exit status is appended structurally after redaction, so an inherited value such as `ELIDE_EXIT_CODE` or `x` cannot corrupt `executable`, `working directory`, `exit code`, or the status itself.
- Replaced environment-sized capture storage with fixed limits: at most 64 KiB of rendered output, a fixed 3 KiB raw look-ahead, and fixed redaction metadata (at most 256 non-empty values, each at most 1,024 Java characters / 3 KiB UTF-8). If the inherited environment exceeds those safe metadata limits, the task withholds captured child output rather than risking a partial secret at a boundary. Both standard streams use the same fixed policy.
- Kept look-ahead sufficient to redact a permitted environment value that starts at the visible 64-KiB boundary. Redaction-marker rendering itself is capped at the visible bound, then reports a fixed truncation marker.
- Removed the explicit `cmd.exe /c` batch-script branch. `ExecOperations` now receives the configured executable and argument list directly, so Task 5 does not compose a raw command-interpreter command from an executable path or user-supplied compiler arguments. Native `elide.exe` remains the Windows production executable.
- Added focused diagnostics/capture regressions and a Windows-only native fixture regression with `%`, `&`, `|`, `<`, `>`, and `^` in one argument. The Windows test records individual argv elements and verifies the exact list without simulating a Windows launcher on macOS.

### TDD evidence

#### RED

The following focused tests were added before the round-3 implementation:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.preservesLiteralDiagnosticContextWhenEnvironmentValuesMatchLabels --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.withholdsCapturedOutputWhenAnEnvironmentValueExceedsTheFixedSafeBound --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.executesWindowsNativeArgumentsDirectlyWithoutTheCommandInterpreter --no-configuration-cache
```

Before the implementation, the literal-context case rendered corrupt labels (`e[redacted]ecutable` / `e[redacted]it`) and lost the required status because `ELIDE_EXIT_CODE` was redacted as a sentinel. The oversized-value case rendered redacted child output instead of the explicit withheld-output diagnostic. The original batch route also sent the configured executable and arguments through `cmd.exe /c`; the initial macOS `-Dos.name=Windows` simulation proved invalid because it causes Gradle to select a Windows process launcher on a non-Windows host. The final hostile-argument regression is therefore conditionally executed only on a real Windows runner.

#### GREEN

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.preservesLiteralDiagnosticContextWhenEnvironmentValuesMatchLabels --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.redactsAnEnvironmentValueThatCrossesTheCaptureBoundary --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.withholdsCapturedOutputWhenAnEnvironmentValueExceedsTheFixedSafeBound --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.executesWindowsNativeArgumentsDirectlyWithoutTheCommandInterpreter --no-configuration-cache
```

Result: `BUILD SUCCESSFUL in 5s`. The three runnable regressions passed on macOS; the native-Windows argv regression was skipped by its OS condition and is ready for the Windows matrix. The existing boundary regression still passed, confirming that bounded look-ahead redacts a secret spanning the 64-KiB visible edge.

### Verification

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: the unit and functional task graph completed successfully and stored its configuration-cache entry. The subsequent identical invocation reported `Configuration cache entry reused.`

```text
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: both commands completed successfully; the second reported `Configuration cache entry reused.`

```text
git diff --check
```

Result: exit 0.

### Files changed in this round

- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/CompilerIntegrationFunctionalTest.java`

### Self-review

- Required diagnostic context is literal and cannot be changed by replacement of an environment value; executable and working-directory values, exception text, stdout, and stderr remain individually redacted.
- Exit code uses no redactable placeholder. The `ELIDE_EXIT_CODE`/`x` regression verifies both the old sentinel collision and broad-label collision.
- Capture allocation is bounded independently of the count and size of inherited values. Oversized values cause withholding before their bytes are copied into capture metadata or buffers, avoiding integer-overflow arithmetic and boundary-prefix disclosure.
- The generated output still excludes environment maps and all normal functional tests use fixtures. No fake `JAVA_HOME`, real Elide runtime, ambient developer `PATH`, or external network was introduced.
- Removing the explicit command interpreter prevents Task 5 from raw-concatenating user values into a `cmd.exe /c` string. The actual Windows regression is platform-native rather than an `os.name` spoof.

### Concerns

- The hostile-argument execution assertion requires the planned Windows CI runner; it is correctly conditional and was not represented as executed on this macOS host. The test uses the shared native Windows fixture and records exact individual argv values when that matrix runs.
- The repository continues to emit pre-existing restricted-native-access, Java-installation-path, and Gradle deprecation warnings. They did not cause test or configuration-cache failures.

---

## Review-fix round 4: stacktrace-safe failures and one bounded redaction policy

### Implementation

- Removed the raw process-start exception as the cause of the structured `GradleException`. The error text is incorporated only through the redaction policy; Gradle therefore has no unredacted nested exception to display with `--stacktrace`.
- Replaced the repeated, unbounded `environmentValues()` collection with one static `RedactionPolicy`. It reads at most 257 non-empty inherited values while initializing, stores no more than 256 references plus their bounded UTF-8 forms (each at most 1,024 Java characters / 3 KiB), and uses the same policy for context and captured streams.
- If either count or value size exceeds the fixed metadata bound, the policy returns `[redacted]` for every untrusted diagnostic field and capture withholds child output. Literal labels, the numeric exit status, and the fixed withheld-output marker remain useful and cannot be mutated by an inherited value.
- Added multibyte UTF-8 boundary coverage alongside the existing byte-boundary regression.
- Removed the prior hostile-metacharacter batch assertion. Windows `.cmd` files are documented as test-only fixture helpers; production runtime resolution uses native `elide.exe`, which continues through direct `ExecOperations` execution. The Windows lane retains ordinary fixture coverage for `install` and adds exact `javac`, `--`, and space-containing compiler-argument recording.

### TDD evidence

#### RED

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.reportsAnUnstartableExecutableAsAStructuredRedactedFailure --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.withholdsAllUntrustedDiagnosticFieldsWhenEnvironmentMetadataIsOverLimit --no-configuration-cache
```

Result: `2 tests completed, 2 failed`.

- With `--stacktrace`, Gradle traversed the retained `ProcessExecutionException` cause and printed the secret-bearing executable/project path after the otherwise redacted outer failure.
- With 257 added environment values, the old capture policy withheld child output but the independently rebuilt context redactor still emitted the opaque project-path component, demonstrating both incomplete conservative redaction and environment-dependent diagnostic work.

#### GREEN

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.reportsAnUnstartableExecutableAsAStructuredRedactedFailure --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.withholdsAllUntrustedDiagnosticFieldsWhenEnvironmentMetadataIsOverLimit --tests dev.elide.gradle.DependencyInstallFunctionalTest.windowsTestFixtureRecordsOrdinaryCompilerArguments --no-configuration-cache
```

Result: `BUILD SUCCESSFUL in 4s`. The stacktrace regression retained literal `executable`, `working directory`, and `exit code -1` context without the secret. The count-over-limit regression omitted both opaque context and child output. The native-Windows ordinary-compiler fixture test was correctly skipped on macOS.

```text
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.CompilerIntegrationFunctionalTest.redactsAMultibyteEnvironmentValueThatCrossesTheCaptureBoundary --no-configuration-cache
```

Result: `BUILD SUCCESSFUL in 2s`; the multibyte secret did not appear when its first UTF-8 code point crossed the 64-KiB visible boundary.

### Final verification

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: the complete unit/functional suite completed with zero test failures; the following identical invocation reported `Configuration cache entry reused.`

```text
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: both commands completed successfully; the second reported `Configuration cache entry reused.`

```text
git diff --check
```

Result: exit 0.

### Files changed in this round

- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/CompilerIntegrationFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/DependencyInstallFunctionalTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/PlatformFixture.java`

### Self-review

- Process-launch failures no longer retain an exception cause; both outer output and `--stacktrace` are covered by a secret-path regression.
- The redaction policy's allocation and traversal bounds are fixed before any environment value is copied. Oversized values and count overflow short-circuit to the conservative policy instead of collecting or sorting the complete inherited environment.
- The same fixed policy drives byte capture matching and text diagnostics, eliminating divergent stream/context behavior. The byte and multibyte boundary tests exercise both sides of the visible capture limit.
- Batch helpers remain test code. No production command line is assembled for `cmd.exe`, native `elide.exe` remains the resolved Windows runtime, and the removed hostile test is not represented as a user-facing `.cmd` compatibility contract.
- Normal tests remain local fixtures or local fixture servers only; no fake `JAVA_HOME`, real runtime, ambient developer `PATH`, or real network was added.

### Residual Windows verification

- macOS did not execute the two Windows-only batch-fixture tests. They exercise only ordinary fixture arguments (`install`, and `javac -- -Dfixture.compiler.argument=has a space`) and are explicitly conditioned for the planned native Windows CI lane. The codebase no longer claims hostile metacharacter preservation for `.cmd` files.
- Fixture setup no longer creates generated Maven output. The fake installers do, while no normal test uses a real Elide executable, real network, or developer `PATH`.
