package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerIntegrationFunctionalTest {
    private static final String SECRET = "not-for-build-output";

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void preservesUserCompilerArgumentsAfterTheElideJavacPrefixWithoutWritingJavaHome() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The Windows matrix exercises the native batch install fixture separately.");
        Path projectDirectory = temporaryDirectory.resolve("compiler-project");
        Path invocationLog = projectDirectory.resolve("compiler-invocations.log");
        Path executable = writeCompilerRuntime(projectDirectory, invocationLog);
        writeCompilerProject(projectDirectory, executable);

        runner(projectDirectory, Map.of()).withArguments("compileJava").build();

        List<List<String>> invocations = PlatformFixture.readInvocations(invocationLog);
        assertEquals(1, invocations.size());
        assertEquals(List.of("javac", "--", "-Xdefinitely-not-a-java-option"),
                invocations.get(0).subList(0, 3));
        String argumentFile = invocations.get(0).get(3);
        assertTrue(argumentFile.startsWith("@"), invocations.toString());
        assertTrue(Files.readString(Path.of(argumentFile.substring(1)))
                .contains("-Afixture.compiler.argument=has a space"));
    }

    @Test
    void reportsElideFailuresWithoutLeakingEnvironmentValues() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("failure-project");
        Path executable = writeFailingExecutable(projectDirectory);
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);

        BuildResult result = runner(projectDirectory, Map.of("ELIDE_TEST_SECRET", SECRET))
                .withArguments("elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("executable "), result.getOutput());
        assertTrue(result.getOutput().contains("working directory "), result.getOutput());
        assertTrue(result.getOutput().contains("exit code 23"), result.getOutput());
        assertFalse(result.getOutput().contains(SECRET), result.getOutput());
    }

    @Test
    void preservesLiteralDiagnosticContextWhenEnvironmentValuesMatchLabels() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("literal-context-project");
        Path executable = writeFailingExecutable(projectDirectory);
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);

        BuildResult result = runner(projectDirectory, Map.of(
                "ELIDE_TEST_SECRET", SECRET,
                "ELIDE_TEST_MARKER", "ELIDE_EXIT_CODE",
                "ELIDE_TEST_LABEL_FRAGMENT", "x"))
                .withArguments("elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("Elide command failed: executable "), result.getOutput());
        assertTrue(result.getOutput().contains(", working directory "), result.getOutput());
        assertTrue(result.getOutput().contains(", exit code 23."), result.getOutput());
        assertFalse(result.getOutput().contains(SECRET), result.getOutput());
    }

    @Test
    void redactsEnvironmentValuesFromExecutableAndWorkingDirectoryWithLongestValueFirst() throws IOException {
        String prefix = "fixture-secret";
        String secret = prefix + "-suffix";
        Path projectDirectory = temporaryDirectory.resolve("failure-project-" + secret);
        Path executable = writeFailingExecutable(projectDirectory);
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);

        BuildResult result = runner(projectDirectory, Map.of(
                "ELIDE_TEST_SECRET", secret,
                "ELIDE_TEST_SECRET_PREFIX", prefix))
                .withArguments("elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("exit code 23"), result.getOutput());
        assertFalse(result.getOutput().contains(secret), result.getOutput());
        assertFalse(result.getOutput().contains(prefix), result.getOutput());
        assertFalse(result.getOutput().contains("suffix"), result.getOutput());
    }

    @Test
    void redactsAnEnvironmentValueThatCrossesTheCaptureBoundary() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The boundary fixture uses a POSIX fake executable.");
        String secret = "cross-boundary-secret-value";
        int capturedPrefixLength = "cross-boundary-secret".length();
        String padding = "x".repeat((64 * 1024) - capturedPrefixLength);
        Path projectDirectory = temporaryDirectory.resolve("boundary-project");
        Path executable = writeExecutable(projectDirectory, """
                #!/bin/sh
                printf '%%s%%s' '%s' "$ELIDE_TEST_SECRET"
                exit 23
                """.formatted(padding));
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);

        BuildResult result = runner(projectDirectory, Map.of("ELIDE_TEST_SECRET", secret))
                .withArguments("elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("exit code 23"), result.getOutput());
        assertFalse(result.getOutput().contains(secret.substring(0, capturedPrefixLength)),
                "a boundary prefix of the environment value leaked");
    }

    @Test
    void redactsAMultibyteEnvironmentValueThatCrossesTheCaptureBoundary() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The boundary fixture uses a POSIX fake executable.");
        String secret = "界界界";
        String padding = "x".repeat((64 * 1024) - 3);
        Path projectDirectory = temporaryDirectory.resolve("multibyte-boundary-project");
        Path executable = writeExecutable(projectDirectory, """
                #!/bin/sh
                printf '%%s%%s' '%s' "$ELIDE_TEST_SECRET"
                exit 23
                """.formatted(padding));
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);

        BuildResult result = runner(projectDirectory, Map.of("ELIDE_TEST_SECRET", secret))
                .withArguments("elideInstall")
                .buildAndFail();

        assertFalse(result.getOutput().contains(secret), result.getOutput());
    }

    @Test
    void withholdsCapturedOutputWhenAnEnvironmentValueExceedsTheFixedSafeBound() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The oversized-output fixture uses a POSIX fake executable.");
        Path projectDirectory = temporaryDirectory.resolve("oversized-secret-project");
        Path executable = writeExecutable(projectDirectory, """
                #!/bin/sh
                printf '%s\\n' "$ELIDE_TEST_SECRET"
                exit 23
                """);
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);
        String oversizedSecret = "s".repeat(2048);

        BuildResult result = runner(projectDirectory, Map.of("ELIDE_TEST_SECRET", oversizedSecret))
                .withArguments("elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("Captured output withheld"), result.getOutput());
        assertFalse(result.getOutput().contains(oversizedSecret.substring(0, 64)), result.getOutput());
    }

    @Test
    void reportsAnUnstartableExecutableAsAStructuredRedactedFailure() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The unstartable fixture uses a missing POSIX interpreter.");
        String secret = "unstartable-executable-secret";
        Path projectDirectory = temporaryDirectory.resolve("unstartable-" + secret);
        Path executable = writeExecutable(projectDirectory, """
                #!/definitely/missing/elide-interpreter
                """);
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);

        BuildResult result = runner(projectDirectory, Map.of("ELIDE_TEST_SECRET", secret))
                .withArguments("--stacktrace", "elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("Elide command failed: executable "), result.getOutput());
        assertTrue(result.getOutput().contains("working directory "), result.getOutput());
        assertTrue(result.getOutput().contains("exit code -1"), result.getOutput());
        assertFalse(result.getOutput().contains(secret), result.getOutput());
    }

    @Test
    void withholdsAllUntrustedDiagnosticFieldsWhenEnvironmentMetadataIsOverLimit() throws IOException {
        Assumptions.assumeFalse(PlatformFixture.isWindows(),
                "The bounded-metadata fixture uses a POSIX fake executable.");
        String opaqueContext = "opaque-untrusted-context";
        String childOutput = "untrusted-child-details";
        Path projectDirectory = temporaryDirectory.resolve("over-limit-" + opaqueContext);
        Path executable = writeExecutable(projectDirectory, """
                #!/bin/sh
                printf '%%s\\n' '%s'
                exit 23
                """.formatted(childOutput));
        writeProject(projectDirectory, executable, """
                getEnableInstall().set(true)
                getEnableJavaCompiler().set(false)
                """);
        Map<String, String> environment = new HashMap<>();
        for (int index = 0; index <= 256; index++) {
            environment.put("ELIDE_TEST_COUNT_" + index, "metadata-value-" + index);
        }

        BuildResult result = runner(projectDirectory, environment)
                .withArguments("elideInstall")
                .buildAndFail();

        assertTrue(result.getOutput().contains("Elide command failed: executable "), result.getOutput());
        assertTrue(result.getOutput().contains(", working directory "), result.getOutput());
        assertTrue(result.getOutput().contains(", exit code 23."), result.getOutput());
        assertFalse(result.getOutput().contains(opaqueContext), result.getOutput());
        assertFalse(result.getOutput().contains(childOutput), result.getOutput());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void configuresCompilerWithAWindowsNativeFixtureWithoutExecutingIt() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("windows-compiler-project");
        Path executable = projectDirectory.resolve("bin/elide.exe");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "@echo off\r\nexit /b 0\r\n");
        writeCompilerProject(projectDirectory, executable);
        Files.writeString(projectDirectory.resolve("build.gradle"), """

                tasks.register('recordCompilerConfiguration') {
                    doLast {
                        def compiler = tasks.named('compileJava', org.gradle.api.tasks.compile.JavaCompile).get()
                        file('compiler-configuration.txt').text = compiler.options.forkOptions.executable + '\\n' + compiler.options.forkOptions.allJvmArgs.join('\\n')
                    }
                }
                """, StandardOpenOption.APPEND);

        runner(projectDirectory, Map.of()).withArguments("recordCompilerConfiguration").build();

        List<String> configuration = Files.readAllLines(projectDirectory.resolve("compiler-configuration.txt"));
        assertTrue(configuration.get(0).endsWith("java.exe"), configuration.toString());
        assertTrue(configuration.contains(executable.toAbsolutePath().toString()), configuration.toString());
        assertTrue(configuration.contains("javac"), configuration.toString());
        assertTrue(configuration.contains("--"), configuration.toString());
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

    private static void writeCompilerProject(Path projectDirectory, Path executable) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                    id 'java'
                }

                elide {
                    getElideBin().set(layout.projectDirectory.file('%s'))
                    getEnableInstall().set(false)
                }

                tasks.withType(JavaCompile).configureEach {
                    options.forkOptions.jvmArgs.add('-Xdefinitely-not-a-java-option')
                    options.compilerArgs.add('-Afixture.compiler.argument=has a space')
                }
                """.formatted(groovyQuote(projectDirectory.relativize(executable))));
        Files.writeString(projectDirectory.resolve("src/main/java/example/Fixture.java"), """
                package example;
                public final class Fixture { }
                """);
    }

    private static Path writeFailingExecutable(Path projectDirectory) throws IOException {
        Path executable = projectDirectory.resolve("bin").resolve(PlatformFixture.executableName("elide"));
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, PlatformFixture.isWindows() ? """
                @echo off
                echo %ELIDE_TEST_SECRET%
                echo %ELIDE_TEST_SECRET% 1>&2
                exit /b 23
                """ : """
                #!/bin/sh
                printf '%s\\n' "$ELIDE_TEST_SECRET"
                printf '%s\\n' "$ELIDE_TEST_SECRET" >&2
                exit 23
                """);
        executable.toFile().setExecutable(true);
        return executable;
    }

    private static Path writeExecutable(Path projectDirectory, String contents) throws IOException {
        Path executable = projectDirectory.resolve("bin/elide");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, contents);
        executable.toFile().setExecutable(true);
        return executable;
    }

    private static Path writeCompilerRuntime(Path projectDirectory, Path invocationLog) throws IOException {
        Path executable = PlatformFixture.writeRecordingExecutable(projectDirectory.resolve("bin"), "elide", invocationLog);
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

}
