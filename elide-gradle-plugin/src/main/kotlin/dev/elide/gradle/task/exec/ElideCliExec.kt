package dev.elide.gradle.task.exec

import dev.elide.gradle.ElideGradlePlugin
import dev.elide.gradle.cli.ElideCli
import dev.elide.gradle.cli.ElideCliInvocation
import dev.elide.gradle.internal.elideDebug
import dev.elide.gradle.internal.elideError
import dev.elide.gradle.service.ElideThreadPoolService
import org.gradle.api.DefaultTask
import org.gradle.api.Transformer
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Console
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * Gradle task for executing the Elide CLI with configured arguments,
 * debug and verbose flags, and capturing output streams for logging.
 *
 * Supports optional transformation of the underlying [ElideCliInvocation]
 * to customize invocation behavior or result handling.
 */
public abstract class ElideCliExec : DefaultTask() {
    /**
     * Sets whether to ignore absence of Elide's binary and do nothing in that case.
     */
    @get:Input
    public abstract val silentMode: Property<Boolean>

    /**
     * Sets path to the Elide's binary.
     */
    @get:Input
    internal abstract val binPath: Property<String>

    /**
     * The [ElideCli] instance used to create CLI invocations.
     */
    @get:Internal
    public abstract val cli: Property<ElideCli>

    /**
     * Enables or disables debug mode for the Elide CLI binary.
     * By default, uses the debug flag from the Elide diagnostics configuration.
     */
    @get:Internal
    @get:Console
    public abstract val debug: Property<Boolean>

    /**
     * Enables or disables verbose mode for the Elide CLI binary.
     * By default, uses the verbose flag from the Elide diagnostics configuration.
     */
    @get:Internal
    @get:Console
    public abstract val verbose: Property<Boolean>

    /**
     * Enables or disables telemetry used in Elide.
     *
     * @see dev.elide.gradle.configuration.ElideDiagnosticsConfiguration.telemetry
     */
    @get:Internal
    @get:Console
    public abstract val telemetry: Property<Boolean>

    /**
     * Arguments passed to the Elide CLI invocation.
     */
    @get:Input
    public abstract val args: ListProperty<String>

    /**
     * Shared thread pool service used for executing CLI invocation tasks.
     */
    @get:ServiceReference(ElideGradlePlugin.THREAD_POOL_SERVICE_NAME)
    internal abstract val threadPoolService: ElideThreadPoolService

    @get:Internal
    private var invocationTransformer: Transformer<ElideCliInvocation<*>, ElideCliInvocation<*>>? = null

    /**
     * Sets the output file to write a result. Unless you write something to it,
     * a task will rerun on every build.
     */
    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    /**
     * Sets an optional transformer to customize the [ElideCliInvocation].
     *
     * If specified, this transformer will be applied to the invocation
     * before execution, allowing users to modify logging, output handling, or other behaviors.
     *
     * @param transformer a [Transformer] that maps an invocation to a new invocation.
     */
    public fun useInvocation(transformer: Transformer<ElideCliInvocation<*>, ElideCliInvocation<*>>? = null) {
        invocationTransformer = transformer
    }

    /**
     * Adds arguments to the invocation.
     *
     * @param args vararg list of CLI arguments.
     */
    public fun args(vararg args: String) {
        this.args.addAll(args.toList())
    }

    init {
        outputs.upToDateWhen {
            val file = outputFile.get().asFile
            file.exists() && file.length() != 0L
        }

        // Skip task execution if no arguments are specified.
        onlyIf("no arguments specified") {
            args.getOrElse(emptyList()).isNotEmpty()
        }

        onlyIf("Elide binary is not resolvable or non-executable; skipping due to silent mode enabled") {
            val path = Path(binPath.get()).toRealPath()
            path.exists() && path.isExecutable() && silentMode.get()
        }
    }

    /**
     * Task action that creates and executes the Elide CLI invocation
     * with the configured arguments, debug/verbose flags, and thread pool.
     *
     * If an invocation transformer is set via [useInvocation], applies it before executing.
     * Otherwise, logs stdout and stderr live, and fails on non-success exit codes or exceptions.
     */
    @TaskAction
    public fun execute() {
        val invocation = cli.get().createInvocation(
            buildList {
                if (debug.getOrElse(false))
                    add("--debug")

                if (verbose.getOrElse(false))
                    add("--verbose")

                addAll(args.getOrElse(emptyList()).filter { it.isNullOrEmpty() })
            },
            threadPoolService.executor,
        ).let { invocation ->
            if (invocationTransformer == null) {
                invocation
                    .consumeStdout { stream ->
                        stream.forEach {
                            logger.elideDebug(it)
                        }
                    }.consumeStderr { stream ->
                        stream.forEach {
                            logger.elideError(it)
                        }
                    }
                    .onNonZeroExitCode { exitCode ->
                        error("Failed to invoke `elide ${args.get().joinToString(" ")}`. Process finished with non-success exit code: $exitCode")
                    }.onException { exception ->
                        throw IllegalStateException("Failed to invoke `elide ${args.get().joinToString(" ")}`", exception)
                    }
            } else {
                invocationTransformer!!.transform(invocation)
            }
        }

        outputFile.get().asFile.createNewFile()

        invocation.execute()
    }
}