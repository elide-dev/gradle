package dev.elide.gradle.configuration.binary

import dev.elide.gradle.annotation.ElideGradleDsl
import org.gradle.api.Incubating
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property
import java.io.File

@ElideGradleDsl
public class ElideBinaryConfiguration(private val project: Project) {
    private inline val objects get() = project.objects
    private inline val providers get() = project.providers

    /**
     * Declares whether the Elide Gradle plugin should suppress build failure if binary
     * resolution fails.
     *
     * This only affects plugin-level behavior. It does **not guarantee** that Java or Kotlin
     * compilation tasks will succeed. For example, if [resolutionSource] is not `LocalOnly`
     * and the local binary cannot be used, the plugin will attempt to download the binary.
     * If the download fails and **Gradle is not explicitly running in offline mode**,
     * the build will still fail — even if `silentMode` is enabled.
     *
     * You can also configure this property via the Gradle property:
     * ```
     * dev.elide.gradle.bin.silentMode=true
     * ```
     */
    @Incubating
    public val silentMode: Property<Boolean> = objects.property<Boolean>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.bin.silentMode")
                .map(String::toBooleanStrict)
                .orElse(false)
        )

    /**
     * Specifies the expected version of the Elide binary to use.
     *
     * The resolved binary’s version will be validated against this value.
     * If not set (`null`), version validation is skipped. The absence of the value
     * will fail if it's expected Elide to be downloaded.
     *
     * You can also configure it through `dev.elide.gradle.bin.version` gradle property.
     */
    @Incubating
    public val version: Property<String> = objects.property<String>()
        .convention(providers.gradleProperty("dev.elide.gradle.bin.version"))

    /**
     * Enables strict version validation for the resolved Elide binary.
     *
     * When set to `true`, the plugin will fail or download a new binary (depending on [resolutionSource])
     * the build if the local resolved binary's version does not exactly match the configured [version].
     * If `false`, version mismatches may be tolerated depending on the selected [resolutionSource].
     *
     * You can also configure it through `dev.elide.gradle.bin.strictVersionCheck` gradle property.
     */
    @Incubating
    public val strictVersionCheck: Property<Boolean> = objects.property<Boolean>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.bin.strictVersionCheck")
                .map(String::toBooleanStrict)
                .orElse(false)
        )

    /**
     * Sets the resolution strategy when resolving the Elide's binary application. By default, we use
     * locally downloaded elide unless the specified [version] is not met. This behavior might be changed
     * by setting up [resolutionSource] to [ElideBinaryResolutionSource.LocalOnly] or [ElideBinaryResolutionSource.Project].
     */
    public val resolutionSource: Property<ElideBinaryResolutionSource> = objects.property<ElideBinaryResolutionSource>()
        .convention(ElideBinaryResolutionSource.LocalOnly(null))

    /**
     * Configures the Elide binary resolution to **use only a local binary** available on the system.
     *
     * This disables any version requirement and clears any explicitly set binary path.
     * The plugin will **not** attempt to download or resolve any binary from the project directory.
     */
    public fun useLocalOnly(path: File? = null) {
        version.unset()
        this.strictVersionCheck.set(strictVersionCheck)
        resolutionSource.set(ElideBinaryResolutionSource.LocalOnly(path?.toPath()))
    }

    /**
     * Configures the Elide binary resolution to **prefer a local binary** if it's present on
     * the local machine. In addition, if [strictVersionCheck] is enabled, it's checked whether the version is the same.
     * If the local binary is missing or incompatible, the plugin may fallback to other resolution strategies (e.g., downloading the required version).
     *
     * @param version The minimum compatible version string to require from the local binary.
     *                Must not be blank or `"latest"`.
     * @param strictVersionCheck Determines whether is to check the version against given one.
     * @throws IllegalArgumentException if [version] is blank or equals `"latest"`.
     */
    @Incubating
    public fun useLocalIfApplicable(
        version: String,
        strictVersionCheck: Boolean = true,
        downloadPath: File? = null,
    ) {
        require(version.isNotBlank()) {
            "elide.binary.useLocalBinaryIfApplicable does not accept blank version."
        }
        require(version != "latest") {
            "elide.binary.useLocalBinaryIfApplicable does not accept 'latest' as a version."
        }

        this.strictVersionCheck.set(strictVersionCheck)
        this.version.set(version)
        resolutionSource.set(ElideBinaryResolutionSource.LocalIfApplicable(downloadPath?.toPath()))
    }

    /**
     * Configures the Elide binary resolution to **always use a project-scoped binary**, which
     * is downloaded and managed inside the project. This ignores any local binaries on the system.
     *
     * @param version The version string of the binary to download and use.
     *                Must not be blank or `"latest"`.
     * @throws IllegalArgumentException if [version] is blank or equals `"latest"`.
     */
    public fun useProjectBinary(
        version: String,
        downloadPath: File? = null,
    ) {
        require(version.isNotBlank()) {
            "elide.binary.useLocalBinaryIfApplicable does not accept blank version."
        }
        require(version != "latest") {
            "elide.binary.useLocalBinaryIfApplicable does not accept 'latest' as a version."
        }

        this.strictVersionCheck.unset()
        this.version.set(version)
        resolutionSource.set(ElideBinaryResolutionSource.Project(downloadPath?.toPath()))
    }
}