# Gradle Plugin Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modernize the repository build and make the published Elide Gradle plugin reliable, reproducible, cross-platform, configuration-cache-safe, and compatible with conservative consumer environments.

**Architecture:** The repository builds on current Gradle while emitted plugin classes target Java 17. Runtime selection is split into pure platform/location logic, a Gradle-aware resolver, and a managed preparation task; plugin application wires providers and task dependencies without configuration-time subprocesses, downloads, or JDK mutation.

**Tech Stack:** Gradle 9.7.1 Kotlin DSL, Java 17 plugin bytecode, JUnit 6.1.3, Gradle TestKit, Java `HttpClient`, Gradle `ArchiveOperations`/`FileSystemOperations`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-03-gradle-plugin-hardening-design.md`

## Global Constraints

- Repository wrapper: Gradle 9.7.1 with SHA-256 `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`.
- Published plugin bytecode: Java 17, independent of the daemon JDK.
- Consumer matrix: Gradle 7.6.4/Java 17; Gradle 8.14.5/Java 17 and 24; Gradle 9.7.1/Java 17 and 26.
- Runtime modes: `AUTO`, `PATH`, and `MANAGED`; `AUTO` precedence is explicit executable, PATH executable, managed runtime.
- Runtime pin: `1.5.1+20260903`; release base URL is `https://github.com/elide-dev/elide/releases/download`.
- Platforms: Linux amd64/arm64 (`tgz`), macOS amd64/arm64 (`tgz`), Windows amd64 (`zip`); all other combinations fail explicitly.
- Applying the plugin performs no network request, subprocess execution, or mutation beneath `JAVA_HOME`.
- Managed archives must pass their release-provided SHA-256 before atomic promotion into Gradle User Home.
- Existing extension properties remain source-compatible; the manual JDK shim is removed.
- Implement every behavioral change test-first and retain the observed red/green commands in the task report.

---

### Task 1: Modernize the repository build

**Files:**
- Create: `gradle/libs.versions.toml`
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `gradle/wrapper/gradle-wrapper.jar`
- Modify: `settings.gradle.kts`
- Modify: `elide-gradle-plugin/build.gradle.kts`
- Modify: `elide-gradle-catalog/build.gradle.kts`
- Create: `elide-gradle-plugin/gradle.lockfile`
- Delete: `elide-gradle-plugin/src/test/java/com/example/plugin/ElidePluginTest.java`
- Modify: `elide-gradle-plugin/src/functionalTest/java/com/example/plugin/ElidePluginFunctionalTest.java`

**Interfaces:**
- Produces: version aliases `pluginPublish`, `downloadTask`, and library alias `junitBom`; Java 17 main/test toolchains; JUnit Platform test tasks.
- Consumes: existing project/version properties and publication definitions.

- [ ] **Step 1: Add the centralized version catalog**

```toml
[versions]
plugin-publish = "2.1.1"
download-task = "5.7.0"
junit = "6.1.3"

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }

[plugins]
plugin-publish = { id = "com.gradle.plugin-publish", version.ref = "plugin-publish" }
download-task = { id = "de.undercouch.download", version.ref = "download-task" }
```

- [ ] **Step 2: Upgrade and regenerate the root wrapper**

Set `distributionUrl` to `https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip` and add:

```properties
distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
```

Run twice so properties and wrapper JAR are current:

```bash
./gradlew wrapper --gradle-version 9.7.1 --distribution-type bin
./gradlew wrapper --gradle-version 9.7.1 --distribution-type bin
```

- [ ] **Step 3: Apply catalog aliases and Java 17 output policy**

In `elide-gradle-plugin/build.gradle.kts`, use:

```kotlin
plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.download.task)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencyLocking {
    lockAllConfigurations()
}
```

