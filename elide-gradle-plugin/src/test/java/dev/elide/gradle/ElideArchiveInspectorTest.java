package dev.elide.gradle;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hostile-archive coverage for both asset formats. The ZIP cases live here rather than in the
 * functional suite because that suite always runs against the host platform's asset format, so the
 * Windows ZIP path is unreachable end-to-end on Linux.
 */
class ElideArchiveInspectorTest {
    private static final ElidePlatform LINUX = ElidePlatform.detect("Linux", "amd64");
    private static final ElidePlatform WINDOWS = ElidePlatform.detect("Windows 11", "amd64");
    private static final byte[] CONTENT = "runtime".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void acceptsACleanTarArchive() throws IOException {
        Path archive = tarArchive("clean.tgz", tar -> {
            tar.putArchiveEntry(regularTarEntry("bin/elide"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
        });

        assertDoesNotThrow(() -> ElideArchiveInspector.requireSafeArchive(archive, LINUX));
    }

    @Test
    void acceptsTheDotSlashPrefixedEntryNamesTheRealReleaseArchiveUses() throws IOException {
        // The published elide.linux-amd64.tgz stores entries as ./bin/elide, ./lib/... — a leading
        // "." segment must not be mistaken for traversal.
        Path archive = tarArchive("dot-prefixed.tgz", tar -> {
            tar.putArchiveEntry(regularTarEntry("./bin/elide"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
            tar.putArchiveEntry(regularTarEntry("./lib/modules"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
        });

        assertDoesNotThrow(() -> ElideArchiveInspector.requireSafeArchive(archive, LINUX));
    }

    @Test
    void rejectsATarEntryThatTraversesOutOfTheStagingDirectory() throws IOException {
        Path archive = tarArchive("traversal.tgz", tar -> {
            tar.putArchiveEntry(regularTarEntry("bin/elide"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
            tar.putArchiveEntry(regularTarEntry("../escaped-outside-staging.txt"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
        });

        GradleException failure = assertThrows(GradleException.class,
                () -> ElideArchiveInspector.requireSafeArchive(archive, LINUX));
        assertTrue(failure.getMessage().contains("Refusing Elide archive entry outside runtime staging directory"),
                failure.getMessage());
    }

    @Test
    void rejectsAnAbsoluteTarEntry() throws IOException {
        Path archive = tarArchive("absolute.tgz", tar -> {
            // The single-argument TarArchiveEntry constructor strips leading slashes, so an
            // absolute entry has to be written with preserveAbsolutePath. Reading preserves the
            // name as stored, which is how a hostile archive delivers one.
            TarArchiveEntry escape = new TarArchiveEntry("/tmp/absolute-escape.txt", true);
            escape.setSize(CONTENT.length);
            tar.putArchiveEntry(escape);
            tar.write(CONTENT);
            tar.closeArchiveEntry();
        });

        GradleException failure = assertThrows(GradleException.class,
                () -> ElideArchiveInspector.requireSafeArchive(archive, LINUX));
        assertTrue(failure.getMessage().contains("Refusing Elide archive entry outside runtime staging directory"),
                failure.getMessage());
    }

    @Test
    void rejectsASymbolicLinkTarEntry() throws IOException {
        Path archive = tarArchive("symlink.tgz", tar -> {
            tar.putArchiveEntry(regularTarEntry("bin/elide"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
            TarArchiveEntry link = new TarArchiveEntry("lib/evil-link", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("/etc/passwd");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        });

        GradleException failure = assertThrows(GradleException.class,
                () -> ElideArchiveInspector.requireSafeArchive(archive, LINUX));
        assertTrue(failure.getMessage().contains("Refusing symbolic link in Elide runtime archive"),
                failure.getMessage());
    }

    @Test
    void rejectsAHardLinkTarEntry() throws IOException {
        Path archive = tarArchive("hardlink.tgz", tar -> {
            tar.putArchiveEntry(regularTarEntry("bin/elide"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
            TarArchiveEntry link = new TarArchiveEntry("lib/evil-hard-link", TarArchiveEntry.LF_LINK);
            link.setLinkName("bin/elide");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        });

        GradleException failure = assertThrows(GradleException.class,
                () -> ElideArchiveInspector.requireSafeArchive(archive, LINUX));
        assertTrue(failure.getMessage().contains("Refusing symbolic link in Elide runtime archive"),
                failure.getMessage());
    }

    @Test
    void rejectsATraversingEntryCarriedInAGnuLongName() throws IOException {
        String longEscape = "../" + "escaped-directory-with-a-very-long-name/".repeat(5) + "payload.txt";
        Path archive = tarArchive("longname.tgz", tar -> {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            tar.putArchiveEntry(regularTarEntry("bin/elide"));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
            tar.putArchiveEntry(regularTarEntry(longEscape));
            tar.write(CONTENT);
            tar.closeArchiveEntry();
        });

        GradleException failure = assertThrows(GradleException.class,
                () -> ElideArchiveInspector.requireSafeArchive(archive, LINUX));
        assertTrue(failure.getMessage().contains("Refusing Elide archive entry outside runtime staging directory"),
                failure.getMessage());
    }

    @Test
    void acceptsACleanZipArchive() throws IOException {
        Path archive = zipArchive("clean.zip", zip -> {
            zip.putArchiveEntry(new ZipArchiveEntry("bin/elide.exe"));
            zip.write(CONTENT);
            zip.closeArchiveEntry();
        });

        assertDoesNotThrow(() -> ElideArchiveInspector.requireSafeArchive(archive, WINDOWS));
    }

    @Test
    void rejectsAZipEntryThatTraversesOutOfTheStagingDirectory() throws IOException {
        Path archive = zipArchive("traversal.zip", zip -> {
            zip.putArchiveEntry(new ZipArchiveEntry("bin/elide.exe"));
            zip.write(CONTENT);
            zip.closeArchiveEntry();
            zip.putArchiveEntry(new ZipArchiveEntry("../escaped-outside-staging.txt"));
            zip.write(CONTENT);
            zip.closeArchiveEntry();
        });

        GradleException failure = assertThrows(GradleException.class,
                () -> ElideArchiveInspector.requireSafeArchive(archive, WINDOWS));
        assertTrue(failure.getMessage().contains("Refusing Elide archive entry outside runtime staging directory"),
                failure.getMessage());
    }

    @Test
    void rejectsASymbolicLinkZipEntry() throws IOException {
        Path archive = zipArchive("symlink.zip", zip -> {
            zip.putArchiveEntry(new ZipArchiveEntry("bin/elide.exe"));
            zip.write(CONTENT);
            zip.closeArchiveEntry();
            ZipArchiveEntry link = new ZipArchiveEntry("lib/evil-link");
            // 0120000 is S_IFLNK; commons-compress records it in the external attributes.
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        });

        GradleException failure = assertThrows(GradleException.class,
                () -> ElideArchiveInspector.requireSafeArchive(archive, WINDOWS));
        assertTrue(failure.getMessage().contains("Refusing symbolic link in Elide runtime archive"),
                failure.getMessage());
    }

    private static TarArchiveEntry regularTarEntry(String name) {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(CONTENT.length);
        entry.setMode(0755);
        return entry;
    }

    private Path tarArchive(String name, TarContent content) throws IOException {
        Path archive = tempDir.resolve(name);
        try (OutputStream fileOutput = Files.newOutputStream(archive);
             GZIPOutputStream gzipOutput = new GZIPOutputStream(fileOutput);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzipOutput)) {
            content.write(tar);
            tar.finish();
        }
        return archive;
    }

    private Path zipArchive(String name, ZipContent content) throws IOException {
        Path archive = tempDir.resolve(name);
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive.toFile())) {
            content.write(zip);
            zip.finish();
        }
        return archive;
    }

    @FunctionalInterface
    private interface TarContent {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    @FunctionalInterface
    private interface ZipContent {
        void write(ZipArchiveOutputStream zip) throws IOException;
    }
}
