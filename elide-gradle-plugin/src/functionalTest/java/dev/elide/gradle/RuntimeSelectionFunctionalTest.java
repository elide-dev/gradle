package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSelectionFunctionalTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void explicitRuntimeConfigurationDoesNotExecuteElideDuringConfiguration() throws IOException {
        assertConfigurationIsPure(true);
    }

    @Test
    void pathRuntimeConfigurationDoesNotExecuteElideDuringConfiguration() throws IOException {
        assertConfigurationIsPure(false);
    }

    private void assertConfigurationIsPure(boolean explicitRuntime) throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("project");
        Path executableDirectory = projectDirectory.resolve("bin");
        Path executable = executableDirectory.resolve(PlatformFixture.isWindows() ? "elide.exe" : "elide");
        Path invocationLog = projectDirectory.resolve("elide-invocations.log");
        Files.createDirectories(executableDirectory);
        if (PlatformFixture.isWindows()) {
            Files.copy(Path.of(System.getProperty("java.home")).resolve("bin/java.exe"), executable);
        } else {
            Files.writeString(executable,
                    "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + shellQuote(invocationLog) + "'\n");
        }
        executable.toFile().setExecutable(true);
        Files.writeString(projectDirectory.resolve("settings.gradle"), "");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                    id 'java'
                }

                elide {
                    %s
                }
                """.formatted(explicitRuntime
                ? "elideBin = layout.projectDirectory.file('bin/" + executable.getFileName() + "')"
                : "runtimeMode = dev.elide.gradle.ElideRuntimeMode.PATH"));

        BuildResult first = configuredRunner(projectDirectory, environmentWithPath(executableDirectory))
                .withArguments("--configuration-cache", "help")
                .build();
        BuildResult second = configuredRunner(projectDirectory, environmentWithPath(executableDirectory))
                .withArguments("--configuration-cache", "help")
                .build();

        assertFalse(Files.exists(invocationLog));
        assertTrue(first.getOutput().contains("Configuration cache entry stored"));
        assertTrue(second.getOutput().contains("Configuration cache entry reused"));
    }

    private GradleRunner configuredRunner(Path projectDirectory, Map<String, String> environment) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                .withEnvironment(environment);
    }

    private static Map<String, String> environmentWithPath(Path executableDirectory) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        // Windows treats environment names case-insensitively, but the copied Java map may retain
        // `Path`; remove it before adding `PATH` so ProcessBuilder cannot choose the stale value.
        environment.keySet().removeIf(name -> name.equalsIgnoreCase("PATH"));
        environment.put("PATH", executableDirectory.toString());
        environment.put("GRADLE_USER_HOME", executableDirectory.getParent().resolve("gradle-user-home").toString());
        return environment;
    }

    private static String shellQuote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "'\\\"'\\\"'");
    }

}
