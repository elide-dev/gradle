# Task 7 Report: Production runtime documentation and release verification

## Scope

Task 7 replaced the shim-era README guidance, added runtime and compatibility documentation, refreshed the remote
bootstrap metadata, corrected the tracked Gradle distribution checksum in the approved plan, and ran the release-grade
verification commands. The implementation remains on branch `cleanup-1.1.0` at base `9bb5f87`.

Files changed:

- `README.md`
- `docs/runtime-management.md`
- `docs/compatibility.md`
- `elide.gradle.kts`
- `docs/superpowers/plans/2026-09-03-gradle-plugin-hardening.md` (checksum correction required by the ledger)
- `elide-gradle-plugin/src/realRuntimeSmoke/java/dev/elide/gradle/RealRuntimeSmokeTest.java` (minimal smoke-harness fix)

## Documentation delivered

- README installation now uses the remote bootstrap and project plugin version `1.0.0`; it no longer asks users to
  install Elide first or create an `elide-javac` launcher under `JAVA_HOME`.
- README documents `AUTO`, `PATH`, and `MANAGED`, including the exact Kotlin DSL managed-runtime example and the
  explicit `elideBin` override.
- README states that applying the plugin performs no download, subprocess execution, or `JAVA_HOME` mutation; managed
  preparation and optional `elide install` happen only through Gradle tasks.
- `docs/runtime-management.md` records AUTO precedence, PATH's no-network guarantee, the Gradle User Home cache and
  lock layout, release asset mapping, GitHub release origin, release `.sha256` verification, staging/atomic promotion,
  offline behavior, cache-removal boundaries, and exact error messages/remedies.
- The pinned `1.5.1+20260903` asset table lists Linux amd64/arm64 TGZ, macOS arm64 TGZ, and Windows amd64 ZIP. It
  explicitly states that macOS amd64 has no published managed asset and remains supported through PATH or `elideBin`.
- `docs/compatibility.md` records the exact consumer matrix, explains that the repository's Gradle 9.7.1 wrapper is not
  the consumer minimum, and gives migration steps from manual `elide-javac`, including managed pinning and connected
  cache pre-population before offline builds.
- `elide.gradle.kts` retains the existing settings-script/bootstrap shape, uses catalog/plugin coordinate `1.0.0`, and
  explicitly separates that coordinate from Elide's independently pinned runtime version.

## Minimal smoke-harness fix

The first required real smoke command reached the GitHub release but failed its completion-marker assertion because
`GradleRunner.withTestKitDir(...)` made the nested build use its `test-kit` directory as Gradle User Home while the test
asserted against a separate temporary directory. The nested build itself was successful. The smoke fixture now passes
`--gradle-user-home <temporary Gradle User Home>` explicitly, matching the path asserted by the test. No production
runtime/archive code was changed.

After this fix, the real managed smoke downloaded and prepared the current macOS arm64 release asset successfully. A
direct release check also observed HTTP 200 for `elide.macos-arm64.tgz` and HTTP 404 for the intentionally undocumented
`elide.macos-amd64.tgz`, confirming the platform caveat in the docs.

## Verification evidence

All commands below were run from `/Users/sam/workspace/gradle` with the Gradle 9.7.1 wrapper.

### Clean build and compatibility suite

```text
./gradlew clean build compatibilityTest --configuration-cache
```

Result after the final smoke-harness fix: `BUILD SUCCESSFUL in 40s`; 18 actionable tasks executed; unit, functional,
and compatibility suites passed. The functional suite reported 25 tests with 3 expected platform skips; the
compatibility suite covered Gradle 7.6.4, 8.14.5, and 9.7.1. The output ended with `Configuration cache entry reused`.

```text
./gradlew build compatibilityTest --configuration-cache
```

Result: `BUILD SUCCESSFUL in 300ms`; 16 actionable tasks up-to-date; `Configuration cache entry reused`.

The same second command was also run once before the final clean pass; it passed and stored a distinct cache entry because
its task graph omitted `clean`. The immediate repeat reused that entry. This is expected Gradle configuration-cache keying,
not a test failure.

### Real managed-runtime smoke

```text
./gradlew realRuntimeSmoke -Pelide.runtime.mode=MANAGED
```

