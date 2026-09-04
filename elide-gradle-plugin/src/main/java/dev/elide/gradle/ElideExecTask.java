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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Executes Elide with inputs declared for Gradle up-to-date and cache analysis. */
@DisableCachingByDefault(because = "Elide commands may change dependency state outside declared outputs.")
public abstract class ElideExecTask extends DefaultTask {
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_REDACTION_VALUE_CHARS = 1024;
    private static final int MAX_REDACTION_VALUE_BYTES = MAX_REDACTION_VALUE_CHARS * 3;
    private static final int MAX_REDACTION_VALUES = 256;
    private static final int MAX_CAPTURE_STORAGE_BYTES = MAX_CAPTURED_OUTPUT_BYTES + MAX_REDACTION_VALUE_BYTES;
    private static final byte[] REDACTION_MARKER = "[redacted]".getBytes(StandardCharsets.UTF_8);
    private static final String TRUNCATION_MARKER = "\n[output truncated after "
            + MAX_CAPTURED_OUTPUT_BYTES + " bytes]";
    private static final String WITHHELD_OUTPUT_MARKER = "[Captured output withheld: environment values exceed the "
            + "redaction-safe capture bound]";
    private static final CaptureRedaction CAPTURE_REDACTION = CaptureRedaction.create();

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
                spec.executable(executable);
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
        String message = "Elide command failed: executable "
                + redactUntrusted(getElideExecutable().get().getAsFile().getAbsolutePath())
                + ", working directory "
                + redactUntrusted(getWorkingDirectory().get().getAsFile().getAbsolutePath())
                + ", exit code " + exitCode + ".";
        if (!standardOutput.isBlank()) {
            message += "\nStandard output:\n" + redactUntrusted(standardOutput);
        }
        if (!errorOutput.isBlank()) {
            message += "\nStandard error:\n" + redactUntrusted(errorOutput);
        }
        return message;
    }

    private static String redactUntrusted(String untrustedValue) {
        String redacted = untrustedValue;
        for (String environmentValue : environmentValues()) {
            redacted = redacted.replace(environmentValue, "[redacted]");
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

    /** Limits captured process output so a noisy failed command cannot exhaust the Gradle daemon. */
    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream(MAX_CAPTURE_STORAGE_BYTES);
        private boolean sawOutput;
        private boolean truncated;

        @Override
        public void write(int value) {
            sawOutput = true;
            if (!CAPTURE_REDACTION.isSafe()) {
                return;
            }
            if (delegate.size() < MAX_CAPTURE_STORAGE_BYTES) {
                delegate.write(value);
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (length == 0) {
                return;
            }
            sawOutput = true;
            if (!CAPTURE_REDACTION.isSafe()) {
                return;
            }
            int available = MAX_CAPTURE_STORAGE_BYTES - delegate.size();
            int capturedLength = Math.min(Math.max(available, 0), length);
            if (capturedLength > 0) {
                delegate.write(bytes, offset, capturedLength);
            }
            if (capturedLength < length) {
                truncated = true;
            }
        }

        private String content() {
            if (!sawOutput) {
                return "";
            }
            if (!CAPTURE_REDACTION.isSafe()) {
                return WITHHELD_OUTPUT_MARKER;
            }
            byte[] captured = delegate.toByteArray();
            int outputLimit = Math.min(captured.length, MAX_CAPTURED_OUTPUT_BYTES);
            ByteArrayOutputStream redacted = new ByteArrayOutputStream(outputLimit);
            boolean renderedTruncated = false;
            for (int offset = 0; offset < outputLimit; ) {
                int redactionLength = CAPTURE_REDACTION.matchingLength(captured, offset);
                if (redactionLength == 0) {
                    if (redacted.size() == MAX_CAPTURED_OUTPUT_BYTES) {
                        renderedTruncated = true;
                        break;
                    }
                    redacted.write(captured[offset]);
                    offset++;
                } else {
                    if (redacted.size() + REDACTION_MARKER.length > MAX_CAPTURED_OUTPUT_BYTES) {
                        renderedTruncated = true;
                        break;
                    }
                    redacted.writeBytes(REDACTION_MARKER);
                    offset += redactionLength;
                }
            }
            String output = redacted.toString(StandardCharsets.UTF_8);
            return truncated || captured.length > MAX_CAPTURED_OUTPUT_BYTES || renderedTruncated
                    ? output + TRUNCATION_MARKER
                    : output;
        }
    }

    /** Fixed-size capture-redaction metadata; unsafe environments withhold child output. */
    private static final class CaptureRedaction {
        private final boolean safe;
        private final List<byte[]> values;

        private CaptureRedaction(boolean safe, List<byte[]> values) {
            this.safe = safe;
            this.values = values;
        }

        private static CaptureRedaction create() {
            List<byte[]> values = new ArrayList<>();
            for (String value : System.getenv().values()) {
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if (values.size() == MAX_REDACTION_VALUES || value.length() > MAX_REDACTION_VALUE_CHARS) {
                    return new CaptureRedaction(false, List.of());
                }
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_REDACTION_VALUE_BYTES) {
                    return new CaptureRedaction(false, List.of());
                }
                values.add(bytes);
            }
            values.sort(Comparator.comparingInt((byte[] value) -> value.length).reversed());
            return new CaptureRedaction(true, List.copyOf(values));
        }

        private boolean isSafe() {
            return safe;
        }

        private int matchingLength(byte[] captured, int offset) {
            for (byte[] value : values) {
                int available = Math.min(value.length, captured.length - offset);
                boolean matches = available > 0;
                for (int index = 0; index < available; index++) {
                    if (captured[offset + index] != value[index]) {
                        matches = false;
                        break;
                    }
                }
                if (matches && (available == value.length || offset + available == captured.length)) {
                    return value.length;
                }
            }
            return 0;
        }
    }
}
