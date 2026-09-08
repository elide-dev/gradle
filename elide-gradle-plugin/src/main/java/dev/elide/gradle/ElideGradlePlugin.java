package dev.elide.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.net.URISyntaxException;
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
        ElideFormatting.configure(project, resolution);
        ElideDependencies.configure(project);
        resolution.preparationTask().configure(task -> task.usesService(buildConfiguration));
        Provider<Boolean> mavenInstallerEnabled = mavenInstallerEnabled(project, extension);
        Provider<Boolean> installEnabled = extension.getEnableInstall()
                .zip(mavenInstallerEnabled, (install, mavenInstaller) -> install || mavenInstaller);
        TaskProvider<ElideExecTask> installTask = configureInstallTask(
                project, extension, resolution, installEnabled);

        project.afterEvaluate(ignored -> {
            if (extension.getDependencyMode().get() == ElideDependencyMode.GRADLE
                    && (extension.getEnableInstall().get() || mavenInstallerEnabled.get())) {
                throw new org.gradle.api.GradleException("dependencyMode GRADLE requires install = false; declare JVM dependencies in Gradle and use its locking and verification.");
            }
            if (mavenInstallerEnabled.get()) {
                installMavenDepsSupport(project, extension);
            }
        });
        project.getPluginManager().withPlugin(JAVA_PLUGIN_ID, ignored ->
                project.getTasks().withType(JavaCompile.class).configureEach(task -> {
                    if (!enableJavaCompiler(project, extension)) {
                        return;
                    }
                    configureJavaCompileToUseElide(task, resolution, extension);
                    addManagedPreparationDependency(task, resolution);
                    task.dependsOn(installTask);
                }));
    }

    private void configureJavaCompileToUseElide(JavaCompile task, ElideRuntimeResolution resolution, ElideExtension extension) {
        var options = task.getOptions();
        options.setFork(true);
        ElideCompilerArgumentProvider arguments = task.getProject().getObjects()
                .newInstance(ElideCompilerArgumentProvider.class);
        arguments.getElideExecutable().set(resolution.executable());
        ElideTaskInputs.runtime(task, resolution);
        task.getInputs().property("elide.launcherJavaVersion", System.getProperty("java.runtime.version"));
        task.getInputs().property("elide.runtimeVersion", extension.getRuntimeVersion());
        for (String name : List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            task.getInputs().property("elide.environment." + name,
                    task.getProject().getProviders().environmentVariable(name).orElse(""));
        }
        arguments.getPersistentCompiler().set(extension.getPersistentCompiler());
        arguments.getDependencyMode().set(extension.getDependencyMode());
        var compilerService = task.getProject().getGradle().getSharedServices().registerIfAbsent(
                "elideCompiler", ElideCompilerService.class, ignored -> {});
        arguments.getCompilerService().set(compilerService);
        arguments.getWorkerClasspath().from(task.getClasspath());
        task.usesService(compilerService);
        arguments.getLauncherClasspath().from(compilerLauncherFile());
        arguments.getForwardedJvmArguments().set(task.getProject().provider(() -> {
            List<String> configured = options.getForkOptions().getJvmArgs();
            return configured == null ? List.of() : List.copyOf(configured);
        }));
        options.getForkOptions().getJvmArgumentProviders().add(arguments);
        task.doFirst(ignored -> {
            arguments.getForwardedJvmArguments().finalizeValue();
            options.getForkOptions().setJvmArgs(List.of());
            // The executable override is execution machinery. Compiler identity is fingerprinted
            // by ElideCompilerArgumentProvider; keeping this unset during snapshotting avoids
            // Gradle's blanket cache exclusion for otherwise untracked custom compilers.
            var compiler = task.getJavaCompiler().getOrNull();
            File home = compiler == null ? new File(System.getProperty("java.home"))
                    : compiler.getMetadata().getInstallationPath().getAsFile();
            options.getForkOptions().setExecutable(new File(home, "bin/" +
                    (System.getProperty("os.name").toLowerCase().startsWith("windows")
                            ? "java.exe" : "java")).getAbsolutePath());
        });
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
