package dev.elide.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads a candidate executable's reported version.
 *
 * <p>A {@link ValueSource} rather than a plain call, so the result is a tracked configuration-cache
 * input that is re-read when the cache is checked, instead of a value frozen into the cache entry.
 *
 * <p>The process is bounded in both time and output. This runs while the task graph is being
 * computed, against a binary the plugin does not control: one that waits on stdin would otherwise
 * block the build indefinitely, and one that writes without end would exhaust the daemon. Either
 * way the candidate is simply unreadable, which callers already treat as unusable.
 */
public abstract class ElideVersionProbeSource
        implements ValueSource<String, ElideVersionProbeSource.Parameters> {
    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT_BYTES = 8 * 1024;

    public interface Parameters extends ValueSourceParameters {
        Property<String> getExecutablePath();
    }

    @Override
    public String obtain() {
        ProcessBuilder builder = new ProcessBuilder(getParameters().getExecutablePath().get(), "--version");
        builder.redirectErrorStream(false);
        Process process = null;
        try {
            process = builder.start();
            process.getOutputStream().close();
            // Drained on another thread so the deadline below governs. Reading inline would block
            // indefinitely on a child that holds its output open without ever writing, which is
            // precisely the case the timeout exists for.
            Process reading = process;
            CompletableFuture<String> output =
                    CompletableFuture.supplyAsync(() -> readBounded(reading.getInputStream()));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }
            // Output is trusted only on a clean exit; a foreign binary that rejects --version and
            // prints a usage banner must not have a number from it read as a version.
            if (process.exitValue() != 0) {
                return null;
            }
            String text = output.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return text.isBlank() ? null : text;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            return null;
        } finally {
            if (process != null) {
                terminate(process);
            }
        }
    }

    /**
     * Kills the candidate, and any of its children still running under it. The tree is captured
     * before the parent is destroyed, because descendants are reparented the moment it dies and can
     * no longer be reached from it.
     *
     * <p>This covers the case that matters here: a probe that timed out, where the candidate is
     * still alive and holding its tree. It cannot cover a candidate that deliberately detaches a
     * process and then exits normally -- by the time this runs there is nothing left to walk, and
     * reliably collecting such a process would need the probe to run in a killable process group,
     * which the JDK does not expose portably. A candidate that backgrounds work is doing so on its
     * own account; the probe only declines to wait for it.
     */
    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    private static String readBounded(InputStream input) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            int read;
            while (captured.size() < MAX_OUTPUT_BYTES && (read = input.read(buffer)) != -1) {
                captured.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - captured.size()));
            }
        } catch (Exception exception) {
            // A partial read is still enough to recognise a version, or to fail to.
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
