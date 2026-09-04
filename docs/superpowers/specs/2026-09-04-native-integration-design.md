# Native Elide Gradle integration

Status: implemented slice, ready for review

Base: `feat/settings-plugin`; keep the existing PR stack open.

## Outcome

Reuse the settings runtime for compilation and Java/Kotlin formatting. Make compiler outputs reusable through Gradle's build cache, preserve Gradle's source dependency analysis, and reuse Elide's existing Bazel protobuf worker protocol for warm compilation. Gradle owns dependency selection, verification, locking, offline resolution, and the classpath supplied to Elide.

## Contracts

- Keep JavaCompile and its source-set/lifecycle integration. Declare the Elide compiler identity and launcher as inputs; configure the executable at execution only after Gradle has fingerprinted those inputs. Validate this on every supported Gradle version because Gradle otherwise disables caching for arbitrary executable overrides.
- A build service owns bounded persistent compiler processes. A process handles one request at a time; separate processes allow parallel work. Use content digests for worker inputs, bounded protocol frames, request IDs, timeout and process teardown. Elide's worker cache remains an optimization; Gradle's outputs are authoritative.
- Format checks never mutate source files. Cache formatting of staged copies, compare those outputs with sources for checks, and apply only through explicitly invoked tasks. Java and Kotlin formatter arguments are independent inputs. Use the selected Elide distribution for both tools.
- Gradle-native dependency mode forbids the legacy installer/local repository combination and suppresses ambient CLASSPATH. Produce a deterministic resolved-artifact inventory for inspection; do not run an independent Maven resolver during compilation. Gradle's existing lockfile and verification metadata remain authoritative. Importing Elide manifests and interpreting binary Elide lockfiles are not implemented by this slice.
- Runtime/tool inputs, platform, compiler arguments and relevant environment must invalidate caches. Workspace and Gradle User Home relocation must preserve cache hits when content is identical.
- Remain compatible with Gradle 7.6.4 / Java 17, with functional coverage on 8.14.5 and 9.7.1. Verify real Elide compilation and formatting in addition to deterministic fixtures.

## Verification

Test no-op, clean-cache restore, relocated checkout, source edit/removal, dependency change, compiler change, configuration-cache reuse and parallel modules. Test repeated worker requests and failure isolation. Test formatting checks, explicit application, cache hits, and preservation of original sources during checking. Test Gradle dependency locking and offline resolution without an Elide install.

## Evidence and operational limits

The cache regression exercises Gradle 7.6.4, 8.14.5, and 9.7.1, including checkout relocation and independent-source incremental compilation. The real native fixture demonstrates one worker for main/test compilation and separate outputs under parallel multi-project execution. The managed-runtime smoke lane uses persistent compilation with the pinned distribution and a real Maven dependency.

The process pool is build-scoped, capped at four workers, and keyed by executable and working directory. It preserves relative-path semantics; modules with different working directories do not reuse the same process. Requests time out after five minutes. Formatters use one-shot commands because the inspected Elide formatter entry points do not expose the compiler worker protocol. Remote cache transport and Gradle User Home relocation are not separately exercised by the new tests; the task uses Gradle's cache implementation and content-based executable identity.
