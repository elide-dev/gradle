# Changelog

All notable changes to the Elide Gradle Plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This project does not currently assign
semantic versions to unreleased changes, so work completed after `1.0.0` remains under **Unreleased** until the next
release is cut.

## [Unreleased]

### Added

- Added a version check for `PATH`-selected runtimes. A candidate must report at least the configured `runtime.version`,
  read from the start of its `--version` output and compared on the semantic version only. Under `AUTO` an out-of-date
  installed Elide is skipped, with the rejected path and both versions logged, and the managed runtime is provisioned
  instead of compiling with it; under `PATH` the build fails and names both versions. An explicit `runtime.executable` is
  never probed, and a `runtime.version` that cannot be resolved leaves the check disabled rather than failing a build
  that never needed a managed runtime.
- Documented the `elide.builder.javac.enable` and `elide.builder.maven.install.enable` Gradle properties, which override
  the extension in both directions, and added functional coverage for them.
- Added relocatable Java compilation cache entries while preserving Gradle's incremental analysis and source removal.
- Added opt-in build-scoped Elide compiler workers using the Bazel protobuf protocol and digest-keyed classpath reuse.
- Added cacheable staged Java/Kotlin formatting, non-mutating `elideCheckFormat`, and explicit `elideFormat` application.
- Added explicit Gradle dependency ownership and cacheable per-source-set exports of resolved coordinates and SHA-256 hashes.
- Required real native CI coverage for both compiler modes, HTTP caches, worker recovery, formatters, and dependency
  verification; corrected a Gradle 7.6 instrumentation failure in the persistent compiler launcher.

- Added the `dev.elide.settings` plugin for build-wide Elide runtime policy with explicit per-project opt-in.
- Added direct, provider-backed, and version-catalog runtime version sources through the settings `elide.runtime` DSL.
- Added shared managed-runtime coordination for parallel multi-project builds and Isolated Projects coverage.
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
- Added dependency update automation, workflow concurrency controls, and immutable action pins. Scheduled runtime smoke
  lanes additionally restrict network egress to an explicit allow-list; pull-request and push lanes run the runner
  hardening in audit mode, which records outbound traffic without blocking it.
- Added [runtime management](docs/runtime-management.md) and [compatibility and migration](docs/compatibility.md)
  documentation.

### Changed

- Unified runtime selection on `ElideRuntimeLocator.locate`, which the resolver previously bypassed in favor of a
  duplicated inline copy of the same precedence rules. The documented precedence table is now tested against the code
  the plugin actually runs.
- Declared configuration-cache support in both published plugin descriptors, which previously reported `UNDECLARED`.
- Read `elide.builder.javac.enable` through a Gradle provider rather than eager, configuration-cache-untracked
  `findProperty`.
- Pinned the example project wrappers to supported Gradle versions with distribution checksum verification.
- Replaced remote-script installation with the conventional settings plugin and concise `install`, `compiler`, `maven`,
  and nested `runtime` project configuration.
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

- Fixed failure diagnostics being destroyed by over-broad redaction. Every inherited environment value was previously
  substituted, so ordinary short values such as `DISPLAY=:1` or `LC_TIME=C` mangled line numbers and identifiers in a
  failing command's output. A value is redacted now when its name says it holds a credential, or when the value itself
  carries one regardless of its name, and values that cannot hold a recoverable secret -- a couple of bytes, a boolean,
  a small integer -- are never substituted.
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

- Removed the remotely applied `elide.gradle.kts` bootstrap script. The `gradle.elide.dev` worker that served it now
  returns HTTP 410 with a notice pointing at the settings plugin; it previously still served the superseded script for
  pinned versions.
- Removed the `elide-javac` shim and its CI setup script.
- Removed the requirement to preinstall Elide for managed-mode builds.
- Removed normal obsolete tests that depended on the developer's real `PATH`, a pre-created Java-home shim, or live
  downloads during the normal test lifecycle.
- Removed the legacy smoke implementation that downloaded and executed an archive independently of the plugin's managed
  runtime code.

### Security

- Managed archives are authenticated with their published SHA-256 digest before extraction.
- Archive entry metadata is inspected before extraction, and entries naming an absolute path or a `..` segment, as well 
  as symbolic and hard links, are refused. The previous checks ran against the already-extracted tree, where Gradle had 
  already rejected a traversing archive with a generic "might be corrupted" message and had flattened symbolic links 
  into empty regular files, so neither documented guard could fire.
- Cache publication is serialized and staged so concurrent or failed builds cannot publish a partial runtime as valid.
- Failure diagnostics redact inherited environment values that look like credentials, by sensitive name segment or by
  value shape (known token prefixes, and URLs carrying inline credentials), without printing the environment and using
  fixed memory bounds.
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
