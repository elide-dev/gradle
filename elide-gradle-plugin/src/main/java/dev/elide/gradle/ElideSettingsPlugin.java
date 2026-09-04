package dev.elide.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

import java.net.URI;

/** Configures one Elide runtime convention for a Gradle build. */
public final class ElideSettingsPlugin implements Plugin<Settings> {
    private static final String ELIDE_EXTENSION_NAME = "elide";
    private static final URI ELIDE_MAVEN_REPOSITORY = URI.create("https://maven.elide.dev");

    @Override
    public void apply(Settings settings) {
        ElideSettingsExtension extension = settings.getExtensions()
                .create(ELIDE_EXTENSION_NAME, ElideSettingsExtension.class);

        settings.getGradle().getSharedServices().registerIfAbsent(
                ElideBuildConfiguration.SERVICE_NAME,
                ElideBuildConfiguration.class,
                service -> {
                    service.getMaxParallelUsages().set(1);
                    var parameters = service.getParameters();
                    parameters.getRuntimeMode().set(extension.getRuntime().getModeProperty());
                    parameters.getVersionSource().set(extension.getRuntime().getVersionSourceProperty());
                    parameters.getRuntimeVersion().set(extension.getRuntime().getVersionProperty());
                    parameters.getCatalogName().set(extension.getRuntime().getCatalogNameProperty());
                    parameters.getCatalogAlias().set(extension.getRuntime().getCatalogAliasProperty());
                });

        settings.getDependencyResolutionManagement().getRepositories().maven(repository -> {
            repository.setName("elide");
            repository.setUrl(ELIDE_MAVEN_REPOSITORY);
            repository.content(content -> {
                content.includeGroup("dev.elide");
                content.includeGroup("dev.elide.gradle");
            });
        });
    }
}
