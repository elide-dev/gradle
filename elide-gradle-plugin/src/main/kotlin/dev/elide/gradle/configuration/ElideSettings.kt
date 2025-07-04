package dev.elide.gradle.configuration

import dev.elide.gradle.annotation.ElideGradleDsl
import dev.elide.gradle.configuration.binary.ElideBinaryConfiguration
import dev.elide.gradle.configuration.features.ElideFeaturesConfiguration
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal

@ElideGradleDsl
public class ElideSettings(
    project: Project,
) {
    @Internal
    private val objects = project.objects

    /**
     * Sets the path to the project manifest, expressed in Pkl format. Elide project manifests can
     * specify dependencies, build scripts, and other project metadata. Defaults to `elide.pkl` and
     * automatically finds any present `elide.pkl` in the active project.
     */
    public val manifest: RegularFileProperty = objects.fileProperty()
        .convention(project.layout.projectDirectory.file("elide.pkl"))

    /**
     * Sets the path to the internal hidden folder of the Elide. The default is
     * the `.dev` inside target project (module).
     */
    public val devRoot: DirectoryProperty = objects.directoryProperty()
        .convention(project.layout.projectDirectory.dir(".dev"))

    /**
     * Configuration block for enabling or disabling key Elide integration features in the Gradle build.
     *
     * This includes options for dependency resolution, Maven integration, project awareness, and Java compiler strategy.
     * The settings here control how Elide interacts with Gradle and the build lifecycle, enabling opt-in or experimental
     * behaviors while maintaining sensible defaults.
     *
     * For example, enabling Elide's dependency resolver activates downloading and managing dependencies through Elide,
     * while the Java compiler strategy controls whether to use Gradle’s default `javac`, prefer Elide's optimized
     * compiler, or enforce strict use of Elide’s compiler.
     *
     * Typical usage:
     * ```
     * elide {
     *   features {
     *     enableElideInstall = true
     *     enableMavenIntegration = false
     *
     *     javacStrategy = JavacIntegrationStrategy.PREFER_ELIDE
     *   }
     * }
     * ```
     */
    public val features: ElideFeaturesConfiguration = ElideFeaturesConfiguration(project, manifest)

    /**
     * Configuration for controlling diagnostic options, such as debug and verbose logging.
     *
     * Use this block to adjust the level of logging and diagnostic output
     * emitted by the Elide.
     */
    public val diagnostics: ElideDiagnosticsConfiguration = ElideDiagnosticsConfiguration(project)

    /**
     * Configuration for managing the Elide binary executable used by the plugin.
     *
     * Includes settings for binary path overrides, version pinning, and resolution strategy
     * (e.g., whether to use a local binary or download a project-scoped one).
     */
    public val binary: ElideBinaryConfiguration = ElideBinaryConfiguration(project)


    public fun diagnostics(action: Action<ElideDiagnosticsConfiguration>) {
        action.execute(diagnostics)
    }

    public fun features(action: Action<ElideFeaturesConfiguration>) {
        action.execute(features)
    }

    public fun binary(action: Action<ElideBinaryConfiguration>) {
        action.execute(binary)
    }
}