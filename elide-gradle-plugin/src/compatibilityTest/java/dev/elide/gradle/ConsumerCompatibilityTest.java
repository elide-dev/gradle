package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerCompatibilityTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @ParameterizedTest(name = "Gradle {0}")
    @ValueSource(strings = {"7.6.4", "8.14.5", "9.7.1"})
    void appliesThePluginAndCompilesJavaWithEachSupportedGradleVersion(String gradleVersion) throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("gradle-" + gradleVersion);
        Path invocationDirectory = projectDirectory.resolve("elide-invocations");
        Path fixtureClassPath = projectDirectory.resolve("elide-fixture-classes");
        Path executable = PlatformFixture.writeJavaRuntimeFixture(fixtureClassPath);
        writeConsumerProject(projectDirectory, executable);

        BuildResult result = GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                .withEnvironment(isolatedEnvironment(projectDirectory, fixtureClassPath, invocationDirectory))
                .withArguments("help", "compileJava", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        List<List<String>> invocations = PlatformFixture.readInvocations(invocationDirectory);
        assertEquals(1, invocations.size());
        assertEquals(List.of("javac", "--"), invocations.get(0).subList(0, 2));
    }

    @Test
    void kotlinDslConsumerUsesPropertySetSyntaxOnGradle764() throws IOException {
        Path projectDirectory = temporaryDirectory.resolve("kotlin-gradle-7.6.4");
        Path invocationDirectory = projectDirectory.resolve("elide-invocations");
        Path fixtureClassPath = projectDirectory.resolve("elide-fixture-classes");
        Path executable = PlatformFixture.writeJavaRuntimeFixture(fixtureClassPath);
        writeKotlinConsumerProject(projectDirectory, executable);

        BuildResult result = GradleRunner.create()
                .withGradleVersion("7.6.4")
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                .withEnvironment(isolatedEnvironment(projectDirectory, fixtureClassPath, invocationDirectory))
                .withArguments("help", "compileJava", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        List<List<String>> invocations = PlatformFixture.readInvocations(invocationDirectory);
        assertEquals(1, invocations.size());
        assertEquals(List.of("javac", "--"), invocations.get(0).subList(0, 2));
    }

    private static void writeConsumerProject(Path projectDirectory, Path executable) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), """
                plugins {
                    id 'dev.elide.settings'
                }

                elide {
                    runtime {
                        mode = dev.elide.gradle.ElideRuntimeMode.PATH
                        version = 'fixture-1.0'
                    }
                }

                rootProject.name = 'consumer-compatibility'
                """);
        Files.writeString(projectDirectory.resolve("src/main/java/example/Consumer.java"), """
                package example;
                public final class Consumer { }
                """);
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                    id 'java'
                }

                elide {
                    install = false
                    compiler = true
                    runtime.executable = layout.projectDirectory.file('%s')
                }
                """.formatted(groovyQuote(executable)));
    }

    private static void writeKotlinConsumerProject(Path projectDirectory, Path executable) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode
                import dev.elide.gradle.ElideSettingsExtension

                plugins {
                    id("dev.elide.settings")
                }

                configure<ElideSettingsExtension> {
                    runtime {
                        mode = ElideRuntimeMode.PATH
                        version = "fixture-1.0"
                    }
                }

                rootProject.name = "consumer-compatibility"
                """);
        Files.writeString(projectDirectory.resolve("src/main/java/example/Consumer.java"), """
                package example;
                public final class Consumer { }
                """);
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), """
                import dev.elide.gradle.ElideRuntimeMode

                plugins {
                    java
                    id("dev.elide")
                }

                elide {
                    install = false
                    compiler = true
                    runtime.executable = layout.projectDirectory.file("%s")
                }
                """.formatted(kotlinQuote(executable)));
    }

    private static Map<String, String> isolatedEnvironment(
            Path projectDirectory, Path fixtureClassPath, Path invocationDirectory) {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Map<String, String> environment = new HashMap<>();
        environment.put("GRADLE_USER_HOME", projectDirectory.resolve("gradle-user-home").toString());
        environment.put("JAVA_HOME", javaHome.toString());
        environment.put("PATH", javaHome.resolve("bin").toString() + File.pathSeparator);
        environment.put("CLASSPATH", fixtureClassPath.toString());
        environment.put("ELIDE_FIXTURE_LOG_DIRECTORY", invocationDirectory.toString());
        return environment;
    }

    private static String groovyQuote(Path path) {
        return path.toString().replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String kotlinQuote(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }
}
