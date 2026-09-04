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

    private GradleRunner configuredRunner(Path projectDirectory) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile());
    }
}
