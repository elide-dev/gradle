package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

class CompilationCacheFunctionalTest {
    @TempDir Path directory;
    private String gradleVersion;

    @ParameterizedTest
    @ValueSource(strings = {"7.6.4", "8.14.5", "9.7.1"})
    @DisabledOnOs(OS.WINDOWS)
    void restoresCompiledClassesFromCacheAndInvalidatesChangedCompiler(String version)
            throws Exception {
        gradleVersion = version;
        Path compiler = directory.resolve("elide");
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        Files.writeString(
                compiler,
                "#!/bin/sh\n"
                    + "shift\n"
                    + "shift\n"
                    + "for arg in \"$@\"; do\n"
                    + "case \"$arg\" in\n"
                    + "@*) cp \"${arg#@}\" compiler.last-args ;;\n"
                    + "esac\n"
                    + "done\n"
                    + "exec '"
                        + javac
                        + "' \"$@\"\n");
        compiler.toFile().setExecutable(true);
        Files.writeString(
                directory.resolve("settings.gradle"),
                "rootProject.name='cache-test'\nbuildCache { local { directory = file('"
                        + directory.resolve("cache").toString().replace("\\", "/")
                        + "') } }\n");
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins { id 'java'; id 'dev.elide' }
                elide { runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH; executable = layout.projectDirectory.file('elide') } }
                """);
        Path source = directory.resolve("src/main/java/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "public class Example { public int value() { return 1; } }\n");
        Files.writeString(source.resolveSibling("Unrelated.java"), "public class Unrelated {}\n");
        assertEquals(TaskOutcome.SUCCESS, run("compileJava").task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run("compileJava").task(":compileJava").getOutcome());
        assertEquals(
                TaskOutcome.FROM_CACHE,
                run("clean", "compileJava").task(":compileJava").getOutcome());
        assertTrue(Files.exists(directory.resolve("build/classes/java/main/Example.class")));
        Path relocated = directory.resolve("relocated");
        for (String file :
                java.util.List.of(
                        "elide",
                        "settings.gradle",
                        "build.gradle",
                        "src/main/java/Example.java",
                        "src/main/java/Unrelated.java")) {
            Path target = relocated.resolve(file);
            Files.createDirectories(target.getParent());
            Files.copy(directory.resolve(file), target);
        }
        relocated.resolve("elide").toFile().setExecutable(true);
        assertEquals(
                TaskOutcome.FROM_CACHE,
                GradleRunner.create()
                        .withGradleVersion(version)
                        .withPluginClasspath()
                        .withProjectDir(relocated.toFile())
                        .withArguments(
                                "compileJava",
                                "--build-cache",
                                "--configuration-cache",
                                "--stacktrace")
                        .build()
                        .task(":compileJava")
                        .getOutcome());
        Files.writeString(source, "public class Example { public int value() { return 2; } }\n");
        assertEquals(TaskOutcome.SUCCESS, run("compileJava").task(":compileJava").getOutcome());
        assertFalse(
                Files.readString(directory.resolve("compiler.last-args"))
                        .contains("Unrelated.java"),
                "An independent source should not be recompiled after a method-body change");
        Files.writeString(compiler, Files.readString(compiler) + "# changed compiler\n");
        assertEquals(TaskOutcome.SUCCESS, run("compileJava").task(":compileJava").getOutcome());
        Files.delete(source);
        run("compileJava");
        assertFalse(Files.exists(directory.resolve("build/classes/java/main/Example.class")));
    }

    private org.gradle.testkit.runner.BuildResult run(String... tasks) {
        java.util.List<String> arguments = new java.util.ArrayList<>(java.util.List.of(tasks));
        arguments.addAll(
                java.util.List.of("--build-cache", "--configuration-cache", "--stacktrace"));
        return GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withPluginClasspath()
                .withProjectDir(directory.toFile())
                .withArguments(arguments)
                .build();
    }
}
