package dev.elide.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.provider.Provider;

/** Resolves the effective Elide version without using generated catalog accessors. */
final class ElideVersionResolver {
    private ElideVersionResolver() {
    }

    static Provider<String> resolve(
            Project project,
            Provider<ElideBuildConfiguration> configuration,
            String standaloneDefault) {
        return configuration.flatMap(service -> service.getParameters().getVersionSource().flatMap(source -> {
            var parameters = service.getParameters();
            return switch (source) {
                case DIRECT -> parameters.getRuntimeVersion();
                case CATALOG -> project.provider(() -> resolveCatalogVersion(
                        project,
                        parameters.getCatalogName().get(),
                        parameters.getCatalogAlias().get()));
                case DEFAULT -> project.provider(() -> standaloneDefault);
            };
        }));
    }

    private static String resolveCatalogVersion(Project project, String catalogName, String alias) {
        VersionCatalogsExtension catalogs = project.getExtensions().getByType(VersionCatalogsExtension.class);
        VersionCatalog catalog = catalogs.find(catalogName).orElseThrow(() -> new GradleException(
                "Elide version catalog '" + catalogName + "' does not exist for project " + project.getPath()));
        return catalog.findVersion(alias).orElseThrow(() -> new GradleException(
                        "Elide version alias '" + alias + "' does not exist in catalog '" + catalogName
                                + "' for project " + project.getPath()))
                .getRequiredVersion();
    }
}