In `settings.gradle.kts`, declare `mavenCentral()` in `dependencyResolutionManagement.repositories`, set `repositoriesMode` to `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, and remove the project-level `repositories` block. Preserve publishing, signing, functional-test source set, and the working `1.5.1+20260903` real-runtime smoke tasks.

- [ ] **Step 4: Migrate tests from JUnit 4 to Jupiter**

Replace `org.junit.Test` with `org.junit.jupiter.api.Test` and static imports with Jupiter assertions. Delete the assertion-free `ElidePluginTest`; Task 3 replaces it with behavior-level configuration tests after explicit executable selection exists.

```java
@Test
void canRunTasks() {
    BuildResult result = configuredRunner("tasks").build();
    assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
}
```

- [ ] **Step 5: Verify the build upgrade and bytecode target**

```bash
./gradlew :elide-gradle-plugin:dependencies --write-locks
./gradlew clean test functionalTest validatePlugins
javap -verbose -classpath elide-gradle-plugin/build/libs/elide-gradle-plugin-1.0.0.jar dev.elide.gradle.ElideGradlePlugin | rg "major version: 61"
```

Expected: all tasks pass and `javap` prints `major version: 61`.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml gradle/wrapper settings.gradle.kts elide-gradle-plugin/build.gradle.kts elide-gradle-plugin/gradle.lockfile elide-gradle-catalog/build.gradle.kts elide-gradle-plugin/src/test elide-gradle-plugin/src/functionalTest
git commit -m "build: upgrade Gradle and test toolchain"
```

---

### Task 2: Add the runtime model and platform mapping

**Files:**
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeMode.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeSource.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElidePlatform.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeSelection.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeLocator.java`
- Create: `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElidePlatformTest.java`
- Create: `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideRuntimeLocatorTest.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtensionConfig.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtension.java`

**Interfaces:**
- Produces: `ElideRuntimeMode`, `ElidePlatform.detect(String,String)`, `ElidePlatform.assetName()`, `ElideRuntimeLocator.locate(...)`, `ElideExtensionConfig.getRuntimeMode()`, and `getRuntimeVersion()`.
- Consumes: the global mode precedence, platform list, and runtime pin.

- [ ] **Step 1: Write failing platform tests**

Cover literal expectations:

```java
assertEquals("elide.linux-amd64.tgz", ElidePlatform.detect("Linux", "x86_64").assetName());
assertEquals("elide.linux-arm64.tgz", ElidePlatform.detect("Linux", "aarch64").assetName());
assertEquals("elide.macos-amd64.tgz", ElidePlatform.detect("Mac OS X", "amd64").assetName());
assertEquals("elide.macos-arm64.tgz", ElidePlatform.detect("Mac OS X", "arm64").assetName());
assertEquals("elide.windows-amd64.zip", ElidePlatform.detect("Windows 11", "x86_64").assetName());
assertEquals("elide.exe", ElidePlatform.detect("Windows 11", "amd64").executableName());
assertThrows(IllegalArgumentException.class, () -> ElidePlatform.detect("Windows 11", "arm64"));
assertThrows(IllegalArgumentException.class, () -> ElidePlatform.detect("Plan 9", "amd64"));
```

Run `./gradlew :elide-gradle-plugin:test --tests dev.elide.gradle.ElidePlatformTest` and confirm missing production types fail compilation.

- [ ] **Step 2: Implement immutable platform and selection types**

```java
public enum ElideRuntimeMode { AUTO, PATH, MANAGED }
public enum ElideRuntimeSource { EXPLICIT, PATH, MANAGED }

public record ElidePlatform(String os, String arch, String archiveExtension, String executableName) {
    public static ElidePlatform detect(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String normalizedOs = os.equals("linux") ? "linux"
            : os.equals("mac os x") ? "macos"
            : os.startsWith("windows") ? "windows"
            : throwUnsupported(osName, architecture);
        String arch = switch (architecture.toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64" -> "amd64";
            case "arm64", "aarch64" -> "arm64";
            default -> throwUnsupported(osName, architecture);
        };
        if (normalizedOs.equals("windows") && arch.equals("arm64")) {
            return throwUnsupported(osName, architecture);
        }
        return new ElidePlatform(
            normalizedOs,
            arch,
            normalizedOs.equals("windows") ? "zip" : "tgz",
            normalizedOs.equals("windows") ? "elide.exe" : "elide");
    }
    private static <T> T throwUnsupported(String os, String arch) {
        throw new IllegalArgumentException("Unsupported Elide platform: " + os + "/" + arch);
    }
    public String key() { return os + "-" + arch; }
    public String assetName() { return "elide." + key() + "." + archiveExtension; }
}

public record ElideRuntimeSelection(ElideRuntimeSource source, Path executable) {}
```

Normalize `x86_64`/`amd64` and `arm64`/`aarch64`; normalize macOS to `macos`, Linux to `linux`, and strings beginning with `windows` to `windows`.

- [ ] **Step 3: Write failing locator precedence tests**

Use `@TempDir` files and assert:

```java
assertEquals(EXPLICIT, locate(AUTO, Optional.of(explicit), List.of(pathBin), managed).source());
assertEquals(PATH, locate(AUTO, Optional.empty(), List.of(pathBin), managed).source());
assertEquals(MANAGED, locate(AUTO, Optional.empty(), List.of(emptyBin), managed).source());
assertEquals(MANAGED, locate(MANAGED, Optional.of(explicit), List.of(pathBin), managed).source());
assertThrows(IllegalStateException.class,
    () -> locate(PATH, Optional.empty(), List.of(emptyBin), managed));
