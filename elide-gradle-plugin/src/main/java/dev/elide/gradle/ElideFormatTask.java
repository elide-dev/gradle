package dev.elide.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import javax.inject.Inject;

/** Formats staged copies so cache restoration and verification never overwrite source files. */
@CacheableTask
public abstract class ElideFormatTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getElideExecutable();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @IgnoreEmptyDirectories
    public abstract ConfigurableFileCollection getSources();

    @Input
    public abstract Property<String> getTool();

    @Input
    public abstract ListProperty<String> getArguments();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @OutputDirectory
    public abstract DirectoryProperty getDestinationDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    public ElideFormatTask() {
        getArguments().convention(List.of());
    }

    @Input
    public List<String> getRelativeSourcePaths() {
        Path root = getSourceRoot().get().getAsFile().toPath();
        return getSources().getFiles().stream()
                .map(file -> root.relativize(file.toPath()).toString())
                .sorted()
                .toList();
    }

    @TaskAction
    public void format() throws IOException {
        Set<String> allowed =
                getTool().get().equals("ktfmt")
                        ? Set.of(
                                "--meta-style",
                                "--google-style",
                                "--kotlinlang-style",
                                "--do-not-remove-unused-imports")
                        : Set.of(
                                "--aosp",
                                "--fix-imports-only",
                                "--skip-sorting-imports",
                                "--skip-removing-unused-imports",
                                "--skip-reflowing-long-strings",
                                "--skip-javadoc-formatting");
        for (String argument : getArguments().get()) {
            if (!allowed.contains(argument))
                throw new GradleException("Unsupported formatter style option: " + argument);
        }
        Path root = getSourceRoot().get().getAsFile().toPath();
        Path output = getDestinationDirectory().get().getAsFile().toPath();
        if (root.startsWith(output)
                || getSources().getFiles().stream()
                        .anyMatch(file -> file.toPath().startsWith(output))) {
            throw new GradleException("Formatter destination must not contain project sources");
        }
        if (Files.exists(output)) {
            try (var files = Files.walk(output)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(file);
            }
        }
        Files.createDirectories(output);
        List<String> sources = new ArrayList<>();
        for (File source : getSources().getFiles().stream().sorted().toList()) {
            Path relative = root.relativize(source.toPath());
            if (relative.startsWith(".."))
                throw new GradleException("Formatter source must be inside the project: " + source);
            Path destination = output.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
            sources.add("./" + relative.toString());
        }
        // Keep argument vectors bounded on Windows while retaining filenames containing spaces.
        List<String> batch = new ArrayList<>();
        int length = 0;
        for (String source : sources) {
            if (length + source.length() > 4000 && !batch.isEmpty()) {
                execute(output, batch);
                batch.clear();
                length = 0;
            }
            batch.add(source);
            length += source.length() + 3;
        }
        if (!batch.isEmpty()) execute(output, batch);
    }

    private void execute(Path output, List<String> sources) {
        List<String> args = new ArrayList<>(List.of(getTool().get(), "--"));
        args.addAll(getArguments().get());
        if (getTool().get().equals("javaformat")) args.add("--replace");
        args.addAll(sources);
        getExecOperations()
                .exec(
                        spec -> {
                            spec.executable(getElideExecutable().get().getAsFile());
                            spec.setWorkingDir(output.toFile());
                            spec.args(args);
                        })
                .assertNormalExitValue();
    }
}