Result after the harness fix: exit 0, `BUILD SUCCESSFUL in 472ms` (the smoke test was already up-to-date after the fresh
successful run below).

Fresh execution of the same smoke test after the fix:

```text
./gradlew realRuntimeSmoke -Pelide.runtime.mode=MANAGED --rerun-tasks
```

Result: `BUILD SUCCESSFUL in 15s`; `RealRuntimeSmokeTest` downloaded the pinned GitHub release, verified its checksum,
extracted the runtime, and observed the `.complete` marker in the isolated Gradle User Home cache.

The initial run before the fix failed only at the marker assertion. The diagnostic run established that the nested build's
Gradle User Home was `<test-kit>` rather than the assertion path; this was corrected as described above.

### Local publication

```text
./gradlew publishAllPublicationsToMavenRepository
```

Result: `BUILD SUCCESSFUL in 540ms`; 23 actionable tasks, 11 executed and 12 up-to-date. Local artifacts were published
under `build/elide-maven/`, including:

- `dev/elide/gradle/elide-gradle-plugin/1.0.0/elide-gradle-plugin-1.0.0.jar`
- `dev/elide/gradle/elide-gradle-plugin/1.0.0/elide-gradle-plugin-1.0.0.module`
- `dev/elide/dev.elide.gradle.plugin/1.0.0/dev.elide.gradle.plugin-1.0.0.pom`
- `dev/elide/gradle/elide-gradle-catalog/1.0.0/elide-gradle-catalog-1.0.0.toml`

Published metadata inspection showed:

```text
dev.elide.gradle:elide-gradle-plugin:1.0.0
variant=apiElements jvm=17
variant=runtimeElements jvm=17
```

The plugin marker POM points to `dev.elide.gradle:elide-gradle-plugin:1.0.0` and has no additional dependency. No published
metadata raises the Java floor above Java 17 or introduces a Gradle dependency that raises the documented Gradle 7.6.4
consumer floor; the TestKit compatibility matrix independently passed all three consumer Gradle versions.

### Bytecode and whitespace

```text
javap -verbose -classpath elide-gradle-plugin/build/libs/elide-gradle-plugin-1.0.0.jar dev.elide.gradle.ElideGradlePlugin | rg "major version: 61"
```

Result:

```text
  major version: 61
```

```text
git diff --check
```

Result: no output, exit 0.

## Self-review

- README examples use the final extension names (`runtimeMode`, `runtimeVersion`, and `elideBin`) and distinguish
  plugin/catalog version `1.0.0` from runtime version `1.5.1+20260903`.
- AUTO is documented as explicit > PATH > managed; MANAGED never silently selects an installed runtime; PATH never
  registers or accesses the managed network path.
- Cache paths include exact version/platform directories and sibling locks. Removal guidance is bounded to one
  version/platform and warns against deleting shared parents or active lock files.
- Checksum, archive staging, executable validation, offline diagnostics, and redacted bounded subprocess diagnostics are
  described without promising behavior the implementation does not provide.
- The pinned release's missing macOS-amd64 asset is called out explicitly; no `elide.zip` origin or nonexistent asset is
  documented.
- The compatibility page states the exact matrix and clearly separates repository-wrapper tooling from consumer minimums.
- The smoke fix is test-only and limited to aligning the nested TestKit Gradle User Home with the assertion path.

## Unresolved external CI evidence

This macOS workspace cannot provide hosted Windows execution, hosted Linux execution, Java 24/26 matrix evidence, or the
scheduled three-OS smoke workflow evidence. Those remain owned by the configured GitHub Actions workflows. The local
real smoke did verify the production GitHub release path on macOS arm64; the workflow's immutable action pins and egress
allow-list still require hosted CI confirmation.

## Fix round 1/5: review findings

The first review identified four Important and two Minor documentation/metadata findings. This round addresses each:

- Every copyable Kotlin DSL snippet that uses `ElideRuntimeMode` now imports `dev.elide.gradle.ElideRuntimeMode` in
  `README.md`, `docs/runtime-management.md`, and `docs/compatibility.md`.
