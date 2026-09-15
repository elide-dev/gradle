package dev.elide.gradle;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.gradle.api.GradleException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.GZIPInputStream;

/**
 * Rejects unsafe archive entries before extraction.
 *
 * <p>Gradle's own {@code tarTree}/{@code zipTree} cannot be relied on for this: it refuses a
 * traversing archive with a generic "the tar might be corrupted" message before any per-entry hook
 * runs, and it materializes a symbolic-link entry as an ordinary empty file, so inspecting the
 * extracted tree afterwards finds nothing to reject. Entry metadata is therefore read directly from
 * the archive first.
 *
 * <p>Only the commons-compress API present in 1.21 is used, because Gradle bundles its own copy
 * (1.21 on the Gradle 7.6.4 consumer floor) which may take precedence at runtime.
 */
final class ElideArchiveInspector {
    private static final long UNIX_FILE_TYPE_MASK = 0xF000L;
    private static final long UNIX_SYMLINK = 0xA000L;

    private ElideArchiveInspector() {
    }

    /**
     * Verifies every entry of a downloaded, checksum-verified archive.
     *
     * @throws GradleException if any entry escapes the staging directory or is a link
     */
    static void requireSafeArchive(Path archive, ElidePlatform platform) {
        try {
            if (platform.archiveExtension().equals("zip")) {
                inspectZip(archive);
            } else {
                inspectTar(archive);
            }
        } catch (IOException exception) {
            throw new GradleException("Unable to validate extracted Elide runtime", exception);
        }
    }

    private static void inspectTar(Path archive) throws IOException {
        try (InputStream fileInput = Files.newInputStream(archive);
             InputStream gzipInput = new GZIPInputStream(new BufferedInputStream(fileInput));
             TarArchiveInputStream tarInput = new TarArchiveInputStream(gzipInput)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                // Hard links are rejected alongside symbolic links: both name a target outside the
                // entry itself, and neither belongs in a runtime distribution.
                if (entry.isSymbolicLink() || entry.isLink()) {
                    throw new GradleException(
                            "Refusing symbolic link in Elide runtime archive: " + entry.getName());
                }
                requireContainedName(entry.getName());
            }
        }
    }

    private static void inspectZip(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (isSymbolicLink(entry)) {
                    throw new GradleException(
                            "Refusing symbolic link in Elide runtime archive: " + entry.getName());
                }
                requireContainedName(entry.getName());
            }
        }
    }

    /**
     * Tests the {@code S_IFLNK} bits in the raw external attributes rather than using
     * {@link ZipArchiveEntry#isUnixSymlink()}, which returns {@code false} unless the entry also
     * declares the Unix platform. A hostile archive can set the link mode while declaring FAT, and
     * this is the gate that is supposed to stop it before anything reaches the filesystem.
     */
    private static boolean isSymbolicLink(ZipArchiveEntry entry) {
        return ((entry.getExternalAttributes() >> 16) & UNIX_FILE_TYPE_MASK) == UNIX_SYMLINK;
    }

    /**
     * Rejects absolute paths, drive-qualified paths, and any {@code ..} segment. The check is on the
     * declared entry name rather than a resolved path, so it does not depend on the host filesystem.
     */
    private static void requireContainedName(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            throw new GradleException("Refusing Elide archive entry outside runtime staging directory");
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new GradleException("Refusing Elide archive entry outside runtime staging directory");
            }
        }
    }
}
