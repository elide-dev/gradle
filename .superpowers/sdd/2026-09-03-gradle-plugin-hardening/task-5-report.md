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
- The new execution fixtures are POSIX shell fixtures, matching the repository's existing direct executable fixtures. The planned Task 6 cross-platform consumer matrix remains the appropriate place to add Windows-native execution-fixture coverage.