```

Mutation target: swapping any precedence branch or allowing PATH mode to return managed must fail a test.

- [ ] **Step 4: Implement pure runtime location**

```java
public final class ElideRuntimeLocator {
    public static ElideRuntimeSelection locate(
        ElideRuntimeMode mode,
        Optional<Path> explicit,
        List<Path> pathDirectories,
        Path managedExecutable,
        ElidePlatform platform) {
        Optional<Path> explicitExecutable = explicit.filter(path -> usable(path, platform));
        Optional<Path> pathExecutable = pathDirectories.stream()
            .map(directory -> directory.resolve(platform.executableName()))
            .filter(path -> usable(path, platform))
            .findFirst();
        if (mode == ElideRuntimeMode.MANAGED) {
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable);
        }
        if (explicitExecutable.isPresent()) {
            return new ElideRuntimeSelection(ElideRuntimeSource.EXPLICIT, explicitExecutable.get());
        }
        if (pathExecutable.isPresent()) {
            return new ElideRuntimeSelection(ElideRuntimeSource.PATH, pathExecutable.get());
        }
        if (mode == ElideRuntimeMode.AUTO) {
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable);
        }
        throw new IllegalStateException("Elide PATH runtime was requested but no executable was found");
    }

    private static boolean usable(Path path, ElidePlatform platform) {
        return Files.isRegularFile(path) && (platform.os().equals("windows") || Files.isExecutable(path));
    }
}
```

An explicit or PATH candidate must be a regular executable file on Unix and a regular file on Windows. PATH lookup uses `platform.executableName()` and preserves directory order.

- [ ] **Step 5: Extend the public extension compatibly**

Add:

```java
Property<ElideRuntimeMode> getRuntimeMode();
Property<String> getRuntimeVersion();
```

Use conventions `AUTO` and `1.5.1+20260903`. Keep `getResolveElideFromPath()` but deprecate it; remove its default convention so an explicitly supplied legacy value can map `true` to `PATH` and `false` to `MANAGED` without overriding the new `AUTO` default.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew :elide-gradle-plugin:test
git add elide-gradle-plugin/src/main/java/dev/elide/gradle elide-gradle-plugin/src/test/java/dev/elide/gradle
git commit -m "feat: model Elide runtime selection"
```

---

### Task 3: Make plugin configuration lazy and remove the JDK shim

**Files:**
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolution.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolver.java`
- Create: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/RuntimeSelectionFunctionalTest.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideTaskName.java`
- Delete: `.github/workflows/shim.sh`

**Interfaces:**
- Consumes: Task 2 runtime types and extension properties.
- Produces: `ElideRuntimeResolver.resolve(Project, ElideExtension) -> ElideRuntimeResolution`, declared task inputs, direct `elide javac --` invocation, and no configuration-time side effects.

- [ ] **Step 1: Write failing functional tests for configuration purity**

Build a TestKit fixture with a fake executable that logs invocations. Apply `dev.elide`, run `help --configuration-cache`, and assert:

```java
assertFalse(Files.exists(invocationLog));
assertFalse(Files.exists(fakeJavaHome.resolve("bin/elide-javac")));
assertTrue(first.getOutput().contains("Configuration cache entry stored"));
assertTrue(second.getOutput().contains("Configuration cache entry reused"));
```

Run the same fixture twice. Confirm the current plugin fails because it invokes `elide --version` during configuration.

- [ ] **Step 2: Implement a declared-input Elide execution task**

```java
public abstract class ElideExecTask extends DefaultTask {
    @InputFile public abstract RegularFileProperty getElideExecutable();
    @Input public abstract ListProperty<String> getElideArguments();
    @InputDirectory public abstract DirectoryProperty getWorkingDirectory();
    @Internal protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void executeElide() {
        getExecOperations().exec(spec -> {
            spec.executable(getElideExecutable().get().getAsFile());
            spec.args(getElideArguments().get());
            spec.setWorkingDir(getWorkingDirectory().get().getAsFile());
        }).assertNormalExitValue();
    }
}
```

