package dev.elide.gradle.task

import dev.elide.gradle.ElideGradlePlugin
import dev.elide.gradle.cli.ElideCli
import dev.elide.gradle.cli.ElideCliInvocationResult
import dev.elide.gradle.cli.getSuccessValueOrElse
import dev.elide.gradle.configuration.binary.ElideBinaryConfiguration
import dev.elide.gradle.configuration.binary.ElideBinaryResolutionSource
import dev.elide.gradle.service.ElideThreadPoolService
import dev.elide.gradle.task.exec.ElideCliExec
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.notExists
import kotlin.io.path.setPosixFilePermissions

public abstract class ElideCheckVersionTask : DefaultTask() {

    @get:Internal
    internal abstract val cli: Property<ElideCli>

    /**
     * Sets path to the Elide's binary.
     */
    @get:Input
    internal abstract val binPath: Property<String>

    /**
     * Sets whether to ignore absence of Elide's binary. The task will still fail [strictVersionCheck]
     * is enabled and versions don't match.
     */
    @get:Input
    public abstract val silentMode: Property<Boolean>

    /**
     * Sets the target version of the Elide to check against.
     *
     * @see ElideBinaryConfiguration.version
     */
    @get:Input
    internal abstract val targetVersion: Property<String>

    /**
     * Sets the strategy of resolving Elide binary. If not [ElideBinaryResolutionSource.LocalOnly],
     * the task is skipped.
     */
    @get:Input
    internal abstract val resolutionSource: Property<ElideBinaryResolutionSource>

    /**
     * Sets whether to check the version. If false, a task is skipped.
     */
    @get:Input
    internal abstract val strictVersionCheck: Property<Boolean>

    /**
     * Shared thread pool service used for executing CLI invocation tasks.
     */
    @get:ServiceReference(ElideGradlePlugin.THREAD_POOL_SERVICE_NAME)
    internal abstract val threadPoolService: ElideThreadPoolService

    /**
     * The cached resolved version of the Elide.
     */
    @get:OutputFile
    public abstract val versionFile: RegularFileProperty

    init {
        group = "Elide"
        description = "Validates the elide's binary version if strict check is enabled and it's a local-only resolution source"

        onlyIf("`elide.binary.strictVersionCheck` is set to false") {
            strictVersionCheck.get()
        }
        onlyIf("`elide.binary.resolutionSource` is not LocalOnly or LocalIfApplicable") {
            val source = resolutionSource.get()

            source is ElideBinaryResolutionSource.LocalOnly ||
                source is ElideBinaryResolutionSource.LocalIfApplicable
        }
        onlyIf("Local binary is not found and silent mode is enabled") {
            Path(binPath.get()).exists() || silentMode.get()
        }
    }

    @TaskAction
    public fun check() {
        val bin = Path(binPath.get()).toRealPath()

        if (bin.notExists()) {
            error("Unable to validate the version of Elide: binary does not exist at specified path: ${bin.absolutePathString()}.")
        }

        if (!bin.isExecutable() && !bin.toFile().setExecutable(true)) {
            error("Unable to validate the version of Elide: binary is not executable and cannot be set from the build. Try `chmod +x ${binPath.get()}`.")
        }

        val version = cli.get().getVersion(threadPoolService.executor)
            .getSuccessValueOrElse { failure ->
                when (failure) {
                    is ElideCliInvocationResult.Error -> throw IllegalStateException(
                        "Unable to validate version of Elide",
                        failure.exception
                    )
                    is ElideCliInvocationResult.ExitFailure -> error("Unable to validate version of Elide: non-zero exit. Exit code: ${failure.exitCode}")
                }
            }

        if (version != targetVersion.get() && resolutionSource.get() is ElideBinaryResolutionSource.LocalOnly) {
            error("Elide version check failed due to version mismatch: expected ${targetVersion.get()}, but got $version.")
        }

        versionFile.get().asFile.writeText(version)
    }
}