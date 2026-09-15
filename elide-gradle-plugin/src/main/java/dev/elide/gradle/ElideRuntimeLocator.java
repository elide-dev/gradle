package dev.elide.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pure runtime selection logic, independent of Gradle and process execution.
 *
 * <p>Package-private along with {@link ElideVersionProbe} and {@link ElideVersion}: this is plugin
 * machinery, and a public entry point taking package-private parameter types could not be called
 * from outside the package anyway.
 */
final class ElideRuntimeLocator {
    private ElideRuntimeLocator() {
    }

    /**
     * Selects the runtime for a mode.
     *
     * <p>An explicit executable is taken as configured and is not version-checked. A {@code PATH}
     * candidate must additionally report at least {@code requiredVersion}; candidates are probed in
     * directory order and probing stops at the first acceptable one. In {@code AUTO} an
     * out-of-date candidate is skipped, so selection continues to the managed runtime.
     *
     * @param managedExecutable supplies the managed path only when it is actually selected, because
     *                          resolving it requires a configured runtime version
     * @param requiredVersion   supplies the floor a {@code PATH} candidate must meet, queried only
     *                          when there is a candidate to judge. A {@code null} result means the
     *                          floor is unknown -- modes that never provision must not fail merely
     *                          because a managed version cannot be resolved -- and the first usable
     *                          candidate is then accepted without probing.
     * @param onCandidateRejected reports an out-of-date candidate that {@code AUTO} skipped. This
     *                          is a callback rather than a log call because selection is re-run on
     *                          every read of the provider that wraps it, so the caller has to be
     *                          the one that decides a given rejection is only worth saying once.
     */
    static ElideRuntimeSelection locate(
            ElideRuntimeMode mode,
            Optional<Path> explicit,
            List<Path> pathDirectories,
            Supplier<Path> managedExecutable,
            ElidePlatform platform,
            Supplier<ElideVersion> requiredVersion,
            ElideVersionProbe versionProbe,
            Consumer<String> onCandidateRejected) {
        if (mode == ElideRuntimeMode.MANAGED) {
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable.get());
        }

        Optional<Path> explicitExecutable = explicit.filter(path -> usable(path, platform));
        if (explicitExecutable.isPresent()) {
            return new ElideRuntimeSelection(ElideRuntimeSource.EXPLICIT, explicitExecutable.get());
        }

        List<Path> candidates = new ArrayList<>();
        for (Path directory : pathDirectories) {
            Path candidate = directory.resolve(platform.executableName());
            if (usable(candidate, platform)) {
                candidates.add(candidate);
            }
        }

        ElideVersion floor = candidates.isEmpty() ? null : requiredVersion.get();
        Path rejected = null;
        ElideVersion rejectedVersion = null;
        for (Path candidate : candidates) {
            if (floor == null) {
                return new ElideRuntimeSelection(ElideRuntimeSource.PATH, candidate);
            }
            Optional<ElideVersion> reported = versionProbe.version(candidate).flatMap(ElideVersion::parse);
            if (reported.isPresent() && reported.get().compareTo(floor) >= 0) {
                return new ElideRuntimeSelection(ElideRuntimeSource.PATH, candidate);
            }
            if (rejected == null) {
                rejected = candidate;
                rejectedVersion = reported.orElse(null);
            }
        }

        if (mode == ElideRuntimeMode.AUTO) {
            if (rejected != null) {
                // Without this the only visible effect is an unexplained managed download, or an
                // offline cache-miss failure, with nothing pointing at the installed runtime.
                onCandidateRejected.accept("Elide runtime on PATH (" + rejected + ") reports version "
                        + describe(rejectedVersion) + ", below the required " + floor
                        + "; using the managed runtime instead.");
            }
            return new ElideRuntimeSelection(ElideRuntimeSource.MANAGED, managedExecutable.get());
        }
        if (rejected != null) {
            throw new IllegalStateException("Elide PATH runtime " + rejected + " reports version "
                    + describe(rejectedVersion) + ", but " + floor + " or newer is required; upgrade Elide, set "
                    + "runtime.executable, or choose MANAGED");
        }
        throw new IllegalStateException("Elide PATH runtime was requested but no executable was found");
    }

    private static String describe(ElideVersion version) {
        return version == null ? "an unreadable version" : version.toString();
    }

    private static boolean usable(Path path, ElidePlatform platform) {
        return Files.isRegularFile(path)
                && (platform.os().equals("windows") || Files.isExecutable(path));
    }
}
