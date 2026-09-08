package dev.elide.gradle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElideDownloaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesSha256ChecksumFile() {
        assertEquals(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ElideDownloader.parseSha256(
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  elide.tgz\n"));
    }

    @Test
    void rejectsMalformedChecksumFile() {
        assertThrows(IllegalArgumentException.class, () -> ElideDownloader.parseSha256("not-a-checksum"));
    }

    @Test
    void writesOnlyAnArchiveThatMatchesTheDownloadedChecksum() throws Exception {
        byte[] archive = "fixture archive".getBytes(StandardCharsets.UTF_8);
        try (FixtureServer server = FixtureServer.start(archive, sha256(archive))) {
            Path target = temporaryDirectory.resolve("elide.tgz");

            new ElideDownloader().downloadVerified(server.release(), target);

            assertEquals("fixture archive", Files.readString(target));
        }
    }

    @Test
    void rejectsAChecksumMismatchWithoutReplacingTheTargetOrLeavingPartialDownloads() throws Exception {
        try (FixtureServer server = FixtureServer.start("fixture archive".getBytes(StandardCharsets.UTF_8),
                "0000000000000000000000000000000000000000000000000000000000000000")) {
            Path target = temporaryDirectory.resolve("elide.tgz");
            Files.writeString(target, "existing archive");

            assertThrows(IOException.class, () -> new ElideDownloader().downloadVerified(server.release(), target));

            assertEquals("existing archive", Files.readString(target));
            try (Stream<Path> files = Files.list(temporaryDirectory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static final class FixtureServer implements AutoCloseable {
        private final HttpServer server;

        private FixtureServer(HttpServer server) {
            this.server = server;
        }

        static FixtureServer start(byte[] archive, String checksum) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/releases/1.5.1/elide.linux-amd64.tgz", exchange -> {
                exchange.sendResponseHeaders(200, archive.length);
                exchange.getResponseBody().write(archive);
                exchange.close();
            });
            byte[] checksumBytes = (checksum + "  elide.linux-amd64.tgz\n").getBytes(StandardCharsets.UTF_8);
            server.createContext("/releases/1.5.1/elide.linux-amd64.tgz.sha256", exchange -> {
                exchange.sendResponseHeaders(200, checksumBytes.length);
                exchange.getResponseBody().write(checksumBytes);
                exchange.close();
            });
            server.start();
            return new FixtureServer(server);
        }

        ElideRelease release() {
            return new ElideRelease(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases"),
                    "1.5.1",
                    ElidePlatform.detect("Linux", "amd64"));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
