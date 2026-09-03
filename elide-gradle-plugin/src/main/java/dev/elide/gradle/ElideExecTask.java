package dev.elide.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Executes Elide with inputs declared for Gradle up-to-date and cache analysis. */
@DisableCachingByDefault(because = "Elide commands may change dependency state outside declared outputs.")
public abstract class ElideExecTask extends DefaultTask {
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 64 * 1024;
    private static final byte[] REDACTION_MARKER = "[redacted]".getBytes(StandardCharsets.UTF_8);

    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getElideExecutable();

    @Input
    public abstract ListProperty<String> getElideArguments();

    @Internal
    public abstract DirectoryProperty getWorkingDirectory();

    /** Identifies the execution directory without snapshotting every project file. */
    @Input
    public abstract Property<String> getWorkingDirectoryPath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getManifest();

    /** Elide development inputs, excluding files produced by this task. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @IgnoreEmptyDirectories
    public abstract ConfigurableFileCollection getDevRootInputs();

    @OutputDirectory
    public abstract DirectoryProperty getGeneratedDependencyRepository();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void executeElide() {
        BoundedOutputStream standardOutput = new BoundedOutputStream();
        BoundedOutputStream errorOutput = new BoundedOutputStream();
        ExecResult result;
        try {
            result = getExecOperations().exec(spec -> {
                var executable = getElideExecutable().get().getAsFile();
                if (isWindowsBatchScript(executable)) {
                    spec.executable(System.getenv().getOrDefault("ComSpec", "cmd.exe"));
                    spec.args("/d", "/c", executable.getAbsolutePath());
                } else {
                    spec.executable(executable);
                }
                spec.args(getElideArguments().get());
                spec.setWorkingDir(getWorkingDirectory().get().getAsFile());
                spec.setStandardOutput(standardOutput);
                spec.setErrorOutput(errorOutput);
                spec.setIgnoreExitValue(true);
            });
        } catch (RuntimeException exception) {
            throw new GradleException(failureMessage(
                    -1,
                    standardOutput.content(),
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()),
                    exception);
        }
        if (result.getExitValue() != 0) {
            throw new GradleException(failureMessage(
                    result.getExitValue(),
                    standardOutput.content(),
                    errorOutput.content()));
        }
    }

    private String failureMessage(int exitCode, String standardOutput, String errorOutput) {
        String exitCodeMarker = "\u0000ELIDE_EXIT_CODE\u0000";
        String message = "Elide command failed: executable "
                + getElideExecutable().get().getAsFile().getAbsolutePath()
                + ", working directory "
                + getWorkingDirectory().get().getAsFile().getAbsolutePath()
                + ", exit code " + exitCodeMarker + ".";
        if (!standardOutput.isBlank()) {
            message += "\nStandard output:\n" + standardOutput;
        }
        if (!errorOutput.isBlank()) {
            message += "\nStandard error:\n" + errorOutput;
        }
        return redactEnvironmentValues(message).replace(exitCodeMarker, Integer.toString(exitCode));
    }

    private static String redactEnvironmentValues(String message) {
        String redacted = message;
        for (String value : environmentValues()) {
            redacted = redacted.replace(value, "[redacted]");
        }
        return redacted;
    }

    private static List<String> environmentValues() {
        return System.getenv().values().stream()
                .filter(value -> value != null && !value.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private static boolean isWindowsBatchScript(java.io.File executable) {
        String name = executable.getName().toLowerCase(Locale.ROOT);
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                && (name.endsWith(".cmd") || name.endsWith(".bat"));
    }

    /** Limits captured process output so a noisy failed command cannot exhaust the Gradle daemon. */
    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final List<byte[]> environmentValueBytes = environmentValues().stream()
                .map(value -> value.getBytes(StandardCharsets.UTF_8))
                .sorted(Comparator.comparingInt((byte[] value) -> value.length).reversed())
                .toList();
        private final int safeCaptureLimit = MAX_CAPTURED_OUTPUT_BYTES + environmentValueBytes.stream()
                .mapToInt(value -> value.length)
                .max()
                .orElse(0);
        private boolean truncated;

        @Override
        public void write(int value) {
            if (delegate.size() < safeCaptureLimit) {
                delegate.write(value);
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            int available = safeCaptureLimit - delegate.size();
            int capturedLength = Math.min(Math.max(available, 0), length);
            if (capturedLength > 0) {
                delegate.write(bytes, offset, capturedLength);
            }
            if (capturedLength < length) {
                truncated = true;
            }
        }

        private String content() {
            byte[] captured = delegate.toByteArray();
            int outputLimit = Math.min(captured.length, MAX_CAPTURED_OUTPUT_BYTES);
            ByteArrayOutputStream redacted = new ByteArrayOutputStream(outputLimit);
            for (int offset = 0; offset < outputLimit; ) {
                byte[] value = matchingEnvironmentValue(captured, offset);
                if (value == null) {
                    redacted.write(captured[offset]);
                    offset++;
                } else {
                    redacted.writeBytes(REDACTION_MARKER);
                    offset += value.length;
                }
            }
            String output = redacted.toString(StandardCharsets.UTF_8);
            return truncated || captured.length > MAX_CAPTURED_OUTPUT_BYTES
                    ? output + "\n[output truncated after " + MAX_CAPTURED_OUTPUT_BYTES + " bytes]"
                    : output;
        }

        private byte[] matchingEnvironmentValue(byte[] captured, int offset) {
            for (byte[] value : environmentValueBytes) {
                if (offset + value.length > captured.length) {
                    continue;
                }
                boolean matches = true;
                for (int index = 0; index < value.length; index++) {
                    if (captured[offset + index] != value[index]) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return value;
                }
            }
            return null;
        }
    }
}
