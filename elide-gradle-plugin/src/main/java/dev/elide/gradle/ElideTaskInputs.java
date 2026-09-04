package dev.elide.gradle;

import org.gradle.api.Task;
import org.gradle.api.tasks.PathSensitivity;

import java.io.File;

final class ElideTaskInputs {
    static void runtime(Task task, ElideRuntimeResolution resolution) {
        task.getInputs()
                .property(
                        "elide.platform",
                        System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        task.getInputs()
                .files(
                        resolution
                                .source()
                                .zip(
                                        resolution.executable(),
                                        (source, executable) ->
                                                source == ElideRuntimeSource.MANAGED
                                                        ? new File[] {
                                                            new File(
                                                                    executable
                                                                            .getAsFile()
                                                                            .getParentFile()
                                                                            .getParentFile(),
                                                                    ".complete")
                                                        }
                                                        : new File[0]))
                .withPropertyName("elide.distributionChecksum")
                .withPathSensitivity(PathSensitivity.NONE);
    }
}
