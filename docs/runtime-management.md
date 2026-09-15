# Runtime management

The settings plugin defines one Elide runtime policy for the build. Projects opt in independently by applying `dev.elide`.
Selection is lazy: applying the project plugin registers the extension and task wiring, but does not download, extract,
or execute Elide. Invoke `prepareElideRuntime` directly or let a consuming task depend on it. No runtime mode creates or
modifies files below `JAVA_HOME`.

## Modes and precedence

`ElideRuntimeMode` has three values:

| Mode | Selection | Network behavior |
| --- | --- | --- |
| `AUTO` | Explicit executable, then the first usable executable on `PATH`, then managed runtime | May download only when it reaches managed fallback |
| `PATH` | Explicit executable, then the first usable executable on `PATH`; no fallback | Never downloads or extracts a managed runtime; its preparation task is skipped |
| `MANAGED` | The configured version and platform cache entry | Downloads on a cache miss unless Gradle is offline |

In `AUTO`, an explicit executable is considered before `PATH`. A PATH lookup preserves directory order and uses the
platform executable name (`elide` on Unix-like systems and `elide.exe` on Windows). Unix candidates must be regular
executable files; Windows candidates must be regular files. `MANAGED` intentionally ignores explicit and PATH candidates.

Each `PATH` candidate is additionally run with `--version` and must report at least the configured `runtime.version`.
Only the semantic version is compared, so `1.5.1+20260903.4c6cdc7` satisfies a requirement of `1.5.1+20260903`, and
components are compared numerically, so `1.10.0` is newer than `1.9.9`. Candidates are probed in directory order and
probing stops at the first acceptable one; a candidate whose version cannot be read is treated as unusable. In `AUTO`
an out-of-date candidate is skipped and selection falls through to the managed runtime, so a stale installed Elide
causes a download rather than a surprising toolchain. In `PATH` the build fails and names both versions. An explicit
`runtime.executable` is a deliberate choice and is never probed.

Probing starts a process, so it happens only when a task actually needs the runtime — not when the plugin is applied,
and not for configuration-only invocations such as `./gradlew help`.

`runtime.executable` is the explicit project override. `runtime.version` defaults to `1.5.1+20260903`. The deprecated
`resolveElideFromPath` property remains for source compatibility: an explicitly supplied `true` selects `PATH`, and an
explicit `false` selects `MANAGED`.

For example, select and pin the managed runtime once in the consumer settings script before invoking
`./gradlew prepareElideRuntime`:

