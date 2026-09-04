# Elide Settings Plugin Design

**Status:** Proposed

**Date:** 2026-09-03

**Base branch:** `cleanup-1.1.0`

**Related:** [Issue #1](https://github.com/elide-dev/gradle/issues/1),
[draft PR #2](https://github.com/elide-dev/gradle/pull/2)

## Purpose

Replace the remote settings script with a published Gradle settings plugin and make Elide runtime selection a
build-wide convention. A multi-project build configures its Elide runtime once in `settings.gradle(.kts)` and explicitly
opts participating projects into the existing project plugin.

The design should make Elide feel native to Gradle: configuration is typed and lazy, expensive runtime work is shared,
project boundaries remain isolated, and applying either plugin performs no network, process, or filesystem work.

## Decision

Publish two binary plugins from the existing plugin artifact:

- `dev.elide.settings`, a `Plugin<Settings>` that owns build-wide runtime conventions.
- `dev.elide`, the existing `Plugin<Project>` that owns project tasks and integrations.

The settings plugin does not apply the project plugin. Each participating project applies `dev.elide` explicitly. This
avoids hidden cross-project configuration and remains compatible with Gradle's Configuration Cache and Isolated
Projects model.

The Gradle plugin release version and the Elide runtime/library version remain independent. The plugin release is
selected in the settings `plugins` block; the Elide version is selected in the settings extension, directly or by a
version-catalog reference.

## Consumer API

### Direct version

```kotlin
// settings.gradle.kts
plugins {
    id("dev.elide.settings") version "1.1.0"
}

elide {
    runtime {
        mode = ElideRuntimeMode.MANAGED
        version = "1.5.1+20260903"
    }
}
```

### Version catalog

Given this consumer catalog:

```toml
# gradle/libs.versions.toml
[versions]
elide = "1.5.1+20260903"
```

the settings configuration is:

```kotlin
elide {
    runtime {
        mode = ElideRuntimeMode.MANAGED
        versionFrom("libs", "elide")
    }
}
```

`versionFrom` stores a catalog name and version alias. It does not parse TOML or depend on generated Kotlin accessors.
Each opted-in project resolves the reference through Gradle's public `VersionCatalogsExtension`, which is available at
the project lifecycle boundary.

Direct versions and catalog references are mutually exclusive. The extension also accepts a `Provider<String>` for
builds that source the version from a Gradle property or another lazy input.

### Project opt-in

```kotlin
// service/build.gradle.kts
plugins {
    id("dev.elide")
    java
}

elide {
    install = true
    compiler = true
}
```

The canonical DSL uses affirmative, concise names rather than implementation-oriented `enable...` names. Scalar DSL
setters delegate to provider-backed internal state so both Kotlin and Groovy scripts can use normal assignment syntax.
Provider-accepting methods remain available for values that must stay externally lazy.

The current properties remain as deprecated 1.x compatibility aliases and mutate the same backing state:

| Canonical API | Compatibility alias |
| --- | --- |
| `install` | `enableInstall` |
| `compiler` | `enableJavaCompiler` |
| `maven` | `enableMavenIntegration` |
| `runtime.mode` | `runtimeMode` |
| `runtime.version` | `runtimeVersion` |
| `runtime.executable` | `elideBin` |

Project-level runtime assignments are retained for compatibility and as deliberate overrides. New builds should put
runtime selection in settings.

## Configuration Model

`dev.elide.settings` creates an `elide` settings extension containing a nested runtime specification. It registers one
named, parameterized shared build service whose parameters are wired to that specification with Gradle providers. The
service is a configuration carrier and coordination boundary; it does not perform downloads or run Elide.

`dev.elide` creates an `elide` project extension. Its provider-backed properties use these conventions, in order:

1. An explicit project value.
2. The build-wide setting, whether direct or catalog-backed.
3. The existing standalone default.

Explicit settings values and catalog selectors are alternatives at the same precedence level, not two fallback layers.
A conflict is an error. When the settings plugin is absent, the project plugin registers or uses a standalone service
configuration with today's defaults, preserving existing builds.

The shared service parameters are configured only in `registerIfAbsent` and are finalized before they are consumed.
Projects do not inspect `Gradle` extensions, read root-project mutable state, or configure other projects.

## Runtime and Task Lifecycle

Plugin application only creates extensions, providers, service registration, and lazy task wiring. It must not:

- contact a network service;
- start Elide or another subprocess;
- create `.dev`, a runtime cache entry, or another directory;
- resolve a dependency or version-catalog artifact;
- eagerly realize tasks or provider values.

The project plugin reacts to capabilities with `pluginManager.withPlugin(...)` and configures matching task collections
with `configureEach`. It does not use `afterEvaluate`, `allprojects`, or `subprojects`.

Only tasks that invoke Elide acquire the selected runtime. `PATH` never registers or executes managed download work.
`MANAGED` uses the exact configured version. `AUTO` retains the documented precedence of explicit executable, PATH,
then managed fallback.

Managed runtime preparation remains keyed by exact version and platform in Gradle User Home. All opted-in projects use
the same service-coordinated preparation state. Existing filesystem locking, checksum verification, staging, and atomic
promotion remain the correctness boundary for concurrent Gradle invocations. Within one multi-project invocation,
parallel consumers converge on one preparation operation rather than independently downloading or extracting the same
runtime.

Project-scoped work stays project-scoped. In particular, `elide install` keeps the owning project's manifest, inputs,
working directory, and generated dependency repository. Shared runtime selection must not merge project manifests or
task outputs.

## Repository and Dependency Integration

The settings plugin replaces the remote `apply(from = "https://gradle.elide.dev/...")` bootstrap path. Through public
Settings APIs, it adds the named Elide Maven repository to `dependencyResolutionManagement` with content restricted to
Elide's published groups. It cannot make itself resolvable retroactively: consumers obtain the settings plugin through
the Plugin Portal or declare the Elide repository in `pluginManagement` before the settings `plugins` block.

The project-generated `.dev/dependencies/m2` repository remains tied to the opted-in project's `maven` convention and
is never created during configuration. The structural PR preserves its current behavior; redesigning Elide manifest
dependencies as native Gradle dependency metadata is a separate concern.

The existing `elide-gradle-catalog` publication remains available during the 1.x line for compatibility, but the settings
plugin does not inject a catalog automatically. Automatic injection would create surprising accessors and could diverge
from a catalog chosen by the consumer. A consumer-owned version catalog is the supported catalog source for
`versionFrom`.

Draft PR #2's goal of aligning Elide library coordinates to the runtime version is sound, but a bespoke dependency DSL
must not replace Gradle dependency declarations or version catalogs. Any library-coordinate convenience added in a
later stack must return Gradle providers/dependency notation, preserve normal conflict resolution, and use this design's
single resolved Elide version. The draft's Java-to-Kotlin rewrite, Javadoc integration, custom CLI execution API, and
unrelated task expansion are outside this structural PR.

## Compatibility

The published classes continue to target Java 17 and the supported consumer floor remains Gradle 7.6.4. The settings
and project plugins must use only public APIs available at that floor. Newer Gradle behavior, including Isolated
Projects verification, is tested on the current Gradle lane without making newer APIs mandatory at runtime.

Existing builds that apply only `dev.elide` keep working. Existing extension names compile and configure the same state,
but emit deprecation guidance toward the concise API. Applying only `dev.elide.settings` does not change any project.

The settings plugin and project plugin ship in the same implementation artifact. Applying the settings plugin makes the
matching project plugin implementation available to participating projects; consumers do not repeat the plugin release
version in every module.

## Validation and Errors

Configuration errors identify the bad setting and its remedy. Distinct diagnostics cover:

- both a direct/provider version and `versionFrom` being configured;
- a named catalog not existing in an opted-in project;
- a version alias not existing in that catalog;
- `MANAGED` reaching execution without a concrete version;
- an unsupported platform or missing PATH executable;
- mutation of finalized build-wide configuration.

Catalog failures occur only for projects that apply `dev.elide`; an unrelated project does not fail because it does not
consume Elide configuration. Secrets or complete environment values are never included in diagnostics.

## Verification

Unit tests cover source precedence, direct/provider/catalog alternatives, compatibility aliases, runtime-mode behavior,
and diagnostic construction.

TestKit functional tests cover:

- direct settings configuration in single- and multi-project builds;
- catalog-backed settings configuration;
- explicit opt-in, including an untouched non-opted-in sibling;
- project override precedence;
- standalone `dev.elide` behavior without the settings plugin;
- Kotlin and Groovy DSL assignment, including `install = true`;
- deprecated DSL aliases on Gradle 7.6.4;
- one managed preparation under parallel multi-project consumption;
- PATH's no-download guarantee;
- Configuration Cache storage and reuse;
- Isolated Projects on the current Gradle 9 lane;
- no configuration-time network, process, dependency resolution, or filesystem mutation.

The existing Gradle 7.6.4, 8.14.5, and 9.7.1 compatibility lanes remain required. Plugin validation and the real-runtime
smoke lane continue to cover publication metadata and the actual Elide release contract.

## Alternatives Considered

### Store configuration on `Gradle` or the root project

This is superficially simple but makes opted-in projects read mutable owner/root state. It conflicts with project
isolation and creates invisible coupling, so it is rejected.

### Bridge configuration through Gradle properties

This works across old Gradle versions but replaces a typed model with global string keys, weak precedence, and poor DSL
ergonomics. Gradle properties remain valid inputs to the typed extension, not its internal transport.

### Apply `dev.elide` automatically to every project

This removes one line per module but surprises non-Java and aggregation projects, obscures plugin ownership, and pushes
the design toward cross-project lifecycle callbacks. Explicit application is more conventional and is required by this
design.

## Acceptance Criteria

- Consumers install `dev.elide.settings` without a remote applied script.
- One settings block selects the Elide runtime for every opted-in project.
- The runtime version may be direct, provider-backed, or selected from a consumer version catalog.
- Project builds use concise assignment syntax such as `install = true`.
- No project is opted in implicitly and non-participating projects remain untouched.
- Multi-project runtime preparation is shared and safe under parallel execution.
- Plugin application is configuration-only and both Configuration Cache and Isolated Projects checks pass.
- Standalone project-plugin usage and deprecated 1.x DSL names remain functional.
- The full supported Gradle/JDK consumer matrix remains green.
