# Elide Settings Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish `dev.elide.settings` so a build selects one Elide runtime and explicitly opted-in projects inherit that selection through a concise, conventional DSL.

**Architecture:** A settings extension wires provider-backed runtime conventions into a parameterized shared build service. The project plugin uses those parameters as conventions, resolves optional version-catalog aliases through Gradle's public API, and retains project overrides and legacy aliases. Project task wiring remains lazy and project-local; managed preparation is coordinated through the shared service.

**Tech Stack:** Java 17, Gradle Plugin API, Gradle shared build services, Version Catalog API, JUnit 6, Gradle TestKit, Kotlin and Groovy DSL fixtures.

**Spec:** `docs/superpowers/specs/2026-09-03-settings-plugin-design.md`

## Global Constraints

- Base all work on `cleanup-1.1.0`; this branch is a stacked PR and must not absorb unrelated draft PR #2 work.
- Published classes target Java 17 and use public APIs available in Gradle 7.6.4.
- Preserve the Gradle 7.6.4, 8.14.5, and 9.7.1 consumer matrix.
- `dev.elide.settings` never applies `dev.elide`; every project opts in explicitly.
- Plugin application performs no network access, subprocess execution, dependency resolution, or filesystem mutation.
- Do not use `allprojects`, `subprojects`, project-to-root mutable state, or `afterEvaluate`.
- Preserve `AUTO`, `PATH`, and `MANAGED`, including PATH's no-download guarantee.
- Preserve standalone `dev.elide` behavior and all existing 1.x extension properties.
- Canonical scalar DSL supports normal Kotlin and Groovy assignment, including `install = true`.
- Direct/provider and catalog-backed settings versions are mutually exclusive.
- The existing published catalog remains available but is not injected automatically.

---

## File Map

- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideSettingsPlugin.java`: settings plugin entry point and shared-service registration.
- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideSettingsExtension.java`: build-wide `elide` DSL root.
- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeSettings.java`: clean runtime DSL and exclusive version-source state.
- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideBuildConfiguration.java`: shared build-service parameter contract.
- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideVersionSource.java`: `DEFAULT`, `DIRECT`, and `CATALOG` source enum.
- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideVersionResolver.java`: resolve direct or catalog-backed versions for a project.
- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideProjectRuntimeSettings.java`: clean project runtime overrides backed by legacy properties.
- Modify `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtension.java`: shared conventions and concise compatibility-safe setters.
- Modify `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`: service consumption and lazy project integration.
- Modify `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolution.java`: provider-backed runtime source.
- Modify `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolver.java`: defer selection and register preparation without `afterEvaluate`.
- Create `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideCompilerArgumentProvider.java`: execution-time Elide launcher arguments for `JavaCompile`.
- Modify `elide-gradle-plugin/src/main/java/dev/elide/gradle/PrepareElideRuntimeTask.java`: skip preparation unless the resolved source is managed.
- Modify `elide-gradle-plugin/build.gradle.kts`: publish the settings plugin marker.
- Create `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideRuntimeSettingsTest.java`: version-source validation.
- Create `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java`: settings and multi-project behavior.
- Modify `elide-gradle-plugin/src/compatibilityTest/java/dev/elide/gradle/ConsumerCompatibilityTest.java`: clean DSL and settings plugin across supported Gradle versions.
- Modify `README.md`, `docs/runtime-management.md`, `docs/compatibility.md`, and `CHANGELOG.md`: installation, migration, and lifecycle documentation.
- Remove `elide.gradle.kts` only after all docs and tests use the settings plugin.

---

### Task 1: Settings DSL and Plugin Marker

**Files:**
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideVersionSource.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeSettings.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideSettingsExtension.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideBuildConfiguration.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideSettingsPlugin.java`
- Create: `elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideRuntimeSettingsTest.java`
- Create: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java`
- Modify: `elide-gradle-plugin/build.gradle.kts`

**Interfaces:**
- Produces: `ElideSettingsPlugin implements Plugin<Settings>` with ID `dev.elide.settings`.
- Produces: `ElideSettingsExtension#getRuntime()` and `runtime(Action<? super ElideRuntimeSettings>)`.
- Produces: clean `ElideRuntimeSettings` bean setters plus internal provider accessors.
- Produces: shared service name `ElideBuildConfiguration.SERVICE_NAME` and provider parameters consumed by Task 2.
- Produces: the settings-managed `elide` Maven repository, content-filtered to Elide publication groups.

