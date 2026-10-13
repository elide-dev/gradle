package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class ElideWorkerProcessTest {
    @TempDir Path directory;

    @Test
    void preservesWorkerAcrossFailedRequestsAndClosesIt() throws Exception {
        ElideWorkerProcess worker = newWorker(Fixture.class.getName(), false);
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
    void suppressesAmbientClasspathWhenRequested() throws Exception {
        // The test task seeds a known ambient CLASSPATH so suppression is observable as the
        // difference between inheritance and removal rather than a null that exists regardless.
        String ambient = System.getenv("CLASSPATH");
        assertNotNull(ambient, "The 'test' task must seed CLASSPATH for the suppression assertion");
        Path marker = directory.resolve("classpath-suppressed.txt");
        ElideWorkerProcess worker = newClasspathReportingWorker(marker, true);
        try {
            assertEquals(0, worker.compile(List.of("first"), Map.of()).exitCode());
        } finally {
            worker.close();
        }
        assertNull(
                readClasspath(marker),
                "Gradle dependency mode must not leak the ambient CLASSPATH to the worker");
    }

    @Test
    void preservesAmbientClasspathWhenSuppressionIsDisabled() throws Exception {
        String ambient = System.getenv("CLASSPATH");
        assertNotNull(ambient, "The 'test' task must seed CLASSPATH for the preserve assertion");
        Path marker = directory.resolve("classpath-preserved.txt");
        ElideWorkerProcess worker = newClasspathReportingWorker(marker, false);
        try {
            assertEquals(0, worker.compile(List.of("first"), Map.of()).exitCode());
        } finally {
            worker.close();
        }
        assertEquals(ambient, readClasspath(marker), "Non-Gradle modes must inherit CLASSPATH");
    }

    private ElideWorkerProcess newWorker(String mainClass, boolean suppressAmbientClasspath)
            throws Exception {
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
        return new ElideWorkerProcess(
                List.of(java, "-cp", classes, mainClass), directory, suppressAmbientClasspath);
    }

    private ElideWorkerProcess newClasspathReportingWorker(Path marker, boolean suppressAmbientClasspath)
            throws Exception {
        String java =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        String classes =
                Path.of(ClasspathFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .toString();
        return new ElideWorkerProcess(
                List.of(java, "-cp", classes, ClasspathFixture.class.getName(), marker.toString()),
                directory,
                suppressAmbientClasspath);
    }

    private static String readClasspath(Path marker) throws Exception {
        String value = Files.readString(marker).trim();
        return value.equals("<unset>") ? null : value;
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
     * Speaks the Bazel worker protocol and records the inherited {@code CLASSPATH} env var to a
     * marker file so a test can observe whether the worker process suppressed it.
     */
    public static class ClasspathFixture {
        public static void main(String[] args) throws Exception {
            java.nio.file.Path marker = java.nio.file.Path.of(args[0]);
            String classpath = System.getenv("CLASSPATH");
            java.nio.file.Files.createDirectories(marker.getParent());
            java.nio.file.Files.writeString(
                    marker, classpath == null ? "<unset>" : classpath);
            for (int id = 1; ; id++) {
                int length = System.in.read();
                if (length == -1) return;
                byte[] request = System.in.readNBytes(length);
                if (request.length != length || request[request.length - 1] != id) System.exit(9);
                System.out.write(new byte[] {4, 8, 0, 24, (byte) id});
                System.out.flush();
            }
        }
    }
}
