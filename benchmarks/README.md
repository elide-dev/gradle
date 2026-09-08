# Build benchmarks

Two independent single-module Java 17 CLI projects share all application sources,
tests, and packaging configuration:

| CodSpeed series | Compiler |
| --- | --- |
| `java-cli/clean-build/javac` | Standard Gradle `JavaCompile` / JDK javac |
| `java-cli/clean-build/elide` | This checkout's Elide plugin, managed Elide `1.5.1+20260903` |

The Elide variant uses the default one-shot compiler and Gradle-owned dependencies.
Its plugin JAR is built and copied before measurement. Neither measured project
includes the plugin build. The application has no external dependencies; its
small test harness also requires no libraries. Both builds compile main and test
sources, execute the same assertions, and produce a JAR plus application ZIP/TAR.
The standard framework-based `test` task is disabled; `check` runs `cliTest` instead.

## Measurement contract

Each sample measures `clean build` end to end, including the wrapper/client,
single-use Gradle daemon startup/shutdown, project configuration, cleaning,
compilation, CLI tests, and packaging. It is not an isolated compiler benchmark.
The shell script overhead is included equally in both variants.

- Gradle is pinned by the repository wrapper (currently 9.7.1).
- CI measures on Elide's dedicated `linux-amd64-bench` cluster using Temurin
  `17.0.16+8`, inside a digest-pinned Ubuntu 24.04 container. Both variants use the same
  container, which starts before timing. The measurement container permits
  CodSpeed's profiler to configure kernel perf sysctls on the benchmark runner.
  `CODSPEED_ISOLATION=false` uses the runner/container CPU allocation without
  requesting a second systemd scope inside the container.
  Local runs must set
  `JAVA_HOME` to a Java 17 JDK; measurements from other JDKs/machines are not CI baselines.
- Both variants use two Gradle workers and disable persistent daemons, the build
  cache, and the configuration cache. Compilation outputs are removed each round.
- Preparation provisions Elide, compiles the plugin, and warms script/dependency
  caches outside measurement. Measured builds run offline using a dedicated
  `benchmarks/build/gradle-home`, excluding user Gradle configuration/init scripts.
  OS filesystem caches remain warm. This is not a first-install benchmark.
- CodSpeed runs the variants sequentially with 20 seconds of warmup and ten
  measurement rounds. The CI job has a 30-minute timeout.
- Preparation verifies two consecutive offline builds of each variant: compilation,
  test execution, JAR and distribution tasks must execute each time, and both JARs
  must print `Hello, Ada Lovelace!`. Elide must report native compilation of both
  source sets, guarding against accidental fallback to javac. Failed commands fail the job.

Keep the series names stable to retain history. Change names when changing the
measurement contract (for example, adding a warm-daemon or incremental scenario).
This tiny fixture intentionally captures fixed build overhead. It does not establish
performance for large applications, annotation processors, incremental compilation,
cache restoration, or persistent compiler workers. Add separate series for those.

## Run locally

```sh
export JAVA_HOME=/path/to/jdk-17
bash benchmarks/prepare.sh
bash benchmarks/run.sh javac
bash benchmarks/run.sh elide
```

Preparation includes correctness verification; rerun it after plugin changes to
refresh the copied JAR. To repeat only correctness checks, use
`bash benchmarks/verify.sh`. Verification logs and environment metadata are in
`benchmarks/build/` (ignored by Git).

For tracked measurements on Linux, install the [CodSpeed CLI](https://codspeed.io/docs/cli),
authenticate/link the repository, prepare as above, and run from the repository root:

```sh
codspeed run --mode walltime
```

The pinned CodSpeed 5.2.1 command harness has no macOS ARM download. The shell-based
build verification above works on macOS; use Linux/CI for CodSpeed measurement.

## Continuous reporting

`.github/workflows/on.benchmark.yml` runs on pull requests, pushes to `main`, daily,
and manual dispatch. The CodSpeed action reads root `codspeed.yml` directly and
uploads wall-clock results. Its action SHA pins the bundled runner version.
CI artifacts retain preparation verification logs and toolchain metadata.
An independent GitHub-hosted Ubuntu AMD64 smoke job uses the same container image
and verifies both fixtures before measurement on `linux-amd64-bench`.
Environment artifacts include the OS, glibc version,
page size, and CPU features, and are captured before compilation so startup
failures retain diagnostics.

The repository must be activated in CodSpeed. Public uploads use GitHub OIDC
(`id-token: write`); no repository upload secret is needed. See
[CodSpeed authentication](https://codspeed.io/docs/integrations/ci/github-actions/configuration)
and [walltime guidance](https://codspeed.io/docs/instruments/walltime).

The benchmark job requires the `linux-amd64-bench` runner label to be available to
this repository. Keep machines serving that label consistent in CPU and operating
environment; establish new series/baselines when changing runner hardware.

Two Elide Linux ARM64 compatibility failures observed on CodSpeed Macro runners
are tracked separately: [glibc symbol requirements](https://github.com/elide-dev/WHIPLASH/issues/1739)
and [CPU feature requirements](https://github.com/elide-dev/WHIPLASH/issues/1738).
The benchmarks execute natively on the AMD64 cluster, without emulation or CPU-check overrides.

PR comparisons need a recorded baseline. The first merge to `main` seeds that
baseline; stacked branches may initially show results without a base comparison.
Start with reporting and inspect normal variation before making regression checks
required. No speedup or regression threshold is asserted by this initial suite.
