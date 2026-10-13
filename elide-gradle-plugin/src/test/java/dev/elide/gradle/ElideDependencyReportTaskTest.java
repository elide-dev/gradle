package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

class ElideDependencyReportTaskTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesPortableTsvWithUnixLineEndingsSortedArtifactsAndChecksums() throws Exception {
        File depOne = writeArtifact("dep-1.0.jar", "one");
        File depTwo = writeArtifact("dep-2.0.jar", "two");
        File depThree = writeArtifact("dep-3.0.jar", "three");
        File report = temporaryDirectory.resolve("build/elide/dependencies/main.tsv").toFile();

        Project project = ProjectBuilder.builder().build();
        ElideDependencyReportTask task =
                project.getTasks().register("elideExportMainDependencies", ElideDependencyReportTask.class).get();
        task.getReportFile().set(report);
        task.getArtifacts()
                .set(List.of(
                        new ElideDependencyReportTask.Artifact("fixture:dep:3.0", depThree),
                        new ElideDependencyReportTask.Artifact("fixture:dep:1.0", depOne),
                        new ElideDependencyReportTask.Artifact("fixture:dep:2.0", depTwo)));

        task.writeReport();

        byte[] bytes = Files.readAllBytes(report.toPath());
        String reportText = new String(bytes, StandardCharsets.UTF_8);

        assertFalse(reportText.contains("\r"), "report must not contain carriage returns: " + reportText);

        String expected =
                "# Gradle-resolved Elide JVM dependencies; coordinate, artifact, SHA-256\n"
                        + "fixture:dep:1.0\tdep-1.0.jar\t" + sha256(depOne) + "\n"
                        + "fixture:dep:2.0\tdep-2.0.jar\t" + sha256(depTwo) + "\n"
                        + "fixture:dep:3.0\tdep-3.0.jar\t" + sha256(depThree) + "\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), bytes, reportText);
    }

    @Test
    void writesHeaderOnlyReportWithTrailingNewlineAndNoCarriageReturns() throws Exception {
        File report = temporaryDirectory.resolve("build/elide/dependencies/main.tsv").toFile();

        Project project = ProjectBuilder.builder().build();
        ElideDependencyReportTask task =
                project.getTasks().register("elideExportMainDependencies", ElideDependencyReportTask.class).get();
        task.getReportFile().set(report);
        task.getArtifacts().set(List.of());

        task.writeReport();

        byte[] bytes = Files.readAllBytes(report.toPath());
        assertFalse(
                new String(bytes, StandardCharsets.UTF_8).contains("\r"),
                "report must not contain carriage returns");
        assertArrayEquals(
                ("# Gradle-resolved Elide JVM dependencies; coordinate, artifact, SHA-256\n")
                        .getBytes(StandardCharsets.UTF_8),
                bytes);
    }

    private File writeArtifact(String name, String content) throws Exception {
        File file = temporaryDirectory.resolve(name).toFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
