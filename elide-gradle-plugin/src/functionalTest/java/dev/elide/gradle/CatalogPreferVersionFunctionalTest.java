package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for prefer-only and other rich version catalog aliases, which expose their
 * version via {@code getPreferredVersion()} / {@code getStrictVersion()} rather than
 * {@code getRequiredVersion()}.
 */
class CatalogPreferVersionFunctionalTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void managedModeResolvesAPreferOnlyCatalogAlias() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("managed-prefer");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.createDirectories(projectDirectory.resolve("gradle"));
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), """
                [versions]
                elide = { prefer = "1.5.2" }
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

        assertTrue(result.getOutput().contains("ELIDE_VERSION=1.5.2"), result.getOutput());
    }

    @Test
    void managedModeResolvesAStrictlyOnlyCatalogAlias() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("managed-strictly");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.createDirectories(projectDirectory.resolve("gradle"));
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), """
                [versions]
                elide = { strictly = "1.5.2" }
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

        assertTrue(result.getOutput().contains("ELIDE_VERSION=1.5.2"), result.getOutput());
    }

    @Test
    void pathModeRejectsAnOutdatedRuntimeWithAPreferOnlyCatalogAlias() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The version-reporting fixture is a POSIX shell script.");
        Path projectDirectory = temporaryDirectory.resolve("path-prefer-outdated");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.createDirectories(projectDirectory.resolve("gradle"));
        Path executableDirectory = projectDirectory.resolve("bin");
        writeVersionReportingExecutable(executableDirectory, "1.5.1+20260903.fixture");
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), """
                [versions]
                elide = { prefer = "1.5.2" }
                """);
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode

                plugins { id("dev.elide.settings") }
                elide {
                    runtime {
                        mode = ElideRuntimeMode.PATH
                        versionFrom("libs", "elide")
                    }
                }
                include("app")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins { id("dev.elide"); id("java") }
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withEnvironment(environmentWithPath(executableDirectory, projectDirectory))
                .withArguments(":app:compileJava")
                .buildAndFail();

        assertTrue(result.getOutput().contains("reports version 1.5.1"), result.getOutput());
        assertTrue(result.getOutput().contains("1.5.2 or newer is required"), result.getOutput());
    }

    @Test
    void pathModeAcceptsANewerRuntimeWithAPreferOnlyCatalogAlias() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The version-reporting fixture is a POSIX shell script.");
        Path projectDirectory = temporaryDirectory.resolve("path-prefer-newer");
        Files.createDirectories(projectDirectory.resolve("app/src/main/java/example"));
        Files.createDirectories(projectDirectory.resolve("gradle"));
        Path executableDirectory = projectDirectory.resolve("bin");
        writeVersionReportingExecutable(executableDirectory, "1.6.0+20270101.aaaaaaa");
        Files.writeString(projectDirectory.resolve("app/src/main/java/example/Fixture.java"), """
                package example;
                public final class Fixture { }
                """);
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), """
                [versions]
                elide = { prefer = "1.5.2" }
                """);
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode

                plugins { id("dev.elide.settings") }
                elide {
                    runtime {
                        mode = ElideRuntimeMode.PATH
                        versionFrom("libs", "elide")
                    }
                }
                include("app")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins { id("dev.elide"); id("java") }
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withEnvironment(environmentWithPath(executableDirectory, projectDirectory))
                .withArguments(":app:compileJava", "--offline")
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        assertFalse(result.getOutput().contains("is not cached at"), result.getOutput());
    }

    @Test
    void autoModeResolvesAPreferOnlyCatalogAliasAndRejectsAnOutdatedPathRuntime() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The version-reporting fixture is a POSIX shell script.");
        Path projectDirectory = temporaryDirectory.resolve("auto-prefer-outdated");
        Files.createDirectories(projectDirectory.resolve("app"));
        Files.createDirectories(projectDirectory.resolve("gradle"));
        Path executableDirectory = projectDirectory.resolve("bin");
        writeVersionReportingExecutable(executableDirectory, "1.5.1+20260903.fixture");
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), """
                [versions]
                elide = { prefer = "1.5.2" }
                """);
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode

                plugins { id("dev.elide.settings") }
                elide {
                    runtime {
                        mode = ElideRuntimeMode.AUTO
                        versionFrom("libs", "elide")
                    }
                }
                include("app")
                """);
        Files.writeString(projectDirectory.resolve("app/build.gradle.kts"), """
                plugins { id("dev.elide"); id("java") }
                """);

        BuildResult result = configuredRunner(projectDirectory)
                .withEnvironment(environmentWithPath(executableDirectory, projectDirectory))
                .withArguments(":app:compileJava", "--offline")
                .buildAndFail();

        assertTrue(result.getOutput().contains("reports version 1.5.1"), result.getOutput());
        assertTrue(result.getOutput().contains("using the managed runtime instead"), result.getOutput());
        assertTrue(result.getOutput().contains("is not cached at"), result.getOutput());
    }

    private static void writeVersionReportingExecutable(Path directory, String version) throws IOException {
        Files.createDirectories(directory);
        Path executable = directory.resolve("elide");
        Files.writeString(executable, "#!/bin/sh\n"
                + "if [ \"${1-}\" = '--version' ]; then printf '" + version + "\\n'; exit 0; fi\n"
                + "exit 0\n");
        executable.toFile().setExecutable(true);
    }

    private static Map<String, String> environmentWithPath(Path executableDirectory, Path projectDirectory) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.keySet().removeIf(name -> name.equalsIgnoreCase("PATH"));
        environment.put("PATH", executableDirectory.toString());
        environment.put("GRADLE_USER_HOME", projectDirectory.resolve("gradle-user-home").toString());
        return environment;
    }

    private GradleRunner configuredRunner(Path projectDirectory) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile());
    }
}