- [ ] **Step 1: Write unit tests for version-source exclusivity**

Create `ElideRuntimeSettingsTest` with a `ProjectBuilder` object factory and these cases:

```java
@Test
void acceptsDirectVersion() {
    ElideRuntimeSettings runtime = runtimeSettings();
    runtime.setVersion("1.5.1+20260903");
    assertEquals(ElideVersionSource.DIRECT, runtime.getVersionSourceProperty().get());
    assertEquals("1.5.1+20260903", runtime.getVersionProperty().get());
}

@Test
void acceptsCatalogVersion() {
    ElideRuntimeSettings runtime = runtimeSettings();
    runtime.versionFrom("libs", "elide");
    assertEquals(ElideVersionSource.CATALOG, runtime.getVersionSourceProperty().get());
    assertEquals("libs", runtime.getCatalogNameProperty().get());
    assertEquals("elide", runtime.getCatalogAliasProperty().get());
}

@Test
void rejectsTwoVersionSources() {
    ElideRuntimeSettings runtime = runtimeSettings();
    runtime.setVersion("1.5.1+20260903");
    IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> runtime.versionFrom("libs", "elide"));
    assertEquals("Elide runtime version is already configured directly; choose either version or versionFrom", failure.getMessage());
}
```

Construct the type with `new ElideRuntimeSettings(project.getObjects())`; add the reverse conflict and blank catalog/alias cases with exact diagnostics.

- [ ] **Step 2: Run the unit test and verify it fails**

Run:

```bash
./gradlew :elide-gradle-plugin:test --tests dev.elide.gradle.ElideRuntimeSettingsTest
```

Expected: compilation fails because `ElideRuntimeSettings` and `ElideVersionSource` do not exist.

- [ ] **Step 3: Implement the runtime and settings DSL types**

Implement package-private provider accessors and public clean bean methods with these signatures:

```java
public final class ElideRuntimeSettings {
    public ElideRuntimeMode getMode();
    public void setMode(ElideRuntimeMode mode);
    public String getVersion();
    public void setVersion(String version);
    public void version(Provider<String> version);
    public void versionFrom(String catalogName, String versionAlias);

    Property<ElideRuntimeMode> getModeProperty();
    Property<String> getVersionProperty();
    Property<ElideVersionSource> getVersionSourceProperty();
    Property<String> getCatalogNameProperty();
    Property<String> getCatalogAliasProperty();
}

public final class ElideSettingsExtension {
    public ElideRuntimeSettings getRuntime();
    public void runtime(Action<? super ElideRuntimeSettings> action);
}
```

Use `ObjectFactory.property(...)`; set only `mode` (`AUTO`) and source (`DEFAULT`) conventions. Do not assign a direct version convention in this type. `setVersion` and `version(Provider)` reject an existing `CATALOG` source, while `versionFrom` rejects an existing `DIRECT` source. Reject blank strings before mutating any property.

- [ ] **Step 4: Add a failing TestKit test for the settings plugin DSL**

Add `settingsPluginSupportsCleanKotlinDsl` to `SettingsPluginFunctionalTest`. Write this `settings.gradle.kts` into a temporary project:

```kotlin
import dev.elide.gradle.ElideRuntimeMode

plugins {
    id("dev.elide.settings")
}

elide {
    runtime {
        mode = ElideRuntimeMode.MANAGED
        version = "1.5.1+20260903"
    }
}

check(elide.runtime.mode == ElideRuntimeMode.MANAGED)
check(elide.runtime.version == "1.5.1+20260903")
rootProject.name = "settings-dsl"
```

