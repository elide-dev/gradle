package com.example.plugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ElidePluginFunctionalTest {
    @Test
    void canRunTasks() throws IOException {
        File projectDir = new File("build/functionalTest");
        Files.createDirectories(projectDir.toPath());
        writeString(new File(projectDir, "settings.gradle"), "");
        writeString(new File(projectDir, "build.gradle"),
                """
                    plugins {
                      id('dev.elide')
                    }
                    """);

        BuildResult result = configuredRunner(projectDir, "tasks").build();

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
    }

    private void writeString(File file, String string) throws IOException {
        try (Writer writer = new FileWriter(file)) {
            writer.write(string);
        }
    }

    private GradleRunner configuredRunner(File projectDir, String... arguments) {
        return GradleRunner.create()
                .forwardOutput()
                .withPluginClasspath()
                .withArguments(arguments)
                .withProjectDir(projectDir);
    }
}
