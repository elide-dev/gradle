package dev.elide.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves Elide runtime inputs using Gradle-managed providers without starting a process. */
public final class ElideRuntimeResolver {
    private static final URI DEFAULT_RELEASE_BASE_URI =
            URI.create("https://github.com/elide-dev/elide/releases/download");
    static final String TEST_RELEASE_BASE_URI_PROPERTY = "dev.elide.gradle.test.releaseBaseUri";
    private ElideRuntimeResolver() {
    }

    public static ElideRuntimeResolution resolve(Project project, ElideExtension extension) {
        ElidePlatform platform = ElidePlatform.detect(
                System.getProperty("os.name"),
                System.getProperty("os.arch"));
        ElideRuntimeMode mode = effectiveMode(extension);
        Optional<Path> explicit = extension.getElideBin().isPresent()
                ? Optional.of(extension.getElideBin().get().getAsFile().toPath())
                : Optional.empty();
        List<Path> pathDirectories = pathDirectories(project);
        Path managedExecutable = managedExecutable(project, extension, platform);
        ElideRuntimeSelection selection = ElideRuntimeLocator.locate(
                mode,
                explicit,
                pathDirectories,
                managedExecutable,
                platform);
        Provider<RegularFile> executable = project.getLayout().file(
                project.provider(() -> selection.executable().toFile()));

        Optional<TaskProvider<? extends org.gradle.api.Task>> preparationTask = selection.source()
                == ElideRuntimeSource.MANAGED
                ? Optional.of(registerManagedPreparation(project, extension, platform))
                : Optional.empty();
        return new ElideRuntimeResolution(executable, selection.source(), preparationTask);
    }

    @SuppressWarnings("deprecation")
    private static ElideRuntimeMode effectiveMode(ElideExtension extension) {
        if (extension.getResolveElideFromPath().isPresent()) {
            return extension.getResolveElideFromPath().get()
                    ? ElideRuntimeMode.PATH
                    : ElideRuntimeMode.MANAGED;
        }
        return extension.getRuntimeMode().get();
    }

    private static List<Path> pathDirectories(Project project) {
        String path = project.getProviders().environmentVariable("PATH").getOrElse("");
        return Arrays.stream(path.split(Pattern.quote(File.pathSeparator)))
                .filter(directory -> !directory.isEmpty())
                .map(Path::of)
                .toList();
    }

    private static Path managedExecutable(Project project, ElideExtension extension, ElidePlatform platform) {
        return project.getGradle().getGradleUserHomeDir().toPath()
                .resolve("caches")
                .resolve("dev.elide")
                .resolve("runtimes")
                .resolve(extension.getRuntimeVersion().get())
                .resolve(platform.key())
                .resolve("bin")
                .resolve(platform.executableName());
    }

    private static TaskProvider<PrepareElideRuntimeTask> registerManagedPreparation(
            Project project, ElideExtension extension, ElidePlatform platform) {
        Path runtimeDirectory = managedExecutable(project, extension, platform).getParent().getParent();
        return project.getTasks().register("prepareElideRuntime", PrepareElideRuntimeTask.class, task -> {
            task.setGroup("Elide");
            task.setDescription("Downloads and verifies the managed Elide runtime.");
            task.getRuntimeVersion().set(extension.getRuntimeVersion());
            task.getPlatformOs().set(platform.os());
            task.getPlatformArch().set(platform.arch());
            task.getArchiveExtension().set(platform.archiveExtension());
            task.getExecutableName().set(platform.executableName());
            task.getReleaseBaseUri().set(releaseBaseUri(project).toString());
            task.getOffline().set(project.getGradle().getStartParameter().isOffline());
            task.getRuntimeDirectory().set(project.getLayout().dir(project.provider(() -> runtimeDirectory.toFile())));
        });
    }

    static URI releaseBaseUri(Project project) {
        return URI.create(project.getProviders().systemProperty(TEST_RELEASE_BASE_URI_PROPERTY)
                .getOrElse(DEFAULT_RELEASE_BASE_URI.toString()));
    }
}
