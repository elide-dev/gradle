package dev.elide.gradle;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** One serial Elide compiler worker, owned and closed by the build service. */
final class ElideWorkerProcess implements AutoCloseable {
    private final Process process;
    private final ExecutorService reader =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "elide-worker-response");
                        thread.setDaemon(true);
                        return thread;
                    });
    private int sequence;
    private final ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();

    ElideWorkerProcess(String executable, Path directory) throws IOException {
        this(List.of(executable, "javac", "--persistent_worker", "--classpath-cache"), directory);
    }

    ElideWorkerProcess(List<String> command, Path directory) throws IOException {
        process = new ProcessBuilder(command).directory(directory.toFile()).start();
        org.gradle.api.logging.Logging.getLogger(ElideWorkerProcess.class)
                .info("Started Elide compiler worker (pid {})", process.pid());
        Thread errors =
                new Thread(
                        () -> {
                            try (InputStream input = process.getErrorStream()) {
                                byte[] bytes = new byte[4096];
                                int count;
                                while ((count = input.read(bytes)) != -1) {
                                    synchronized (errorOutput) {
                                        errorOutput.write(
                                                bytes,
                                                0,
                                                Math.min(count, 65536 - errorOutput.size()));
                                    }
                                }
                            } catch (IOException ignored) {
                            }
                        },
                        "elide-worker-stderr");
        errors.setDaemon(true);
        errors.start();
    }

    synchronized ElideWorkerProtocol.Response compile(
            List<String> arguments, Map<String, byte[]> inputs) throws IOException {
        int id = ++sequence;
        Future<ElideWorkerProtocol.Response> response =
                reader.submit(
                        () -> {
                            ElideWorkerProtocol.writeRequest(
                                    process.getOutputStream(), arguments, inputs, id);
                            return ElideWorkerProtocol.readResponse(process.getInputStream());
                        });
        try {
            var result = response.get(5, TimeUnit.MINUTES);
            if (result.requestId() != id) {
                close();
                throw new IOException("Elide worker returned an unexpected request ID");
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Elide compilation interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            close();
            Throwable cause =
                    exception instanceof ExecutionException ? exception.getCause() : exception;
            String diagnostics;
            synchronized (errorOutput) {
                diagnostics = errorOutput.toString(java.nio.charset.StandardCharsets.UTF_8);
            }
            throw new IOException(
                    "Elide compiler worker failed: "
                            + cause.getClass().getSimpleName()
                            + ": "
                            + cause.getMessage()
                            + "\n"
                            + diagnostics,
                    exception);
        }
    }

    boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        reader.shutdownNow();
    }
}
