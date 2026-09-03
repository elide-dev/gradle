package dev.elide.gradle;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads Elide release archives only after their published SHA-256 is verified. */
public final class ElideDownloader {
    private static final Pattern SHA256_LINE = Pattern.compile("^([0-9a-fA-F]{64})(?:\\s+\\*?\\S+)?$");

    private final HttpClient client;

    public ElideDownloader() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ElideDownloader(HttpClient client) {
        this.client = client;
    }

    public void downloadVerified(ElideRelease release, Path archiveTarget) throws IOException, InterruptedException {
        Path absoluteTarget = archiveTarget.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        Files.createDirectories(parent);
        String prefix = absoluteTarget.getFileName().toString() + ".";
        Path checksumTemporary = null;
        Path archiveTemporary = null;
        Throwable failure = null;
        try {
            checksumTemporary = Files.createTempFile(parent, prefix, ".sha256.tmp");
            archiveTemporary = Files.createTempFile(parent, prefix, ".archive.tmp");
            download(release.checksumUri(), checksumTemporary);
            String expected = parseSha256(Files.readString(checksumTemporary, StandardCharsets.UTF_8));
            download(release.archiveUri(), archiveTemporary);
            String actual = sha256(archiveTemporary);
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("SHA-256 mismatch for Elide archive " + release.archiveUri());
            }
            Files.move(archiveTemporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException exception) {
            failure = exception;
            throw exception;
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            IOException cleanupFailure = deleteTemporaryFiles(checksumTemporary, archiveTemporary);
            if (cleanupFailure != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static IOException deleteTemporaryFiles(Path checksumTemporary, Path archiveTemporary) {
        IOException failure = deleteTemporaryFile(checksumTemporary, null);
        return deleteTemporaryFile(archiveTemporary, failure);
    }

    private static IOException deleteTemporaryFile(Path temporary, IOException failure) {
        if (temporary == null) {
            return failure;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }

    public static String parseSha256(String checksumFile) {
        String checksum = null;
        for (String line : checksumFile.split("\\R")) {
            String candidate = line.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            Matcher matcher = SHA256_LINE.matcher(candidate);
            if (!matcher.matches() || checksum != null) {
                throw new IllegalArgumentException("Expected one SHA-256 checksum");
            }
            checksum = matcher.group(1).toLowerCase(Locale.ROOT);
        }
        if (checksum == null) {
            throw new IllegalArgumentException("Expected one SHA-256 checksum");
        }
        return checksum;
    }

    private void download(URI uri, Path destination) throws IOException, InterruptedException {
        HttpResponse<Path> response = client.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Unable to download " + uri + ": HTTP " + response.statusCode());
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }
}