Inject `ExecOperations` through an `@Inject` accessor. Set `workingDirectory` from the project layout during task registration; never read `Project` inside the task action.

- [ ] **Step 3: Implement Gradle-aware resolution without subprocesses**

Define the integration result as:

```java
public record ElideRuntimeResolution(
    Provider<RegularFile> executable,
    ElideRuntimeSource source,
    Optional<TaskProvider<? extends Task>> preparationTask) {}
```

`ElideRuntimeResolver.resolve(Project, ElideExtension)` must read `PATH` through `providers.environmentVariable("PATH")`, explicit files through `RegularFileProperty`, and Gradle User Home through `Project.getGradle().getGradleUserHomeDir()`. It returns the pure Task 2 selection mapped into `ElideRuntimeResolution`. Before Task 4 supplies managed preparation, a managed result has the deterministic expected cache executable and an empty preparation task; Task 3 tests exercise explicit and PATH modes only. Legacy `resolveElideFromPath` maps only when explicitly present.

- [ ] **Step 4: Replace shim-based compiler configuration**

Remove `enableShim`, every `JAVA_HOME/bin/elide-javac` branch, `callElideCaptured`, and configuration-time version printing. Configure each `JavaCompile` as:

```java
options.setFork(true);
options.getForkOptions().setExecutable(selection.executable().toString());
List<String> existing = Optional.ofNullable(options.getForkOptions().getJvmArgs()).orElseGet(List::of);
List<String> args = new ArrayList<>(existing.size() + 2);
args.add("javac");
args.add("--");
args.addAll(existing);
options.getForkOptions().setJvmArgs(args);
```

Add the runtime preparation dependency only for a managed selection. Do not write beneath Java Home.

- [ ] **Step 5: Convert `elide install` to `ElideExecTask`**

Register `elideInstall` with arguments `List.of("install")`, declared executable, declared working directory, and manifest/dev-root inputs and outputs. Java compile tasks depend on it only when installation is enabled.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
test ! -e .github/workflows/shim.sh
git add elide-gradle-plugin/src .github/workflows/shim.sh
git commit -m "refactor: resolve Elide without configuration side effects"
```

---

### Task 4: Implement checksum-verified managed runtimes

**Files:**
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRelease.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideDownloader.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/PrepareElideRuntimeTask.java`
- Create: `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideReleaseTest.java`
- Create: `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideDownloaderTest.java`
- Create: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/ManagedRuntimeFunctionalTest.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolver.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`

**Interfaces:**
- Consumes: Task 2 platform/version types and Task 3 resolver contract.
- Produces: `ElideRelease.archiveUri()`, `checksumUri()`, `PrepareElideRuntimeTask`, and shared validated cache entries.

- [ ] **Step 1: Write failing release URL and checksum parsing tests**

```java
ElideRelease release = new ElideRelease(
    URI.create("https://github.com/elide-dev/elide/releases/download"),
    "1.5.1+20260903",
    ElidePlatform.detect("Mac OS X", "arm64"));
assertEquals(
    "https://github.com/elide-dev/elide/releases/download/1.5.1+20260903/elide.macos-arm64.tgz",
    release.archiveUri().toString());
