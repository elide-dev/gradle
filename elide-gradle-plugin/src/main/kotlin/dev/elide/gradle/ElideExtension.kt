package dev.elide.gradle

import dev.elide.gradle.annotation.ElideGradleDsl
import dev.elide.gradle.cli.ElideCli
import dev.elide.gradle.configuration.ElideSettings
import dev.elide.gradle.service.ElideThreadPoolService
import dev.elide.gradle.task.exec.ElideCliExec
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

/**
 * Main extension for the Elide Gradle plugin.
 *
 * This class is registered under the `elide` block in the Gradle DSL and serves as the entry point for all
 * Elide-related configuration. It allows customization of build settings, runtime dependencies, and other
 * Elide-specific features.
 *
 * Example usage in a build script:
 * ```kotlin
 * elide {
 *   settings {
 *     // configure Elide settings here
 *   }
 * }
 * ```
 *
 * @param project The Gradle project instance where this extension is applied.
 */
@ElideGradleDsl
public open class ElideExtension internal constructor(
    private val project: Project,
    private val elideCli: Provider<ElideCli>,
    private val elideThreadPoolService: Provider<ElideThreadPoolService>,
    private val cliPreparatoryTasks: Array<TaskProvider<*>>,
) {
    /**
     * Configuration block for general Elide plugin settings.
     *
     * Provides access to project-level options such as build modes, target platforms, binary version,
     * and other behavior flags.
     */
    public val settings: ElideSettings = ElideSettings(project)

    /**
     * Applies an action to configure [ElideSettings] using the Gradle DSL.
     *
     * This allows fluent DSL usage such as:
     * ```kotlin
     * elide.settings {
     *  features.enableElideInstall = true
     * }
     * ```
     *
     * @param action A Gradle [Action] that operates on the [settings] instance.
     */
    public fun settings(action: Action<ElideSettings>) {
        action.execute(settings)
    }

    /**
     * Provides access to the core set of Elide runtime libraries, versioned according to the
     * configured Elide binary version.
     *
     * This includes Maven coordinates for Elide modules such as:
     * - `elide-core`: Pure Kotlin, cross-platform foundational utilities (annotations, encoding, crypto)
     * - `elide-base`: MPP data structures, logging, and general-purpose helpers
     * - `elide-graalvm`: Integration layer between Elide and GraalVM for native builds
     *
     * These libraries form the base runtime for most Elide projects, enabling Elide's core features and
     * compatibility across Kotlin/JVM, native image, and multiplatform contexts.
     *
     * Example usage:
     * ```kotlin
     * dependencies {
     *   implementation(elide.runtime.core)
     *   implementation(elide.runtime.graalvm)
     * }
     * ```
     *
     * See the [ElideLibraries] class for details on each module.
     */
    public val runtime: ElideLibraries = ElideLibraries(settings.binary.version)

    /**
     * Creates a task named [taskName] of type [ElideCliExec] with necessary defaults from a Gradle Plugin
     * and its extension.
     *
     * @param taskName The name of the task that will be created.
     */
    public fun exec(
        taskName: String,
        configure: Action<ElideCliExec>,
    ): TaskProvider<ElideCliExec> {
        return project.tasks.register<ElideCliExec>(taskName) {
            dependsOn(cliPreparatoryTasks)

            cli.set(elideCli)
            debug.set(settings.diagnostics.debug)
            verbose.set(settings.diagnostics.verbose)
            telemetry.set(settings.diagnostics.telemetry)
            binPath.set(binPath)

            usesService(elideThreadPoolService)

            configure.execute(this)
        }
    }
}