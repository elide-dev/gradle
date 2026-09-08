package dev.elide.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.ArrayList;
import java.util.List;

/** Supplies the selected Elide compiler launcher inputs when Java compilation executes. */
public abstract class ElideCompilerArgumentProvider implements CommandLineArgumentProvider {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getElideExecutable();

    @Classpath
    public abstract ConfigurableFileCollection getLauncherClasspath();

    @Input
    public abstract ListProperty<String> getForwardedJvmArguments();

    @Input public abstract Property<Boolean> getPersistentCompiler();
    @Input public abstract Property<ElideDependencyMode> getDependencyMode();
    @Internal public abstract Property<ElideCompilerService> getCompilerService();
    @Internal public abstract ConfigurableFileCollection getWorkerClasspath();

    @Override
    public Iterable<String> asArguments() {
        List<String> arguments = new ArrayList<>(6 + getForwardedJvmArguments().get().size());
        arguments.add("-cp");
        arguments.add(getLauncherClasspath().getAsPath());
        arguments.add(ElideJavaCompilerLauncher.class.getName());
        if (!getPersistentCompiler().get() && getDependencyMode().get() == ElideDependencyMode.GRADLE) {
            arguments.add("--gradle-classpath");
        }
        if (getPersistentCompiler().get()) {
            if (!getForwardedJvmArguments().get().isEmpty()) {
                throw new IllegalArgumentException("persistentCompiler does not support forkOptions.jvmArgs; disable it for this compiler configuration");
            }
            arguments.add("--worker");
            arguments.add(getCompilerService().get().endpoint());
            // Keep large classpaths inside the service instead of on the OS command line.
            arguments.add("request=" + getCompilerService().get().registerCompiler(
                    getElideExecutable().get().getAsFile().getAbsolutePath(), getWorkerClasspath().getFiles()));
        }
        arguments.add(getElideExecutable().get().getAsFile().getAbsolutePath());
        arguments.add("javac");
        arguments.add("--");
        arguments.addAll(getForwardedJvmArguments().get());
        return arguments;
    }
}
