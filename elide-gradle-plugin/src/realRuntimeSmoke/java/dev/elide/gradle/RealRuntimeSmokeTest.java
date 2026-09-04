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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealRuntimeSmokeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesThePinnedManagedRuntime() throws IOException {
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
                        "verifyManagedRuntime", "-Pelide.runtime.mode=" + runtimeMode)
                .build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        assertTrue(Files.isRegularFile(gradleUserHome.resolve("caches/dev.elide/runtimes")
                .resolve(runtimeVersion)
                .resolve(platformKey())
                .resolve(".complete")));
    }

    private static void writeSmokeProject(Path projectDirectory, String runtimeVersion) throws IOException {
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'managed-runtime-smoke'\n");
        Files.writeString(projectDirectory.resolve("build.gradle"), """
                plugins {
                    id 'dev.elide'
                }

                def requestedRuntimeMode = providers.gradleProperty('elide.runtime.mode').get()
                elide {
                    runtimeMode = dev.elide.gradle.ElideRuntimeMode.valueOf(requestedRuntimeMode)
                    runtimeVersion = '%s'
                    enableJavaCompiler = false
                }

                tasks.register('verifyManagedRuntime') {
                    dependsOn 'prepareElideRuntime'
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
