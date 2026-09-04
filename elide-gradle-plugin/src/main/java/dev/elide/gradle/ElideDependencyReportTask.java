package dev.elide.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.*;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Auditable, portable inventory of the exact artifacts selected and verified by Gradle. */
@CacheableTask
public abstract class ElideDependencyReportTask extends DefaultTask {
    @Nested
    public abstract ListProperty<Artifact> getArtifacts();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    public static final class Artifact implements Serializable {
        private final String coordinate;
        private final File file;

        public Artifact(String coordinate, File file) {
            this.coordinate = coordinate;
            this.file = file;
        }

        @Input
        public String getCoordinate() {
            return coordinate;
        }

        @InputFile
        @PathSensitive(PathSensitivity.NAME_ONLY)
        public File getFile() {
            return file;
        }
    }

    @TaskAction
    public void writeReport() throws IOException, NoSuchAlgorithmException {
        List<String> lines = new ArrayList<>();
        lines.add("# Gradle-resolved Elide JVM dependencies; coordinate, artifact, SHA-256");
        for (Artifact artifact : getArtifacts().get()) {
            File file = artifact.getFile();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            lines.add(
                    artifact.getCoordinate()
                            + "\t"
                            + file.getName()
                            + "\t"
                            + HexFormat.of().formatHex(digest.digest()));
        }
        Collections.sort(lines.subList(1, lines.size()));
        Path report = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(report.getParent());
        Files.write(report, lines);
    }
}
