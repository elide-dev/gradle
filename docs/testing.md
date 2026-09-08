# Integration testing

Run the deterministic suites with `./gradlew build compatibilityTest`. These use Gradle TestKit consumer builds,
local repositories, and controlled runtime fixtures, alongside unit tests for the worker protocol and lifecycle.

Run the real native suite separately:

```sh
ELIDE_INTEGRATION_EXECUTABLE="$(command -v elide)" ./gradlew realNativeIntegrationTest
./gradlew realRuntimeSmoke -Pelide.runtime.mode=MANAGED
```

On PowerShell, set `$env:ELIDE_INTEGRATION_EXECUTABLE = (Get-Command elide).Source`, then run
`./gradlew.bat realNativeIntegrationTest`. The native task fails when its binary is missing; it never silently skips
or restores its own test results from cache. The consumer builds inside the tests intentionally use caches.

PR CI installs pinned Elide `1.5.1+20260903` with a commit-pinned `elide-dev/setup-elide` action and requires the native
suite on Linux, macOS, and Windows, plus the current Gradle/JDK build lanes. The managed smoke test independently
provisions its runtime with an isolated Gradle User Home and PATH, so setup-elide cannot mask a provisioning failure.
All build lanes upload HTML and XML test reports even after failures.

## Coverage contracts

| Boundary | Regression evidence |
| --- | --- |
| Settings and provisioning | Explicit opt-in, inherited/overridden/catalog runtime versions, isolated projects, checksum failures, offline reuse, parallel provisioning; real managed install/compile/run |
| Gradle compilation | Fixture matrix checks precise incremental source selection and compiler-content invalidation |
| Real compilation | Gradle 7.6.4/8.14.5/9.7.1, one-shot and persistent: HTTP cache upload/restoration with local cache disabled, relocated checkout restoration, changed-source output, deleted-class cleanup; persistent compilation invalidates a replaced dependency JAR |
| Worker service | Real main/test process reuse, compilation-error recovery in the same process, parallel module isolation and configuration-cache reuse; unit tests cover protocol framing and process shutdown |
| Formatters | Real javaformat and ktfmt: check preserves sources, clean local-cache restoration, apply changes both languages, style changes invalidate outputs, deleted Kotlin source removes staged output, unsafe arguments rejected |
| Gradle-owned dependencies | Transitive conflict selection, locks, offline report restoration, catalog dependency export, checksum verification rejects tampering, ambient classpath exclusion, conflicting installer rejection |

Some shell-fixture tests remain Unix-only; the native compilation matrix and native formatter tests have no OS skip.
Assertions check task outcomes and produced artifacts, not elapsed-time thresholds.

## Remaining coverage and scope

No suite can establish every possible compiler or project behavior. Still useful to expand: annotation processors,
JPMS, worker crash/timeout/cancellation against a real binary, large classpath/source batches, cross-Gradle-User-Home
cache relocation, and authenticated/TLS remote caches. The HTTP test covers transport and output correctness using a
loopback server, not a hosted cache service. Full Elide manifest/binary-lock interoperability is not implemented and
must not be inferred from Gradle dependency-mode tests.

## Benchmark follow-up

Add a separate, non-gating benchmark workflow once correctness lanes are stable. Use fixed generated projects with
1, 10, and 50 modules and compare stock javac, one-shot Elide, and persistent Elide on identical sources and toolchains.
Measure cold clean builds, warm no-op builds, method-body and ABI edits, clean local/remote cache restoration, and
javaformat/ktfmt cold/warm staging. Keep compiler mode and Gradle daemon/cache state explicit.

Record warmups and repeated samples, median/p95 wall time, native process count, CPU time, peak memory, runtime/JDK/
Gradle versions, and runner hardware. Publish raw samples as artifacts; keep correctness checks alongside timings.
Do not claim a performance gain from cache hits or worker reuse alone, and do not impose timing gates on shared CI
runners before measuring variance and selecting a baseline.
