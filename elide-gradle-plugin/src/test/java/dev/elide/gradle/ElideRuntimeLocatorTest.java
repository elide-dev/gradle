package dev.elide.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

import static dev.elide.gradle.ElideRuntimeMode.AUTO;
import static dev.elide.gradle.ElideRuntimeMode.MANAGED;
import static dev.elide.gradle.ElideRuntimeMode.PATH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElideRuntimeLocatorTest {
    private static final ElidePlatform LINUX = ElidePlatform.detect("Linux", "amd64");
    private static final ElidePlatform WINDOWS = ElidePlatform.detect("Windows 11", "amd64");

    @TempDir
    Path tempDir;

    @Test
    void explicitRuntimeTakesPrecedenceInAutoMode() throws IOException {
        var explicit = executable(tempDir.resolve("explicit"));
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.of(explicit), List.of(pathBin.getParent()), managed);

        assertEquals(ElideRuntimeSource.EXPLICIT, selection.source());
        assertEquals(explicit, selection.executable());
    }

    @Test
    void pathRuntimeIsUsedWhenExplicitRuntimeIsAbsent() throws IOException {
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.empty(), List.of(pathBin.getParent()), managed);

        assertEquals(ElideRuntimeSource.PATH, selection.source());
        assertEquals(pathBin, selection.executable());
    }

    @Test
    void autoModeFallsBackToManagedRuntime() throws IOException {
        var emptyBin = tempDir.resolve("empty");
        Files.createDirectories(emptyBin);
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.empty(), List.of(emptyBin), managed);

        assertEquals(ElideRuntimeSource.MANAGED, selection.source());
        assertEquals(managed, selection.executable());
    }

    @Test
    void managedModeIgnoresExplicitAndPathRuntimes() throws IOException {
        var explicit = executable(tempDir.resolve("explicit"));
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(MANAGED, Optional.of(explicit), List.of(pathBin.getParent()), managed);

        assertEquals(ElideRuntimeSource.MANAGED, selection.source());
        assertEquals(managed, selection.executable());
    }

    @Test
    void pathModeFailsWhenNoPathRuntimeIsUsable() throws IOException {
        var emptyBin = tempDir.resolve("empty");
        Files.createDirectories(emptyBin);
        var managed = tempDir.resolve("managed").resolve("elide");

        assertThrows(IllegalStateException.class,
                () -> locate(PATH, Optional.empty(), List.of(emptyBin), managed));
    }

    @Test
    void pathLookupPreservesDirectoryOrder() throws IOException {
        var first = executable(tempDir.resolve("first").resolve("elide"));
        var second = executable(tempDir.resolve("second").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.empty(), List.of(first.getParent(), second.getParent()), managed);

        assertEquals(first, selection.executable());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unixCandidatesMustBeExecutableRegularFiles() throws IOException {
        var nonExecutable = tempDir.resolve("path").resolve("elide");
        Files.createDirectories(nonExecutable.getParent());
        Files.writeString(nonExecutable, "runtime");
        Files.setPosixFilePermissions(nonExecutable, PosixFilePermissions.fromString("rw-r--r--"));
        var managed = tempDir.resolve("managed").resolve("elide");

        assertThrows(IllegalStateException.class,
                () -> locate(PATH, Optional.empty(), List.of(nonExecutable.getParent()), managed));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void windowsCandidatesAcceptRegularExeFilesWithoutUnixExecutablePermission() throws IOException {
        Path executable = tempDir.resolve("path").resolve("elide.exe");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "runtime");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rw-r--r--"));
        assertFalse(Files.isExecutable(executable));
        Path managed = tempDir.resolve("managed").resolve("elide.exe");

        ElideRuntimeSelection selection = ElideRuntimeLocator.locate(
                PATH, Optional.empty(), List.of(executable.getParent()), managed, WINDOWS);

        assertEquals(ElideRuntimeSource.PATH, selection.source());
        assertEquals(executable, selection.executable());
    }

    private ElideRuntimeSelection locate(ElideRuntimeMode mode, Optional<Path> explicit,
                                         List<Path> pathDirectories, Path managed) {
        return ElideRuntimeLocator.locate(mode, explicit, pathDirectories, managed, LINUX);
    }

    private static Path executable(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "runtime");
        path.toFile().setExecutable(true);
        return path;
    }
}
