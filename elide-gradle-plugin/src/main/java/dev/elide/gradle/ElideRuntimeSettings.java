package dev.elide.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

import javax.inject.Inject;

/** Build-wide Elide runtime conventions. */
public class ElideRuntimeSettings {
    private final Property<ElideRuntimeMode> mode;
    private final Property<String> version;
    private final Property<ElideVersionSource> versionSource;
    private final Property<String> catalogName;
    private final Property<String> catalogAlias;

    @Inject
    public ElideRuntimeSettings(ObjectFactory objects) {
        mode = objects.property(ElideRuntimeMode.class).convention(ElideRuntimeMode.AUTO);
        version = objects.property(String.class);
        versionSource = objects.property(ElideVersionSource.class).convention(ElideVersionSource.DEFAULT);
        catalogName = objects.property(String.class);
        catalogAlias = objects.property(String.class);
    }

    public ElideRuntimeMode getMode() {
        return mode.get();
    }

    public void setMode(ElideRuntimeMode value) {
        mode.set(value);
    }

    public String getVersion() {
        return version.getOrNull();
    }

    public void setVersion(String value) {
        requireDirectVersionAvailable();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Elide runtime version must not be blank");
        }
        version.set(value);
        versionSource.set(ElideVersionSource.DIRECT);
    }

    public void version(Provider<String> value) {
        requireDirectVersionAvailable();
        version.set(value.map(candidate -> {
            if (candidate.isBlank()) {
                throw new IllegalArgumentException("Elide runtime version must not be blank");
            }
            return candidate;
        }));
        versionSource.set(ElideVersionSource.DIRECT);
    }

    public void versionFrom(String selectedCatalog, String selectedAlias) {
        if (selectedCatalog == null || selectedCatalog.isBlank()) {
            throw new IllegalArgumentException("Elide version catalog name must not be blank");
        }
        if (selectedAlias == null || selectedAlias.isBlank()) {
            throw new IllegalArgumentException("Elide version catalog alias must not be blank");
        }
        if (versionSource.get() == ElideVersionSource.DIRECT) {
            throw new IllegalStateException(
                    "Elide runtime version is already configured directly; choose either version or versionFrom");
        }
        catalogName.set(selectedCatalog);
        catalogAlias.set(selectedAlias);
        versionSource.set(ElideVersionSource.CATALOG);
    }

    Property<ElideRuntimeMode> getModeProperty() {
        return mode;
    }

    Property<String> getVersionProperty() {
        return version;
    }

    Property<ElideVersionSource> getVersionSourceProperty() {
        return versionSource;
    }

    Property<String> getCatalogNameProperty() {
        return catalogName;
    }

    Property<String> getCatalogAliasProperty() {
        return catalogAlias;
    }

    private void requireDirectVersionAvailable() {
        if (versionSource.get() == ElideVersionSource.CATALOG) {
            throw new IllegalStateException("Elide runtime version is already configured from catalog '"
                    + catalogName.get() + "' alias '" + catalogAlias.get()
                    + "'; choose either version or versionFrom");
        }
    }
}
