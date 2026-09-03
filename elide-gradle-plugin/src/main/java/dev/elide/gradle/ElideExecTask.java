package dev.elide.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Executes Elide with inputs declared for Gradle up-to-date and cache analysis. */
@DisableCachingByDefault(because = "Elide commands may change dependency state outside declared outputs.")
public abstract class ElideExecTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getElideExecutable();

    @Input
    public abstract ListProperty<String> getElideArguments();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getWorkingDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getManifest();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getDevRoot();

    @OutputFile
    public abstract RegularFileProperty getLockfile();

    @OutputDirectory
    public abstract DirectoryProperty getGeneratedDependencyRepository();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void executeElide() {
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        ExecResult result = getExecOperations().exec(spec -> {
            spec.executable(getElideExecutable().get().getAsFile());
            spec.args(getElideArguments().get());
            spec.setWorkingDir(getWorkingDirectory().get().getAsFile());
            spec.setStandardOutput(standardOutput);
            spec.setErrorOutput(errorOutput);
            spec.setIgnoreExitValue(true);
        });
        if (result.getExitValue() != 0) {
            throw new GradleException(failureMessage(
                    result.getExitValue(),
                    standardOutput.toString(StandardCharsets.UTF_8),
                    errorOutput.toString(StandardCharsets.UTF_8)));
        }
    }

    private String failureMessage(int exitCode, String standardOutput, String errorOutput) {
        String message = "Elide command failed: executable "
                + getElideExecutable().get().getAsFile().getAbsolutePath()
                + ", working directory "
                + getWorkingDirectory().get().getAsFile().getAbsolutePath()
                + ", exit code " + exitCode + ".";
        String redactedStandardOutput = redactEnvironmentValues(standardOutput);
        String redactedErrorOutput = redactEnvironmentValues(errorOutput);
        if (!redactedStandardOutput.isBlank()) {
            message += "\nStandard output:\n" + redactedStandardOutput;
        }
        if (!redactedErrorOutput.isBlank()) {
            message += "\nStandard error:\n" + redactedErrorOutput;
        }
        return message;
    }

    private static String redactEnvironmentValues(String output) {
        String redacted = output;
        for (String value : System.getenv().values()) {
            if (value != null && !value.isEmpty()) {
                redacted = redacted.replace(value, "[redacted]");
            }
        }
        return redacted;
    }
}
