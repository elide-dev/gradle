package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerIntegrationFunctionalTest {
    private static final String SECRET = "not-for-build-output";

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesUserCompilerArgumentsAfterTheElideJavacPrefixWithoutWritingJavaHome() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("compiler-project");
        Path invocationLog = projectDirectory.resolve("compiler-invocations.log");
        Path fakeJavaHome = projectDirectory.resolve("fake-java-home");
        Path executable = writeCompilerRuntime(projectDirectory, invocationLog);
        writeCompilerProject(projectDirectory, executable, fakeJavaHome);

        runner(projectDirectory, Map.of()).withArguments("compileJava").build();

        List<String> invocations = Files.readAllLines(invocationLog);
        assertEquals(1, invocations.size());
        assertTrue(invocations.get(0).startsWith("javac --"), invocations.toString());
        assertTrue(invocations.get(0).contains("-Dfixture.compiler.argument=true"), invocations.toString());
        assertFalse(Files.exists(fakeJavaHome), "The plugin must not create a Java Home shim");
    }

    @Test
    void reportsElideFailuresWithoutLeakingEnvironmentValues() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("failure-project");
        Path executable = writeFakeExecutable(projectDirectory, """
                #!/bin/sh
                printf '%s\\n' "$ELIDE_TEST_SECRET"
                printf '%s\\n' "$ELIDE_TEST_SECRET" >&2
                exit 23
                """);
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);

        BuildResult result = runner(projectDirectory, Map.of("ELIDE_TEST_SECRET", SECRET))
                .withArguments("elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("executable " + executable.toRealPath()), result.getOutput());
        assertTrue(result.getOutput().contains("working directory " + projectDirectory.toRealPath()),
                result.getOutput());
        assertTrue(result.getOutput().contains("exit code 23"), result.getOutput());
        assertFalse(result.getOutput().contains(SECRET), result.getOutput());
    }

    private static void writeProject(Path projectDirectory, Path executable, String configuration) throws IOException {
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("elide.pkl"), "fixture manifest\n");
        Files.createDirectories(projectDirectory.resolve(".dev"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                }

                elide {
                    getElideBin().set(layout.projectDirectory.file('%s'))
                    %s
                }
                """.formatted(groovyQuote(projectDirectory.relativize(executable)), configuration));
    }

    private static void writeCompilerProject(Path projectDirectory, Path executable, Path fakeJavaHome) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                    id 'java'
                }

                System.setProperty('java.home', '%s')

                elide {
                    getElideBin().set(layout.projectDirectory.file('%s'))
                    getEnableInstall().set(false)
                }

                tasks.withType(JavaCompile).configureEach {
                    options.forkOptions.jvmArgs.add('-Dfixture.compiler.argument=true')
                }
                """.formatted(groovyQuote(fakeJavaHome), groovyQuote(projectDirectory.relativize(executable))));
        Files.writeString(projectDirectory.resolve("src/main/java/example/Fixture.java"), """
                package example;
                public final class Fixture { }
                """);
    }

    private static Path writeFakeExecutable(Path projectDirectory, String script) throws IOException {
        Path executable = projectDirectory.resolve("bin/elide");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, script);
        executable.toFile().setExecutable(true);
        return executable;
    }

    private static Path writeCompilerRuntime(Path projectDirectory, Path invocationLog) throws IOException {
        Path executable = projectDirectory.resolve("bin/elide");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, """
                #!/bin/sh
                printf '%%s\\n' "$*" >> '%s'
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
        return executable;
    }

    private static GradleRunner runner(Path projectDirectory, Map<String, String> additions) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(additions);
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

    private static String shellQuote(Path path) {
        return path.toAbsolutePath().toString().replace("'", "'\"'\"'");
    }
}
