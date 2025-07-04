package dev.elide.gradle.configuration

import dev.elide.gradle.annotation.ElideGradleDsl
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

@ElideGradleDsl
public class ElideDiagnosticsConfiguration(private val project: Project) {
    private inline val objects get() = project.objects
    private inline val providers get() = project.providers

    /**
     * Enables debug logging for Elide and prints debug information to the terminal.
     *
     * This property also respects the `--debug` flag passed to Gradle. In addition,
     * you may use `dev.elide.gradle.diagnostics.debug`.
     */
    public val debug: Property<Boolean> = objects.property<Boolean>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.diagnostics.debug")
                .map(String::toBooleanStrict)
                .orElse(project.provider { project.logger.isDebugEnabled })
        )

    /**
     * Enables verbose logging for the plugin.
     *
     * This property also respects the `--info` flag passed to Gradle,
     * which activates Gradle's own «verbose» mode. In addition,
     * you may use `dev.elide.diagnostics.verbose` property.
     */
    public val verbose: Property<Boolean> = objects.property<Boolean>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.diagnostics.verbose")
                .map(String::toBooleanStrict)
                .orElse(project.provider { project.logger.isInfoEnabled })
        )

    /**
     * Sets enable/disable status on all telemetry features within Elide. Applies
     * only to the tasks owned by the Gradle Plugin, otherwise you should manage it yourself
     * considering this property or an alias provided in [dev.elide.gradle.task.exec.ElideCliExec].
     *
     * This behavior can also be changed via `dev.elide.gradle.diagnostics.telemetry` property.
     */
    public val telemetry: Property<Boolean> = objects.property<Boolean>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.diagnostics.telemetry")
                .map(String::toBooleanStrict)
                .orElse(true)
        )
}