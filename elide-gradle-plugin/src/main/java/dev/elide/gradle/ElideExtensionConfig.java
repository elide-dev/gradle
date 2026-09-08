package dev.elide.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public interface ElideExtensionConfig {
    @Deprecated
    Property<Boolean> getEnableInstall();
    Property<Boolean> getEnableEmbeddedBuild();
    @Deprecated
    Property<Boolean> getEnableMavenIntegration();
    @Deprecated
    Property<Boolean> getEnableJavaCompiler();
    Property<Boolean> getEnableProjectIntegration();
    RegularFileProperty getManifest();
    @Deprecated
    RegularFileProperty getElideBin();
    @Deprecated
    Property<ElideRuntimeMode> getRuntimeMode();
    @Deprecated
    Property<String> getRuntimeVersion();
    @Deprecated
    Property<Boolean> getResolveElideFromPath();
    Property<Boolean> getDebug();
    Property<Boolean> getVerbose();
    DirectoryProperty getDevRoot();
}