- Consumer cache pre-population now configures `runtimeMode = ElideRuntimeMode.MANAGED` and
  `runtimeVersion = "1.5.1+20260903"` in `build.gradle.kts`, then runs `./gradlew prepareElideRuntime` with no
  unsupported runtime-mode project property. The `-Pelide.runtime.mode=MANAGED` property is explicitly documented as
  the repository smoke guard only.
- Unsupported OS/Windows ARM64 guidance now states that host detection fails before PATH/explicit selection. Only the
  mapped macOS-amd64 case (whose pinned release asset is missing) documents PATH/explicit fallback.
- The catalog now keeps library aliases on runtime version `1.5.1+20260903`, adds an `elidePlugin` version key from the
  project version, and points the `dev.elide` plugin alias at `elidePlugin` (`1.0.0`).
- PATH's network guarantee is explicitly limited to managed runtime archive/checksum provisioning; enabled `elide install`
  may still resolve project dependencies. Managed preparation is documented as registered for managed selection and
  invokable directly or through consuming-task dependencies.

### Focused catalog generation/publication verification

```text
./gradlew :elide-gradle-catalog:generateCatalogAsToml :elide-gradle-catalog:generateMetadataFileForMavenPublication
```

Result: `BUILD SUCCESSFUL in 2s`; 6 actionable tasks, 2 executed and 4 up-to-date.

The generated `elide-gradle-catalog/build/version-catalog/libs.versions.toml` contains:

```text
elide = "1.5.1+20260903"
elidePlugin = "1.0.0"
elide = {id = "dev.elide", version.ref = "elidePlugin" }
```

```text
./gradlew :elide-gradle-catalog:publishAllPublicationsToMavenRepository
```

Result: `BUILD SUCCESSFUL in 392ms`; 9 actionable tasks, 4 executed and 5 up-to-date. The published TOML under
`build/elide-maven/dev/elide/gradle/elide-gradle-catalog/1.0.0/` contains the same three assertions. Published module
metadata identifies `dev.elide.gradle:elide-gradle-catalog:1.0.0` and the `versionCatalogElements` artifact.

Focused shell assertions required exactly one runtime key, one plugin-version key, and one plugin alias reference:

```text
runtime key: 8:elide = "1.5.1+20260903"
plugin key: 9:elidePlugin = "1.0.0"
plugin alias: 17:elide = {id = "dev.elide", version.ref = "elidePlugin" }
```

### Fix-round build and docs verification

```text
./gradlew build compatibilityTest
```

Result: `BUILD SUCCESSFUL in 407ms`; 20 actionable tasks up-to-date after the catalog source change.

```text
./gradlew :elide-gradle-plugin:compatibilityTest --rerun-tasks
```

Result: all three literal consumer versions (Gradle 7.6.4, 8.14.5, and 9.7.1) passed under the Java 17 compatibility
launcher. This rerun is intentionally limited to local compatibility coverage; the external smoke was not rerun because
this fix round does not touch runtime/download code.

The copyable Kotlin snippets were checked for an import immediately preceding each enum use. A docs contract grep found
`AUTO`, `PATH`, `MANAGED`, `1.5.1+20260903`, `JAVA_HOME`, cache paths, GitHub release origin, Java 17, and macOS amd64 in
the expected documentation files. The runtime/compatibility docs contain no consumer pre-population command using
`-Pelide.runtime.mode=MANAGED`; the only remaining occurrences are the repository smoke command and its explanatory
report evidence.

```text
git diff --check
```

Result after staging the fix-round changes: no output, exit 0.

### Post-append verification

The compatibility rerun above produced the following fresh result after all fix-round source edits:

```text
./gradlew :elide-gradle-plugin:compatibilityTest --rerun-tasks
BUILD SUCCESSFUL in 8s
11 actionable tasks: 11 executed
```

The focused documentation checks produced:

```text
enum snippets: all imports present
consumer pre-population docs: no repository smoke -P guard
unsupported-host fallback: no PATH/explicit promise
git diff --check: no output
```

## Fix round 2/5: unsupported-platform remedy wording

Corrected `docs/runtime-management.md` so the error-remedy row permits `PATH` or explicit `elideBin` only for mapped macOS amd64 when the pinned asset is absent. Unknown operating systems and Windows ARM64 now explicitly require a supported host/platform because detection fails before runtime selection.

```text
git diff --check
```

Result: no output, exit 0.
