package dev.elide.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.ArrayList;
import java.util.List;

/** Supplies the selected Elide compiler launcher inputs when Java compilation executes. */
public abstract class ElideCompilerArgumentProvider implements CommandLineArgumentProvider {
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getElideExecutable();

    @Classpath
    public abstract ConfigurableFileCollection getLauncherClasspath();

    @Input
    public abstract ListProperty<String> getForwardedJvmArguments();

    @Override
    public Iterable<String> asArguments() {
        List<String> arguments = new ArrayList<>(6 + getForwardedJvmArguments().get().size());
        arguments.add("-cp");
        arguments.add(getLauncherClasspath().getAsPath());
        arguments.add(ElideJavaCompilerLauncher.class.getName());
        arguments.add(getElideExecutable().get().getAsFile().getAbsolutePath());
        arguments.add("javac");
        arguments.add("--");
        arguments.addAll(getForwardedJvmArguments().get());
        return arguments;
    }
}
