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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElideRuntimeLocatorTest {
    private static final ElidePlatform LINUX = ElidePlatform.detect("Linux", "amd64");
    private static final ElidePlatform WINDOWS = ElidePlatform.detect("Windows 11", "amd64");
    private static final ElideVersion REQUIRED = ElideVersion.parse("1.5.1+20260903").orElseThrow();
    private static final String CURRENT = "1.5.1+20260903.4c6cdc7";
    private static final String OUTDATED = "1.4.9+20260101.0000000";

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
                PATH, Optional.empty(), List.of(executable.getParent()), () -> managed, WINDOWS,
                REQUIRED, reporting(CURRENT));

        assertEquals(ElideRuntimeSource.PATH, selection.source());
        assertEquals(executable, selection.executable());
    }

    @Test
    void autoModeSkipsAnOutdatedPathRuntimeAndFallsBackToManaged() throws IOException {
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.empty(), List.of(pathBin.getParent()), managed,
                reporting(OUTDATED));

        assertEquals(ElideRuntimeSource.MANAGED, selection.source());
        assertEquals(managed, selection.executable());
    }

    @Test
    void autoModeAcceptsANewerPathRuntime() throws IOException {
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.empty(), List.of(pathBin.getParent()), managed,
                reporting("1.10.0+20270101.aaaaaaa"));

        assertEquals(ElideRuntimeSource.PATH, selection.source());
        assertEquals(pathBin, selection.executable());
    }

    @Test
    void pathModeNamesTheOutdatedRuntimeAndTheRequiredVersion() throws IOException {
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var failure = assertThrows(IllegalStateException.class,
                () -> locate(PATH, Optional.empty(), List.of(pathBin.getParent()), managed,
                        reporting(OUTDATED)));

        assertTrue(failure.getMessage().contains("1.4.9"), failure.getMessage());
        assertTrue(failure.getMessage().contains("1.5.1"), failure.getMessage());
    }

    @Test
    void pathLookupPrefersTheFirstAcceptableCandidateRatherThanTheFirstUsableOne() throws IOException {
        var first = executable(tempDir.resolve("first").resolve("elide"));
        var second = executable(tempDir.resolve("second").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.empty(), List.of(first.getParent(), second.getParent()),
                managed, candidate -> Optional.of(candidate.equals(first) ? OUTDATED : CURRENT));

        assertEquals(ElideRuntimeSource.PATH, selection.source());
        assertEquals(second, selection.executable());
    }

    @Test
    void anExplicitRuntimeIsNotVersionChecked() throws IOException {
        var explicit = executable(tempDir.resolve("explicit"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.of(explicit), List.of(), managed, reporting(OUTDATED));

        assertEquals(ElideRuntimeSource.EXPLICIT, selection.source());
        assertEquals(explicit, selection.executable());
    }

    @Test
    void aCandidateWithUnreadableVersionOutputIsNotUsable() throws IOException {
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(AUTO, Optional.empty(), List.of(pathBin.getParent()), managed,
                executable -> Optional.empty());

        assertEquals(ElideRuntimeSource.MANAGED, selection.source());
    }

    @Test
    void managedModeNeverProbesACandidate() throws IOException {
        var pathBin = executable(tempDir.resolve("path").resolve("elide"));
        var managed = tempDir.resolve("managed").resolve("elide");

        var selection = locate(MANAGED, Optional.empty(), List.of(pathBin.getParent()), managed,
                executable -> {
                    throw new AssertionError("MANAGED must not execute a candidate");
                });

        assertEquals(ElideRuntimeSource.MANAGED, selection.source());
    }

    private ElideRuntimeSelection locate(ElideRuntimeMode mode, Optional<Path> explicit,
                                         List<Path> pathDirectories, Path managed) {
        return locate(mode, explicit, pathDirectories, managed, reporting(CURRENT));
    }

    private ElideRuntimeSelection locate(ElideRuntimeMode mode, Optional<Path> explicit,
                                         List<Path> pathDirectories, Path managed,
                                         ElideVersionProbe probe) {
        return ElideRuntimeLocator.locate(
                mode, explicit, pathDirectories, () -> managed, LINUX, REQUIRED, probe);
    }

    /** Every candidate reports the same version. */
    private static ElideVersionProbe reporting(String version) {
        return executable -> Optional.of(version);
    }

    private static Path executable(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "runtime");
        path.toFile().setExecutable(true);
        return path;
    }
}
