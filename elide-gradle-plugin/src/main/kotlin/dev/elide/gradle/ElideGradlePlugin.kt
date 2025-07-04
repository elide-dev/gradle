package dev.elide.gradle

import dev.elide.gradle.cli.ElideCli
import dev.elide.gradle.configuration.ElideDiagnosticsConfiguration
import dev.elide.gradle.configuration.ElideSettings
import dev.elide.gradle.configuration.binary.ElideBinaryResolutionSource
import dev.elide.gradle.configuration.features.ElideFeaturesConfiguration
import dev.elide.gradle.configuration.features.ElideIntegrationStrategy
import dev.elide.gradle.internal.Platform
import dev.elide.gradle.internal.elideDebug
import dev.elide.gradle.internal.mapNotNull
import dev.elide.gradle.service.ElideThreadPoolService
import dev.elide.gradle.task.ElideCheckVersionTask
import dev.elide.gradle.task.GenerateModuleBuildConfigurationTask
import dev.elide.gradle.task.PrepareElideJavadocShimTask
import dev.elide.gradle.task.download.DownloadElideBinaryTask
import dev.elide.gradle.task.download.ExtractElideBinaryTask
import dev.elide.gradle.task.download.PrepareDownloadedElideBinary
import dev.elide.gradle.task.download.VerifyElideBinaryTask
import dev.elide.gradle.task.exec.ElideCliExec
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isSameFileAs
import kotlin.io.path.toPath

public class ElideGradlePlugin : Plugin<Project> {
    internal companion object {
        const val THREAD_POOL_SERVICE_NAME = "elideThreadPoolService"
    }

