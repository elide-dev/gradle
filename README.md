## Elide Gradle Plugin

Experimental plugin for using [Elide](https://github.com/elide-dev/elide) from within Gradle.

### Installation

Make sure to [install Elide](https://docs.elide.dev/installation.html) before proceeding. In GHA, use our
[`elide-dev/setup-elide`](https://github.com/elide-dev/setup-elide) action to install Elide.

1) Create the `javac` shim in your `JAVA_HOME`:

   **`$JAVA_HOME/bin/elide-javac`**
    ```bash
    #!/usr/bin/env bash
    exec elide javac -- "${@}"
    ```

    Also, make sure to mark it as executable:
    ```bash
    chmod +x $JAVA_HOME/bin/elide-javac
    ```

2) Install and use the plugin as shown below.
3) **That's it! Enjoy faster dependency resolution and Java compilation.**

> [!NOTE]
> We hope to eliminate the `JAVA_HOME` shim soon.

### Usage

**`gradle.properties`**
```properties
elidePluginVersion=latest
```

**`settings.gradle.kts`**
```kotlin
// Use `latest` for the latest version, or any other tag, branch, or commit SHA on this project.
val elidePluginVersion: String by settings
apply(from = "https://gradle.elide.dev/$elidePluginVersion/elide.gradle.kts")
```

**`build.gradle.kts`**
```kotlin
plugins {
  // The `elideRuntime` catalog is added for you. Add the plugin like this:
  alias(elideRuntime.plugins.elide)
}

// Settings here apply on a per-project basis. See below for available settings; all properties
// are optional, and you don't need to include this block at all if you are fine with defaults.
elide {
    // specifies to use only locally installed Elide
    binary.useLocalOnly()
    // specifies to use a local version if version is the same
    binary.useLocalIfApplicable(version = "1.0.0-beta6")
    // or
    binary.useProjectOnly(version = null)
}
```

### What's this?

Elide is a runtime and batteries-included toolchain for Kotlin/Java, Python, JavaScript, and TypeScript, that can be
used as a drop-in replacement for `javac` (among other tools).

Elide builds `javac` as a native image and includes it within the Elide binary. This plugin changes your Gradle build
(as applicable) to use Elide's toolchain facilities instead of Gradle's built-in ones.

The result can be a significant performance improvement for **fetching dependencies** and **compiling code**.

Learn more about Elide at [elide.dev](https://elide.dev).

### Features

> [!NOTE]
> Elide is in beta, and this plugin is experimental. Use at your own risk. Please report any issues you encounter.

- [x] Provide a Gradle plugin
- [x] Provide a Gradle Catalog
- [x] Support for `elide install` as Gradle's Maven resolver
- [x] Support for `elide javac -- ...` as Gradle's Java compiler
- [x] Use Elide from the user's `PATH`
- [x] Use a local copy of Elide within the project
- [ ] Gradle-level Elide download cache
- [ ] Ability to pin Elide version
- [ ] Support the configuration cache
- [ ] Race-and-report vs. `javac`
- [ ] Augment project metadata for reporting
- [ ] Generate dependency manifests

### How does it work?

[Elide](https://github.com/elide-dev/elide) is a [GraalVM](https://graalvm.org) native image which functions as a Node-
like runtime. It speaks multiple languages, including Java, Kotlin, Python, JavaScript, TypeScript, WASM, and Pkl.

In addition to features which run code (i.e. the runtime!), Elide _also_ is a full batteries-included toolchain for
supported languages, including:

- A drop-in replacement for `javac` and `kotlinc`
- A drop-in replacement for `jar` and `javadoc`
- Maven-compatible dependency resolution and fetching

This plugin configures your Gradle build to use Elide's dependency and/or compile features instead of Gradle's.

#### Compiling Java with Elide + Gradle

Gradle's `JavaCompile` tasks are configured to use Elide through `isFork = true` and `forkOptions.executable`. These
point to a shim in the `JAVA_HOME` which invokes `elide javac -- ...` instead of `javac ...`.

As a result, JIT warmup is entirely skipped when compiling Java. **Projects under 10,000 classes may see better compiler
performance, in some cases up to 20x faster than stock `javac`.**

#### Fetching Dependencies with Elide + Gradle

Elide resolves and fetches Maven dependencies with identical semantics to Maven's own resolver, but again in a native
image, and with an optimized resolution step (through the use of a checked-in lockfile).

When activated for use with Gradle, a few changes are made to your build:

- **An invocation of `elide install`** is added before any Java compilation tasks.
- **Gradle is configured for a local Maven repo** at `.dev/dependencies/m2`, which is where Elide puts JARs.
- Thus, when Gradle resolves dependencies, they are _already on disk_ and ready to be used in a classpath.

In this mode, dependencies are downloaded once and then can be used with both Elide and Gradle.

> [!WARNING]
> Fetching dependencies with Elide currently requires an `elide.pkl` manifest listing your Maven dependencies. This will
> change in the future.
