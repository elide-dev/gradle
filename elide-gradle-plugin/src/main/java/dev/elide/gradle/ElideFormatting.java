package dev.elide.gradle;

import org.gradle.api.Project;

import java.util.List;

final class ElideFormatting {
    static void configure(Project project, ElideRuntimeResolution runtime) {
        var javaSources = project.fileTree("src", tree -> tree.include("**/*.java"));
        var kotlinSources = project.fileTree("src", tree -> tree.include("**/*.kt", "**/*.kts"));
        var java =
                project.getTasks()
                        .register(
                                "elideJavaFormat",
                                ElideFormatTask.class,
                                task -> {
                                    task.setGroup("formatting");
                                    task.setDescription(
                                            "Formats Java source copies with Elide javaformat.");
                                    task.getTool().set("javaformat");
                                    task.getSources().from(javaSources);
                                    task.getSourceRoot()
                                            .set(project.getLayout().getProjectDirectory());
                                    task.getDestinationDirectory()
                                            .set(
                                                    project.getLayout()
                                                            .getBuildDirectory()
                                                            .dir("elide/formatted/java"));
                                    task.getElideExecutable().set(runtime.executable());
                                    ElideTaskInputs.runtime(task, runtime);
                                    task.dependsOn(runtime.preparationTask());
                                });
        var kotlin =
                project.getTasks()
                        .register(
                                "elideKtfmt",
                                ElideFormatTask.class,
                                task -> {
                                    task.setGroup("formatting");
                                    task.setDescription(
                                            "Formats Kotlin source copies with Elide ktfmt.");
                                    task.getTool().set("ktfmt");
                                    task.getSources().from(kotlinSources);
                                    task.getSourceRoot()
                                            .set(project.getLayout().getProjectDirectory());
                                    task.getDestinationDirectory()
                                            .set(
                                                    project.getLayout()
                                                            .getBuildDirectory()
                                                            .dir("elide/formatted/kotlin"));
                                    task.getElideExecutable().set(runtime.executable());
                                    ElideTaskInputs.runtime(task, runtime);
                                    task.dependsOn(runtime.preparationTask());
                                });
        for (String name : List.of("elideCheckFormat", "elideFormat")) {
            project.getTasks()
                    .register(
                            name,
                            ElideFormatSourcesTask.class,
                            task -> {
                                task.setGroup("formatting");
                                task.setDescription(
                                        name.equals("elideFormat")
                                                ? "Applies Elide formatting to project sources."
                                                : "Checks project formatting without modifying"
                                                      + " sources.");
                                task.getApplyChanges().set(name.equals("elideFormat"));
                                task.getSourceRoot().set(project.getLayout().getProjectDirectory());
                                task.getSources().from(javaSources, kotlinSources);
                                task.getFormattedDirectories()
                                        .from(
                                                java.flatMap(
                                                        ElideFormatTask::getDestinationDirectory),
                                                kotlin.flatMap(
                                                        ElideFormatTask::getDestinationDirectory));
                            });
        }
    }
}
