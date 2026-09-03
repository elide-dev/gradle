package dev.elide.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Gradle integration for Elide runtime selection, compilation, and dependency installation. */
public class ElideGradlePlugin implements Plugin<Project> {
    private static final String JAVA_PLUGIN_ID = "java";
    private static final String ELIDE_EXTENSION_NAME = "elide";

    @Override
    public void apply(Project project) {
        ElideExtension extension = new ElideExtension(project, project.getObjects());
        project.getExtensions().add(ELIDE_EXTENSION_NAME, extension);

        project.afterEvaluate(ignored -> configure(project, extension));
    }

    private void configure(Project project, ElideExtension extension) {
        ElideRuntimeResolution resolution = ElideRuntimeResolver.resolve(project, extension);
        boolean mavenInstallerEnabled = enableMavenInstaller(project, extension);
        boolean installationEnabled = extension.getEnableInstall().get() || mavenInstallerEnabled;
        TaskProvider<ElideExecTask> installTask = configureInstallTask(
                project, extension, resolution, installationEnabled);

        if (mavenInstallerEnabled) {
            installMavenDepsSupport(project, extension);
        }
        if (!project.getPluginManager().hasPlugin(JAVA_PLUGIN_ID) || !enableJavaCompiler(project, extension)) {
            return;
        }

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            configureJavaCompileToUseElide(task, resolution);
            addManagedPreparationDependency(task, resolution);
            if (installTask != null) {
                task.dependsOn(installTask);
            }
        });
    }

    private void configureJavaCompileToUseElide(JavaCompile task, ElideRuntimeResolution resolution) {
        var options = task.getOptions();
        options.setFork(true);
        options.getForkOptions().setExecutable(
                resolution.executable().get().getAsFile().getAbsolutePath());
        List<String> existing = Optional.ofNullable(options.getForkOptions().getJvmArgs()).orElseGet(List::of);
        List<String> arguments = new ArrayList<>(existing.size() + 2);
        arguments.add("javac");
        arguments.add("--");
        arguments.addAll(existing);
        options.getForkOptions().setJvmArgs(arguments);
    }

    private TaskProvider<ElideExecTask> configureInstallTask(
            Project project,
            ElideExtension extension,
            ElideRuntimeResolution resolution,
            boolean installationEnabled) {
        if (!installationEnabled) {
            return null;
        }

        return project.getTasks().register(
                ElideTaskName.ELIDE_TASK_INSTALL,
                ElideExecTask.class,
                task -> {
                    task.setGroup("Elide");
                    task.setDescription("Runs `elide install` to prepare the project for compilation.");
                    task.getElideExecutable().set(resolution.executable());
                    task.getElideArguments().set(List.of("install"));
                    task.getWorkingDirectory().set(project.getLayout().getProjectDirectory());
                    task.getInputs().file(extension.getManifest())
                            .withPropertyName("manifest")
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                            .optional();
                    task.getInputs().dir(extension.getDevRoot())
                            .withPropertyName("devRoot")
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                            .optional();
                    task.getOutputs().file(extension.resolveLockfilePath())
                            .withPropertyName("lockfile");
                    task.getOutputs().dir(extension.getDevRoot().dir("dependencies/m2"))
                            .withPropertyName("dependencyRepository");
                    addManagedPreparationDependency(task, resolution);
                });
    }

    private void addManagedPreparationDependency(Task task, ElideRuntimeResolution resolution) {
        if (resolution.source() == ElideRuntimeSource.MANAGED) {
            resolution.preparationTask().ifPresent(task::dependsOn);
        }
    }

    private void installMavenDepsSupport(Project project, ElideExtension extension) {
        project.getRepositories().maven(repository -> {
            repository.setName("elide");
            repository.setUrl(URI.create("file://" + extension.resolveLocalDepsPath().toAbsolutePath()));
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
