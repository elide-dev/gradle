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
        String java =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        String classes =
                Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .toString();
        ElideWorkerProcess worker =
                new ElideWorkerProcess(
                        List.of(java, "-cp", classes, Fixture.class.getName()), directory);
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
}
