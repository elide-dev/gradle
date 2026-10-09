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

/**
 * Covers the two documented Gradle properties that override the extension in both directions.
 */
class PropertyOverrideFunctionalTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void javacPropertyRemovesElideFromTheCompilePathWhenTheExtensionEnablesTheCompiler() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("javac-disabled");
        writeProject(projectDirectory, "getEnableJavaCompiler().set(true)");

        BuildResult result = runner(projectDirectory)
                .withArguments("compileJava", "-Pelide.builder.javac.enable=false", "--dry-run")
                .build();

        assertFalse(result.getOutput().contains(":prepareElideRuntime"), result.getOutput());
        assertFalse(result.getOutput().contains(":elideInstall"), result.getOutput());
        assertTrue(result.getOutput().contains(":compileJava"), result.getOutput());
    }

    @Test
    void javacPropertyEnablesTheCompilerWhenTheExtensionDisablesIt() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("javac-enabled");
        writeProject(projectDirectory, "getEnableJavaCompiler().set(false)");

        BuildResult result = runner(projectDirectory)
                .withArguments("compileJava", "-Pelide.builder.javac.enable=true", "--dry-run")
                .build();

        assertTrue(result.getOutput().contains(":prepareElideRuntime"), result.getOutput());
    }

    @Test
    void mavenInstallPropertyOverridesTheExtensionAndInvalidatesGradleDependencyMode() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("maven-forced");
        // install stays at its default of false, which dependencyMode GRADLE accepts.
        writeProject(projectDirectory, """
                getDependencyMode().set(dev.elide.gradle.ElideDependencyMode.GRADLE)
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("compileJava", "-Pelide.builder.maven.install.enable=true")
                .buildAndFail();

        assertTrue(result.getOutput().contains("dependencyMode GRADLE requires install = false"),
                result.getOutput());
    }

    @Test
    void gradleDependencyModeConfiguresWithoutTheOverride() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("maven-default");
        writeProject(projectDirectory, """
                getDependencyMode().set(dev.elide.gradle.ElideDependencyMode.GRADLE)
                """);

        BuildResult result = runner(projectDirectory)
                .withArguments("compileJava", "--dry-run")
                .build();

        assertFalse(result.getOutput().contains("dependencyMode GRADLE requires install = false"),
                result.getOutput());
    }

    private static void writeProject(Path projectDirectory, String configuration) throws IOException {
        Path invocationDirectory = projectDirectory.resolve("elide-invocations");
        Path executable = PlatformFixture.writeRecordingExecutable(
                projectDirectory.resolve("bin"), "elide", invocationDirectory);

        Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
        Files.writeString(projectDirectory.resolve("src/main/java/example/Fixture.java"), """
                package example;
                public final class Fixture { }
                """);
        Files.writeString(projectDirectory.resolve("elide.pkl"), "fixture manifest\n");
        Files.createDirectories(projectDirectory.resolve(".dev"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                    id 'java'
                }

                elide {
                    getElideBin().set(layout.projectDirectory.file('%s'))
                    %s
                }
                """.formatted(groovyQuote(projectDirectory.relativize(executable)), configuration));
    }

    private static GradleRunner runner(Path projectDirectory) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("GRADLE_USER_HOME", projectDirectory.resolve("gradle-user-home").toString());
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                .withEnvironment(environment);
    }

    private static String groovyQuote(Path path) {
        return path.toString().replace("\\", "\\\\").replace("'", "\\'");
    }
}