Run it with `GradleRunner.create().withPluginClasspath().withArguments("help", "--stacktrace")` and assert `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the functional test and verify it fails**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.SettingsPluginFunctionalTest.settingsPluginSupportsCleanKotlinDsl
```

Expected: plugin resolution fails because `dev.elide.settings` is not declared.

- [ ] **Step 6: Implement the shared service and settings plugin**

Use this public parameter contract:

```java
public abstract class ElideBuildConfiguration
        implements BuildService<ElideBuildConfiguration.Parameters> {
    static final String SERVICE_NAME = "elideBuildConfiguration";

    public interface Parameters extends BuildServiceParameters {
        Property<ElideRuntimeMode> getRuntimeMode();
        Property<ElideVersionSource> getVersionSource();
        Property<String> getRuntimeVersion();
        Property<String> getCatalogName();
        Property<String> getCatalogAlias();
    }
}
```

`ElideSettingsPlugin.apply(Settings)` creates the extension, calls `registerIfAbsent`, and wires every parameter with `set(extension.getRuntime().get...Property())`. Set `maximumParallelUsages` to one so managed preparation tasks can use this same service as their in-build coordination lock. Do not call `get()` on an extension property.

Also add `https://maven.elide.dev` to `settings.getDependencyResolutionManagement().getRepositories()` under the name
`elide`, restricting repository content to `dev.elide` and `dev.elide.gradle`. Extend the TestKit settings script with
`check(dependencyResolutionManagement.repositories.named("elide").get().name == "elide")`; this configures metadata
only and must not resolve the repository.

Declare the second plugin in `gradlePlugin.plugins`:

```kotlin
val elideSettings by plugins.creating {
    id = "dev.elide.settings"
    displayName = "Elide Gradle Settings Plugin"
    implementationClass = "dev.elide.gradle.ElideSettingsPlugin"
    description = "Configure the Elide runtime once for a Gradle build"
    tags.set(listOf("elide", "settings", "toolchain", "dependencies"))
}
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :elide-gradle-plugin:test --tests dev.elide.gradle.ElideRuntimeSettingsTest :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.SettingsPluginFunctionalTest.settingsPluginSupportsCleanKotlinDsl
```

Expected: PASS.

- [ ] **Step 8: Commit the settings plugin foundation**

```bash
git add elide-gradle-plugin/build.gradle.kts elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideVersionSource.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeSettings.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideSettingsExtension.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideBuildConfiguration.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideSettingsPlugin.java elide-gradle-plugin/src/test/java/dev/elide/gradle/ElideRuntimeSettingsTest.java elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java
git commit -m "feat: add Elide settings plugin"
```

### Task 2: Project Inheritance, Catalog Resolution, and Clean DSL

**Files:**
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideVersionResolver.java`
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideProjectRuntimeSettings.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtension.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`
- Modify: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java`

**Interfaces:**
- Consumes: `ElideBuildConfiguration.Parameters` from Task 1.
- Produces: `ElideVersionResolver.resolve(Project, Provider<ElideBuildConfiguration>, String): Provider<String>`.
- Produces: concise project setters `install`, `compiler`, and `maven`, backed by existing properties.
- Produces: `ElideExtension#getRuntime()` and `runtime(Action<? super ElideProjectRuntimeSettings>)`.

- [ ] **Step 1: Add failing direct-inheritance and explicit-opt-in tests**

Create a two-project fixture with `include("app", "plain")`. The root settings plugin configures `PATH` and a direct version. Only `app/build.gradle.kts` applies `dev.elide`; `plain` applies `base`. Register this diagnostic task in `app`:

```kotlin
tasks.register("printElideConfiguration") {
    doLast {
        println("ELIDE_MODE=${elide.runtimeMode.get()}")
        println("ELIDE_VERSION=${elide.runtimeVersion.get()}")
        println("ELIDE_INSTALL=${elide.install}")
    }
}
```

