package com.example.plugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ElidePluginFunctionalTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void canRunTasksWithAnExplicitFakeRuntime() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("project");
        Path executable = projectDirectory.resolve("bin/elide");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        executable.toFile().setExecutable(true);
        Files.writeString(projectDirectory.resolve("settings.gradle"), "");
        Files.writeString(projectDirectory.resolve("build.gradle"),
                """
                    plugins {
                      id('dev.elide')
                    }

                    elide {
                      getElideBin().set(layout.projectDirectory.file('bin/elide'))
                      getEnableJavaCompiler().set(false)
                    }
                    """);

        BuildResult result = configuredRunner(projectDirectory, "tasks").build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
    }

    private GradleRunner configuredRunner(Path projectDirectory, String... arguments) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withArguments(arguments)
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile());
    }
}
