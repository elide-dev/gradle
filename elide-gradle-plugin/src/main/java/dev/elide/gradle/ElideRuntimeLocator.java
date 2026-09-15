package dev.elide.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Pure runtime selection logic, independent of Gradle and process execution. */
public final class ElideRuntimeLocator {
    private ElideRuntimeLocator() {
    }

    /**
     * Selects the runtime for a mode.
     *
     * <p>An explicit executable is taken as configured and is not version-checked. A {@code PATH}
     * candidate must additionally report at least {@code requiredVersion}; candidates are probed in
     * directory order and probing stops at the first acceptable one. In {@code AUTO} an
     * out-of-date candidate is simply not usable, so selection continues to the managed runtime.
     *
     * @param managedExecutable supplies the managed path only when it is actually selected, because
     *                          resolving it requires a configured runtime version
     */
    public static ElideRuntimeSelection locate(
            ElideRuntimeMode mode,
            Optional<Path> explicit,
            List<Path> pathDirectories,
            Supplier<Path> managedExecutable,
            ElidePlatform platform,
            ElideVersion requiredVersion,
            ElideVersionProbe versionProbe) {
        if (mode == ElideRuntimeMode.MANAGED) {
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable.get());
        }

        Optional<Path> explicitExecutable = explicit.filter(path -> usable(path, platform));
        if (explicitExecutable.isPresent()) {
            return new ElideRuntimeSelection(ElideRuntimeSource.EXPLICIT, explicitExecutable.get());
        }

        List<Path> candidates = pathDirectories.stream()
                .map(directory -> directory.resolve(platform.executableName()))
                .filter(path -> usable(path, platform))
                .toList();
        Path outdated = null;
        ElideVersion outdatedVersion = null;
        for (Path candidate : candidates) {
            Optional<ElideVersion> reported = versionProbe.version(candidate).flatMap(ElideVersion::parse);
            if (reported.isPresent() && reported.get().compareTo(requiredVersion) >= 0) {
                return new ElideRuntimeSelection(ElideRuntimeSource.PATH, candidate);
            }
            if (outdated == null) {
                outdated = candidate;
                outdatedVersion = reported.orElse(null);
            }
        }

        if (mode == ElideRuntimeMode.AUTO) {
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable.get());
        }
        if (outdated != null) {
            throw new IllegalStateException("Elide PATH runtime " + outdated + " reports version "
                    + (outdatedVersion == null ? "an unreadable version" : outdatedVersion)
                    + ", but " + requiredVersion + " or newer is required; upgrade Elide, set "
                    + "runtime.executable, or choose MANAGED");
        }
        throw new IllegalStateException("Elide PATH runtime was requested but no executable was found");
    }

    private static boolean usable(Path path, ElidePlatform platform) {
        return Files.isRegularFile(path)
                && (platform.os().equals("windows") || Files.isExecutable(path));
    }
}
