package dev.elide.gradle;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void completesAVerifiedDownloadEvenWhenTheChecksumTempCannotBeDeleted() throws Exception {
        byte[] archive = "fixture archive".getBytes(StandardCharsets.UTF_8);
        try (FixtureServer server = FixtureServer.start(archive, sha256(archive))) {
            Path target = temporaryDirectory.resolve("elide.tgz");
            AtomicInteger checksumDeleteAttempts = new AtomicInteger();
            // Simulate a transient Windows AV lock on the checksum temp: every deletion throws,
            // but the archive temp (already moved away) deletes normally.
            ElideDownloader downloader = new ElideDownloader(temporary -> {
                if (temporary.getFileName().toString().endsWith(".sha256.tmp")) {
                    checksumDeleteAttempts.incrementAndGet();
                    throw new AccessDeniedException(temporary.toString());
                }
                Files.deleteIfExists(temporary);
            });

            // A verified download must not be negated by a cleanup failure on the success path.
            downloader.downloadVerified(server.release(), target);

            assertEquals("fixture archive", Files.readString(target));
            // The success-path cleanup retried the locked checksum temp three times before giving up.
            assertEquals(3, checksumDeleteAttempts.get());
        }
    }

    @Test
    void deletesTemporaryFilesAfterAVerifiedDownload() throws Exception {
        byte[] archive = "fixture archive".getBytes(StandardCharsets.UTF_8);
        try (FixtureServer server = FixtureServer.start(archive, sha256(archive))) {
            Path target = temporaryDirectory.resolve("elide.tgz");

            new ElideDownloader().downloadVerified(server.release(), target);

            assertEquals("fixture archive", Files.readString(target));
            try (Stream<Path> files = Files.list(temporaryDirectory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
            }
        }
    }

    @Test
    void doesNotThrowWhenTheArchiveTempCleanupFailsToo() throws Exception {
        byte[] archive = "fixture archive".getBytes(StandardCharsets.UTF_8);
        try (FixtureServer server = FixtureServer.start(archive, sha256(archive))) {
            Path target = temporaryDirectory.resolve("elide.tgz");
            AtomicInteger archiveDeleteAttempts = new AtomicInteger();
            ElideDownloader downloader = new ElideDownloader(temporary -> {
                if (temporary.getFileName().toString().endsWith(".archive.tmp")) {
                    archiveDeleteAttempts.incrementAndGet();
                    throw new AccessDeniedException(temporary.toString());
                }
                Files.deleteIfExists(temporary);
            });

            downloader.downloadVerified(server.release(), target);

            // The archive temp is moved away by Files.move before cleanup, so its delete is a no-op;
            // even a throwing deleter must not surface as a primary failure of a verified download.
            assertEquals("fixture archive", Files.readString(target));
            assertTrue(archiveDeleteAttempts.get() >= 1);
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
            server.createContext("/releases/v1.5.1/elide.linux-amd64.tgz", exchange -> {
                exchange.sendResponseHeaders(200, archive.length);
                exchange.getResponseBody().write(archive);
                exchange.close();
            });
            byte[] checksumBytes = (checksum + "  elide.linux-amd64.tgz\n").getBytes(StandardCharsets.UTF_8);
            server.createContext("/releases/v1.5.1/elide.linux-amd64.tgz.sha256", exchange -> {
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
