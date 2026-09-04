# Changelog

All notable changes to the Elide Gradle Plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This project does not currently assign
semantic versions to unreleased changes, so work completed after `1.0.0` remains under **Unreleased** until the next
release is cut.

## [Unreleased]

### Added

- Added `AUTO`, `PATH`, and `MANAGED` runtime-selection modes. `AUTO` prefers an explicitly configured executable, then
  a usable executable on `PATH`, and finally the managed runtime.
- Added the independently configurable `runtimeVersion`, pinned by default to Elide `1.5.1+20260903`.
- Added Gradle-managed runtime provisioning for Linux amd64/arm64, macOS arm64, and Windows amd64. Managed provisioning
  downloads the matching GitHub Release archive and adjacent SHA-256 checksum only when an executing task needs it.
- Added a shared runtime cache under
  `<GRADLE_USER_HOME>/caches/dev.elide/runtimes/<version>/<platform>/`, including offline reuse, per-platform file locks,
  completion markers, staging directories, and atomic promotion.
- Added verified TGZ and ZIP extraction with path-traversal protection, symlink rejection, expected-executable
  validation, Unix permission repair, and cleanup of partial downloads and staging directories.
- Added deterministic functional coverage for runtime selection, dependency installation, Java compiler invocation,
  managed downloads, checksum failures, offline cache hits and misses, configuration-cache reuse, and subprocess
  diagnostics.
- Added consumer compatibility tests for Gradle 7.6.4, 8.14.5, and 9.7.1 on Java 17, plus opt-in tests for the
  Gradle 8.14.5/Java 24 and Gradle 9.7.1/Java 26 consumer pairs.
- Added Linux, macOS, and Windows CI coverage, including a real Elide integration test on pull requests, pushes,
  schedules, and manual runs. The test provisions managed Elide, installs a real dependency, compiles Java with Elide,
  and runs the resulting application through Gradle.
- Added dependency update automation, workflow concurrency controls, immutable action pins, and restricted network
  egress.
- Added [runtime management](docs/runtime-management.md) and [compatibility and migration](docs/compatibility.md)
  documentation.

### Changed

- Upgraded the repository wrapper to Gradle 9.7.1 with distribution checksum verification. This changes the build used
  to develop the plugin, not the supported consumer minimum of Gradle 7.6.4.
- Standardized published plugin classes on Java 17 bytecode while testing supported newer JDK/Gradle pairs separately.
- Updated the test, publishing, and build plugin dependencies, including JUnit 6.1.3, Plugin Publish 2.1.1, and the
  download plugin 5.7.0.
- Enabled dependency locking for plugin test dependencies.
- Made plugin configuration lazy: applying the plugin no longer locates, downloads, or executes Elide and does not
  mutate `JAVA_HOME`.
- Changed Java compilation to invoke the selected Elide executable directly with the literal `javac --` prefix while
  preserving Gradle and user compiler arguments.
- Made `elideInstall` ordering deterministic before Java compilation and restricted the generated Maven repository to
  builds where Maven integration is enabled.
- Declared task inputs and outputs for executable selection, arguments, working directories, manifests, development
  roots, and generated dependency repositories so Gradle can make correct up-to-date decisions.
- Changed process failures to report the executable, working directory, and exit code with fixed-size captured output
  and bounded environment-value redaction. Process-start failures use the same structured diagnostic path.
- Separated the Gradle plugin/catalog release version (`1.0.0`) from the Elide library/runtime version
  (`1.5.1+20260903`) in the published version catalog.
- Updated the remote bootstrap metadata and all Kotlin DSL examples while retaining syntax compatible with Gradle 7.6.4.

### Fixed

- Fixed configuration-time Elide execution and downloads, which previously made basic commands such as `clean` depend
  on a locally installed or downloadable Elide runtime.
- Fixed the obsolete `1.0.0-beta5` runtime download URL that returned HTTP 404.
- Fixed Gradle 9 artifact instrumentation failures caused by the former plugin/build setup.
- Fixed the old Java compiler shim requirement and all writes beneath `JAVA_HOME`.
- Fixed incomplete temporary-file cleanup, including checked and unchecked cleanup failures and correct suppressed-error
  handling.