Set `install = true` in `app`. Assert the output contains `PATH`, the settings version, and `true`; run `plain:tasks` and assert no `elideInstall` task appears.

- [ ] **Step 2: Add failing catalog and project-override tests**

Write `gradle/libs.versions.toml` with `[versions] elide = "catalog-version"`, configure `versionFrom("libs", "elide")`, and assert an opted-in project prints `catalog-version`. In a second fixture set `runtimeVersion.set("project-version")` and assert that value wins. Add missing catalog and missing alias cases asserting messages that include both requested names and the project path.

- [ ] **Step 3: Run the settings functional tests and verify they fail**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.SettingsPluginFunctionalTest
```

Expected: direct settings values are not inherited and `elide.install` does not exist.

- [ ] **Step 4: Implement catalog-aware version resolution**

Implement:

```java
final class ElideVersionResolver {
    static Provider<String> resolve(
            Project project,
            Provider<ElideBuildConfiguration> configuration,
            String standaloneDefault);
}
```

Map `DIRECT` to `parameters.getRuntimeVersion()`, `CATALOG` to a provider that uses:

```java
VersionCatalogsExtension catalogs = project.getExtensions().getByType(VersionCatalogsExtension.class);
VersionCatalog catalog = catalogs.find(name)
        .orElseThrow(() -> new GradleException("Elide version catalog '" + name + "' does not exist for project " + project.getPath()));
VersionConstraint version = catalog.findVersion(alias)
        .orElseThrow(() -> new GradleException("Elide version alias '" + alias + "' does not exist in catalog '" + name + "' for project " + project.getPath()));
return version.getRequiredVersion();
```

Treat `DEFAULT` as the supplied standalone default. Keep lookup inside `ProviderFactory.provider(...)`; do not resolve the catalog in plugin `apply`.

- [ ] **Step 5: Wire service parameters as project conventions**

In `ElideGradlePlugin.apply`, obtain the shared service using `registerIfAbsent(ElideBuildConfiguration.SERVICE_NAME, ...)`. The fallback registration sets `AUTO`, `DEFAULT`, and the existing pinned version. Pass providers into a refactored `ElideExtension` constructor and set conventions, never values:

```java
doRuntimeMode.convention(configuration.flatMap(service -> service.getParameters().getRuntimeMode()));
doRuntimeVersion.convention(ElideVersionResolver.resolve(project, configuration, DEFAULT_RUNTIME_VERSION));
```

Keep `runtimeMode`, `runtimeVersion`, and `elideBin` as the resolver-facing provider properties.

- [ ] **Step 6: Add concise compatibility-safe project setters**

Add JavaBean accessors that delegate to existing properties:

```java
public boolean isInstall() { return getEnableInstall().get(); }
public void setInstall(boolean value) { getEnableInstall().set(value); }
public boolean isCompiler() { return getEnableJavaCompiler().get(); }
public void setCompiler(boolean value) { getEnableJavaCompiler().set(value); }
public boolean isMaven() { return getEnableMavenIntegration().get(); }
public void setMaven(boolean value) { getEnableMavenIntegration().set(value); }
```

Add overloads accepting `Provider<Boolean>` named `install`, `compiler`, and `maven`. Deprecate, but do not remove or duplicate, the old provider-returning getters. Both APIs must mutate the same `Property<Boolean>` objects.

Create `ElideProjectRuntimeSettings` as a façade over the existing `runtimeMode`, `runtimeVersion`, and `elideBin`
properties, with these public signatures:

```java
public ElideRuntimeMode getMode();
public void setMode(ElideRuntimeMode value);
public String getVersion();
public void setVersion(String value);
public RegularFile getExecutable();
public void setExecutable(RegularFile value);
```

Expose one instance from `ElideExtension#getRuntime()` and `runtime(Action)`. It must delegate to the exact legacy
property instances so either spelling has identical precedence and task inputs.

