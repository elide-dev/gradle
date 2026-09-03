package dev.elide.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

        fixture.runner().withArguments("compileJava", "recordElideRepository").build();

        List<String> invocations = Files.readAllLines(fixture.invocationLog());
        assertEquals("install", invocations.get(0));
        assertTrue(invocations.get(1).startsWith("javac --"), invocations.toString());
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

        fixture.runner().withArguments("compileJava").build();

        List<String> invocations = Files.readAllLines(fixture.invocationLog());
        assertEquals(1, invocations.size());
        assertTrue(invocations.get(0).startsWith("javac --"), invocations.toString());
        assertFalse(Files.exists(fixture.projectDirectory().resolve(".dev/dependencies/m2")));
    }

    private record Fixture(Path projectDirectory, Path invocationLog) {
        static Fixture create(Path projectDirectory, boolean enableInstall, boolean enableMavenIntegration)
                throws IOException {
            Path invocationLog = projectDirectory.resolve("elide-invocations.log");
            Path executable = projectDirectory.resolve("bin/elide");
            Files.createDirectories(executable.getParent());
            Files.writeString(executable, """
                    #!/bin/sh
                    printf '%%s\\n' "$*" >> '%s'
                    if [ "$1" = 'install' ]; then
                      mkdir -p .dev/dependencies/m2
                    fi
                    """.formatted(shellQuote(invocationLog)));
            executable.toFile().setExecutable(true);
            Path java = executable.resolveSibling("java");
            Files.writeString(java, """
                    #!/bin/sh
                    exec '%s' "$@"
                    """.formatted(shellQuote(Path.of(System.getProperty("java.home")).resolve("bin/java"))));
            java.toFile().setExecutable(true);
            Path javac = executable.resolveSibling("javac");
            Files.writeString(javac, """
                    #!/bin/sh
                    printf '%%s\\n' "$*" >> '%s'
                    """.formatted(shellQuote(invocationLog)));
            javac.toFile().setExecutable(true);

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
                        getElideBin().set(layout.projectDirectory.file('bin/elide'))
                        getEnableInstall().set(%s)
                        getEnableMavenIntegration().set(%s)
                    }

                    tasks.register('recordElideRepository') {
                        doLast {
                            def repository = repositories.findByName('elide')
                            file('elide-repository.txt').text = repository == null ? '' : repository.url.toString()
                        }
                    }
                    """.formatted(enableInstall, enableMavenIntegration));
            return new Fixture(projectDirectory, invocationLog);
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

    private static String shellQuote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "'\"'\"'");
    }

}
