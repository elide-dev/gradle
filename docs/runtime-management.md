# Runtime management

The plugin resolves one Elide executable for each project. Selection is lazy: applying `dev.elide` registers the
extension and task wiring, but does not download, extract, or execute Elide. A managed preparation task runs only when a
managed executable is selected and a consuming task needs it. No runtime mode creates or modifies files below `JAVA_HOME`.

## Modes and precedence

`ElideRuntimeMode` has three values:

| Mode | Selection | Network behavior |
| --- | --- | --- |
| `AUTO` | Explicit `elideBin`, then the first usable executable on `PATH`, then managed runtime | May download only when it reaches managed fallback |
| `PATH` | Explicit `elideBin`, then the first usable executable on `PATH`; no fallback | Never downloads, extracts, or registers managed preparation |
| `MANAGED` | The configured version and platform cache entry | Downloads on a cache miss unless Gradle is offline |

In `AUTO`, an explicit executable is considered before `PATH`. A PATH lookup preserves directory order and uses the
platform executable name (`elide` on Unix-like systems and `elide.exe` on Windows). Unix candidates must be regular
executable files; Windows candidates must be regular files. `MANAGED` intentionally ignores explicit and PATH candidates.

`elideBin` is the explicit executable override. `runtimeVersion` defaults to `1.5.1+20260903`. The deprecated
`resolveElideFromPath` property remains for source compatibility: an explicitly supplied `true` selects `PATH`, and an
explicit `false` selects `MANAGED`.

## Managed release assets

Production releases are read from:

```text
https://github.com/elide-dev/elide/releases/download/<version>/<asset>
```

For the pinned `1.5.1+20260903` release, the available assets are:

| Platform | Archive | Executable in archive |
| --- | --- | --- |
| Linux amd64 | `elide.linux-amd64.tgz` | `bin/elide` |
| Linux arm64 | `elide.linux-arm64.tgz` | `bin/elide` |
| macOS arm64 | `elide.macos-arm64.tgz` | `bin/elide` |
| Windows amd64 | `elide.windows-amd64.zip` | `bin/elide.exe` |

This release does not publish `elide.macos-amd64.tgz`. Therefore `MANAGED` is unavailable on Intel macOS for this
pinned version and fails when the missing archive is requested. `PATH` and an explicit `elideBin` remain supported there.
Unknown operating systems, Windows ARM64, and other unsupported combinations fail with
`Unsupported Elide platform: <os>/<arch>`; use a supported platform or select a locally installed runtime with `PATH` or
`elideBin`.

The plugin requests the archive and its adjacent `.sha256` file. It parses one 64-character SHA-256 value, hashes the
downloaded archive, and compares the values before extraction. Extraction occurs in a unique sibling staging directory;
the expected executable is validated, Unix permissions are repaired when needed, `.complete` is written, and the
completed directory is promoted into the cache while holding the platform lock. A failed download, checksum check,
extraction, or validation cannot create a valid completion marker.

## Cache layout and reuse

The managed cache is shared through Gradle User Home:

```text
<GRADLE_USER_HOME>/caches/dev.elide/runtimes/<version>/<platform>/
<GRADLE_USER_HOME>/caches/dev.elide/runtimes/<version>/<platform>.lock
```

For example, the pinned Linux amd64 executable is:

```text
<GRADLE_USER_HOME>/caches/dev.elide/runtimes/1.5.1+20260903/linux-amd64/bin/elide
```

The runtime directory contains the extracted distribution and `.complete`, whose contents are the verified archive
SHA-256. A cache hit requires a parseable completion marker and the expected regular executable (`bin/elide` or
`bin/elide.exe`). Builds coordinate preparation with the sibling `<platform>.lock`, so concurrent builds do not publish
partial runtime directories.

To force a fresh download, stop builds using the cache and remove only the exact version/platform directory. The matching
lock file may be removed only when no build can be preparing that version/platform; it is safe to leave it in place because
the next build reuses or recreates it. Do not remove the parent `caches/dev.elide/runtimes` directory or another version's
platform entries to clear one runtime. The managed runtime cache is separate from a project's `.dev/dependencies/m2`
repository; deleting `.dev` does not remove a managed runtime.

## Offline builds and network guarantees

With `--offline`, managed preparation succeeds only when the selected cache entry is complete. A cache miss fails with
this diagnostic, including the exact version and path:

```text
Elide runtime version <version> is not cached at <GRADLE_USER_HOME>/caches/dev.elide/runtimes/<version>/<platform>
```

The remedy is to run a connected build once (for example, `./gradlew prepareElideRuntime -Pelide.runtime.mode=MANAGED`),
then rerun with `--offline`, or choose `PATH`/`elideBin` for an installed runtime. `PATH` mode has a hard network
guarantee: it never downloads an archive or checksum, never extracts a distribution, and never registers a managed
preparation task. If no usable executable is found, it fails with:

```text
Elide PATH runtime was requested but no executable was found
```

The remedy is to install a compatible `elide`/`elide.exe` on `PATH`, set `elideBin`, or choose `MANAGED` in a connected
build. `AUTO` can reach the network only after explicit and PATH lookup both fail and managed preparation is actually
requested.

## Error remedies

| Diagnostic | Remedy |
| --- | --- |
| `Unsupported Elide platform: ...` | Use Linux amd64/arm64, macOS arm64, or Windows amd64 for managed assets; otherwise use PATH/explicit runtime. |
| `Unable to download <URI>: HTTP <status>` | Check the version, platform asset, network access, and GitHub release availability; use PATH/explicit runtime if the asset is unavailable. |
| `Expected one SHA-256 checksum` | Check that the release `.sha256` asset contains exactly one 64-character hexadecimal digest, then retry from the official release. |
| `SHA-256 mismatch for Elide archive <URI>` | Remove any failed staging files, verify the release/checksum source, retry, and report a changed or corrupt asset. Do not bypass verification. |
| `Elide archive does not contain <filename>` | Use a release with the expected `bin/elide` or `bin/elide.exe` layout; the archive is not a usable Elide distribution. |
| `Unable to set executable permissions for <path>` | Use a filesystem that supports executable permissions or select a PATH/explicit runtime; do not mark an unverified archive usable. |
| `Refusing Elide archive entry ...` or `Unable to validate extracted Elide runtime` | Treat the archive as unsafe or malformed and retry from the official release; do not reuse the partial cache. |
| `Elide command failed: executable ..., working directory ..., exit code ...` | Check the selected executable, project directory, manifest, and bounded standard error/output; fix the Elide command or project inputs and rerun. |

The plugin captures only bounded diagnostics and redacts environment values. It does not print the full environment when an
Elide subprocess fails.
