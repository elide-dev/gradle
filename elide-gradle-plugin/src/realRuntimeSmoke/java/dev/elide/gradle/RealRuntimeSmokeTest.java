package dev.elide.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealRuntimeSmokeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsCompilesAndRunsWithThePinnedManagedRuntime() throws IOException {
        String runtimeMode = System.getProperty("elide.runtime.mode");
        String runtimeVersion = System.getProperty("elide.runtime.version");
        assertEquals("MANAGED", runtimeMode);
        assertEquals("1.5.1+20260903", runtimeVersion);

        Path projectDirectory = temporaryDirectory.resolve("managed-runtime-smoke");
        Path gradleUserHome = temporaryDirectory.resolve("gradle-user-home");
        writeSmokeProject(projectDirectory, runtimeVersion);

        BuildResult result = GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDirectory.toFile())
                .withTestKitDir(projectDirectory.resolve("test-kit").toFile())
                .withEnvironment(isolatedEnvironment(gradleUserHome))
                .withArguments("--gradle-user-home", gradleUserHome.toString(),
                        "clean", "run", "-Pelide.runtime.mode=" + runtimeMode, "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        assertTrue(Files.isRegularFile(gradleUserHome.resolve("caches/dev.elide/runtimes")
                .resolve(runtimeVersion)
                .resolve(platformKey())
                .resolve(".complete")));
        var installTask = result.task(":elideInstall");
        assertNotNull(installTask);
        assertEquals(TaskOutcome.SUCCESS, installTask.getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(Files.isRegularFile(projectDirectory.resolve("build/classes/java/main/example/RealElideSmoke.class")));
        assertTrue(Files.isRegularFile(projectDirectory.resolve(
                ".dev/dependencies/m2/com/google/guava/guava/33.4.8-jre/guava-33.4.8-jre.jar")));
        assertTrue(result.getOutput().contains("REAL_ELIDE_OK=2"), result.getOutput());
    }

    private static void writeSmokeProject(Path projectDirectory, String runtimeVersion) throws IOException {
        Files.createDirectories(projectDirectory.resolve("src/main/java/example"));
        Files.writeString(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'managed-runtime-smoke'\n");
        Files.writeString(projectDirectory.resolve("elide.pkl"), """
                amends "elide:project.pkl"

                name = "managed-runtime-smoke"

                dependencies {
                  maven {
                    packages {
                      "com.google.guava:guava:33.4.8-jre"
                    }
                  }
                }
                """);
        Files.writeString(projectDirectory.resolve("src/main/java/example/RealElideSmoke.java"), """
                package example;

                import com.google.common.collect.ImmutableList;

                public final class RealElideSmoke {
                    public static void main(String[] args) {
                        System.out.println("REAL_ELIDE_OK=" + ImmutableList.of("alpha", "beta").size());
                    }
                }
                """);
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                    id 'java'
                    id 'application'
                }

                def requestedRuntimeMode = providers.gradleProperty('elide.runtime.mode').get()
                elide {
                    runtimeMode.set(dev.elide.gradle.ElideRuntimeMode.valueOf(requestedRuntimeMode))
                    runtimeVersion.set('%s')
                    enableInstall.set(true)
                    enableMavenIntegration.set(true)
                    enableJavaCompiler.set(true)
                    manifest.set(layout.projectDirectory.file('elide.pkl'))
                }

                dependencies {
                    implementation 'com.google.guava:guava:33.4.8-jre'
                }

                application {
                    mainClass.set('example.RealElideSmoke')
                }
                """.formatted(runtimeVersion));
    }

    private static Map<String, String> isolatedEnvironment(Path gradleUserHome) {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Map<String, String> environment = new HashMap<>();
        environment.put("GRADLE_USER_HOME", gradleUserHome.toString());
        environment.put("JAVA_HOME", javaHome.toString());
        environment.put("PATH", javaHome.resolve("bin").toString() + File.pathSeparator);
        return environment;
    }

    private static String platformKey() {
        String osName = System.getProperty("os.name").toLowerCase();
        String os = osName.equals("linux") ? "linux"
                : osName.equals("mac os x") ? "macos"
                : "windows";
        String architecture = System.getProperty("os.arch").toLowerCase();
        String arch = architecture.equals("x86_64") || architecture.equals("amd64") ? "amd64" : "arm64";
        return os + "-" + arch;
    }
}
