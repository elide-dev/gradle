package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class GradleDependencyFunctionalTest {
    @TempDir Path directory;

    @Test
    void exportsGradleConflictResolutionWithLockingAndOfflineReuseWithoutElide() throws Exception {
        module("dep", "1.0", "");
        module("dep", "2.0", "");
        module(
                "bridge",
                "1.0",
                "<dependencies><dependency><groupId>fixture</groupId><artifactId>dep</artifactId><version>2.0</version></dependency></dependencies>");
        Files.writeString(
                directory.resolve("settings.gradle"),
                "rootProject.name='gradle-dependencies'\n"
                    + "buildCache { local { directory = file('cache') } }\n");
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins { id 'java'; id 'dev.elide' }
                elide { dependencyMode.set(dev.elide.gradle.ElideDependencyMode.GRADLE) }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'fixture:dep:1.0'; implementation 'fixture:bridge:1.0' }
                dependencyLocking { lockAllConfigurations() }
                """);
        var result = run("elideExportDependencies", "--write-locks").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":elideExportMainDependencies").getOutcome());
        String report = Files.readString(directory.resolve("build/elide/dependencies/main.tsv"));
        assertTrue(report.contains("fixture:dep:2.0\tdep-2.0.jar\t"), report);
        assertFalse(report.contains("fixture:dep:1.0"), report);
        assertFalse(report.contains(directory.toString()), report);
        assertTrue(
                Files.readString(directory.resolve("gradle.lockfile")).contains("fixture:dep:2.0"));
        assertFalse(Files.exists(directory.resolve(".dev")));
        assertEquals(
                TaskOutcome.FROM_CACHE,
                run("clean", "elideExportDependencies", "--offline")
                        .build()
                        .task(":elideExportMainDependencies")
                        .getOutcome());
    }

    @Test
    void rejectsConflictingInstallerInGradleMode() throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='conflict'\n");
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins { id 'dev.elide' }
                elide { dependencyMode.set(dev.elide.gradle.ElideDependencyMode.GRADLE); install = true }
                """);
        assertTrue(run("help").buildAndFail().getOutput().contains("requires install = false"));
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void requiresAnExplicitGradleDependencyInsteadOfAmbientClasspath() throws Exception {
        Path ambient = directory.resolve("ambient");
        Files.createDirectories(ambient);
        Path dependency = directory.resolve("Unlisted.java");
        Files.writeString(dependency, "public class Unlisted {}\n");
        assertEquals(
                0,
                javax.tools.ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", ambient.toString(), dependency.toString()));
        Path executable = directory.resolve("elide");
        Files.writeString(
                executable,
                "#!/bin/sh\nshift\nshift\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin", "javac")
                        + "' \"$@\"\n");
        executable.toFile().setExecutable(true);
        Files.writeString(
                directory.resolve("settings.gradle"), "rootProject.name='strict-classpath'\n");
        Path build = directory.resolve("build.gradle");
        Files.writeString(
                build,
                """
                plugins { id 'java'; id 'dev.elide' }
                elide {
                  dependencyMode.set(dev.elide.gradle.ElideDependencyMode.GRADLE)
                  runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH; executable = layout.projectDirectory.file('elide') }
                }
                """);
        Path source = directory.resolve("src/main/java/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "public class Example extends Unlisted {}\n");
        var environment = new java.util.HashMap<>(System.getenv());
        environment.put("CLASSPATH", ambient.toString());
        assertTrue(
                run("compileJava")
                        .withEnvironment(environment)
                        .buildAndFail()
                        .getOutput()
                        .contains("cannot find symbol"));
        Files.writeString(
                build,
                "\ndependencies { implementation files('ambient') }\n",
                StandardOpenOption.APPEND);
        run("compileJava").withEnvironment(environment).build();
        assertTrue(Files.exists(directory.resolve("build/classes/java/main/Example.class")));
    }

    private void module(String name, String version, String dependencies) throws Exception {
        Path folder = directory.resolve("repo/fixture/" + name + "/" + version);
        Files.createDirectories(folder);
        Files.writeString(
                folder.resolve(name + "-" + version + ".pom"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>"
                        + name
                        + "</artifactId><version>"
                        + version
                        + "</version>"
                        + dependencies
                        + "</project>");
        try (var jar =
                new java.util.jar.JarOutputStream(
                        Files.newOutputStream(folder.resolve(name + "-" + version + ".jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("version.txt"));
            jar.write(version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private GradleRunner run(String... tasks) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(tasks));
        arguments.addAll(
                java.util.List.of("--configuration-cache", "--build-cache", "--stacktrace", "--no-watch-fs"));
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(directory.toFile())
                .withArguments(arguments);
    }
}
