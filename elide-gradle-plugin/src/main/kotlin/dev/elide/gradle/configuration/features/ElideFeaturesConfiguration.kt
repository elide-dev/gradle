package dev.elide.gradle.configuration.features

import dev.elide.gradle.annotation.ElideGradleDsl
import org.gradle.api.Incubating
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

@ElideGradleDsl
public class ElideFeaturesConfiguration(private val project: Project, manifest: RegularFileProperty) {
    private inline val objects get() = project.objects
    private inline val providers get() = project.providers

    /**
     * Determines whether to use Elide's dependency resolver and downloader.
     *
     * Defaults to `true` when an `elide.pkl` file is present in the project root.
     */
    public val enableElideInstall: Property<Boolean> = objects.property<Boolean>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.features.enableElideInstall")
                .map(String::toBooleanStrict)
                .orElse(manifest.map { it.asFile.exists() })
        )

    /**
     * Specifies whether is to use Elide's maven resolver. The difference between enabling it
     * and [enableElideInstall] is that [generatePklBuildConfiguration] enables task to write a `.pkl` file
     * with list of dependencies and repositories to be imported and used in a `project.pkl`.
     *
     * @see dev.elide.gradle.task.GenerateModuleBuildConfigurationTask
     */
    public val generatePklBuildConfiguration: Property<Boolean> = objects.property<Boolean>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.features.generatePklBuildConfiguration")
                .map(String::toBooleanStrict)
        )

    /**
     * Determines whether Elide should be used to compile Java instead of Gradle’s default Java compiler. This behavior
     * can also be changed via `dev.elide.gradle.features.javacStrategy` property.
     *
     * Defaults to [ElideIntegrationStrategy.PREFER_ELIDE] when the plugin is applied to the project.
     */
    public val javacStrategy: Property<ElideIntegrationStrategy> = objects.property<ElideIntegrationStrategy>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.features.javacStrategy")
                .map { ElideIntegrationStrategy.valueOf(it.uppercase().replace('-', '_')) }
                .orElse(ElideIntegrationStrategy.PREFER_ELIDE)
        )

    /**
     * Determines whether Elide should be used to generate `javadoc` instead of Gradle’s default. This behavior
     * can also be changed via `dev.elide.gradle.features.javadocStrategy` property.
     *
     * Defaults to `true` when the plugin is applied to the project.
     */
    public val javadocStrategy: Property<ElideIntegrationStrategy> = objects.property<ElideIntegrationStrategy>()
        .convention(
            providers.gradleProperty("dev.elide.gradle.features.javadocStrategy")
                .map { ElideIntegrationStrategy.valueOf(it.uppercase().replace('-', '_')) }
                .orElse(ElideIntegrationStrategy.PREFER_ELIDE)
        )
}