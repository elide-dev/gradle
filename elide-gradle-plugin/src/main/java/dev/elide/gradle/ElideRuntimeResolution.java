package dev.elide.gradle;

import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/** A runtime selection expressed in Gradle task-wiring types. */
public record ElideRuntimeResolution(
        Provider<RegularFile> executable,
        Provider<ElideRuntimeSource> source,
        TaskProvider<PrepareElideRuntimeTask> preparationTask) {
}