assertEquals(release.archiveUri() + ".sha256", release.checksumUri().toString());
assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    ElideDownloader.parseSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  elide.tgz\n"));
assertThrows(IllegalArgumentException.class, () -> ElideDownloader.parseSha256("not-a-checksum"));
```

- [ ] **Step 2: Implement release metadata and streaming verified download**

Use Java 17 `HttpClient` with `Redirect.NORMAL`. `ElideDownloader.downloadVerified` downloads checksum and archive to sibling temporary files, hashes the archive with `MessageDigest.getInstance("SHA-256")`, compares lowercase hexadecimal values using `MessageDigest.isEqual`, deletes temporary files on mismatch, and moves the verified archive into place.

```java
public void downloadVerified(ElideRelease release, Path archiveTarget) throws IOException, InterruptedException;
public static String parseSha256(String checksumFile);
```

- [ ] **Step 3: Write failing managed-runtime functional tests**

Use JDK `HttpServer` serving a tiny fixture archive and checksum. Cover:

- First execution downloads, verifies, extracts, and runs a fake Elide executable.
- Second execution sends no HTTP request and reuses `.complete`.
- A bad checksum fails and creates no `.complete` marker.
- `--offline` succeeds with a completed cache and fails with a message containing exact version and cache path on a miss.
- Windows maps to ZIP and `bin/elide.exe`; Unix maps to TGZ and `bin/elide`.

Run the focused test and confirm managed mode is currently unavailable.

- [ ] **Step 4: Implement atomic preparation**

`PrepareElideRuntimeTask` declares version, platform fields, base URI, and offline as `@Input`; its final runtime directory is `@OutputDirectory`. Use this cache layout:

```text
<GRADLE_USER_HOME>/caches/dev.elide/runtimes/<version>/<platform>/
<GRADLE_USER_HOME>/caches/dev.elide/runtimes/<version>/<platform>.lock
```

Acquire the lock with `FileChannel.open(lock, CREATE, WRITE).lock()`. While locked, accept a cache hit only when `.complete` contains the verified archive SHA and the expected executable is a regular file. Otherwise download unless offline, extract with injected `ArchiveOperations` and `FileSystemOperations` into a sibling `.tmp-<UUID>` directory, validate `bin/elide` or `bin/elide.exe`, set Unix executable permissions, write `.complete`, and promote with `Files.move(..., ATOMIC_MOVE)` falling back to a same-filesystem non-atomic move only when `AtomicMoveNotSupportedException` is thrown. Clean the staging directory on failure.

- [ ] **Step 5: Wire managed selection lazily**

Register `prepareElideRuntime` with the exact extension version and platform. `MANAGED` always selects its expected executable. `AUTO` selects it only when explicit/PATH lookup fails. `PATH` neither depends on nor executes managed preparation. Expose a package-private release base URI override used only by TestKit fixtures.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
git add elide-gradle-plugin/src
git commit -m "feat: provision verified managed Elide runtimes"
```

---

### Task 5: Harden dependency installation and functional coverage

**Files:**
- Create: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/DependencyInstallFunctionalTest.java`
- Create: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/CompilerIntegrationFunctionalTest.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExecTask.java`
- Modify: `elide-gradle-plugin/src/functionalTest/java/com/example/plugin/ElidePluginFunctionalTest.java`

**Interfaces:**
- Consumes: resolved executable and managed preparation provider.
- Produces: deterministic compiler/install task wiring with declared inputs/outputs and diagnostic failures.

- [ ] **Step 1: Write failing install wiring tests**

Use a fake executable that records argv and creates `.dev/dependencies/m2`. Assert `compileJava` invokes `install` before `javac --`, the local Maven repository is present only when Maven integration is enabled, and disabling installation produces no `install` invocation.

- [ ] **Step 2: Write failing compiler argument preservation tests**

Configure a user compiler argument and assert the fake executable receives a command beginning with literal `javac`, `--`, followed by Gradle's compiler arguments without losing the user argument. Assert no path below fake `JAVA_HOME` is created.

- [ ] **Step 3: Declare execution inputs, outputs, and captured errors**

`ElideExecTask` must declare executable, arguments, working directory, manifest, dev root, and generated dependency repository according to task role. Capture stdout/stderr for failure reporting and throw a `GradleException` containing executable, working directory, and exit code; redact environment values and do not print the full environment.

- [ ] **Step 4: Remove obsolete tests and behavior**

