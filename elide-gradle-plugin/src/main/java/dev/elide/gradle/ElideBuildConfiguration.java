package dev.elide.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Build-wide Elide conventions shared with explicitly opted-in projects. */
public abstract class ElideBuildConfiguration implements BuildService<ElideBuildConfiguration.Parameters> {
    static final String SERVICE_NAME = "elideBuildConfiguration";

    private final Map<Path, Optional<String>> probedRuntimeVersions = new ConcurrentHashMap<>();
    private final Set<String> reportedMessages = ConcurrentHashMap.newKeySet();

    /**
     * Memoizes a runtime version probe for the whole build. Runtime selection happens once per
     * project, so without a build-scoped cache a large multi-project build would fork the candidate
     * executable once per module during configuration.
     *
     * <p>The probe deliberately runs outside {@code computeIfAbsent}: it starts a process and
     * blocks, and a mapping function must not do either while holding a bin lock that unrelated
     * keys may hash into. A rare duplicate probe under contention is the better trade.
     */
    Optional<String> probedRuntimeVersion(Path executable, Function<Path, Optional<String>> probe) {
        Optional<String> cached = probedRuntimeVersions.get(executable);
        if (cached != null) {
            return cached;
        }
        Optional<String> probed = probe.apply(executable);
        Optional<String> raced = probedRuntimeVersions.putIfAbsent(executable, probed);
        return raced != null ? raced : probed;
    }

    /**
     * @return whether this message has not already been reported in this build
     */
    boolean shouldReportOnce(String message) {
        return reportedMessages.add(message);
    }

    public interface Parameters extends BuildServiceParameters {
        Property<ElideRuntimeMode> getRuntimeMode();
        Property<ElideVersionSource> getVersionSource();
        Property<String> getRuntimeVersion();
        Property<String> getCatalogName();
        Property<String> getCatalogAlias();
    }
}
