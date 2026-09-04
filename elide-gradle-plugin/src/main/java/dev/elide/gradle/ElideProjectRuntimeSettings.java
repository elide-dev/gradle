package dev.elide.gradle;

import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Clean project-level overrides for build-wide runtime conventions. */
public final class ElideProjectRuntimeSettings {
    private final Property<ElideRuntimeMode> mode;
    private final Property<String> version;
    private final RegularFileProperty executable;

    ElideProjectRuntimeSettings(
            Property<ElideRuntimeMode> mode,
            Property<String> version,
            RegularFileProperty executable) {
        this.mode = mode;
        this.version = version;
        this.executable = executable;
    }

    public ElideRuntimeMode getMode() {
        return mode.get();
    }

    public void setMode(ElideRuntimeMode value) {
        mode.set(value);
    }

    public String getVersion() {
        return version.get();
    }

    public void setVersion(String value) {
        version.set(value);
    }

    public RegularFile getExecutable() {
        return executable.getOrNull();
    }

    public void setExecutable(RegularFile value) {
        executable.set(value);
    }
}
