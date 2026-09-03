package dev.elide.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyInstallFunctionalTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsDependenciesBeforeCompilingAndRegistersTheLocalRepositoryWhenMavenIsEnabled() throws IOException {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("maven-enabled"), true, true);

        if (PlatformFixture.isWindows()) {
            fixture.runner().withArguments("elideInstall", "recordElideRepository").build();
        } else {
            fixture.runner().withArguments("compileJava", "recordElideRepository").build();
        }

        var invocations = PlatformFixture.readInvocations(fixture.invocationDirectory());
        assertEquals(java.util.List.of("install"), invocations.get(0));
        if (!PlatformFixture.isWindows()) {
            assertEquals(java.util.List.of("javac", "--"), invocations.get(1).subList(0, 2));
        }
        assertTrue(Files.isDirectory(fixture.projectDirectory().resolve(".dev/dependencies/m2")));
        assertEquals(
                fixture.projectDirectory().resolve(".dev/dependencies/m2").toRealPath(),
                Path.of(URI.create(Files.readString(fixture.projectDirectory().resolve("elide-repository.txt")))).toRealPath());
    }

    @Test
    void doesNotRegisterTheLocalRepositoryWhenMavenIntegrationIsDisabled() throws IOException {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("maven-disabled"), true, false);

        fixture.runner().withArguments("recordElideRepository").build();

        assertEquals("", Files.readString(fixture.projectDirectory().resolve("elide-repository.txt")));
    }

    @Test
    void doesNotInstallDependenciesWhenInstallationIsDisabled() throws IOException {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("install-disabled"), false, true);

        if (PlatformFixture.isWindows()) {
            fixture.runner().withArguments("tasks").build();
        } else {
            fixture.runner().withArguments("compileJava").build();
        }

        var invocations = PlatformFixture.readInvocations(fixture.invocationDirectory());
        assertEquals(PlatformFixture.isWindows() ? 0 : 1, invocations.size());
        if (!PlatformFixture.isWindows()) {
            assertEquals(java.util.List.of("javac", "--"), invocations.get(0).subList(0, 2));
        }
        assertFalse(Files.exists(fixture.projectDirectory().resolve(".dev/dependencies/m2")));
    }

    @Test
    void installIsUpToDateWhenItsInputsHaveNotChanged() throws IOException {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("install-up-to-date"), true, true);

        fixture.runner().withArguments("elideInstall").build();
        BuildResult second = fixture.runner().withArguments("elideInstall").build();

        assertTrue(second.getOutput().contains(":elideInstall UP-TO-DATE"), second.getOutput());
    }

    @Test
    void windowsFixtureUsesABatchExecutableAndRecordsArgumentsIndividually() {
        assertEquals("elide.cmd", PlatformFixture.executableNameFor("elide", "Windows 11"));
        String script = PlatformFixture.recordingScriptFor("Windows 11", temporaryDirectory);
        assertTrue(script.contains("@echo off"));
        assertTrue(script.contains("echo(%~1"));
    }

    private record Fixture(Path projectDirectory, Path invocationDirectory) {
        static Fixture create(Path projectDirectory, boolean enableInstall, boolean enableMavenIntegration)
                throws IOException {
            Path invocationDirectory = projectDirectory.resolve("elide-invocations");
            if (enableInstall) {
                Files.createDirectories(projectDirectory.resolve(".dev/dependencies/m2"));
            }
            Path executable = PlatformFixture.writeRecordingExecutable(
                    projectDirectory.resolve("bin"), "elide", invocationDirectory);
            if (!PlatformFixture.isWindows()) {
                PlatformFixture.linkActualJavaTools(executable.getParent());
            }

            Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
            Files.writeString(projectDirectory.resolve("src/main/java/example/Fixture.java"), """
                    package example;
                    public final class Fixture { }
                    """);
            Files.writeString(projectDirectory.resolve("settings.gradle"), "");
            Files.writeString(projectDirectory.resolve("build.gradle"), """
                    plugins {
                        id 'dev.elide'
                        id 'java'
                    }

                    elide {
                        getElideBin().set(layout.projectDirectory.file('%s'))
                        getEnableInstall().set(%s)
                        getEnableMavenIntegration().set(%s)
                    }

                    tasks.register('recordElideRepository') {
                        doLast {
                            def repository = repositories.findByName('elide')
                            file('elide-repository.txt').text = repository == null ? '' : repository.url.toString()
                        }
                    }
                    """.formatted(groovyQuote(projectDirectory.relativize(executable)),
                    enableInstall, enableMavenIntegration));
            return new Fixture(projectDirectory, invocationDirectory);
        }

        GradleRunner runner() {
            return GradleRunner.create()
                    .withPluginClasspath()
                    .withProjectDir(projectDirectory.toFile())
                    .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                    .withEnvironment(environment(projectDirectory));
        }
    }

    private static Map<String, String> environment(Path projectDirectory) {
        Map<String, String> environment = new java.util.HashMap<>(System.getenv());
        environment.put("GRADLE_USER_HOME", projectDirectory.resolve("gradle-user-home").toString());
        return environment;
    }

    private static String groovyQuote(Path path) {
        return path.toString().replace("\\", "\\\\").replace("'", "\\'");
    }

}
