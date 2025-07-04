package dev.elide.gradle.configuration.binary

import java.nio.file.Path

public sealed interface ElideBinaryResolutionSource {
    /**
     * Always use a locally installed Elide binary (e.g. from `$PATH` or `$HOME/elide`), regardless of version.
     *
     * @param path Path to elide's binary folder. If unspecified, it's resolved either from `$PATH` or from home directory,
     * otherwise plugin will throw an exception.
     */
    public class LocalOnly(public val path: Path? = null) : ElideBinaryResolutionSource

    /**
     * Resolution strategy that prefers using a local Elide binary over downloading a new one.
     *
     * When enabled:
     * - If strict version checking is enabled and the local binary version matches the required version,
     *   the plugin will **download a new binary**.
     * - Otherwise, the presence of **any local Elide binary version** is sufficient to avoid downloading.
     *
     * @param downloadPath Optional path within the project where the plugin should download the Elide binary
     *                     if it cannot use a preinstalled binary on the machine.
     *                     Defaults to `build/elide-runtime/bin` if not specified.
     */
    public class LocalIfApplicable(public val downloadPath: Path? = null) : ElideBinaryResolutionSource

    /**
     * Always download and use the Elide binary scoped to the project.
     * Local versions will be ignored.
     *
     * @param downloadPath Path within the project to which plugin should download the elide.
     */
    public class Project(public val downloadPath: Path? = null) : ElideBinaryResolutionSource
}
