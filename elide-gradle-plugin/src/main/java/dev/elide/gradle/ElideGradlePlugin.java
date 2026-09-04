package dev.elide.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

/** Gradle integration for Elide runtime selection, compilation, and dependency installation. */
public class ElideGradlePlugin implements Plugin<Project> {
    private static final String JAVA_PLUGIN_ID = "java";
    private static final String ELIDE_EXTENSION_NAME = "elide";

    @Override
    public void apply(Project project) {
        Provider<ElideBuildConfiguration> buildConfiguration = project.getGradle().getSharedServices()
                .registerIfAbsent(
                        ElideBuildConfiguration.SERVICE_NAME,
                        ElideBuildConfiguration.class,
                        service -> {
                            service.getMaxParallelUsages().set(1);
                            service.getParameters().getRuntimeMode().set(ElideRuntimeMode.AUTO);
                            service.getParameters().getVersionSource().set(ElideVersionSource.DEFAULT);
                            service.getParameters().getRuntimeVersion().set(ElideExtension.DEFAULT_RUNTIME_VERSION);
                        });
        ElideExtension extension = project.getExtensions().create(
                ELIDE_EXTENSION_NAME, ElideExtension.class, project, buildConfiguration);
        ElideRuntimeResolution resolution = ElideRuntimeResolver.resolve(project, extension);
        resolution.preparationTask().configure(task -> task.usesService(buildConfiguration));
        Provider<Boolean> mavenInstallerEnabled = mavenInstallerEnabled(project, extension);
        Provider<Boolean> installEnabled = extension.getEnableInstall()
                .zip(mavenInstallerEnabled, (install, mavenInstaller) -> install || mavenInstaller);
        TaskProvider<ElideExecTask> installTask = configureInstallTask(
                project, extension, resolution, installEnabled);

        project.afterEvaluate(ignored -> {
            if (mavenInstallerEnabled.get()) {
                installMavenDepsSupport(project, extension);
            }
        });
        project.getPluginManager().withPlugin(JAVA_PLUGIN_ID, ignored ->
                project.getTasks().withType(JavaCompile.class).configureEach(task -> {
                    if (!enableJavaCompiler(project, extension)) {
                        return;
                    }
                    configureJavaCompileToUseElide(task, resolution);
                    addManagedPreparationDependency(task, resolution);
                    task.dependsOn(installTask);
                }));
    }

    private void configureJavaCompileToUseElide(JavaCompile task, ElideRuntimeResolution resolution) {
        var options = task.getOptions();
        options.setFork(true);
        options.getForkOptions().setExecutable(javaExecutable().toString());
        ElideCompilerArgumentProvider arguments = task.getProject().getObjects()
                .newInstance(ElideCompilerArgumentProvider.class);
        arguments.getElideExecutable().set(resolution.executable());
        arguments.getRuntimeSource().set(resolution.source());
        arguments.getLauncherClasspath().from(compilerLauncherFile());
        options.getForkOptions().getJvmArgumentProviders().add(arguments);
    }

    private static Path javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().startsWith("windows")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName);
    }

    private static File compilerLauncherFile() {
        try {
            return new File(ElideJavaCompilerLauncher.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Unable to locate the Elide compiler launcher", exception);
        }
    }

    private TaskProvider<ElideExecTask> configureInstallTask(
            Project project,
            ElideExtension extension,
            ElideRuntimeResolution resolution,
            Provider<Boolean> installEnabled) {
        return project.getTasks().register(
                ElideTaskName.ELIDE_TASK_INSTALL,
                ElideExecTask.class,
                task -> {
                    task.setGroup("Elide");
                    task.setDescription("Runs `elide install` to prepare the project for compilation.");
                    task.getElideExecutable().set(resolution.executable());
                    task.getElideArguments().set(List.of("install"));
                    task.getWorkingDirectory().set(project.getLayout().getProjectDirectory());
                    task.getWorkingDirectoryPath().set(project.getProjectDir().getAbsolutePath());
                    task.getManifest().set(extension.getManifest());
                    task.getDevRootInputs().from(project.fileTree(extension.getDevRoot())
                            .exclude("dependencies/**", "elide.lock.bin"));
                    task.getGeneratedDependencyRepository().set(extension.getDevRoot().dir("dependencies/m2"));
                    task.onlyIf("Elide dependency installation is enabled", ignored -> installEnabled.get());
                    addManagedPreparationDependency(task, resolution);
                });
    }

    private void addManagedPreparationDependency(Task task, ElideRuntimeResolution resolution) {
        task.dependsOn(resolution.preparationTask());
    }

    private void installMavenDepsSupport(Project project, ElideExtension extension) {
        project.getRepositories().maven(repository -> {
            repository.setName("elide");
            repository.setUrl(extension.resolveLocalDepsPath().toUri());
            repository.metadataSources(sources -> {
                sources.mavenPom();
                sources.artifact();
            });
        });
    }

    private Provider<Boolean> mavenInstallerEnabled(Project project, ElideExtension extension) {
        Provider<Boolean> extensionEnabled = extension.getEnableInstall()
                .zip(extension.getEnableMavenIntegration(), (install, maven) -> install && maven);
        return project.getProviders().gradleProperty("elide.builder.maven.install.enable")
                .map(Boolean::parseBoolean)
                .orElse(extensionEnabled);
    }

    private boolean enableJavaCompiler(Project project, ElideExtension extension) {
        Object configured = project.findProperty("elide.builder.javac.enable");
        if (configured != null) {
            return Boolean.parseBoolean(configured.toString());
        }
        return extension.getEnableJavaCompiler().get();
    }
}
