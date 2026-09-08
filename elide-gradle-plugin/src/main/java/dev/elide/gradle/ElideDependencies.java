package dev.elide.gradle;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class ElideDependencies {
    static void configure(Project project) {
        var aggregate =
                project.getTasks()
                        .register(
                                "elideExportDependencies",
                                task -> {
                                    task.setGroup("Elide");
                                    task.setDescription(
                                            "Exports Gradle-selected JVM artifact coordinates and"
                                                + " SHA-256 checksums.");
                                });
        project.getPluginManager()
                .withPlugin("java", ignored -> configureSourceSets(project, aggregate));
    }

    private static void configureSourceSets(Project project, TaskProvider<Task> aggregate) {
        project.getExtensions()
                .getByType(SourceSetContainer.class)
                .configureEach(
                        sourceSet -> {
                            var report = registerReport(project, sourceSet);
                            aggregate.configure(task -> task.dependsOn(report));
                        });
    }

    private static TaskProvider<ElideDependencyReportTask> registerReport(
            Project project, SourceSet sourceSet) {
        var configuration =
                project.getConfigurations()
                        .getByName(sourceSet.getRuntimeClasspathConfigurationName());
        var artifacts = configuration.getIncoming().getArtifacts();
        String name = sourceSet.getName();
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return project.getTasks()
                .register(
                        "elideExport" + suffix + "Dependencies",
                        ElideDependencyReportTask.class,
                        task -> {
                            task.setGroup("Elide");
                            task.dependsOn(artifacts.getArtifactFiles());
                            task.getArtifacts()
                                    .set(
                                            artifacts
                                                    .getResolvedArtifacts()
                                                    .map(ElideDependencies::describeArtifacts));
                            task.getReportFile()
                                    .set(
                                            project.getLayout()
                                                    .getBuildDirectory()
                                                    .file("elide/dependencies/" + name + ".tsv"));
                        });
    }

    private static List<ElideDependencyReportTask.Artifact> describeArtifacts(
            Set<ResolvedArtifactResult> artifacts) {
        return artifacts.stream()
                .filter(artifact -> artifact.getFile().isFile())
                .map(ElideDependencies::describeArtifact)
                .sorted(
                        Comparator.comparing(ElideDependencyReportTask.Artifact::getCoordinate)
                                .thenComparing(artifact -> artifact.getFile().getName()))
                .toList();
    }

    private static ElideDependencyReportTask.Artifact describeArtifact(
            ResolvedArtifactResult artifact) {
        var id = artifact.getId().getComponentIdentifier();
        String coordinate;
        if (id instanceof ModuleComponentIdentifier module) {
            coordinate = module.getGroup() + ":" + module.getModule() + ":" + module.getVersion();
        } else if (id instanceof ProjectComponentIdentifier project) {
            coordinate = "project:" + project.getProjectPath();
        } else {
            coordinate = "file:" + artifact.getFile().getName();
        }
        return new ElideDependencyReportTask.Artifact(coordinate, artifact.getFile());
    }
}
