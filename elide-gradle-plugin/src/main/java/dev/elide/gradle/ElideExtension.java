package dev.elide.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import java.nio.file.Path;

public class ElideExtension implements ElideExtensionConfig {
    private static final boolean USE_ROOT_FOR_DEPS = true;
    private static final String DEFAULT_DEV_ROOT = ".dev";
    protected Project activeProject;
    protected boolean enableInstall = false;
    protected boolean useBuildEmbedded = false;
    protected boolean enableJavacIntegration = true;
    protected boolean enableProjectIntegration = true;
    protected boolean enableMavenIntegration = true;
    protected boolean enableShim = true;
    protected Property<Boolean> doEnableInstall;
    protected Property<Boolean> doEmbeddedBuild;
    protected Property<Boolean> doUseMavenIntegration;
    protected Property<Boolean> doEnableProjects;
    protected Property<Boolean> doEnableJavaCompiler;
    protected Property<ElideRuntimeMode> doRuntimeMode;
    protected Property<String> doRuntimeVersion;
    protected Property<Boolean> doResolveElideFromPath;
    protected Property<Boolean> enableDebugMode;
    protected Property<Boolean> enableVerboseMode;
    @PathSensitive(PathSensitivity.RELATIVE) protected RegularFileProperty projectManifest;
    @PathSensitive(PathSensitivity.ABSOLUTE) protected RegularFileProperty activeElideBin;
    @PathSensitive(PathSensitivity.RELATIVE) protected DirectoryProperty activeDevRoot;
    @PathSensitive(PathSensitivity.RELATIVE) @Input protected RegularFileProperty activeLockfile;

    @Override
    public Property<Boolean> getEnableInstall() {
        return doEnableInstall;
    }

    @Override
    public Property<Boolean> getEnableEmbeddedBuild() {
        return doEmbeddedBuild;
    }

    @Override
    public Property<Boolean> getEnableMavenIntegration() {
        return doUseMavenIntegration;
    }

    @Override
    public Property<Boolean> getEnableJavaCompiler() {
        return doEnableJavaCompiler;
    }

    @Override
    public Property<Boolean> getEnableProjectIntegration() {
        return doEnableProjects;
    }

    @Override
    public RegularFileProperty getManifest() {
        return projectManifest;
    }

    @Override
    @Deprecated
    public Property<Boolean> getResolveElideFromPath() {
        return doResolveElideFromPath;
    }

    @Override
    public DirectoryProperty getDevRoot() {
        return activeDevRoot;
    }

    @Override
    public RegularFileProperty getElideBin() {
        return activeElideBin;
    }

    @Override
    public Property<ElideRuntimeMode> getRuntimeMode() {
        return doRuntimeMode;
    }

    @Override
    public Property<String> getRuntimeVersion() {
        return doRuntimeVersion;
    }

    @Override
    public Property<Boolean> getDebug() {
        return enableDebugMode;
    }

    @Override
    public Property<Boolean> getVerbose() {
        return enableVerboseMode;
    }

    boolean enableShim() {
        return enableShim;
    }

    Path resolveLocalDepsPath() {
        return activeDevRoot.getAsFile().get().toPath()
                .resolve("dependencies")
                .resolve("m2");
    }

    Provider<RegularFile> resolveLockfilePath() {
        return activeDevRoot.file("elide.lock.bin");
    }

    ElideExtension(Project project, ObjectFactory objects) {
        this.activeProject = project;
        this.doEnableInstall = objects.property(Boolean.class).convention(enableInstall);
        this.doEmbeddedBuild = objects.property(Boolean.class).convention(useBuildEmbedded);
        this.doUseMavenIntegration = objects.property(Boolean.class).convention(enableMavenIntegration);
        this.doEnableProjects = objects.property(Boolean.class).convention(enableProjectIntegration);
        this.doEnableJavaCompiler = objects.property(Boolean.class).convention(enableJavacIntegration);
        this.doRuntimeMode = objects.property(ElideRuntimeMode.class).convention(ElideRuntimeMode.AUTO);
        this.doRuntimeVersion = objects.property(String.class).convention("1.5.1+20260903");
        this.doResolveElideFromPath = objects.property(Boolean.class);
        this.projectManifest = objects.fileProperty()
                .convention(project.getLayout().getProjectDirectory().file("elide.pkl"));

        var devRootProject = (USE_ROOT_FOR_DEPS ? activeProject.getRootProject() : activeProject);
        this.activeElideBin = objects.fileProperty();
        this.activeDevRoot = objects.directoryProperty()
                .convention(devRootProject.getLayout().getProjectDirectory().dir(DEFAULT_DEV_ROOT));

        this.enableDebugMode = objects.property(Boolean.class).convention(false);
        this.enableVerboseMode = objects.property(Boolean.class).convention(false);
        this.activeLockfile = objects.fileProperty().convention(resolveLockfilePath());
    }
}