```kotlin
import dev.elide.gradle.ElideRuntimeMode

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

The runtime version can instead come from a version catalog with `versionFrom("libs", "elide")`. The settings plugin
shares the resolved policy and managed-preparation coordination across every opted-in project in a multi-project build.

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
The resolver detects the host platform before runtime selection. Unknown operating systems, Windows ARM64, and other
unsupported combinations therefore fail with `Unsupported Elide platform: <os>/<arch>` before PATH or explicit fallback
is considered; use a supported host/platform. macOS amd64 is a recognized platform mapping, but this pinned release lacks
its managed asset, so PATH or `elideBin` can be used there.

The plugin requests the archive and its adjacent `.sha256` file. It parses one 64-character SHA-256 value, hashes the
downloaded archive, and compares the values before extraction. The verified archive's entry metadata is then inspected
before anything is written to disk: any entry naming an absolute path or a `..` segment, and any symbolic or hard link,
is refused with `Refusing Elide archive entry ...`, and the archive is discarded. Extraction occurs in a unique sibling
staging directory;
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
`bin/elide.exe`); Unix entries also require executable permission, while Windows accepts a regular `.exe` file. If a Unix
cache entry loses its execute bit, preparation reacquires the platform lock and repairs the verified executable without
downloading again. Builds coordinate preparation with the sibling `<platform>.lock`, so concurrent builds do not publish
partial runtime directories.

A managed cache entry is large. For `1.5.1+20260903` on Linux amd64 the archive download is roughly 753 MiB and the
extracted entry is about **2.5 GB**, of which `bin/elide` is ~512 MB and `bin/elide.debug` is ~1.1 GB. Budget at least
3 GB of free space per version/platform entry, and remember that each distinct version or platform is a separate entry.
Provisioning extracts through the JVM's temporary directory, so a small `/tmp` (a 4 GB tmpfs, for example) can fail with
`No space left on device` even when Gradle User Home has room; set `TMPDIR` to a location on disk if so.

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

The remedy is to run a connected build once (for example, `./gradlew prepareElideRuntime` after configuring `MANAGED` in
the settings script), then rerun with `--offline`, or choose `PATH`/`runtime.executable` for an installed runtime. `PATH`
mode has a hard managed-runtime network guarantee: it never downloads a managed runtime archive or checksum and never
extracts that archive; the lazily registered preparation task is skipped. If `install = true`, its separate `elide install`
task may still resolve project dependencies. If no usable executable is found, it
fails with:

```text
Elide PATH runtime was requested but no executable was found
```

The remedy is to install a compatible `elide`/`elide.exe` on `PATH`, set `runtime.executable`, or choose `MANAGED` in a
connected build. `AUTO` can reach the managed-runtime network only after explicit and PATH lookup both fail and managed
preparation is actually requested.

## Error remedies

| Diagnostic | Remedy |
| --- | --- |
| `Unsupported Elide platform: ...` | For mapped macOS amd64 with its pinned asset absent, use `PATH` or explicit `elideBin`; unknown operating systems and Windows ARM64 require a supported host/platform because detection fails before runtime selection. |
| `Unable to download <URI>: HTTP <status>` | Check the version, platform asset, network access, and GitHub release availability; use PATH/explicit runtime if the asset is unavailable. |
| `Expected one SHA-256 checksum` | Check that the release `.sha256` asset contains exactly one 64-character hexadecimal digest, then retry from the official release. |
| `SHA-256 mismatch for Elide archive <URI>` | Remove any failed staging files, verify the release/checksum source, retry, and report a changed or corrupt asset. Do not bypass verification. |
| `Elide archive does not contain <filename>` | Use a release with the expected `bin/elide` or `bin/elide.exe` layout; the archive is not a usable Elide distribution. |
| `Unable to set executable permissions for <path>` | Use a filesystem that supports executable permissions or select a PATH/explicit runtime; do not mark an unverified archive usable. |
| `Refusing Elide archive entry ...` or `Unable to validate extracted Elide runtime` | Treat the archive as unsafe or malformed and retry from the official release; do not reuse the partial cache. |
| `Elide PATH runtime ... reports version ..., but ... or newer is required` | The installed Elide predates the configured `runtime.version`. Upgrade it, lower `runtime.version`, set `runtime.executable` to bypass the check, or choose `MANAGED`. `AUTO` skips the candidate instead of failing. |
| `Elide command failed: executable ..., working directory ..., exit code ...` | Check the selected executable, project directory, manifest, and bounded standard error/output; fix the Elide command or project inputs and rerun. |

The plugin captures only bounded diagnostics and redacts inherited environment values that look like credentials. A
value is redacted when either its name or its own shape says so:

- a name *segment* matches a sensitive word (`TOKEN`, `SECRET`, `PASSWORD`, `KEY`, `AUTH`, `PAT`, `DSN` and similar),
  with the name split on `_`, `-` and `.`. Matching whole segments rather than substrings is what keeps `KEYBOARD` and
  `MONKEY` out of it, and a short list of known-benign names (`SSH_AUTH_SOCK`, `XDG_SESSION_TYPE`, `SESSION_MANAGER`
  and friends) is excluded outright;
- or the value carries a recognizable credential regardless of its name — a known token prefix such as `ghp_`,
  `github_pat_`, `xoxb-` or a PEM header, or a URL with inline credentials such as
  `postgres://user:password@host/db`. This covers conventions like `GH_PAT` and `DATABASE_URL` that say nothing in
  their name.

Name-matched values must also be at least six bytes long, because substituting a one- or two-character value such as
`DISPLAY=:1` corrupts far more output than it protects. Ordinary variables such as `PWD`, `USER`, `HOME` and `LANG` are
left alone, so paths, identifiers and line numbers in a failing command's output stay readable. The plugin does not
print the full environment when an Elide subprocess fails.
