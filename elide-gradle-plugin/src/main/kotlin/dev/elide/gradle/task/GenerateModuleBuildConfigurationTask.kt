package dev.elide.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import kotlin.io.path.absolutePathString
import kotlin.io.path.toPath

/**
 * Gradle task that generates a `.pkl` file containing
 * the current module's resolved Maven dependencies and repositories.
 *
 * This file is consumed by Elide's build tooling to enable
 * Elide-native dependency resolution instead of relying solely on Gradle.
 *
 * The generated `.pkl` file declares:
 *  - a listing of Maven package dependencies (`packages`) that are linked to a source set.
 *  - a listing of Maven repositories (`repositories`)
 *
 * These constants can be imported into the Elide buildscript to
 * propagate the effective dependencies and repository information,
 * ensuring consistent resolution and build reproducibility.
 *
 * This task is optional and only required if the user opts to
 * replace Gradle’s dependency resolution with Elide’s mechanism.
 *
 * Example usage of the generated file in `.pkl`:
 * ```
 * amends "elide:project.pkl"
 *
 * import "build/elide-runtime/generated/generated.pkl" as gradleGenerated
 *
 * dependencies {
 *   maven {
 *     packages = gradleGenerated.packages
 *     // repositories can be set similarly
 *   }
 * }
 * ```
 */
public abstract class GenerateModuleBuildConfigurationTask : DefaultTask() {

    init {
        group = "Elide"
        description =
            "Generates a helper `.pkl` file for the `manifest.pkl` to propagate actual module dependencies and repositories."
    }

    /**
     * The list of Maven artifact repositories declared in the current module.
     * These will be serialized to the generated `.pkl` to allow Elide
     * to replicate repository resolution.
     */
    @get:Input
    public abstract val declaredRepositories: ListProperty<MavenArtifactRepository>

    /**
     * The list of Maven package dependency coordinates declared in the current module for a main source set.
     * These strings represent Maven coordinates such as
     * `"group:name:version"`.
     */
    @get:Input
    public abstract val mainDeclaredDependencies: ListProperty<String>

    /**
     * The list of Maven package dependency coordinates declared in the current module for a test source set.
     * These strings represent Maven coordinates such as
     * `"group:name:version"`.
     */
    @get:Input
    public abstract val testDeclaredDependencies: ListProperty<String>

    /**
     * The output `.pkl` file that will be generated and imported by Elide's build scripts.
     */
    @get:OutputFile
    public abstract val generatedFile: RegularFileProperty

    @TaskAction
    public fun generate() {
        generatedFile.get().asFile.writeText(
            text = buildString {
                appendLine(GENERATION_TEMPLATE)
                appendLine(dependenciesCode("main", mainDeclaredDependencies.get()))
                appendLine(dependenciesCode("test", testDeclaredDependencies.get()))
                appendLine(repositoriesCode(declaredRepositories.get().map { it.url }))
            }
        )
    }

    private companion object {
        private val GENERATION_TEMPLATE = """
            /*
             * This file is **auto-generated** by the Elide Gradle Plugin.
             * Do NOT edit manually — changes will be overwritten on each Gradle build.
             *
             * This module, `gradleGenerated`, exposes two important constants:
             *
             * 1. `dependencies`: A Listing of Maven package dependencies resolved from your Gradle build.
             *    Each entry is a Maven coordinate string or structured dependency that Elide can consume.
             *
             * 2. `repositories`: A Listing of Maven repositories where dependencies are resolved.
             *    This includes remote URLs and local file-based repositories.
             *
             * These constants allow Elide’s build tooling to replicate Gradle’s dependency
             * resolution and ensure your Elide build uses exactly the same artifacts.
             *
             * Usage:
             * ```
             * amends "elide:project.pkl"
             *
             * import "build/elide-runtime/generated/module.pkl" as gradleModule
             *
             * dependencies {
             *   maven {
             *     packages = gradleModule.dependencies
             *     repositories = gradleModule.repositories
             *   }
             * }
             * ```
             *
             * This seamless integration ensures that your Elide build environment
             * reflects the real Gradle dependencies and repository setup.
             */
            module gradleModule

            import "elide:jvm.pkl" as jvm
        """.trimIndent()

        fun dependenciesCode(sourceSetName: String, coordinates: List<String>): String {
            return """
            /// List of Maven package dependencies detected from Gradle $sourceSetName's source set.
            /// Each item is a `jvm.MavenPackageDependency` representing
            /// an artifact coordinate or package spec.
            const ${sourceSetName}Dependencies: Listing<jvm.MavenPackageDependency> = new Listing {
              ${coordinates.joinToString("\n") { "\"$it\"" }}
            }
            """.trimIndent()
        }

        fun repositoriesCode(repos: List<URI>): String = buildString {
            appendLine("""
                /// List of Maven repositories Gradle uses to resolve dependencies.
                /// Includes both remote URLs and local file system repositories.
                /// Each entry is a `jvm.MavenRepository` specification.
                const repositories: Listing<jvm.MavenRepository> = new Listing {
            """.trimIndent())

            repos.forEach { repo ->
                val isLocal = repo.scheme == "file"
                val parameterName = if (isLocal) "path" else "url"
                val repoName = generateRepositoryName(repo.toASCIIString())
                val value = if (isLocal) repo.toPath().absolutePathString() else repo.toURL().toString()

                appendLine("\t[\"$repoName\"] = new {")
                appendLine("\t\t$parameterName = \"$value\"")
                appendLine("\t}")
            }
            appendLine("}")
        }

        private val wellKnownRepositories = mapOf(
            "https://repo.maven.apache.org/maven2" to "mavenCentral",
            "https://jcenter.bintray.com" to "jcenter",
            "https://jitpack.io" to "jitpack",
            "https://plugins.gradle.org/m2" to "gradlePluginPortal",
            "https://maven.pkg.jetbrains.space/public/p/ktor/eap" to "ktorEap",
            "https://s01.oss.sonatype.org/content/repositories/snapshots/" to "sonatypeSnapshots",
            "https://maven.google.com" to "googleMaven",
            (File(System.getProperty("user.home") + "/.m2/repository").normalize().absolutePath) to "localMaven",
        )

        private fun generateRepositoryName(pathOrUrl: String): String {
            val trimmed = pathOrUrl.trimEnd('/')

            // Try to match known repo first
            wellKnownRepositories.forEach { (pattern, name) ->
                if (pattern.startsWith("file:") && trimmed.matches(Regex(pattern))) return name
                if (pattern.startsWith("http") && trimmed.equals(pattern, ignoreCase = true)) return name
            }

            val input = pathOrUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("file:")
                .removePrefix("s3://")
                .removePrefix("ftp://")
                .removePrefix("ssh://")
                .removePrefix("~")
                .removePrefix("/")
                .removePrefix("./")
                .removePrefix("../")
                .replace(Regex("[^a-zA-Z0-9/_\\-.]+"), "") // clean weird chars
                .replace(Regex("[.]"), "/") // treat dots like slashes for domain parts

            return input
                .split(Regex("[/_\\-]+"))
                .filter { it.isNotBlank() }
                .mapIndexed { index, token ->
                    val normalized = token.replace(Regex("[^a-zA-Z0-9]"), "")
                    if (index == 0) normalized.lowercase()
                    else normalized.replaceFirstChar { it.uppercase() }
                }
                .joinToString("")
        }
    }
}