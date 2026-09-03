# Gradle Plugin Hardening Design

## Purpose

Modernize the Elide Gradle plugin repository without unnecessarily raising the requirements imposed on builds that consume the published plugin. The repository build will use current tooling, while the published plugin will maintain an explicit, tested compatibility floor.

## Compatibility Contract

The repository build uses Gradle 9.7.1. Published plugin classes target Java 17 and avoid Gradle APIs introduced after Gradle 7.6.4.

The supported consumer matrix is:

- Gradle 7.6.4 on Java 17.
- Gradle 8.14.5 on Java 17 and Java 24.
- Gradle 9.7.1 on Java 17 and Java 26.

Linux, macOS, and Windows are first-class platforms. The build verifies the plugin with TestKit against the consumer matrix rather than inferring consumer compatibility from the repository wrapper version.

## Build Tooling

The wrapper moves to Gradle 9.7.1 and records the official distribution SHA-256. Build plugins and test libraries move to their latest stable compatible versions, including Plugin Publish 2.1.1, Gradle Download Task 5.7.0, and JUnit 6.1.3.

Dependency and plugin versions live in a Gradle version catalog. Dependency locking covers resolvable build configurations. Repository policy rejects unexpected project-level repositories. The build continues to publish the Gradle plugin and Elide catalog, and includes a local publication smoke test.

## Runtime Resolution

Runtime resolution is a dedicated component with one output: a validated executable path. Plugin application, platform detection, release location, download, verification, extraction, and executable selection are separate responsibilities.

The extension supports three modes:

- `AUTO`: use an explicitly configured executable, then a compatible executable on `PATH`, then the managed pinned runtime.
- `PATH`: use an explicitly configured executable or `PATH`; never download.
- `MANAGED`: use the configured managed version; never silently select an installed version.

Existing extension properties remain source-compatible. New properties expose the runtime mode and managed version. `elideBin` remains the explicit executable override. The repository defaults its managed version to the exact nightly `1.5.1+20260903`; consumers may override that version without changing the plugin version.

Runtime selection does not execute subprocesses during configuration. It uses Gradle providers for environment and property inputs so configuration-cache reuse observes relevant changes.

## Managed Runtime

Managed distributions are cached under Gradle User Home by exact version, operating system, and architecture. Supported release assets are:

- `elide.linux-amd64.tgz`
- `elide.linux-arm64.tgz`
- `elide.macos-amd64.tgz`
- `elide.macos-arm64.tgz`
- `elide.windows-amd64.zip`

The resolver downloads the matching release archive and its published `.sha256` file. It verifies SHA-256 before extraction, extracts into a temporary sibling directory, validates the expected executable, and atomically promotes the completed runtime into the cache. Failed or interrupted preparation cannot leave a cache entry that appears valid.

Concurrent builds coordinate preparation using Gradle task output semantics and an exclusive `FileChannel` lock stored beside the versioned cache directory. An existing validated cache entry is reused. Offline mode uses such an entry or fails before task execution with a message naming the missing version and cache path.

The managed-runtime task graph is lazy. Merely applying the plugin performs no network request and no extraction. Tasks that invoke Elide depend on runtime preparation. `PATH` mode never creates managed-runtime tasks that can access the network.

## Java Compilation Integration

The plugin no longer creates or modifies files beneath `JAVA_HOME`. Each configured `JavaCompile` task forks Elide directly using the resolved executable and passes the `javac --` prefix required by Elide.

The compiler executable and arguments are configured through provider-backed task configuration compatible with Gradle 7.6.4. The plugin preserves user compiler arguments and task dependencies. Direct invocation is the only supported compiler path; the obsolete shim path is removed.

## Dependency Installation

Elide dependency installation remains opt-in according to the existing extension and manifest behavior. The plugin registers preparation tasks without executing `elide install` during configuration. Java compilation tasks depend on installation only when installation is enabled.

The local Maven repository is registered only when Maven integration is active. Paths are derived from Gradle layout APIs and declared as task inputs and outputs. Failures include the Elide executable, project directory, exit code, and captured standard error without leaking environment secrets.

## Error Handling

Failures identify the responsible boundary and an actionable remedy. Distinct messages cover:

- Unsupported operating-system or architecture combinations.
- Missing explicit or `PATH` executables.
- Release or checksum assets that do not exist.
- Checksum mismatches.
- Archive extraction or atomic promotion failures.
- Missing cached distributions in offline mode.
- Elide subprocess failures.

The plugin never reports a permission problem unless the failed filesystem operation actually produced one. Temporary files are removed after failed preparation when safe to do so.

## Testing

Fast unit tests cover runtime-mode precedence, platform and asset mapping, version and checksum parsing, cache paths, offline decisions, and error construction.

TestKit functional tests cover:

- Explicit executable selection.
- Successful and missing `PATH` selection.
- Managed first-run download, checksum verification, extraction, and reuse.
- Offline cache hit and miss.
- Checksum mismatch rejection.
- Linux, macOS, and Windows asset mapping.
- Java compilation through direct `elide javac --` invocation.
- Dependency installation task wiring.
- Configuration-cache storage and reuse.
- Repeated-build up-to-date behavior.

Tests use local fixture HTTP servers and fake executable fixtures for deterministic behavior. Normal unit and functional tests do not download a real Elide distribution. A separate smoke test validates the currently pinned real release and may run on a scheduled or explicitly selected CI lane.

Consumer compatibility tests execute representative plugin builds with Gradle 7.6.4, Gradle 8.14.5, and Gradle 9.7.1. The repository build also runs plugin validation and a local Maven/Plugin Portal publication smoke test.

## Continuous Integration

The primary CI matrix covers Ubuntu, macOS, and Windows. Java 17 validates the consumer floor. Additional lanes run compatible current JDKs with current Gradle. CI runs the wrapper, not an independently installed Gradle version.

GitHub Actions use current stable releases pinned to immutable commit SHAs. Wrapper validation remains enabled. Gradle caching is enabled with read-only behavior on untrusted or non-default branches. Dependabot tracks Gradle and GitHub Actions updates.

A scheduled smoke workflow verifies the pinned Elide nightly asset and checksum on every supported platform without making ordinary pull requests dependent on an external nightly service.

## Documentation and Migration

The README describes the compatibility contract, `AUTO`, `PATH`, and `MANAGED` modes, version pinning, cache location, offline behavior, and platform support. It removes the requirement to place a shim in `JAVA_HOME`.

Migration notes explain that existing PATH-based builds continue to work under `AUTO`, reproducible builds should select `MANAGED`, and network-restricted builds should select `PATH` or pre-populate the managed cache. Examples use lazy Gradle property assignment and exact runtime versions.

## Acceptance Criteria

- The repository builds with the Gradle 9.7.1 wrapper.
- Published classes target Java 17.
- Compatibility tests pass on Gradle 7.6.4, the current Gradle 8.14 patch, and Gradle 9.7.1.
- Unit and functional tests pass on Linux, macOS, and Windows CI.
- Applying the plugin performs no network, subprocess, or `JAVA_HOME` mutation during configuration.
- Both installed and managed Elide runtimes can be selected explicitly, and `AUTO` follows the documented precedence.
- Managed archives are checksum-verified and safely cached.
- Configuration-cache reuse succeeds for representative PATH and managed builds.
- The pinned real Elide nightly smoke test validates `1.5.1+20260903`.
- Documentation contains no manual JDK shim requirement.
