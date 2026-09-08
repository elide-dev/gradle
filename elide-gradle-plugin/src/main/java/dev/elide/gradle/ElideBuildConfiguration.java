package dev.elide.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/** Build-wide Elide conventions shared with explicitly opted-in projects. */
public abstract class ElideBuildConfiguration implements BuildService<ElideBuildConfiguration.Parameters> {
    static final String SERVICE_NAME = "elideBuildConfiguration";

    public interface Parameters extends BuildServiceParameters {
        Property<ElideRuntimeMode> getRuntimeMode();
        Property<ElideVersionSource> getVersionSource();
        Property<String> getRuntimeVersion();
        Property<String> getCatalogName();
        Property<String> getCatalogAlias();
    }
}
