# Task 2 Report: Runtime model and platform mapping

## Scope

Implemented the pure Java runtime model and platform mapping for the Gradle plugin:

- Added `ElideRuntimeMode` (`AUTO`, `PATH`, `MANAGED`) and `ElideRuntimeSource` (`EXPLICIT`, `PATH`, `MANAGED`).
- Added immutable `ElidePlatform`, including OS/architecture normalization, supported asset names, and executable names.
- Added immutable `ElideRuntimeSelection`.
- Added `ElideRuntimeLocator` with explicit > PATH > managed precedence in `AUTO`, managed-only behavior in `MANAGED`, and a hard failure for an unresolved `PATH` mode.
- Added `runtimeMode` and `runtimeVersion` extension properties with conventions `AUTO` and `1.5.1+20260903`.
- Deprecated the legacy `resolveElideFromPath` property and removed its default convention so later resolution can distinguish an explicitly configured legacy value.

## TDD evidence

### RED

Added `ElidePlatformTest` and `ElideRuntimeLocatorTest` before adding production runtime types. The required focused command failed during test compilation because the production types did not exist:

```text
./gradlew :elide-gradle-plugin:test --tests dev.elide.gradle.ElidePlatformTest
```

Observed failure included:

```text
error: cannot find symbol
import static dev.elide.gradle.ElideRuntimeMode.AUTO;
...
30 errors
Execution failed for task ':elide-gradle-plugin:compileTestJava'
```

### GREEN

After implementing the model, locator, and extension changes, the focused tests passed:

```text
./gradlew :elide-gradle-plugin:test \
  --tests 'dev.elide.gradle.ElidePlatformTest' \
  --tests 'dev.elide.gradle.ElideRuntimeLocatorTest'
```

```text
BUILD SUCCESSFUL in 3s
13 actionable tasks: 3 executed, 10 up-to-date
```

The full plugin unit-test task also passed:

```text
./gradlew :elide-gradle-plugin:test
```

```text
BUILD SUCCESSFUL in 4s
13 actionable tasks: 3 executed, 10 up-to-date
```

The resulting XML reports show 7/7 platform tests and 7/7 locator tests, with zero failures and zero errors.

## Files changed

- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeMode.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeSource.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElidePlatform.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeSelection.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeLocator.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtensionConfig.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtension.java`
- `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElidePlatformTest.java`
- `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideRuntimeLocatorTest.java`

## Self-review

- Platform mapping matches the requested literal names: Linux/macOS use `.tgz`, Windows uses `.zip`, and Windows uses `elide.exe`.
- `x86_64`/`amd64` and `arm64`/`aarch64` are normalized; Windows ARM64 and unknown platforms throw `IllegalArgumentException`.
- Locator path lookup preserves directory order and validates Unix executability versus Windows regular-file semantics.
- Managed mode is checked before explicit and PATH candidates, and `PATH` mode never falls back to managed.
- The locator has no Gradle or process-execution dependencies.
- `resolveElideFromPath` remains present for source compatibility, but is intentionally unset by convention. Mapping an explicitly supplied legacy value to the effective mode remains the resolver integration responsibility in Task 3.
- `git diff --check` passed.

## Concerns

- Gradle emits pre-existing environment warnings about restricted native access and a nonexistent `org.gradle.java.installations.paths` entry; neither affects these tests.
- Running the plugin test task invokes the existing managed-runtime preparation tasks and downloads the pinned runtime as configured by the repository build.
