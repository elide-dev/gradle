package dev.elide.gradle

import org.gradle.api.provider.Provider

public class ElideLibraries(
    elideVersion: Provider<String>
) {
    /**
     * Provides the Maven coordinate for the `elide-core` artifact, using the
     * configured in [dev.elide.gradle.configuration.ElideSettings.binary] Elide version.
     *
     * The `elide-core` module offers universal, pure-Kotlin utilities and declarations
     * that form the foundational layer of the Elide framework and runtime. It ensures
     * broad platform compatibility by relying only on Kotlin team's official dependencies,
     * such as the Kotlin standard library and KotlinX.
     *
     * This module includes cross-platform APIs, annotations, encoding utilities (Base64, Hex),
     * cryptography enumerations, and platform-specific defaults. It is designed for
     * maximum portability and is widely used throughout Elide tooling and applications.
     *
     * **[Learn more](https://github.com/elide-dev/elide/blob/main/packages/core/module.md)**
     */
    public val core: Provider<String> = elideVersion.map { "dev.elide:elide-core:$it" }

    /**
     * Provides the Maven coordinate for the `elide-core` artifact, using the
     * configured in [dev.elide.gradle.configuration.ElideSettings.binary] Elide version.
     *
     * - **Annotations:** Common annotations used across the Elide framework and runtime
     * - **Codecs:** Multiplatform-capable implementations of Base64, hex, and other common encoding tools
     * - **Crypto:** Core crypto, hashing, and entropy primitives (for example, `UUID`)
     * - **Structures:** Data structures in MPP and pure Kotlin for sorted maps, sets, and lists
     * - **Logging:** Multiplatform-capable logging, which delegates to platform logging tools
     *
     * **[Learn more](https://github.com/elide-dev/elide/blob/main/packages/base/module.md)**
     */
    public val base: Provider<String> = elideVersion.map { "dev.elide:elide-base:$it" }

    /**
     * Provides the Maven coordinate for the `elide-graalvm` artifact, using the
     * configured in [dev.elide.gradle.configuration.ElideSettings.binary] Elide version.
     *
     * The `elide-graalvm` module serves as the main integration layer between Elide and GraalVM,
     * and powers the core of the Elide runtime. It enables Elide to operate in environments
     * where GraalVM is used, supporting GraalVM-specific features and runtime behavior.
     *
     * This module is central to Elide’s native execution model and is typically included in projects
     * targeting GraalVM or native image builds.
     *
     * **[Learn more](https://github.com/elide-dev/elide/blob/main/packages/graal/module.md)**
     */
    public val graalvm: Provider<String> = elideVersion.map { "dev.elide:elide-graalvm:$it" }
}