    override fun apply(target: Project) {
        val extension = target.extensions.create<ElideExtension>(
            name = "elide",
            constructionArguments = arrayOf(target)
        )
        val settings = extension.settings

        target.gradle.sharedServices.registerIfAbsent(
            THREAD_POOL_SERVICE_NAME, ElideThreadPoolService::class.java,
        )

        val localCliPathProvider = target.objects.directoryProperty().fileProvider(
            settings.binary.resolutionSource.flatMap {
                ElideCli.resolvePathToCli(target.logger, target.providers)
                    .orElse(
                        settings.binary.resolutionSource.mapNotNull {
                            when (it) {
                                is ElideBinaryResolutionSource.LocalOnly -> null
                                is ElideBinaryResolutionSource.LocalIfApplicable -> it.downloadPath
                                is ElideBinaryResolutionSource.Project -> it.downloadPath
                            }
                        }
                    )
            }.map { it.toFile() }
        )

        val cliDownloadPath = target.objects.directoryProperty().fileProvider(
            target.provider {
                if (settings.binary.resolutionSource.get() is ElideBinaryResolutionSource.LocalOnly)
                    return@provider null

                if (settings.binary.version.orNull == null)
                    error("Elide binary resolution source is set not to be local-only, but version isn't provided.")

                target.layout
                    .buildDirectory
                    .dir("elide-runtime/bin")
                    .map { it.dir("${settings.binary.version.get()}-${Platform.platformClassifier}") }
                    .get()
                    .asFile
            }
        )

        val resultingCliPath = localCliPathProvider.orElse(cliDownloadPath).map {
            it.file(ElideCli.ELIDE_BINARY_NAME).asFile.toPath()
        }

        val elideCli = ElideCli(
            cwd = target.objects.directoryProperty().fileProvider(
                target.layout.buildDirectory.dir("elide-runtime").map { it.asFile }
            ),
            bin = resultingCliPath,
            providerFactory = target.providers,
        )

        val downloadElideCli = target.tasks.register<DownloadElideBinaryTask>("downloadElideCli") {
            targetDirectory.set(cliDownloadPath)
            resolutionSource.set(settings.binary.resolutionSource)
            targetVersion.set(settings.binary.version)
        }

        val verifyBinaryCli = target.tasks.register<VerifyElideBinaryTask>("verifyElideCli") {
            dependsOn(downloadElideCli)

            targetDirectory.set(cliDownloadPath)
        }

        val extractElideCli = target.tasks.register<ExtractElideBinaryTask>("extractElideCli") {
            dependsOn(verifyBinaryCli)

            targetDirectory.set(cliDownloadPath)
        }

        target.tasks.register<PrepareDownloadedElideBinary>("prepareDownloadedElideCli") {
            dependsOn(extractElideCli)

            downloadedElideBinary.set(
                target.objects
                    .fileProperty()
                    .fileProvider(cliDownloadPath.file(ElideCli.ELIDE_BINARY_NAME).map { it.asFile })
            )
        }

        target.tasks.register<ElideCheckVersionTask>("checkElideCliVersion") {
            cli.set(elideCli)
            binPath.set(resultingCliPath.map { it.toRealPath().absolutePathString() })
            resolutionSource.set(extension.settings.binary.resolutionSource)
            strictVersionCheck.set(extension.settings.binary.strictVersionCheck)
            targetVersion.set(extension.settings.binary.version)
            silentMode.set(extension.settings.binary.silentMode)
        }

        target.tasks.register<GenerateModuleBuildConfigurationTask>("generateModuleMetadataForElide") {
            onlyIf { extension.settings.features.generatePklBuildConfiguration.get() }

            // Main dependencies
            // Elide for now can't distinguish implementation, compileOnly or runtimeOnly, so we put it all in one
            mainDeclaredDependencies.set(
                target.provider {
                    listOf("implementation", "compileOnly", "runtimeOnly")
                        .mapNotNull { configName ->
                            target.configurations.findByName(configName)
                        }
                        .flatMap { config ->
                            config.dependencies.withType<ModuleDependency>().mapNotNull { dep ->
                                val group = dep.group ?: return@mapNotNull null
                                val name = dep.name
                                val version = dep.version ?: return@mapNotNull null
                                "$group:$name:$version"
                            }
                        }
                }
            )

            // Test dependencies
            // Elide for now can't distinguish implementation, compileOnly or runtimeOnly, so we put it all in one
            testDeclaredDependencies.set(
                target.provider {
                    listOf("testImplementation", "testCompileOnly", "testRuntimeOnly")
                        .mapNotNull { configName ->
                            target.configurations.findByName(configName)
                        }
                        .flatMap { config ->
                            config.dependencies.withType<ModuleDependency>().mapNotNull { dep ->
                                val group = dep.group ?: return@mapNotNull null
                                val name = dep.name
                                val version = dep.version ?: return@mapNotNull null
                                "$group:$name:$version"
                            }
                        }
                }
            )

            declaredRepositories.set(
                target.provider {
                    target.repositories.filterIsInstance<MavenArtifactRepository>()
                        .filter { repo ->
                            repo.url.scheme != "file" ||
                                repo.url.toPath().isSameFileAs(
                                    extension.settings.devRoot.dir("m2/dependencies").get().asFile.toPath()
                                )
                        }
                }
            )

            generatedFile.set(
                target.layout
                    .buildDirectory
                    .dir("elide-runtime/generated")
                    .map { it.file("module.pkl") }
            )
        }

        val elideInstall = extension.exec("elideInstall") {
            group = "Elide"
            description = "Runs `elide install` to prepare the project for compilation."

            args.add("install")
            args.add(telemetry.mapNotNull { enabled -> if (!enabled) "--no-telemetry" else null })

            onlyIf("`elide.settings.manifest` is not set or target file does not exists") {
                settings.manifest.isPresent && settings.manifest.asFile.get().exists()
            }

            onlyIf("`elide.settings.features.enableElideInstall` is false") {
                settings.features.enableElideInstall.get()
            }
        }

        val prepareElideJavadocShimTask = target.tasks.register<PrepareElideJavadocShimTask>(
            name = "prepareElideJavadocShim"
        ) {
            cliPath.set(resultingCliPath.get().absolutePathString())
            shimFile.set(target.layout.buildDirectory.file("elide-runtime/shim/javadoc"))
        }

        target.applyMavenLocalRepo(settings)
        target.overrideJavaCompileIfEnabled(
            featuresConfig = settings.features,
            diagnosticsConfig = settings.diagnostics,
            cliPath = resultingCliPath,
            elideInstall = elideInstall,
        )
        target.overrideJavadocIfEnabled(
            featuresConfig = settings.features,
            cliPath = resultingCliPath,
            prepareElideJavadocShimTask = prepareElideJavadocShimTask,
            silentMode = settings.binary.silentMode,
            shimPath = prepareElideJavadocShimTask.flatMap { file ->
                file.shimFile.asFile.map { it.absolutePath }
            },
        )

        target.configurations.configureEach {
            incoming.beforeResolve {
                // Let's validate that 'elideInstall' is there
                if (settings.features.enableElideInstall.get() && !target.gradle.taskGraph.hasTask(elideInstall.get())) {
                    error("'elideInstall' should run before any dependency resolution. Did somebody started resolution too early (e.g on configuration phase)?")
                }
            }
        }
    }

