package dev.elide.gradle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class ElideCompilerServiceTest {

    private ElideCompilerService createService() {
        Project project = ProjectBuilder.builder().build();
        Provider<ElideCompilerService> provider =
                project.getGradle()
                        .getSharedServices()
                        .registerIfAbsent(
                                "testCompiler", ElideCompilerService.class, spec -> {});
        return provider.get();
    }

    private static String[] endpoint(ElideCompilerService service) {
        return service.endpoint().split(":", 2);
    }

    private static String sendRequest(
            String port, String token, String executable, String cwd, String preparedId, int count)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(port)), 5_000);
            socket.setSoTimeout(10_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.writeUTF(token);
            out.writeUTF(executable);
            out.writeUTF(cwd);
            out.writeUTF(preparedId);
            out.writeInt(count);
            out.flush();
            int exitCode = in.readInt();
            int outputLen = in.readInt();
            byte[] output = in.readNBytes(outputLen);
            return exitCode + ":" + new String(output, StandardCharsets.UTF_8);
        }
    }

    @Test
    void workerFailureReturnsErrorResponse() throws Exception {
        ElideCompilerService service = createService();
        String[] parts = endpoint(service);
        String executable = "/nonexistent/elide-" + System.nanoTime();
        String preparedId = service.registerCompiler(executable, List.of());
        try {
            String result =
                    sendRequest(
                            parts[0],
                            parts[1],
                            executable,
                            Files.createTempDirectory("cwd").toString(),
                            preparedId,
                            0);
            assertTrue(result.startsWith("1:"), result);
            assertTrue(result.contains("Elide compiler worker failed"), result);
        } finally {
            service.close();
        }
    }

    @Test
    void unknownPreparedIdReturnsErrorResponse() throws Exception {
        ElideCompilerService service = createService();
        String[] parts = endpoint(service);
        try {
            String result =
                    sendRequest(
                            parts[0],
                            parts[1],
                            "/some/executable",
                            Files.createTempDirectory("cwd").toString(),
                            "not-a-real-prepared-id",
                            0);
            assertTrue(result.startsWith("1:"), result);
            assertTrue(result.contains("Elide compiler worker failed"), result);
            assertTrue(result.contains("Unknown compiler request"), result);
        } finally {
            service.close();
        }
    }

    @Test
    void executableMismatchReturnsErrorResponse() throws Exception {
        ElideCompilerService service = createService();
        String[] parts = endpoint(service);
        String registered = "/registered/elide-" + System.nanoTime();
        String presented = "/presented/elide-" + System.nanoTime();
        String preparedId = service.registerCompiler(registered, List.of());
        try {
            String result =
                    sendRequest(
                            parts[0],
                            parts[1],
                            presented,
                            Files.createTempDirectory("cwd").toString(),
                            preparedId,
                            0);
            assertTrue(result.startsWith("1:"), result);
            assertTrue(result.contains("Elide compiler worker failed"), result);
            assertTrue(result.contains("Unknown compiler request"), result);
        } finally {
            service.close();
        }
    }

    @Test
    void badTokenIsSilentlyDropped() throws Exception {
        ElideCompilerService service = createService();
        String[] parts = endpoint(service);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(parts[0])), 5_000);
            socket.setSoTimeout(10_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.writeUTF("definitely-not-the-token");
            out.writeUTF("/any/executable");
            out.writeUTF(Files.createTempDirectory("cwd").toString());
            out.writeUTF("any-prepared-id");
            out.writeInt(0);
            out.flush();
            assertThrows(EOFException.class, in::readInt);
        } finally {
            service.close();
        }
    }
}
