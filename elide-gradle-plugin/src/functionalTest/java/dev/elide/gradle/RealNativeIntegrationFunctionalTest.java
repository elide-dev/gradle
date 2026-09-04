package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

/** Opt-in local verification against a real distribution; CI also runs the managed runtime lane. */
class RealNativeIntegrationFunctionalTest {
    @TempDir Path directory;

    @Test
    void parallelModulesKeepOutputsIsolatedWithConfigurationCacheReuse() throws Exception {
        String runtime = System.getenv("ELIDE_INTEGRATION_EXECUTABLE");
        Assumptions.assumeTrue(
                runtime != null,
                "Set ELIDE_INTEGRATION_EXECUTABLE to run the real native integration test");
        Files.writeString(
                directory.resolve("settings.gradle"),
                """
                plugins { id 'dev.elide.settings' }
                elide { runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH } }
                rootProject.name='parallel-native'
                include 'a', 'b'
                buildCache { local { directory = file('cache') } }
                """);
        for (String module : java.util.List.of("a", "b")) {
            Path project = directory.resolve(module);
            Path source = project.resolve("src/main/java/Example.java");
            Files.createDirectories(source.getParent());
            Files.writeString(
                    source,
                    "public class Example { public String value() { return \""
                            + module
                            + "\"; } }\n");
            Files.writeString(
                    project.resolve("build.gradle"),
                    """
                    plugins { id 'java'; id 'dev.elide' }
                    elide {
                      persistentCompiler.set(true)
                      dependencyMode.set(dev.elide.gradle.ElideDependencyMode.GRADLE)
                      runtime { executable = layout.projectDirectory.file('%s') }
                    }
                    """
                            .formatted(runtime.replace("\\", "/").replace("'", "\\'")));
        }
        run(":a:classes", ":b:classes", "--parallel", "--max-workers=2").build();
        byte[] a = Files.readAllBytes(directory.resolve("a/build/classes/java/main/Example.class"));
        byte[] b = Files.readAllBytes(directory.resolve("b/build/classes/java/main/Example.class"));
        assertFalse(java.util.Arrays.equals(a, b), "Each module must keep its own class output");
        var reused = run(":a:classes", ":b:classes", "--parallel", "--max-workers=2").build();
        assertTrue(reused.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":a:compileJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":b:compileJava").getOutcome());
    }

    @Test
    void workerCompilesMainAndTestSourcesAndFormattersCheckBothLanguages() throws Exception {
        String runtime = System.getenv("ELIDE_INTEGRATION_EXECUTABLE");
        Assumptions.assumeTrue(
                runtime != null,
                "Set ELIDE_INTEGRATION_EXECUTABLE to run the real native integration test");
        Files.writeString(
                directory.resolve("settings.gradle"),
                "rootProject.name='real-native'\n"
                    + "buildCache { local { directory = file('cache') } }\n");
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins { id 'java'; id 'dev.elide' }
                elide {
                  runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH; executable = layout.projectDirectory.file('%s') }
                  persistentCompiler.set(true)
                }
                """
                        .formatted(runtime.replace("\\", "/").replace("'", "\\'")));
        Path source = directory.resolve("src/main/java/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "public class Example {public int value(){return 1;}}\n");
        Path test = directory.resolve("src/test/java/ExampleTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(
                test,
                "public class ExampleTest {public int value(){return new Example().value();}}\n");
        Path kotlin = directory.resolve("src/main/kotlin/Example.kt");
        Files.createDirectories(kotlin.getParent());
        Files.writeString(kotlin, "fun example( ) = 1\n");
        var result = run("testClasses").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(
                1,
                result.getOutput().split("Started Elide compiler worker", -1).length - 1,
                "Main and test compilation should share a native process");
        assertTrue(Files.exists(directory.resolve("build/classes/java/test/ExampleTest.class")));
        assertEquals(
                TaskOutcome.UP_TO_DATE,
                run("testClasses").build().task(":compileJava").getOutcome());
        assertEquals(
                TaskOutcome.FROM_CACHE,
                run("clean", "testClasses").build().task(":compileJava").getOutcome());
        String original = Files.readString(source);
        run("elideCheckFormat").buildAndFail();
        assertEquals(original, Files.readString(source));
        run("elideFormat").build();
        assertNotEquals(original, Files.readString(source));
        run("elideCheckFormat").build();
    }

    private GradleRunner run(String... tasks) {
        var args = new java.util.ArrayList<>(java.util.List.of(tasks));
        args.addAll(
                java.util.List.of(
                        "--configuration-cache", "--build-cache", "--stacktrace", "--info"));
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(directory.toFile())
                .withArguments(args);
    }
}