- Fixed invalid or overlapping task input/output declarations that prevented reliable up-to-date behavior.
- Fixed subprocess diagnostics that could expose environment values through paths, output-boundary fragments, nested
  stacktrace causes, or unbounded redaction metadata.
- Fixed Windows wrapper invocation, executable naming, ZIP handling, native fixture execution, and ordinary compiler
  argument preservation.
- Fixed CI lanes that labeled—but did not actually execute—the intended JDK/Gradle consumer pairs.
- Fixed the real-runtime smoke test so it exercises the production managed resolver, checksum, extraction, dependency
  installation, Java compilation, and application execution paths.
- Fixed the version catalog plugin alias so it resolves `dev.elide` version `1.0.0` rather than the Elide runtime version.

### Removed

- Removed the `elide-javac` shim and its CI setup script.
- Removed the requirement to preinstall Elide for managed-mode builds.
- Removed normal obsolete tests that depended on the developer's real `PATH`, a pre-created Java-home shim, or live
  downloads during the normal test lifecycle.
- Removed the legacy smoke implementation that downloaded and executed an archive independently of the plugin's managed
  runtime code.

### Security

- Managed archives are authenticated with their published SHA-256 digest before extraction.
- Archive extraction rejects entries that escape the staging directory and refuses symbolic links.
- Cache publication is serialized and staged so concurrent or failed builds cannot publish a partial runtime as valid.
- Failure diagnostics redact inherited environment values without printing the environment and use fixed memory bounds.
- CI actions are pinned to immutable commits and scheduled runtime smoke jobs use blocking, explicitly allow-listed
  egress.

### Compatibility notes

- Consuming projects require Java 17 or newer and Gradle 7.6.4 or newer; they do not need to adopt this repository's
  Gradle 9.7.1 wrapper.
- The pinned Elide release does not publish a macOS amd64 archive. Intel macOS remains usable through `PATH` or an
  explicit `elideBin`, but `MANAGED` is unavailable for that release.
- Windows managed runtimes currently support amd64 and use `bin/elide.exe` from `elide.windows-amd64.zip`.
- `resolveElideFromPath` remains source-compatible but is deprecated in favor of `runtimeMode`.

## [1.0.0] - 2025-06-02

### Added

- Published the initial stable Gradle plugin and version catalog at version `1.0.0`.
- Added configurable extension properties for dependency installation, Maven integration, Java compilation, project
  integration, manifest selection, debug output, and verbose output.
- Added GitHub Actions build, pull-request, and push workflows.
- Added the `gradle.elide.dev` edge worker used to serve versioned remote bootstrap scripts.
- Added local and remote example projects and expanded documentation of the original compiler and dependency-resolution
  integration.

### Changed

- Updated the remote example wrapper and bootstrap metadata for the stable release.
- Expanded the original installation documentation around the then-required `JAVA_HOME/bin/elide-javac` shim.

### Fixed

- Fixed CI setup and permissions for the original Java compiler shim.
- Added CI diagnostics and adjusted harden-runner behavior for the initial release workflow.

## [1.0.0-beta5] - 2025-05-31

### Added

- Added the initial Elide Gradle plugin and `elideRuntime` version catalog.
- Added `elide install` integration and the generated `.dev/dependencies/m2` Maven repository.
- Added Java compilation through Elide's `javac` support.
- Added initial executable lookup through `PATH` and a local Elide installation.
- Added the first plugin extension, functional test, unit-test scaffold, Gradle wrappers, and local/remote example projects.
- Added the remote `elide.gradle.kts` bootstrap script.

[Unreleased]: https://github.com/elide-dev/gradle/compare/1.0.0...HEAD
[1.0.0]: https://github.com/elide-dev/gradle/compare/1.0.0-beta5...1.0.0
[1.0.0-beta5]: https://github.com/elide-dev/gradle/releases/tag/1.0.0-beta5