    /**
     * Adds the maven local repository pointing to the "[dev.elide.gradle.configuration.ElideSettings.devRoot]/dependencies/m2" folder.
     *
     * This function also reorders the list of maven repositories to ensure that local repository is the first one to be used.
     */
    private fun Project.applyMavenLocalRepo(extension: ElideSettings) {
        val localM2DirPath = extension.devRoot
            .get()
            .dir("dependencies")
            .dir("m2")
            .asFile
            .also { it.mkdirs() }
            .absolutePath

        val indexOfLocalElideRepo = repositories.indexOfFirst {
            it is MavenArtifactRepository
                && it.url.scheme == "file"
                && it.url.path == localM2DirPath
        }.takeIf { it != -1 }

        // early return in case if it's already the first one.
        if (indexOfLocalElideRepo == 0) return

        if (indexOfLocalElideRepo != null) {
            repositories.removeAt(indexOfLocalElideRepo)
        }

        // We need to ensure that our maven local repository is the first one, for not to hit
        // remote ones.
        val snapshot = repositories.toList()
        repositories.clear()
        repositories.mavenLocal {
            uri("file://$localM2DirPath")
        }
        snapshot.forEach(repositories::add)
    }

    private fun Project.overrideJavaCompileIfEnabled(
        featuresConfig: ElideFeaturesConfiguration,
        diagnosticsConfig: ElideDiagnosticsConfiguration,
        cliPath: Provider<Path>,
        elideInstall: TaskProvider<ElideCliExec>,
    ) {
        afterEvaluate {
            val javacStrategy = featuresConfig.javacStrategy.get()

            if (javacStrategy == ElideIntegrationStrategy.NO_ELIDE) {
                logger.elideDebug("Skipping altering JavaCompile tasks: ElideIntegrationStrategy is NO_ELIDE.")
                return@afterEvaluate
            }

            if (cliPath.orNull?.exists() != true && gradle.startParameter.isOffline) {
                logger.elideDebug("Skipping altering JavaCompile tasks: elide is not found and Gradle is in offline mode.")
                return@afterEvaluate
            }

            tasks.withType<JavaCompile>().configureEach {
                dependsOn(elideInstall)

                options.isFork = true
                options.forkOptions {
                    executable = cliPath.get().absolutePathString()
                    jvmArgs = buildList {
                        if (diagnosticsConfig.debug.get())
                            add("--debug")
                        if (diagnosticsConfig.verbose.get())
                            add("--verbose")

                        add("javac")
                        add("--")
                        jvmArgs.orEmpty().forEach {
                            add(it)
                        }
                    }
                }
            }
        }
    }

    private fun Project.overrideJavadocIfEnabled(
        featuresConfig: ElideFeaturesConfiguration,
        cliPath: Provider<Path>,
        shimPath: Provider<String>,
        prepareElideJavadocShimTask: TaskProvider<PrepareElideJavadocShimTask>,
        silentMode: Provider<Boolean>,
    ) {
        afterEvaluate {
            val javadocStrategy = featuresConfig.javadocStrategy.get()

            if (javadocStrategy == ElideIntegrationStrategy.NO_ELIDE) {
                logger.elideDebug("Skipping altering Javadoc tasks: ElideIntegrationStrategy is NO_ELIDE.")
                return@afterEvaluate
            }

            if (cliPath.orNull == null && !silentMode.get()) {
                error("CLI path has not been found. Have you set local-only?")
            }

            if (cliPath.orNull?.exists() != true && gradle.startParameter.isOffline && silentMode.get()) {
                logger.elideDebug("Skipping altering Javadoc tasks: elide is not found and Gradle is in offline mode.")
                return@afterEvaluate
            }

            tasks.withType<Javadoc>().configureEach {
                executable = shimPath.get()
                dependsOn(prepareElideJavadocShimTask)
            }
        }
    }
}