- [ ] **Step 7: Run the settings functional tests**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.SettingsPluginFunctionalTest
```

Expected: PASS, including catalog errors and untouched sibling behavior.

- [ ] **Step 8: Commit project inheritance**

```bash
git add elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideVersionResolver.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideProjectRuntimeSettings.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideExtension.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java
git commit -m "feat: inherit build-wide Elide configuration"
```

### Task 3: Lazy Project Lifecycle and Shared Managed Preparation

**Files:**
- Create: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideCompilerArgumentProvider.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolution.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolver.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/PrepareElideRuntimeTask.java`
- Modify: `elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java`
- Modify: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java`
- Modify: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/RuntimeSelectionFunctionalTest.java`
- Modify: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/ManagedRuntimeFunctionalTest.java`

**Interfaces:**
- Consumes: shared build service provider from Task 2.
- Produces: `ElideRuntimeResolution` containing provider-backed executable and source.
- Produces: `ElideCompilerArgumentProvider implements CommandLineArgumentProvider`.

- [ ] **Step 1: Add a failing test that forbids `afterEvaluate`-dependent behavior**

In `RuntimeSelectionFunctionalTest`, add a Kotlin fixture that applies `java` after `dev.elide`, uses clean setters, runs `help` twice with `--configuration-cache`, and then runs `compileJava`. Assert configuration cache reuse and one `javac --` invocation. This ensures plugin order and late project DSL configuration both work.

- [ ] **Step 2: Add a failing parallel multi-project preparation test**

Extend `ManagedRuntimeFunctionalTest` with two opted-in Java projects using the same settings-level managed version and the local HTTP release fixture. Run:

```text
--parallel :one:prepareElideRuntime :two:prepareElideRuntime
```

Assert both tasks succeed, the archive endpoint and checksum endpoint are each requested exactly once, and the shared cache has one valid completion marker.

- [ ] **Step 3: Run the focused lifecycle tests and verify the new assertions fail**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest --tests dev.elide.gradle.RuntimeSelectionFunctionalTest --tests dev.elide.gradle.ManagedRuntimeFunctionalTest
```

Expected: failure from current eager `afterEvaluate` configuration or duplicate managed preparation.

- [ ] **Step 4: Make runtime resolution provider-backed**

Change `ElideRuntimeResolution` to carry:

```java
record ElideRuntimeResolution(
        Provider<RegularFile> executable,
        Provider<ElideRuntimeSource> source,
        TaskProvider<PrepareElideRuntimeTask> preparationTask) { }
```

In `ElideRuntimeResolver.resolve`, create one provider that performs `ElideRuntimeLocator.locate(...)` only when queried. Derive executable and source providers with `map`. Always register `prepareElideRuntime`, but give the task the resolved source and gate its task action with `onlyIf("the selected Elide runtime is managed", task -> source.get() == ElideRuntimeSource.MANAGED)`. Wire version, platform, offline state, and output paths with providers. PATH selection must never execute downloader code.

- [ ] **Step 5: Configure Java compilation without `afterEvaluate`**

Implement `ElideCompilerArgumentProvider` with annotated provider inputs for the resolved executable and runtime source. Configure every `JavaCompile` through `tasks.withType(JavaCompile.class).configureEach(...)`, set the stable Java launcher executable, and append the argument provider to `getJvmArgumentProviders()` so the Elide executable is resolved at task execution rather than plugin application.

The provider emits the existing launcher protocol exactly:

```text
-cp <plugin-classpath> dev.elide.gradle.ElideJavaCompilerLauncher <elide-executable> javac --
```

Preserve user JVM arguments after that fixed prefix. Keep the existing fake-runtime compiler functional tests green before deleting the old direct-executable branch.

- [ ] **Step 6: Replace project evaluation callbacks with plugin callbacks**

Delete `project.afterEvaluate`. During `apply`, register runtime/install tasks lazily. Use:

