package dev.elide.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Build-wide Elide conventions shared with explicitly opted-in projects. */
public abstract class ElideBuildConfiguration implements BuildService<ElideBuildConfiguration.Parameters> {
    static final String SERVICE_NAME = "elideBuildConfiguration";

    private final Map<Path, Optional<String>> probedRuntimeVersions = new ConcurrentHashMap<>();

    /**
     * Memoizes a runtime version probe for the whole build. Runtime selection happens once per
     * project, so without a build-scoped cache a large multi-project build would fork the candidate
     * executable once per module during configuration.
     */
    Optional<String> probedRuntimeVersion(Path executable, Function<Path, Optional<String>> probe) {
        return probedRuntimeVersions.computeIfAbsent(executable, probe);
    }

    public interface Parameters extends BuildServiceParameters {
        Property<ElideRuntimeMode> getRuntimeMode();
        Property<ElideVersionSource> getVersionSource();
        Property<String> getRuntimeVersion();
        Property<String> getCatalogName();
        Property<String> getCatalogAlias();
    }
}
