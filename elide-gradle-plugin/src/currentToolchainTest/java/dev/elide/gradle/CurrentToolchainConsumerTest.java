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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentToolchainConsumerTest {
    private static final Map<String, String> GRADLE_BY_JAVA = Map.of(
            "24", "8.14.5",
            "26", "9.7.1");

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void runsTheRequestedCurrentToolchainConsumerPair() throws IOException {
        String expectedJavaVersion = requiredProperty("elide.currentToolchain.java");
        String expectedGradleVersion = requiredProperty("elide.currentToolchain.gradle");
        assertEquals(GRADLE_BY_JAVA.get(expectedJavaVersion), expectedGradleVersion,
                "The requested Gradle version must match the supported current-JDK pair");
        assertEquals(expectedJavaVersion, System.getProperty("java.specification.version"),
                "The current-toolchain test JVM must use the requested JDK");

        Path projectDirectory = temporaryDirectory.resolve("consumer");
        Path invocationDirectory = projectDirectory.resolve("elide-invocations");
        Path fixtureClassPath = projectDirectory.resolve("elide-fixture-classes");
        Path executable = PlatformFixture.writeJavaRuntimeFixture(fixtureClassPath);
        Path testJavaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        writeConsumerProject(projectDirectory, executable);

        BuildResult result = GradleRunner.create()
                .withGradleVersion(expectedGradleVersion)
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                .withEnvironment(isolatedEnvironment(projectDirectory, fixtureClassPath, invocationDirectory, testJavaHome))
                .withArguments("--stacktrace", "help", "compileJava", "assertNestedToolchain")
                .build();

        result.getOutput().lines()
                .filter(line -> line.startsWith("NESTED_"))
                .forEach(System.out::println);
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        assertTrue(result.getOutput().contains("NESTED_JAVA_VERSION=" + expectedJavaVersion), result.getOutput());
        assertTrue(result.getOutput().contains("NESTED_GRADLE_VERSION=" + expectedGradleVersion), result.getOutput());
        assertTrue(result.getOutput().contains("NESTED_JAVA_HOME=" + testJavaHome), result.getOutput());
        List<List<String>> invocations = PlatformFixture.readInvocations(invocationDirectory);
        assertEquals(1, invocations.size());
        assertEquals(List.of("javac", "--"), invocations.get(0).subList(0, 2));
    }

    private static void writeConsumerProject(Path projectDirectory, Path executable) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), "rootProject.name = \"current-toolchain-consumer\"\n");
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
                    runtimeMode.set(ElideRuntimeMode.PATH)
                    runtimeVersion.set("fixture-1.0")
                    elideBin.set(file("%s"))
                    enableInstall.set(false)
                    enableJavaCompiler.set(true)
                }

                tasks.register("assertNestedToolchain") {
                    doLast {
                        println("NESTED_JAVA_VERSION=" + System.getProperty("java.specification.version"))
                        println("NESTED_JAVA_HOME=" + System.getenv("JAVA_HOME"))
                        println("NESTED_GRADLE_VERSION=" + gradle.gradleVersion)
                    }
                }
                """.formatted(kotlinQuote(executable)));
    }

    private static Map<String, String> isolatedEnvironment(
            Path projectDirectory, Path fixtureClassPath, Path invocationDirectory, Path javaHome) {
        Map<String, String> environment = new HashMap<>();
        environment.put("GRADLE_USER_HOME", projectDirectory.resolve("gradle-user-home").toString());
        environment.put("JAVA_HOME", javaHome.toString());
        environment.put("PATH", javaHome.resolve("bin").toString() + File.pathSeparator);
        environment.put("CLASSPATH", fixtureClassPath.toString());
        environment.put("ELIDE_FIXTURE_LOG_DIRECTORY", invocationDirectory.toString());
        return environment;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }

    private static String kotlinQuote(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }
}