```java
project.getPluginManager().withPlugin("java", ignored ->
        project.getTasks().withType(JavaCompile.class).configureEach(task -> { /* provider wiring */ }));
```

Every Elide-consuming task depends on the always-registered preparation task; the task skips itself for explicit/PATH selection. Add `task.usesService(configuration)` to preparation tasks so one shared service serializes in-build managed preparation. Keep the cross-process file lock as the final safety boundary.

- [ ] **Step 7: Run all functional tests**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest
```

Expected: PASS; no existing runtime, compiler, or dependency-install behavior regresses.

- [ ] **Step 8: Verify configuration cache and inspect deprecations**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache --warning-mode=all
```

Expected: PASS with configuration-cache reuse in nested fixtures and no new plugin-owned deprecation warning.

- [ ] **Step 9: Commit lifecycle integration**

```bash
git add elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideCompilerArgumentProvider.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolution.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideRuntimeResolver.java elide-gradle-plugin/src/main/java/dev/elide/gradle/PrepareElideRuntimeTask.java elide-gradle-plugin/src/main/java/dev/elide/gradle/ElideGradlePlugin.java elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/RuntimeSelectionFunctionalTest.java elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/ManagedRuntimeFunctionalTest.java
git commit -m "refactor: integrate Elide with the lazy Gradle lifecycle"
```

### Task 4: Consumer Compatibility and Isolated Projects

**Files:**
- Modify: `elide-gradle-plugin/src/compatibilityTest/java/dev/elide/gradle/ConsumerCompatibilityTest.java`
- Modify: `elide-gradle-plugin/src/currentToolchainTest/java/dev/elide/gradle/CurrentToolchainConsumerTest.java`
- Modify: `elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java`

**Interfaces:**
- Consumes: complete settings/project plugin pair from Tasks 1-3.
- Produces: executable compatibility evidence for Gradle 7.6.4, 8.14.5, and 9.7.1.

- [ ] **Step 1: Convert the compatibility fixture to the settings plugin**

For every supported Gradle version, write settings with:

```groovy
plugins {
    id 'dev.elide.settings'
}
elide {
    runtime {
        mode = dev.elide.gradle.ElideRuntimeMode.PATH
        version = 'fixture-1.0'
    }
}
```

Keep `id 'dev.elide'` explicit in the project and switch its feature DSL to:

```groovy
elide {
    install = false
    compiler = true
    runtime.executable = file('...')
}
```

The Kotlin Gradle 7.6.4 fixture must use the same assignment syntax rather than `.set(...)` for the new bean façade.

- [ ] **Step 2: Run compatibility tests**

Run:

```bash
./gradlew :elide-gradle-plugin:compatibilityTest
```

Expected: PASS on 7.6.4, 8.14.5, and 9.7.1. If Kotlin 7.6.4 cannot synthesize a property from the bean façade, fix the Java getter/setter pair; do not raise the consumer floor or revert the test to `.set(...)`.

- [ ] **Step 3: Add current-Gradle Isolated Projects coverage**

Add a multi-project TestKit case using `--isolated-projects` on Gradle 9.7.1. Apply the settings plugin once and project plugin to two sibling projects, run both `tasks`, and assert success without an Isolated Projects problem report.

- [ ] **Step 4: Run current toolchain-compatible tests available locally**

Run the normal current wrapper lane:

```bash
./gradlew :elide-gradle-plugin:functionalTest :elide-gradle-plugin:compatibilityTest
```

If the matching Java 24 or Java 26 runtime is installed, also run the corresponding documented `currentToolchainConsumerTest`; otherwise leave those exact CI lanes unchanged and report that they remain CI-only verification.

- [ ] **Step 5: Commit compatibility coverage**

