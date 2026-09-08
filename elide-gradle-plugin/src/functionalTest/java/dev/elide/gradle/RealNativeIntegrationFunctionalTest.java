package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

/** Required by the realNativeIntegrationTest CI task; never silently skips missing runtimes. */
class RealNativeIntegrationFunctionalTest {
    @TempDir Path directory;
    private String gradleVersion;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "7.6.4,false",
        "7.6.4,true",
        "8.14.5,false",
        "8.14.5,true",
        "9.7.1,false",
        "9.7.1,true"
    })
    void remoteCacheRestoresRealClassesAndSourceChangesInvalidateBothCompilerModes(
            String version, boolean persistent) throws Exception {
        gradleVersion = version;
        String runtime = System.getenv("ELIDE_INTEGRATION_EXECUTABLE");
        assertNotNull(runtime, "Real Elide is required");
        var entries = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var serverErrors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/cache/",
                exchange -> {
                    try (exchange) {
                        // This tiny fixture closes each exchange; advertise it to prevent stale
                        // reuse.
                        exchange.getResponseHeaders().set("Connection", "close");
                        String key = exchange.getRequestURI().getPath();
                        if (exchange.getRequestMethod().equals("PUT")) {
                            entries.put(key, exchange.getRequestBody().readAllBytes());
                            exchange.sendResponseHeaders(200, -1);
                        } else {
                            byte[] content = entries.get(key);
                            if (content == null) {
                                exchange.sendResponseHeaders(404, -1);
                            } else {
                                hits.incrementAndGet();
                                exchange.sendResponseHeaders(200, content.length);
                                exchange.getResponseBody().write(content);
                            }
                        }
                    } catch (Throwable failure) {
                        serverErrors.add(failure);
                    }
                });
        server.start();
        try {
            Files.writeString(
                    directory.resolve("settings.gradle"),
                    """
                    rootProject.name='remote-native'
                    buildCache {
                      local { enabled = false }
                      remote(org.gradle.caching.http.HttpBuildCache) {
                        url = uri('http://127.0.0.1:%d/cache/')
                        allowInsecureProtocol = true
                        push = true
                      }
                    }
                    """
                            .formatted(server.getAddress().getPort()));
            Files.writeString(
                    directory.resolve("build.gradle"),
                    """
                    plugins { id 'java'; id 'dev.elide' }
                    elide {
                      persistentCompiler.set(%s)
                      dependencyMode.set(dev.elide.gradle.ElideDependencyMode.GRADLE)
                      runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH; executable = layout.projectDirectory.file('%s') }
                    }
                    """
                            .formatted(persistent, runtime.replace("\\", "/").replace("'", "\\'")));
            Path source = directory.resolve("src/main/java/Example.java");
            Files.createDirectories(source.getParent());
            Files.writeString(
                    source, "public class Example { public int value() { return 1; } }\n");
            Files.writeString(
                    source.resolveSibling("Independent.java"), "public class Independent {}\n");
            var initial = run("compileJava").build();
            assertEquals(TaskOutcome.SUCCESS, initial.task(":compileJava").getOutcome());
            Path compiled = directory.resolve("build/classes/java/main/Example.class");
            byte[] original = Files.readAllBytes(compiled);
            assertTrue(serverErrors.isEmpty(), serverErrors.toString());
            assertFalse(
                    entries.isEmpty(),
                    "Compilation must upload to the HTTP cache\n" + initial.getOutput());
            var restored = run("clean", "compileJava").build();
            assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileJava").getOutcome());
            assertArrayEquals(original, Files.readAllBytes(compiled));
            assertTrue(hits.get() > 0, "Local caching is disabled: restoration must use HTTP");
            Path relocated = directory.resolve("relocated checkout");
            for (String file :
                    java.util.List.of(
                            "settings.gradle",
                            "build.gradle",
                            "src/main/java/Example.java",
                            "src/main/java/Independent.java")) {
                Path target = relocated.resolve(file);
                Files.createDirectories(target.getParent());
                Files.copy(directory.resolve(file), target);
            }
            var relocatedResult = run("compileJava").withProjectDir(relocated.toFile()).build();
            assertEquals(TaskOutcome.FROM_CACHE, relocatedResult.task(":compileJava").getOutcome());
            assertArrayEquals(
                    original,
                    Files.readAllBytes(relocated.resolve("build/classes/java/main/Example.class")));
            Files.writeString(
                    source, "public class Example { public int value() { return 2; } }\n");
            assertEquals(
                    TaskOutcome.SUCCESS,
                    run("compileJava").build().task(":compileJava").getOutcome());
            assertFalse(java.util.Arrays.equals(original, Files.readAllBytes(compiled)));
            Files.delete(source);
            run("compileJava").build();
            assertFalse(Files.exists(compiled), "Deleted sources must not leave stale classes");
            assertTrue(Files.exists(compiled.resolveSibling("Independent.class")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void changedClasspathJarInvalidatesPersistentCompilation() throws Exception {
        String runtime = System.getenv("ELIDE_INTEGRATION_EXECUTABLE");
        assertNotNull(runtime, "Real Elide is required");
        Files.writeString(
                directory.resolve("settings.gradle"),
                "rootProject.name='classpath-invalidation'\n");
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins { id 'java'; id 'dev.elide' }
                elide {
                  persistentCompiler.set(true)
                  dependencyMode.set(dev.elide.gradle.ElideDependencyMode.GRADLE)
                  runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH; executable = layout.projectDirectory.file('%s') }
                }
                dependencies { implementation files('dependency.jar') }
                """
                        .formatted(runtime.replace("\\", "/").replace("'", "\\'")));
        Path source = directory.resolve("src/main/java/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "public class Example { public int value() { return Dependency.VALUE; } }\n");
        Path dependencySource = directory.resolve("dependency-src/Dependency.java");
        Files.createDirectories(dependencySource.getParent());
        Path classes = directory.resolve("dependency-classes");
        Files.createDirectories(classes);
        byte[] previous = null;
        for (int value : new int[] {1, 2}) {
            Files.writeString(
                    dependencySource,
                    "public class Dependency { public static final int VALUE = " + value + "; }\n");
            assertEquals(
                    0,
                    javax.tools.ToolProvider.getSystemJavaCompiler()
                            .run(
                                    null,
                                    null,
                                    null,
                                    "-d",
                                    classes.toString(),
                                    dependencySource.toString()));
            try (var jar =
                    new java.util.jar.JarOutputStream(
                            Files.newOutputStream(directory.resolve("dependency.jar")))) {
                jar.putNextEntry(new java.util.jar.JarEntry("Dependency.class"));
                jar.write(Files.readAllBytes(classes.resolve("Dependency.class")));
                jar.closeEntry();
            }
            var compiled = run("compileJava").build();
            assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileJava").getOutcome());
            byte[] current =
                    Files.readAllBytes(directory.resolve("build/classes/java/main/Example.class"));
            if (previous != null)
                assertFalse(
                        java.util.Arrays.equals(previous, current),
                        "Replacing the JAR at the same path must update the inlined constant");
            previous = current;
        }
        assertFalse(
                Files.exists(directory.resolve(".dev")),
                "Gradle mode must not run the Elide installer");
    }

    @Test
    void failedCompilationDoesNotPoisonTheSharedWorker() throws Exception {
        String runtime = System.getenv("ELIDE_INTEGRATION_EXECUTABLE");
        assertNotNull(runtime, "Real Elide is required");
        Files.writeString(
                directory.resolve("settings.gradle"), "rootProject.name='worker-recovery'\n");
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins { id 'java'; id 'dev.elide' }
                sourceSets { broken; healthy }
                elide {
                  persistentCompiler.set(true)
                  dependencyMode.set(dev.elide.gradle.ElideDependencyMode.GRADLE)
                  runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH; executable = layout.projectDirectory.file('%s') }
                }
                tasks.named('compileHealthyJava') { mustRunAfter 'compileBrokenJava' }
                """
                        .formatted(runtime.replace("\\", "/").replace("'", "\\'")));
        Path broken = directory.resolve("src/broken/java/Broken.java");
        Path healthy = directory.resolve("src/healthy/java/Healthy.java");
        Files.createDirectories(broken.getParent());
        Files.createDirectories(healthy.getParent());
        Files.writeString(broken, "public class Broken extends MissingType {}\n");
        Files.writeString(healthy, "public class Healthy {}\n");
        var result = run("compileBrokenJava", "compileHealthyJava", "--continue").buildAndFail();
        assertEquals(TaskOutcome.FAILED, result.task(":compileBrokenJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileHealthyJava").getOutcome());
        assertTrue(result.getOutput().contains("cannot find symbol"), result.getOutput());
        assertTrue(Files.exists(directory.resolve("build/classes/java/healthy/Healthy.class")));
        assertFalse(Files.exists(directory.resolve("build/classes/java/broken/Broken.class")));
        assertEquals(
                1,
                result.getOutput().split("Started Elide compiler worker", -1).length - 1,
                "A compilation error must not force the healthy request to start another worker");
    }

    @Test
    void parallelModulesKeepOutputsIsolatedWithConfigurationCacheReuse() throws Exception {
        String runtime = System.getenv("ELIDE_INTEGRATION_EXECUTABLE");
        assertNotNull(
                runtime,
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
        assertNotNull(
                runtime,
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
        String originalKotlin = Files.readString(kotlin);
        run("elideCheckFormat").buildAndFail();
        assertEquals(original, Files.readString(source));
        assertEquals(originalKotlin, Files.readString(kotlin));
        var cachedFormats = run("clean", "elideJavaFormat", "elideKtfmt").build();
        assertEquals(TaskOutcome.FROM_CACHE, cachedFormats.task(":elideJavaFormat").getOutcome());
        assertEquals(TaskOutcome.FROM_CACHE, cachedFormats.task(":elideKtfmt").getOutcome());
        run("elideFormat").build();
        assertNotEquals(original, Files.readString(source));
        assertNotEquals(originalKotlin, Files.readString(kotlin));
        run("elideCheckFormat").build();
        Path stagedJava =
                directory.resolve("build/elide/formatted/java/src/main/java/Example.java");
        Path stagedKotlin =
                directory.resolve("build/elide/formatted/kotlin/src/main/kotlin/Example.kt");
        String defaultJava = Files.readString(stagedJava);
        Files.writeString(
                directory.resolve("build.gradle"),
                """

                tasks.named('elideJavaFormat') { arguments.set(['--aosp']) }
                tasks.named('elideKtfmt') { arguments.set(['--kotlinlang-style']) }
                """,
                StandardOpenOption.APPEND);
        var styles = run("elideJavaFormat", "elideKtfmt").build();
        assertEquals(TaskOutcome.SUCCESS, styles.task(":elideJavaFormat").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, styles.task(":elideKtfmt").getOutcome());
        assertNotEquals(
                defaultJava,
                Files.readString(stagedJava),
                "Changed Java style must change staged output");
        Files.delete(kotlin);
        run("elideKtfmt").build();
        assertFalse(
                Files.exists(stagedKotlin),
                "Removed Kotlin source must not leave stale staged output");
        Files.writeString(
                directory.resolve("build.gradle"),
                "\ntasks.named('elideJavaFormat') { arguments.set(['--dry-run']) }\n",
                StandardOpenOption.APPEND);
        var invalid = run("elideJavaFormat").buildAndFail();
        assertTrue(invalid.getOutput().contains("Unsupported formatter style option"));
    }

    private GradleRunner run(String... tasks) {
        var args = new java.util.ArrayList<>(java.util.List.of(tasks));
        args.addAll(
                java.util.List.of(
                        "--configuration-cache",
                        "--build-cache",
                        "--stacktrace",
                        "--info",
                        "--no-watch-fs"));
        var runner =
                GradleRunner.create()
                        .withPluginClasspath()
                        .withProjectDir(directory.toFile())
                        .withArguments(args);
        if (gradleVersion != null) runner.withGradleVersion(gradleVersion);
        return runner;
    }
}
