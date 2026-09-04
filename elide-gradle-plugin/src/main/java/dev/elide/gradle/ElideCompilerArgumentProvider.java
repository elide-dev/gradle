package dev.elide.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.List;

/** Supplies the selected Elide compiler launcher inputs when Java compilation executes. */
public abstract class ElideCompilerArgumentProvider implements CommandLineArgumentProvider {
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getElideExecutable();

    @Input
    public abstract Property<ElideRuntimeSource> getRuntimeSource();

    @Classpath
    public abstract ConfigurableFileCollection getLauncherClasspath();

    @Override
    public Iterable<String> asArguments() {
        return List.of(
                "-cp",
                getLauncherClasspath().getAsPath(),
                ElideJavaCompilerLauncher.class.getName(),
                getElideExecutable().get().getAsFile().getAbsolutePath(),
                "javac",
                "--");
    }
}
