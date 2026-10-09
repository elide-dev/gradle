package dev.elide.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Pure runtime selection logic, independent of Gradle and process execution. */
public final class ElideRuntimeLocator {
    private ElideRuntimeLocator() {
    }

    static Optional<Path> findInstalled(
            Optional<Path> explicit,
            List<Path> pathDirectories,
            ElidePlatform platform) {
        Optional<Path> explicitExecutable = explicit.filter(path -> usable(path, platform));
        if (explicitExecutable.isPresent()) {
            return explicitExecutable;
        }
        return pathDirectories.stream()
                .map(directory -> directory.resolve(platform.executableName()))
                .filter(path -> usable(path, platform))
                .findFirst();
    }

    private static boolean usable(Path path, ElidePlatform platform) {
        return Files.isRegularFile(path)
                && (platform.os().equals("windows") || Files.isExecutable(path));
    }
}
