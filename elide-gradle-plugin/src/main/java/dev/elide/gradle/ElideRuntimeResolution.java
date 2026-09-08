package dev.elide.gradle;

import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.util.Optional;

/** A runtime selection expressed in Gradle task-wiring types. */
public record ElideRuntimeResolution(
        Provider<RegularFile> executable,
        ElideRuntimeSource source,
        Optional<TaskProvider<? extends Task>> preparationTask) {
}
