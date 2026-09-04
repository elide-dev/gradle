package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsPluginFunctionalTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void settingsPluginSupportsCleanKotlinDsl() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("settings-dsl");
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode

                plugins {
                    id("dev.elide.settings")
                }

                elide {
                    runtime {
                        mode = ElideRuntimeMode.MANAGED
                        version = "1.5.1+20260903"
                    }
                }

                check(elide.runtime.mode == ElideRuntimeMode.MANAGED)
                check(elide.runtime.version == "1.5.1+20260903")
                check(dependencyResolutionManagement.repositories.named("elide").get().name == "elide")
                rootProject.name = "settings-dsl"
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withArguments("help", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
    }

    @Test
    void optedInProjectInheritsSettingsWhileSiblingRemainsUntouched() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("multi-project");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.createDirectories(projectDirectory.resolve("plain"));
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode

                plugins {
                    id("dev.elide.settings")
                }

                elide {
                    runtime {
                        mode = ElideRuntimeMode.MANAGED
                        version = "settings-version"
                    }
                }

                include("app", "plain")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins {
                    base
                    id("dev.elide")
                }

                elide {
                    install = true
                    compiler = false
                }

                tasks.register("printElideConfiguration") {
                    doLast {
                        println("ELIDE_MODE=${elide.runtimeMode.get()}")
                        println("ELIDE_VERSION=${elide.runtimeVersion.get()}")
                        println("ELIDE_INSTALL=${elide.install}")
                    }
                }
                """);
        Files.writeString(projectDirectory.resolve("plain/build.gradle.kts"), """
                plugins { base }
                check(extensions.findByName("elide") == null)
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withArguments(":app:printElideConfiguration", ":plain:tasks", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("ELIDE_MODE=MANAGED"), result.getOutput());
        assertTrue(result.getOutput().contains("ELIDE_VERSION=settings-version"), result.getOutput());
        assertTrue(result.getOutput().contains("ELIDE_INSTALL=true"), result.getOutput());
    }

    @Test
    void optedInProjectResolvesRuntimeVersionFromCatalog() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("catalog-version");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.createDirectories(projectDirectory.resolve("gradle"));
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), """
                [versions]
                elide = "catalog-version"
                """);
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode

                plugins {
                    id("dev.elide.settings")
                }

                elide {
                    runtime {
                        mode = ElideRuntimeMode.MANAGED
                        versionFrom("libs", "elide")
                    }
                }

                include("app")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins {
                    base
                    id("dev.elide")
                }

                elide { compiler = false }

                tasks.register("printElideVersion") {
                    doLast { println("ELIDE_VERSION=${elide.runtimeVersion.get()}") }
                }
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withArguments(":app:printElideVersion", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("ELIDE_VERSION=catalog-version"), result.getOutput());
    }

    @Test
    void missingVersionCatalogReportsRequestedCatalogAndProject() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("missing-catalog");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                plugins { id("dev.elide.settings") }
                elide { runtime { versionFrom("missing", "elide") } }
                include("app")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins { id("dev.elide") }
                tasks.register("resolveElideVersion") {
                    doLast { println(elide.runtime.version) }
                }
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withArguments(":app:resolveElideVersion", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains(
                "Elide version catalog 'missing' does not exist for project :app"), result.getOutput());
    }

    @Test
    void missingVersionAliasReportsRequestedAliasCatalogAndProject() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("missing-alias");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.createDirectories(projectDirectory.resolve("gradle"));
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), """
                [versions]
                other = "1.0"
                """);
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                plugins { id("dev.elide.settings") }
                elide { runtime { versionFrom("libs", "elide") } }
                include("app")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins { id("dev.elide") }
                tasks.register("resolveElideVersion") {
                    doLast { println(elide.runtime.version) }
                }
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withArguments(":app:resolveElideVersion", "--stacktrace")
                .buildAndFail();

        assertTrue(result.getOutput().contains(
                "Elide version alias 'elide' does not exist in catalog 'libs' for project :app"),
                result.getOutput());
    }

    @Test
    void cleanProjectRuntimeDslOverridesSettingsConvention() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("project-override");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                plugins { id("dev.elide.settings") }
                elide { runtime { version = "settings-version" } }
                include("app")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins {
                    base
                    id("dev.elide")
                }

                elide {
                    compiler = false
                    runtime { version = "project-version" }
                }

                tasks.register("printElideVersion") {
                    doLast { println("ELIDE_VERSION=${elide.runtime.version}") }
                }
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withArguments(":app:printElideVersion", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("ELIDE_VERSION=project-version"), result.getOutput());
    }

    @Test
    void multiProjectConfigurationSupportsIsolatedProjects() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("isolated-projects");
        Files.createDirectories(projectDirectory.resolve("one"));
        Files.createDirectories(projectDirectory.resolve("two"));
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                plugins { id("dev.elide.settings") }
                elide { runtime { version = "isolated-version" } }
                include("one", "two")
                """);
        String projectBuild = """
                plugins {
                    base
                    id("dev.elide")
                }
                elide { compiler = false }
                """;
        Files.writeString(projectDirectory.resolve("one/build.gradle.kts"), projectBuild);
        Files.writeString(projectDirectory.resolve("two/build.gradle.kts"), projectBuild);

        BuildResult result = configuredRunner(projectDirectory)
                .withArguments(":one:tasks", ":two:tasks", "--isolated-projects", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
    }

    private GradleRunner configuredRunner(Path projectDirectory) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile());
    }
}
