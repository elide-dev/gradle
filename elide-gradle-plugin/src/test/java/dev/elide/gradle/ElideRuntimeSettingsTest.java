package dev.elide.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElideRuntimeSettingsTest {
    @Test
    void acceptsDirectVersion() {
        ElideRuntimeSettings runtime = runtimeSettings();

        runtime.setVersion("1.5.1+20260903");

        assertEquals(ElideVersionSource.DIRECT, runtime.getVersionSourceProperty().get());
        assertEquals("1.5.1+20260903", runtime.getVersionProperty().get());
    }

    @Test
    void acceptsProviderBackedVersion() {
        Project project = ProjectBuilder.builder().build();
        ElideRuntimeSettings runtime = new ElideRuntimeSettings(project.getObjects());

        runtime.version(project.provider(() -> "provider-version"));

        assertEquals(ElideVersionSource.DIRECT, runtime.getVersionSourceProperty().get());
        assertEquals("provider-version", runtime.getVersionProperty().get());
    }

    @Test
    void acceptsCatalogVersion() {
        ElideRuntimeSettings runtime = runtimeSettings();

        runtime.versionFrom("libs", "elide");

        assertEquals(ElideVersionSource.CATALOG, runtime.getVersionSourceProperty().get());
        assertEquals("libs", runtime.getCatalogNameProperty().get());
        assertEquals("elide", runtime.getCatalogAliasProperty().get());
    }

    @Test
    void rejectsCatalogAfterDirectVersion() {
        ElideRuntimeSettings runtime = runtimeSettings();
        runtime.setVersion("1.5.1+20260903");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> runtime.versionFrom("libs", "elide"));

        assertEquals(
                "Elide runtime version is already configured directly; choose either version or versionFrom",
                failure.getMessage());
    }

    @Test
    void rejectsDirectVersionAfterCatalog() {
        ElideRuntimeSettings runtime = runtimeSettings();
        runtime.versionFrom("libs", "elide");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> runtime.setVersion("1.5.1+20260903"));

        assertEquals(
                "Elide runtime version is already configured from catalog 'libs' alias 'elide'; "
                        + "choose either version or versionFrom",
                failure.getMessage());
    }

    @Test
    void rejectsBlankCatalogCoordinates() {
        ElideRuntimeSettings runtime = runtimeSettings();

        assertEquals(
                "Elide version catalog name must not be blank",
                assertThrows(IllegalArgumentException.class, () -> runtime.versionFrom(" ", "elide")).getMessage());
        assertEquals(
                "Elide version catalog alias must not be blank",
                assertThrows(IllegalArgumentException.class, () -> runtime.versionFrom("libs", " ")).getMessage());
    }

    private static ElideRuntimeSettings runtimeSettings() {
        Project project = ProjectBuilder.builder().build();
        return new ElideRuntimeSettings(project.getObjects());
    }
}
