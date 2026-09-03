package dev.elide.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public interface ElideExtensionConfig {
    Property<Boolean> getEnableInstall();
    Property<Boolean> getEnableEmbeddedBuild();
    Property<Boolean> getEnableMavenIntegration();
    Property<Boolean> getEnableJavaCompiler();
    Property<Boolean> getEnableProjectIntegration();
    RegularFileProperty getManifest();
    RegularFileProperty getElideBin();
    Property<ElideRuntimeMode> getRuntimeMode();
    Property<String> getRuntimeVersion();
    @Deprecated
    Property<Boolean> getResolveElideFromPath();
    Property<Boolean> getDebug();
    Property<Boolean> getVerbose();
    DirectoryProperty getDevRoot();
}
