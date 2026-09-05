## Elide Gradle Plugin

The Elide Gradle Plugin lets Gradle use Elide for Java compilation and optional Maven dependency installation. Runtime
selection is explicit, reproducible, and safe to use with the configuration cache.

### Installation

The remote bootstrap script adds the plugin repository and the version catalog. Pin the plugin release in
`gradle.properties` (or use `latest` while evaluating the plugin):

**`gradle.properties`**

```properties
elidePluginVersion=1.0.0
```

**`settings.gradle.kts`**

```kotlin
val elidePluginVersion: String by settings
apply(from = "https://gradle.elide.dev/$elidePluginVersion/elide.gradle.kts")
```

**`build.gradle.kts`**

```kotlin
plugins {
    alias(elideRuntime.plugins.elide)
    java
}

elide {
    enableJavaCompiler.set(true)
}
```

Applying the plugin only registers Gradle configuration and task wiring. It does not download or execute Elide, and it
does not create or modify any file in `JAVA_HOME`. Managed preparation is registered for a managed selection and runs
when `prepareElideRuntime` is invoked directly or when a consuming task depends on it. Java compilation launches the
selected executable as `elide javac -- ...` through a small Java entry point; no `elide-javac` shim is required.

### Runtime selection

The default `AUTO` mode checks these sources in order:

1. An explicit `elideBin` executable, when it is a usable file.
2. A compatible Elide executable on `PATH`.
3. The managed runtime in Gradle User Home, using the configured `runtimeVersion`.

Use `PATH` to forbid managed runtime archive/checksum downloads and require an installed runtime. Other opt-in tasks such
as `elide install` may still resolve project dependencies. Use `MANAGED` to require the exact configured version and never
silently use an installed runtime. The pinned default managed version is `1.5.1+20260903`; set `runtimeVersion` to another
release when that release publishes a matching asset.

```kotlin
import dev.elide.gradle.ElideRuntimeMode

elide {
    runtimeMode.set(ElideRuntimeMode.MANAGED)
    runtimeVersion.set("1.5.1+20260903")
}
```

For a local or non-standard executable, configure `elideBin` explicitly:

```kotlin
import dev.elide.gradle.ElideRuntimeMode

elide {
    runtimeMode.set(ElideRuntimeMode.PATH)
    elideBin.set(layout.projectDirectory.file("tools/elide"))
}
```

See [runtime management](docs/runtime-management.md) for cache paths, checksums, offline builds, supported assets, and
troubleshooting. See [compatibility and migration](docs/compatibility.md) for consumer versions and migration from the
old manual `elide-javac` setup.

### Dependency installation

Set `enableInstall.set(true)` to run `elide install` before Java compilation. When Maven integration is enabled, the plugin
adds the generated `.dev/dependencies/m2` repository for dependency resolution. The manifest defaults to `elide.pkl` and
can be changed with `manifest`:

```kotlin
elide {
    enableInstall.set(true)
    enableMavenIntegration.set(true)
    manifest.set(layout.projectDirectory.file("elide.pkl"))
}
```

Installation is opt-in and runs as a Gradle task, not while the plugin is being applied. The Elide command runs from the
project directory; failures identify the executable, working directory, exit code, and bounded diagnostic output.

### What this plugin does

[Elide](https://elide.dev) is a native toolchain that includes a `javac` replacement and Maven-compatible dependency
resolution. When enabled, this plugin configures Gradle's `JavaCompile` tasks to launch Elide and optionally
populates a local Maven repository through `elide install`. Gradle still owns task scheduling, inputs, outputs, and
dependency resolution semantics outside the opt-in Elide integration.

The repository publishes a Gradle plugin and a version catalog. The project build and published plugin classes are
covered by the compatibility matrix in [docs/compatibility.md](docs/compatibility.md).

### Run the local example

With a JDK 17 or newer installed, run:

```bash
cd example-project
./gradlew clean build run
```

The example uses the plugin from this checkout, compiles with Elide, and prints `Hello, World!`. Gradle resolves Guava
and its transitive dependencies from Maven Central and targets Java 17 bytecode. Configuration caching is enabled. No
manual `elide install` is needed; if Elide is absent from PATH, the plugin prepares the pinned managed runtime. After the first successful build,
`./gradlew clean build run --offline` can reuse the downloaded dependencies and runtime.

The greeting uses Guava's `SettableFuture`, so building and running it checks both direct and transitive dependencies.
`./gradlew :elide-gradle-plugin:realRuntimeSmoke` from the repository root also tests a fresh copy of this example with
a managed runtime, configuration-cache reuse, and an offline rebuild.

The optional Elide-owned dependency installation mode above requires separate preparation when dependencies exist only
in `.dev/dependencies/m2`: run `./gradlew elideInstall` before invoking `build` or `run`. Gradle can resolve those
dependencies while constructing the task graph or storing the configuration cache, before task execution begins
(see Gradle's [dependency substitution rules](https://docs.gradle.org/current/userguide/resolution_rules.html#sec:dependency_substitution_rules)).
Changes to the Elide manifest require another preparation invocation. The default example uses Gradle-owned dependency
resolution so a fresh checkout works in one command.
