package dev.elide.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Compares staged formatting outputs or explicitly applies them to source files. */
@DisableCachingByDefault(
        because = "Checks current sources or explicitly updates them; formatting itself is cached.")
public abstract class ElideFormatSourcesTask extends DefaultTask {
    @Input
    public abstract Property<Boolean> getApplyChanges();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getFormattedDirectories();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @TaskAction
    public void checkOrApply() throws IOException {
        Path root = getSourceRoot().get().getAsFile().toPath();
        Set<Path> allowed = new HashSet<>();
        getSources()
                .getFiles()
                .forEach(file -> allowed.add(file.toPath().toAbsolutePath().normalize()));
        List<String> changed = new ArrayList<>();
        for (var directory : getFormattedDirectories()) {
            Path staged = directory.toPath();
            if (!Files.isDirectory(staged)) continue;
            try (var files = Files.walk(staged)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    Path relative = staged.relativize(file);
                    Path source = root.resolve(relative).toAbsolutePath().normalize();
                    if (!allowed.contains(source))
                        throw new GradleException("Unexpected formatter output: " + relative);
                    if (Files.mismatch(file, source) != -1) {
                        changed.add(relative.toString());
                        if (getApplyChanges().get())
                            Files.copy(file, source, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        if (!getApplyChanges().get() && !changed.isEmpty()) {
            throw new GradleException(
                    "Formatting required; run elideFormat:\n" + String.join("\n", changed));
        }
        setDidWork(!changed.isEmpty());
    }
}
