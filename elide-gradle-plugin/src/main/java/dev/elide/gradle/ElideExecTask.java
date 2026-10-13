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
import java.util.Arrays;
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
    private final ExecOperations execOperations;

    @Inject
    public ElideExecTask(ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

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

    @Internal
    protected ExecOperations getExecOperations() {
        return execOperations;
    }

    @TaskAction
    public void executeElide() {
        RedactionPolicy redactionPolicy = RedactionPolicy.create();
        BoundedOutputStream standardOutput = new BoundedOutputStream(redactionPolicy);
        BoundedOutputStream errorOutput = new BoundedOutputStream(redactionPolicy);
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
            throw new GradleException(failureMessage(redactionPolicy,
                    -1,
                    standardOutput.content(),
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        }
        if (result.getExitValue() != 0) {
            throw new GradleException(failureMessage(redactionPolicy,
                    result.getExitValue(),
                    standardOutput.content(),
                    errorOutput.content()));
        }
    }

    private String failureMessage(
            RedactionPolicy redactionPolicy, int exitCode, String standardOutput, String errorOutput) {
        String message = "Elide command failed: executable "
                + redactionPolicy.redact(getElideExecutable().get().getAsFile().getAbsolutePath())
                + ", working directory "
                + redactionPolicy.redact(getWorkingDirectory().get().getAsFile().getAbsolutePath())
                + ", exit code " + exitCode + ".";
        if (!standardOutput.isBlank()) {
            message += "\nStandard output:\n" + redactOutput(redactionPolicy, standardOutput);
        }
        if (!errorOutput.isBlank()) {
            message += "\nStandard error:\n" + redactOutput(redactionPolicy, errorOutput);
        }
        return message;
    }

    private static String redactOutput(RedactionPolicy redactionPolicy, String output) {
        return WITHHELD_OUTPUT_MARKER.equals(output) ? output : redactionPolicy.redact(output);
    }

    /** Limits captured process output so a noisy failed command cannot exhaust the Gradle daemon. */
    private static final class BoundedOutputStream extends OutputStream {
        private final RedactionPolicy redactionPolicy;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream(MAX_CAPTURE_STORAGE_BYTES);
        private boolean sawOutput;
        private boolean truncated;

        private BoundedOutputStream(RedactionPolicy redactionPolicy) {
            this.redactionPolicy = redactionPolicy;
        }

        @Override
        public void write(int value) {
            sawOutput = true;
            if (!redactionPolicy.isSafe()) {
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
            if (!redactionPolicy.isSafe()) {
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
            if (!redactionPolicy.isSafe()) {
                return WITHHELD_OUTPUT_MARKER;
            }
            byte[] captured = delegate.toByteArray();
            int outputLimit = Math.min(captured.length, MAX_CAPTURED_OUTPUT_BYTES);
            // Compute the union of every secret occurrence over the captured bytes before emitting
            // anything. A secret may start inside a previously matched secret's span and extend
            // past it (two environment values that share a byte-level suffix-prefix overlap); a
            // single forward scan that skips past a matched value would jump over that second
            // occurrence and emit its un-overlapped tail verbatim. Marking every covered position
            // and emitting one [redacted] marker per contiguous run closes that overlap gap.
            boolean[] covered = new boolean[outputLimit];
            for (RedactionValue value : redactionPolicy.values) {
                int valueLength = value.bytes.length;
                if (valueLength == 0) {
                    continue;
                }
                for (int start = 0; start < outputLimit; start++) {
                    if (redactionPolicy.matchesAt(captured, value, start)) {
                        Arrays.fill(covered, start, Math.min(start + valueLength, outputLimit), true);
                    }
                }
            }
            ByteArrayOutputStream redacted = new ByteArrayOutputStream(outputLimit);
            boolean renderedTruncated = false;
            boolean inRedactedRun = false;
            for (int offset = 0; offset < outputLimit; offset++) {
                if (covered[offset]) {
                    if (!inRedactedRun) {
                        if (redacted.size() + REDACTION_MARKER.length > MAX_CAPTURED_OUTPUT_BYTES) {
                            renderedTruncated = true;
                            break;
                        }
                        redacted.writeBytes(REDACTION_MARKER);
                        inRedactedRun = true;
                    }
                } else {
                    inRedactedRun = false;
                    if (redacted.size() == MAX_CAPTURED_OUTPUT_BYTES) {
                        renderedTruncated = true;
                        break;
                    }
                    redacted.write(captured[offset]);
                }
            }
            String output = redacted.toString(StandardCharsets.UTF_8);
            return truncated || captured.length > MAX_CAPTURED_OUTPUT_BYTES || renderedTruncated
                    ? output + TRUNCATION_MARKER
                    : output;
        }
    }

    /** Fixed-size capture-redaction metadata; unsafe environments withhold child output. */
    private static final class RedactionPolicy {
        private final boolean safe;
        private final List<RedactionValue> values;

        private RedactionPolicy(boolean safe, List<RedactionValue> values) {
            this.safe = safe;
            this.values = values;
        }

        private static RedactionPolicy create() {
            List<RedactionValue> values = new ArrayList<>();
            for (java.util.Map.Entry<String, String> variable : System.getenv().entrySet()) {
                String value = variable.getValue();
                if (!ElideSecretPolicy.isSensitive(variable.getKey(), value)) {
                    continue;
                }
                if (values.size() == MAX_REDACTION_VALUES || value.length() > MAX_REDACTION_VALUE_CHARS) {
                    return new RedactionPolicy(false, List.of());
                }
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_REDACTION_VALUE_BYTES) {
                    return new RedactionPolicy(false, List.of());
                }
                values.add(new RedactionValue(value, bytes));
            }
            values.sort(Comparator.comparingInt((RedactionValue value) -> value.bytes.length).reversed());
            return new RedactionPolicy(true, List.copyOf(values));
        }

        private boolean isSafe() {
            return safe;
        }

        private String redact(String untrustedValue) {
            if (!safe) {
                return "[redacted]";
            }
            if (untrustedValue == null || untrustedValue.isEmpty()) {
                return untrustedValue;
            }
            // Same union-of-intervals treatment as the byte scan: two values may overlap at
            // different start offsets, in which case plain String.replace of the longest value
            // first leaves the un-overlapped tail of the second value in the rendered path. Mask
            // every covered character and emit one [redacted] marker per contiguous run instead.
            int length = untrustedValue.length();
            boolean[] covered = new boolean[length];
            for (RedactionValue value : values) {
                int valueLength = value.text.length();
                if (valueLength == 0) {
                    continue;
                }
                for (int start = 0; start + valueLength <= length; start++) {
                    if (untrustedValue.startsWith(value.text, start)) {
                        Arrays.fill(covered, start, start + valueLength, true);
                    }
                }
            }
            StringBuilder redacted = new StringBuilder(length);
            boolean inRedactedRun = false;
            for (int index = 0; index < length; index++) {
                if (covered[index]) {
                    if (!inRedactedRun) {
                        redacted.append("[redacted]");
                        inRedactedRun = true;
                    }
                } else {
                    inRedactedRun = false;
                    redacted.append(untrustedValue.charAt(index));
                }
            }
            return redacted.toString();
        }

        private static boolean matchesAt(byte[] captured, RedactionValue value, int start) {
            int available = Math.min(value.bytes.length, captured.length - start);
            if (available <= 0) {
                return false;
            }
            for (int index = 0; index < available; index++) {
                if (captured[start + index] != value.bytes[index]) {
                    return false;
                }
            }
            // A full occurrence counts, as does a partial occurrence that runs to the end of the
            // captured buffer (a value split by the capture storage bound): the storage limit is
            // sized so the tail of such a value can still be matched and redacted.
            return available == value.bytes.length || start + available == captured.length;
        }
    }

    /** A fixed-size environment value reference plus its bounded UTF-8 capture form. */
    private static final class RedactionValue {
        private final String text;
        private final byte[] bytes;

        private RedactionValue(String text, byte[] bytes) {
            this.text = text;
            this.bytes = bytes;
        }
    }
}
