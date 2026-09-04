# Compatibility and migration

The published plugin emits Java 17 bytecode and uses Gradle APIs available at the consumer floor. The repository wrapper
is Gradle 9.7.1 for developing and verifying this project; it is not the minimum Gradle version for a consumer build.

## Supported consumer matrix

| Consumer Gradle | Supported JDKs | Notes |
| --- | --- | --- |
| 7.6.4 | Java 17 | Consumer compatibility floor |
| 8.14.5 | Java 17, Java 24 | Current Gradle 8 lane |
| 9.7.1 | Java 17, Java 26 | Repository wrapper/current Gradle 9 lane |

Java 17 is the published bytecode and compatibility baseline. `compatibilityTest` always launches its literal Gradle
7.6.4, 8.14.5, and 9.7.1 TestKit consumers on Java 17, independently of the JDK used to run the repository's own wrapper.
The opt-in `currentToolchainConsumerTest` covers the Java 24/Gradle 8.14.5 and Java 26/Gradle 9.7.1 pairs; it is not part
of normal `build` or `check` and requires the matching JDK plus
`-Pelide.currentToolchain.java=<version> -Pelide.currentToolchain.gradle=<version>`. CI supplies that JDK as both the
test JVM and nested consumer `JAVA_HOME`, then logs and asserts the nested Java and Gradle versions. Consumers should use
their normal Gradle wrapper and do not need to upgrade to Gradle 9.7.1 solely because this repository does.

Linux, macOS, and Windows are first-class plugin platforms. Managed assets for the pinned `1.5.1+20260903` release are
Linux amd64/arm64 TGZ, macOS arm64 TGZ, and Windows amd64 ZIP. The release has no macOS amd64 asset; Intel macOS users
can use `PATH` or an explicit `elideBin` runtime. See [runtime management](runtime-management.md) for the full asset and
cache contract.

## Migrating from a manual `elide-javac`

1. Remove the manually created `$JAVA_HOME/bin/elide-javac` (or `%JAVA_HOME%\\bin\\elide-javac`) file. The plugin now forks
   the selected Elide executable directly and never writes below `JAVA_HOME`.
2. Choose a runtime mode in `build.gradle.kts`:

   ```kotlin
   import dev.elide.gradle.ElideRuntimeMode

   elide {
       runtimeMode.set(ElideRuntimeMode.AUTO)
   }
   ```

   `AUTO` preserves an installed PATH workflow by checking explicit and PATH executables before managed fallback. Choose
   `PATH` to guarantee that a build never downloads a runtime, or `MANAGED` to require a versioned cache entry.
3. For reproducible builds, pin both the plugin version and the managed runtime version rather than using moving labels:

   ```kotlin
   import dev.elide.gradle.ElideRuntimeMode

   elide {
       runtimeMode.set(ElideRuntimeMode.MANAGED)
       runtimeVersion.set("1.5.1+20260903")
   }
   ```

   The plugin version (`dev.elide.gradle:elide-gradle-plugin`) and Elide runtime version are independent coordinates.
4. For an offline managed build, pre-populate the cache while connected:

   ```bash
   ./gradlew prepareElideRuntime
   ./gradlew --offline build
   ```

   The managed `runtimeMode` and `runtimeVersion` are configured in the preceding `build.gradle.kts` snippet; the
   `prepareElideRuntime` command takes no runtime-mode project property. The second command reuses the completed cache.
   If preparation is attempted offline without a completed entry, it fails with the exact version and cache path; see
   [offline behavior](runtime-management.md#offline-builds-and-network-guarantees). The repository's
   `-Pelide.runtime.mode=MANAGED` flag is only an opt-in guard for its real-runtime smoke task, not consumer plugin
   configuration.

Existing `resolveElideFromPath` assignments remain source-compatible but are deprecated. Prefer `runtimeMode` for new
builds. Existing PATH builds that remove the shim and leave Elide installed on PATH can use `AUTO` or `PATH`; existing
managed builds should set `MANAGED` and an explicit `runtimeVersion`.

## Java compilation and installation

When `enableJavaCompiler` is enabled, `JavaCompile` tasks fork the selected executable with the literal argument prefix
`javac --`, preserving Gradle's compiler arguments. When `enableInstall` is enabled, `elideInstall` runs as a task before
Java compilation and can populate `.dev/dependencies/m2` when Maven integration is enabled. Neither command runs while
the plugin is applied or during configuration.
