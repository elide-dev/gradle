package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class FormattingFunctionalTest {
    @TempDir Path directory;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void checkPreservesSourcesAndApplyUsesCachedFormattedOutputs() throws Exception {
        Files.writeString(
                directory.resolve("settings.gradle"),
                "rootProject.name='format-test'\n"
                    + "buildCache { local { directory = file('cache') } }\n");
        Path runtime = directory.resolve("elide");
        Files.writeString(
                runtime,
                """
                #!/bin/sh
                shift
                shift
                for file in "$@"; do
                  case "$file" in
                    *.java|*.kt) printf 'formatted\\n' > "$file" ;;
                  esac
                done
                """);
        runtime.toFile().setExecutable(true);
        Files.writeString(
                directory.resolve("build.gradle"),
                """
                plugins { id 'java'; id 'dev.elide' }
                elide { runtime { mode = dev.elide.gradle.ElideRuntimeMode.PATH; executable = layout.projectDirectory.file('elide') } }
                """);
        Path source = directory.resolve("src/main/java/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "unformatted\n");
        var failed = runner("elideCheckFormat").buildAndFail();
        assertTrue(failed.getOutput().contains("src/main/java/Example.java"));
        assertEquals("unformatted\n", Files.readString(source));
        var applied = runner("elideFormat").build();
        assertEquals(TaskOutcome.UP_TO_DATE, applied.task(":elideJavaFormat").getOutcome());
        assertEquals("formatted\n", Files.readString(source));
        runner("elideCheckFormat").build();
    }

    private GradleRunner runner(String task) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(directory.toFile())
                .withArguments(task, "--configuration-cache", "--build-cache", "--stacktrace");
    }
}