Delete tests that require a pre-created Java Home shim or the developer's real PATH. Every normal functional test uses a fake executable or fixture server. Preserve one separately named `realRuntimeSmoke` task for the pinned release.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew :elide-gradle-plugin:test :elide-gradle-plugin:functionalTest --configuration-cache
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
git add elide-gradle-plugin/src
git commit -m "test: harden compiler and dependency integration"
```

Expected: the second functional run reports configuration-cache reuse and all tests pass.

---

### Task 6: Add consumer compatibility and cross-platform CI

**Files:**
- Create: `elide-gradle-plugin/src/compatibilityTest/java/dev/elide/gradle/ConsumerCompatibilityTest.java`
- Modify: `elide-gradle-plugin/build.gradle.kts`
- Modify: `.github/workflows/job.build.yml`
- Create: `.github/workflows/on.schedule.yml`
- Create: `.github/dependabot.yml`

**Interfaces:**
- Consumes: published Java 17 plugin artifact and all runtime modes.
- Produces: `compatibilityTest` and `realRuntimeSmoke` verification tasks plus OS/JDK CI matrices.

- [ ] **Step 1: Add a compatibility source set and test matrix**

Create `compatibilityTest` using JUnit 6.1.3 and TestKit. Parameterize representative builds over literal versions `7.6.4`, `8.14.5`, and `9.7.1`. Launch the compatibility test JVM with a Java 17 toolchain so older Gradle versions do not inherit Java 26. Each fixture uses explicit fake Elide, applies `java`, runs `help` and `compileJava`, and asserts success.

- [ ] **Step 2: Prove compatibility locally**

```bash
./gradlew :elide-gradle-plugin:compatibilityTest
```

Expected: all three Gradle-version cases pass on Java 17.

- [ ] **Step 3: Update primary CI**

Use these immutable action commits:

```yaml
uses: step-security/harden-runner@e14015d583714f6e62063499dc959a02595150a1 # v2
uses: actions/checkout@23441a48e516b6c34aea4fa41551a30e30af803 # v6
uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5
uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6
```

Run an OS matrix of `ubuntu-latest`, `macos-latest`, and `windows-latest` on Java 17 with `./gradlew build compatibilityTest`. Add a current-toolchain lane using Java 24 with Gradle 8.14.5 and Java 26 with Gradle 9.7.1. Remove manual shim creation and shell-specific `cd` commands; use `working-directory` so Windows runs natively.

- [ ] **Step 4: Add scheduled real-runtime smoke coverage**

Schedule daily plus manual dispatch. On all three OSes, install no PATH Elide, run `./gradlew realRuntimeSmoke -Pelide.runtime.mode=MANAGED`, and pin setup actions to the same SHAs. Allow only `github.com:443`, `release-assets.githubusercontent.com:443`, and `objects.githubusercontent.com:443` in harden-runner egress.

- [ ] **Step 5: Add Dependabot**

```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: "/"
    schedule: { interval: weekly }
  - package-ecosystem: github-actions
    directory: "/"
    schedule: { interval: weekly }
```

- [ ] **Step 6: Verify workflow syntax and commit**

```bash
./gradlew build compatibilityTest
ruby -e 'require "yaml"; Dir[".github/**/*.yml"].each { |f| YAML.load_file(f); puts f }'
git add elide-gradle-plugin .github
git commit -m "ci: test supported Gradle and platform matrix"
```

---

### Task 7: Update documentation and run release-grade verification

**Files:**
- Modify: `README.md`
- Create: `docs/runtime-management.md`
- Create: `docs/compatibility.md`
- Modify: `elide.gradle.kts`

**Interfaces:**
- Consumes: final extension API, compatibility matrix, cache layout, and runtime behavior.
- Produces: accurate installation, migration, offline, and support documentation.

- [ ] **Step 1: Replace shim-era README instructions**

Document `AUTO`, `PATH`, and `MANAGED`; show exact Kotlin DSL examples:

```kotlin
elide {
    runtimeMode = ElideRuntimeMode.MANAGED
    runtimeVersion = "1.5.1+20260903"
}
```

State that applying the plugin does not download or execute Elide and that no file is created in `JAVA_HOME`.

- [ ] **Step 2: Document runtime operations**

`docs/runtime-management.md` must name cache layout, asset mapping, checksum verification, offline behavior, AUTO precedence, PATH network guarantees, cache removal boundaries, and exact error remedies.

- [ ] **Step 3: Document compatibility and migration**

`docs/compatibility.md` must contain the exact Gradle/JDK matrix from Global Constraints and explain that the repository wrapper is not the consumer minimum. Add migration steps from manual `elide-javac`: remove the shim, choose a runtime mode, pin managed versions for reproducibility, and pre-populate cache before offline builds.

- [ ] **Step 4: Refresh remote bootstrap metadata**

Keep `elide.gradle.kts` compatible with existing consumers, remove experimental/shim claims, and ensure its catalog/plugin versions agree with project version `1.0.0` rather than the independently pinned Elide runtime version.

- [ ] **Step 5: Run final verification from a clean build**

```bash
./gradlew clean build compatibilityTest --configuration-cache
./gradlew build compatibilityTest --configuration-cache
./gradlew realRuntimeSmoke -Pelide.runtime.mode=MANAGED
./gradlew publishAllPublicationsToMavenRepository
javap -verbose -classpath elide-gradle-plugin/build/libs/elide-gradle-plugin-1.0.0.jar dev.elide.gradle.ElideGradlePlugin | rg "major version: 61"
git diff --check
```

Expected: both builds and the real-runtime smoke pass, the second configuration-cache build reuses its entry, bytecode is major version 61, and `git diff --check` emits nothing.

- [ ] **Step 6: Commit**

```bash
git add README.md docs elide.gradle.kts
git commit -m "docs: document production runtime management"
```
