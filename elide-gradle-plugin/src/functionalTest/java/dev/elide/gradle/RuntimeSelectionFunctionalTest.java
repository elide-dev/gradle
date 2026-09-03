package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSelectionFunctionalTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void explicitRuntimeConfigurationDoesNotExecuteElideOrCreateAJavacShim() throws IOException {
        assertConfigurationIsPure("elideBin = layout.projectDirectory.file('bin/elide')");
    }

    @Test
    void pathRuntimeConfigurationDoesNotExecuteElideOrCreateAJavacShim() throws IOException {
        assertConfigurationIsPure("runtimeMode = dev.elide.gradle.ElideRuntimeMode.PATH");
    }

    private void assertConfigurationIsPure(String runtimeConfiguration) throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("project");
        Path executableDirectory = projectDirectory.resolve("bin");
        Path executable = executableDirectory.resolve("elide");
        Path invocationLog = projectDirectory.resolve("elide-invocations.log");
        Path fakeJavaHome = projectDirectory.resolve("fake-java-home");
        Files.createDirectories(executableDirectory);
        Files.createDirectories(fakeJavaHome.resolve("bin"));
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + shellQuote(invocationLog) + "'\n");
        executable.toFile().setExecutable(true);
        Files.writeString(projectDirectory.resolve("settings.gradle"),
                "System.setProperty('java.home', '" + groovyQuote(fakeJavaHome) + "')\n");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                    id 'java'
                }

                elide {
                    %s
                }
                """.formatted(runtimeConfiguration));

        BuildResult first = configuredRunner(projectDirectory, environmentWithPath(executableDirectory))
                .withArguments("--configuration-cache", "help")
                .build();
        BuildResult second = configuredRunner(projectDirectory, environmentWithPath(executableDirectory))
                .withArguments("--configuration-cache", "help")
                .build();

        assertFalse(Files.exists(invocationLog));
        assertFalse(Files.exists(fakeJavaHome.resolve("bin/elide-javac")));
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
        String existingPath = environment.getOrDefault("PATH", "");
        environment.put("PATH", executableDirectory + File.pathSeparator + existingPath);
        environment.put("GRADLE_USER_HOME", executableDirectory.getParent().resolve("gradle-user-home").toString());
        return environment;
    }

    private static String shellQuote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "'\\\"'\\\"'");
    }

    private static String groovyQuote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "\\'");
    }
}
