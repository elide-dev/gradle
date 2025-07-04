package dev.elide.gradle.plugin.test

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ElidePluginFunctionalTest {
    private companion object {
        const val ELIDE_VERSION = "1.0.0-Beta7"
    }

    @field:TempDir
    private lateinit var elideInstallDir: Path

    @Test
    fun `check whether plugin successfully applies`(@TempDir projectDir: Path) {
        // Given: a minimal project with the Elide plugin applied
        val settingsFile = projectDir.resolve("settings.gradle.kts")
        settingsFile.createFile()

        val buildFile = projectDir.resolve("build.gradle.kts")
        buildFile.writeText(
            """
            plugins {
              id("dev.elide")
            }
            """.trimIndent()
        )

        // When: we run the 'tasks' task
        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("tasks")
            .forwardOutput()
            .build()

        // Then: the build should succeed
        assert(result.output.contains("BUILD SUCCESSFUL"))
    }


    @Test
    fun `check elide download-related task with build caching`(@TempDir projectDir: Path) {
        // Given:
        projectDir.resolve("settings.gradle.kts").createFile()

        val buildFile = projectDir.resolve("build.gradle.kts")
        buildFile.writeText(
            """
            import dev.elide.gradle.configuration.binary.*
                
            plugins {
              id("dev.elide")
            }
            
            elide.settings {
                binary {
                    version = "$ELIDE_VERSION"
                    useProjectBinary(ElideBinaryResolutionSource.Project("${elideInstallDir.absolutePathString()}"))
                }
            }
            """.trimIndent()
        )

        // When: we run the prepareDownloadedElideCli task twice with build cache enabled
        val firstResult = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("prepareDownloadedElideCli", "--build-cache")
            .forwardOutput()
            .build()

        val secondResult = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("prepareDownloadedElideCli", "--build-cache")
            .forwardOutput()
            .build()

        // Then: the first build should succeed and run the task
        val firstTask = firstResult.task(":prepareDownloadedElideCli")
        assertEquals(
            TaskOutcome.SUCCESS,
            firstTask?.outcome,
        )
        assert(firstResult.output.contains("BUILD SUCCESSFUL"))
        assert(elideInstallDir.resolve("elide").exists()) { "Expected elide binary to be present" }

        // Then: the second build should be FROM-CACHE or UP-TO-DATE
        val secondTask = secondResult.task(":prepareDownloadedElideCli")
        assert(secondTask?.outcome in listOf(TaskOutcome.FROM_CACHE, TaskOutcome.UP_TO_DATE, TaskOutcome.SKIPPED)) {
            "Expected second run to be cached or skipped"
        }
        assert(elideInstallDir.resolve("elide").exists()) { "Expected elide binary to be present" }
        assert(secondResult.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `test elide install with auto-generated helper file`(@TempDir projectDir: Path) {
        // Given: a minimal project with one dependency and repository
        projectDir.resolve("settings.gradle.kts").createFile()

        projectDir.resolve("project.pkl").createFile().writeText(
            """
                amends "elide:project.pkl"

                import "build/elide-runtime/generated/module.pkl" as gradle

                dependencies {
                  maven {
                    packages = gradle.packages
                    testPackages = gradle.testPackages
                    repositories = gradle.repositories
                  }
                }
            """.trimIndent()
        )

        val buildFile = projectDir.resolve("build.gradle.kts")
        buildFile.writeText(
            """
            import dev.elide.gradle.configuration.binary.*
                
            plugins {
              id("dev.elide")
              java
            }
            
            elide.settings {
                binary {
                    version = "$ELIDE_VERSION"
                    useProjectBinary(ElideBinaryResolutionSource.Project("${elideInstallDir.absolutePathString()}"))
                }
                
                features {
                    generatePklBuildConfiguration = true
                }
            }
            
            dependencies {
                implementation("com.google.guava:guava:33.4.8-jre")
            }
            """.trimIndent()
        )

        // When:
        val firstResult = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(":elideInstall", "--build-cache", "--configuration-cache")
            .forwardOutput()
            .build()

        val secondResult = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(":elideInstall", "--build-cache --configuration-cache")
            .forwardOutput()
            .build()

        // Then: the first build should succeed and run the task
        val firstTask = firstResult.task(":prepareDownloadedElideCli")
        assertEquals(
            TaskOutcome.SUCCESS,
            firstTask?.outcome,
        )
        assert(firstResult.output.contains("BUILD SUCCESSFUL"))
        assert(elideInstallDir.resolve("elide").exists()) { "Expected elide binary to be present" }

        // Then: the second build should be FROM-CACHE or UP-TO-DATE
        val secondTask = secondResult.task(":prepareDownloadedElideCli")
        assert(secondTask?.outcome in listOf(TaskOutcome.FROM_CACHE, TaskOutcome.UP_TO_DATE, TaskOutcome.SKIPPED)) {
            "Expected second run to be cached or skipped"
        }
        assert(elideInstallDir.resolve("elide").exists()) { "Expected elide binary to be present" }
        assert(secondResult.output.contains("BUILD SUCCESSFUL"))
    }
}
