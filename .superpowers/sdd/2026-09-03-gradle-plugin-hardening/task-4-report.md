# Task 4 Report: Checksum-verified managed Elide runtimes

## Implementation

- Added `ElideRelease` for deterministic platform asset and `.sha256` locations.
- Added `ElideDownloader`, using Java 17 `HttpClient` with normal redirects. It writes checksum/archive responses to sibling temporary files, streams SHA-256 computation, compares ASCII lowercase digests with `MessageDigest.isEqual`, and only moves a verified archive to its target. Mismatches preserve a pre-existing target and remove temporary downloads.
- Added `PrepareElideRuntimeTask`, with declared version, platform, release-base-URI, and offline inputs plus the final shared runtime directory output. It uses the required Gradle User Home cache layout, a sibling `FileChannel` lock, valid `.complete`/regular-executable cache checks, offline cache-miss diagnostics, staged extraction using injected `ArchiveOperations` and `FileSystemOperations`, executable validation/Unix permissions, and atomic directory promotion with the specified fallback.
- Managed runtime resolution now lazily registers `prepareElideRuntime` only when `AUTO` falls back to managed or `MANAGED` is selected. `PATH` and explicit selections still have no preparation provider. `ElideRuntimeResolver` exposes the package-private TestKit-only release-base-URI system-property override `dev.elide.gradle.test.releaseBaseUri`.
- Removed the repository build’s unconditional real-Elide dependencies from ordinary `test`, `functionalTest`, `check`, and `build` paths. The existing explicit `checkElide` smoke task remains available.

## TDD evidence

### RED

1. Added `ElideReleaseTest` and `ElideDownloaderTest` before their production types, then ran:

```text
./gradlew :elide-gradle-plugin:test --tests 'dev.elide.gradle.ElideReleaseTest' --tests 'dev.elide.gradle.ElideDownloaderTest'
```

Observed `:elide-gradle-plugin:compileTestJava FAILED` with eight expected `cannot find symbol` errors for `ElideRelease` and `ElideDownloader`.

2. Added the first managed-runtime TestKit fixture test before registering a preparation task, then ran:

```text
./gradlew :elide-gradle-plugin:functionalTest --tests 'dev.elide.gradle.ManagedRuntimeFunctionalTest'
```

Observed the expected managed-mode failure:

```text
Task with name 'prepareElideRuntime' not found in root project 'project'.
```

### GREEN

After implementing the release/downloader and managed preparation seam:

```text
./gradlew :elide-gradle-plugin:test --tests 'dev.elide.gradle.ElideReleaseTest' --tests 'dev.elide.gradle.ElideDownloaderTest'
```

completed successfully. The new unit XML reports show 5 tests, zero failures, and zero errors.

```text
./gradlew :elide-gradle-plugin:functionalTest --tests 'dev.elide.gradle.ManagedRuntimeFunctionalTest'
```

completed successfully. The four local-HTTP fixture tests cover first download/verification/extraction/fake execution, no-request reuse, checksum failure without a completion marker, and offline hit/miss behavior. The fixture selects a TGZ plus `bin/elide` on Unix and ZIP plus `bin/elide.exe` on Windows.

## Final verification

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 16s`; the configuration-cache entry was stored. Unit reports contain 19 tests and functional reports contain 7 tests, all with zero failures/errors. No real Elide download was requested.

Immediate re-run:

```text
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 304ms`; `Configuration cache entry reused`.

```text
git diff --check
```

Result: exit 0.

## Files

- `elide-gradle-plugin/build.gradle.kts`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRelease.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideDownloader.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/PrepareElideRuntimeTask.java`
- `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolver.java`
- `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideReleaseTest.java`
- `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideDownloaderTest.java`
- `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/ManagedRuntimeFunctionalTest.java`

## Self-review

- Concurrency: the lock path is exactly `<version>/<platform>.lock`; a same-JVM overlapping lock waits rather than failing, and final promotion occurs while held.
- Partial downloads: checksum/archive data is downloaded to sibling temporary files, which are removed for HTTP failures, interruption, parsing failure, and mismatches. A test proves a mismatch neither replaces an existing target nor leaves temporary download files.
- Verification: calculated and parsed lowercase hexadecimal SHA-256 values are compared through `MessageDigest.isEqual`; the complete marker is written only after archive verification, extraction, and expected-executable validation.
- Offline semantics: a valid marker plus a non-symlink regular expected executable is sufficient for reuse; misses fail before network access with the exact version/cache path.
- Archive handling: extraction is isolated in a unique sibling stage directory. Copy destinations are normalized and constrained beneath staging, empty directory entries are ignored, symlinked parents are rejected during copy, remaining symlinks are rejected after extraction, and staged data is deleted on every failure path.
- Atomicity: only `AtomicMoveNotSupportedException` enables the same-filesystem non-atomic promotion fallback. Invalid prior cache directories are removed only after a new staged runtime has passed validation.
- Configuration purity: managed task registration is conditional on the resolved source; explicit and `PATH` selections do not receive a preparation provider. Managed TestKit builds store and reuse configuration cache.

## Concerns

- The repository continues to emit pre-existing Gradle 9.7 Kotlin DSL deprecation and native-access/JDK-installation warnings. They did not produce test failures.
- Gradle 7.6.4 API compatibility was maintained by using public `ArchiveOperations`, `FileSystemOperations`, `Property`, task I/O annotations, and Java 17 APIs; the separate consumer-matrix execution remains Task 6 scope.

## Review fix: temporary-download cleanup

Review identified that the original downloader created both temporary paths before entering its `try`/`finally`, so a failure creating the archive temporary could leak the already-created checksum temporary. Its linear `finally` also skipped archive cleanup when checksum cleanup failed.

`downloadVerified` now initializes both paths within a guarded `try`, tracks the primary failure, and uses independent cleanup attempts for both paths. Cleanup errors are suppressed onto the primary failure; where no primary operation failed, the first cleanup error is thrown with later cleanup errors suppressed beneath it. This is intentionally local to the downloader and does not add a filesystem abstraction solely for a failure mode that JDK static filesystem calls cannot deterministically inject in the existing test design.

Focused verification:

```text
./gradlew :elide-gradle-plugin:test --tests 'dev.elide.gradle.ElideDownloaderTest'
```

Result: `BUILD SUCCESSFUL in 1s`, 4 tests, zero failures/errors. Existing mismatch coverage continues to prove that failed verification preserves the target and leaves no temporary download files.
