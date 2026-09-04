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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        ElideExtension extension = new ElideExtension(project, project.getObjects(), buildConfiguration);
        project.getExtensions().add(ELIDE_EXTENSION_NAME, extension);
        ElideRuntimeResolution resolution = ElideRuntimeResolver.resolve(project, extension);
        resolution.preparationTask().configure(task -> task.usesService(buildConfiguration));
        TaskProvider<ElideExecTask> installTask = configureInstallTask(project, extension, resolution);

        project.afterEvaluate(ignored -> {
            if (enableMavenInstaller(project, extension)) {
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
        List<String> existing = Optional.ofNullable(options.getForkOptions().getJvmArgs()).orElseGet(List::of);
        List<String> arguments = new ArrayList<>(existing.size() + 6);
        String elideExecutable = resolution.executable().get().getAsFile().getAbsolutePath();
        // Gradle probes this executable's parent as a JDK, including for local runtimes.
        // Launch Elide through Java so its installation never needs to contain a JDK.
        options.getForkOptions().setExecutable(javaExecutable().toString());
        arguments.add("-cp");
        arguments.add(compilerLauncherClasspath());
        arguments.add(ElideJavaCompilerLauncher.class.getName());
        arguments.add(elideExecutable);
        arguments.add("javac");
        arguments.add("--");
        arguments.addAll(existing);
        options.getForkOptions().setJvmArgs(arguments);
    }

    private static Path javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().startsWith("windows")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName);
    }

    private static String compilerLauncherClasspath() {
        try {
            return new File(ElideJavaCompilerLauncher.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
                    .getAbsolutePath();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Unable to locate the Elide compiler launcher", exception);
        }
    }

    private TaskProvider<ElideExecTask> configureInstallTask(
            Project project,
            ElideExtension extension,
            ElideRuntimeResolution resolution) {
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
                    task.onlyIf("Elide dependency installation is enabled",
                            ignored -> extension.getEnableInstall().get() || enableMavenInstaller(project, extension));
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

    private boolean enableMavenInstaller(Project project, ElideExtension extension) {
        Object configured = project.findProperty("elide.builder.maven.install.enable");
        if (configured != null) {
            return Boolean.parseBoolean(configured.toString());
        }
        return extension.getEnableInstall().get() && extension.getEnableMavenIntegration().get();
    }

    private boolean enableJavaCompiler(Project project, ElideExtension extension) {
        Object configured = project.findProperty("elide.builder.javac.enable");
        if (configured != null) {
            return Boolean.parseBoolean(configured.toString());
        }
        return extension.getEnableJavaCompiler().get();
    }
}
