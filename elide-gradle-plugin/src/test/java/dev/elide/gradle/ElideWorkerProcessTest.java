package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class ElideWorkerProcessTest {
    @TempDir Path directory;

    @Test
    void preservesWorkerAcrossFailedRequestsAndClosesIt() throws Exception {
        ElideWorkerProcess worker =
                new ElideWorkerProcess(
                        List.of(
                                javaExecutable(),
                                "-cp",
                                classesOf(Fixture.class),
                                Fixture.class.getName()),
                        directory);
        try {
            assertEquals(0, worker.compile(List.of("first"), Map.of()).exitCode());
            assertEquals(1, worker.compile(List.of("invalid"), Map.of()).exitCode());
            assertEquals(0, worker.compile(List.of("third"), Map.of()).exitCode());
            assertTrue(worker.isAlive());
        } finally {
            worker.close();
        }
        assertFalse(worker.isAlive());
    }

    @Test
    void scopesStderrToFailingRequestOnly() throws Exception {
        ElideWorkerProcess worker =
                new ElideWorkerProcess(
                        List.of(
                                javaExecutable(),
                                "-cp",
                                classesOf(StaleStderrFixture.class),
                                StaleStderrFixture.class.getName()),
                        directory);
        try {
            assertEquals(0, worker.compile(List.of("first"), Map.of()).exitCode());
            assertEquals(1, worker.compile(List.of("second"), Map.of()).exitCode());
            var thrown =
                    assertThrows(
                            java.io.IOException.class,
                            () -> worker.compile(List.of("third"), Map.of()));
            assertTrue(thrown.getMessage().contains("STALE_STDERR_REQUEST_3"));
            assertFalse(thrown.getMessage().contains("STALE_STDERR_REQUEST_1"));
            assertFalse(thrown.getMessage().contains("STALE_STDERR_REQUEST_2"));
            assertFalse(worker.isAlive());
        } finally {
            worker.close();
        }
    }

    @Test
    void doesNotSilentlyDropFailingRequestStderr() throws Exception {
        ElideWorkerProcess worker =
                new ElideWorkerProcess(
                        List.of(
                                javaExecutable(),
                                "-cp",
                                classesOf(DropFixture.class),
                                DropFixture.class.getName()),
                        directory);
        try {
            assertEquals(0, worker.compile(List.of("first"), Map.of()).exitCode());
            assertEquals(1, worker.compile(List.of("second"), Map.of()).exitCode());
            var thrown =
                    assertThrows(
                            java.io.IOException.class,
                            () -> worker.compile(List.of("third"), Map.of()));
            assertTrue(thrown.getMessage().contains("UNIQUE_MARKER_FROM_REQUEST_3"));
            assertFalse(worker.isAlive());
        } finally {
            worker.close();
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    private static String classesOf(Class<?> type) throws java.net.URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
    }

    /** A separate JVM implements just enough of the server protocol to test client lifecycle. */
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            for (int id = 1; ; id++) {
                int length = System.in.read();
                if (length == -1) return;
                byte[] request = System.in.readNBytes(length);
                if (request.length != length || request[length - 1] != id) System.exit(9);
                System.out.write(new byte[] {4, 8, (byte) (id == 2 ? 1 : 0), 24, (byte) id});
                System.out.flush();
            }
        }
    }

    /**
     * Writes a distinct stderr marker per request, then hard-crashes (exits without a response) on
     * request 3 so the worker is torn down via the {@code ExecutionException} path.
     */
    public static class StaleStderrFixture {
        public static void main(String[] args) throws Exception {
            for (int id = 1; ; id++) {
                int length = System.in.read();
                if (length == -1) return;
                byte[] request = System.in.readNBytes(length);
                if (request.length != length || request[length - 1] != id) System.exit(9);
                System.err.println("STALE_STDERR_REQUEST_" + id);
                System.err.flush();
                if (id == 3) System.exit(1);
                System.out.write(new byte[] {4, 8, (byte) (id == 2 ? 1 : 0), 24, (byte) id});
                System.out.flush();
            }
        }
    }

    /**
     * Floods stderr on requests 1 and 2 with ~34 KiB each, then prints a unique marker before
     * hard-crashing on request 3. Under the old cumulative cap the marker was silently dropped once
     * the lifetime buffer exceeded 64 KiB.
     */
    public static class DropFixture {
        public static void main(String[] args) throws Exception {
            for (int id = 1; ; id++) {
                int length = System.in.read();
                if (length == -1) return;
                byte[] request = System.in.readNBytes(length);
                if (request.length != length || request[length - 1] != id) System.exit(9);
                if (id <= 2) {
                    byte[] filler = new byte[34000];
                    java.util.Arrays.fill(filler, (byte) 'X');
                    System.err.write(filler);
                    System.err.write(System.lineSeparator().getBytes());
                    System.err.flush();
                }
                if (id == 3) {
                    System.err.println("UNIQUE_MARKER_FROM_REQUEST_3");
                    System.err.flush();
                    System.exit(1);
                }
                System.out.write(new byte[] {4, 8, (byte) (id == 2 ? 1 : 0), 24, (byte) id});
                System.out.flush();
            }
        }
    }
}
