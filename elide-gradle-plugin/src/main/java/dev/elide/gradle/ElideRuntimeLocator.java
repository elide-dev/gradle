package dev.elide.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Pure runtime selection logic, independent of Gradle and process execution. */
public final class ElideRuntimeLocator {
    private ElideRuntimeLocator() {
    }

    public static ElideRuntimeSelection locate(
            ElideRuntimeMode mode,
            Optional<Path> explicit,
            List<Path> pathDirectories,
            Path managedExecutable,
            ElidePlatform platform) {
        Optional<Path> explicitExecutable = explicit.filter(path -> usable(path, platform));
        Optional<Path> pathExecutable = pathDirectories.stream()
                .map(directory -> directory.resolve(platform.executableName()))
                .filter(path -> usable(path, platform))
                .findFirst();

        if (mode == ElideRuntimeMode.MANAGED) {
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable);
        }
        if (explicitExecutable.isPresent()) {
            return new ElideRuntimeSelection(ElideRuntimeSource.EXPLICIT, explicitExecutable.get());
        }
        if (pathExecutable.isPresent()) {
            return new ElideRuntimeSelection(ElideRuntimeSource.PATH, pathExecutable.get());
        }
        if (mode == ElideRuntimeMode.AUTO) {
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable);
        }
        throw new IllegalStateException("Elide PATH runtime was requested but no executable was found");
    }

    private static boolean usable(Path path, ElidePlatform platform) {
        return Files.isRegularFile(path)
                && (platform.os().equals("windows") || Files.isExecutable(path));
    }
}