```bash
git add elide-gradle-plugin/src/compatibilityTest/java/dev/elide/gradle/ConsumerCompatibilityTest.java elide-gradle-plugin/src/currentToolchainTest/java/dev/elide/gradle/CurrentToolchainConsumerTest.java elide-gradle-plugin/src/functionalTest/java/dev/elide/gradle/SettingsPluginFunctionalTest.java
git commit -m "test: verify settings plugin consumer compatibility"
```

### Task 5: Installation and Migration Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/runtime-management.md`
- Modify: `docs/compatibility.md`
- Modify: `CHANGELOG.md`
- Delete: `elide.gradle.kts`

**Interfaces:**
- Consumes: final DSL and behavior from Tasks 1-4.
- Produces: canonical installation and migration instructions.

- [ ] **Step 1: Replace the remote-script README installation**

Use this canonical example:

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

Then show explicit `dev.elide` application and `install = true` in each participating project. Explain that plugin and runtime versions are independent and include the `versionFrom("libs", "elide")` form.

- [ ] **Step 2: Document migration and retained compatibility**

In `docs/compatibility.md`, give this migration order:

1. Remove `apply(from = "https://gradle.elide.dev/...")`.
2. Apply `dev.elide.settings` in settings with the former plugin release version.
3. Move `runtimeMode`/`runtimeVersion` into `elide.runtime` in settings.
4. Keep `dev.elide` only in participating projects.
5. Rename feature flags to `install`, `compiler`, and `maven`; old names remain deprecated for 1.x.

State that `elide-gradle-catalog` remains published but is no longer injected by bootstrap logic.

- [ ] **Step 3: Update runtime and changelog documentation**

Change runtime-management examples to settings-level configuration and document project override precedence. Add a `1.1.0` changelog entry naming the settings plugin, explicit opt-in, catalog version source, clean DSL, shared preparation, and remote script removal.

- [ ] **Step 4: Remove the obsolete remote bootstrap script and scan references**

Delete `elide.gradle.kts`, then run:

```bash
rg -n 'apply\(from|gradle\.elide\.dev|enableInstall|runtimeMode\.set|runtimeVersion\.set' README.md docs CHANGELOG.md example-project example-project-remote
```

Expected: no canonical documentation or example still recommends the remote script or deprecated DSL. Historical design documents may retain old names when describing past behavior.

- [ ] **Step 5: Run documentation-adjacent smoke tests**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest :elide-gradle-plugin:compatibilityTest
```

Expected: PASS.

- [ ] **Step 6: Commit docs and bootstrap removal**

```bash
git add README.md docs/runtime-management.md docs/compatibility.md CHANGELOG.md elide.gradle.kts
git commit -m "docs: adopt settings plugin installation"
```

### Task 6: Final Verification

**Files:**
- Modify only files required by failures directly attributable to Tasks 1-5.

**Interfaces:**
- Consumes: all implementation and documentation.
- Produces: a verified stacked branch ready for review.

- [ ] **Step 1: Run formatting and static validation**

Run:

```bash
git diff --check cleanup-1.1.0...HEAD
./gradlew :elide-gradle-plugin:validatePlugins
```

Expected: no whitespace errors and `validatePlugins` passes.

- [ ] **Step 2: Run the complete normal verification suite**

Run:

```bash
./gradlew clean check
```

Expected: all unit, functional, compatibility, and build checks pass.

- [ ] **Step 3: Run explicit configuration-cache verification**

Run:

```bash
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
./gradlew :elide-gradle-plugin:functionalTest --configuration-cache
```

Expected: both pass and the second invocation reports reuse for the outer build; nested fixture assertions also pass.

- [ ] **Step 4: Review the branch scope**

Run:

```bash
git status --short
git log --oneline cleanup-1.1.0..HEAD
git diff --stat cleanup-1.1.0...HEAD
```

Expected: a clean tree; only the design, plan, settings-plugin implementation, focused lifecycle refactor, tests, docs, and bootstrap removal are present.

- [ ] **Step 5: Commit any verification-only correction**

If and only if verification required a correction, stage only that correction and commit it with a message describing the actual fix. Otherwise do not create an empty commit.
