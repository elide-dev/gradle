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
        Provider<ElideRuntimeSelection> selection = project.provider(() -> ElideRuntimeLocator.locate(
                effectiveMode(extension),
                extension.getElideBin().isPresent()
                        ? java.util.Optional.of(extension.getElideBin().get().getAsFile().toPath())
                        : java.util.Optional.empty(),
                pathDirectories(project),
                managedExecutable(project, extension.getRuntimeVersion().get(), platform),
                platform));
        Provider<RegularFile> executable = project.getLayout().file(
                selection.map(selected -> selected.executable().toFile()));
        Provider<ElideRuntimeSource> source = selection.map(ElideRuntimeSelection::source);
        TaskProvider<PrepareElideRuntimeTask> preparationTask = registerManagedPreparation(
                project, extension, platform, source);
        return new ElideRuntimeResolution(executable, source, preparationTask);
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

    private static Path managedExecutable(Project project, String version, ElidePlatform platform) {
        return project.getGradle().getGradleUserHomeDir().toPath()
                .resolve("caches")
                .resolve("dev.elide")
                .resolve("runtimes")
                .resolve(version)
                .resolve(platform.key())
                .resolve("bin")
                .resolve(platform.executableName());
    }

    private static TaskProvider<PrepareElideRuntimeTask> registerManagedPreparation(
            Project project,
            ElideExtension extension,
            ElidePlatform platform,
            Provider<ElideRuntimeSource> source) {
        Provider<java.io.File> runtimeDirectory = extension.getRuntimeVersion().map(version ->
                managedExecutable(project, version, platform).getParent().getParent().toFile());
        return project.getTasks().register(
                ElideTaskName.ELIDE_RUNTIME_PREPARE, PrepareElideRuntimeTask.class, task -> {
            task.setGroup("Elide");
            task.setDescription("Downloads and verifies the managed Elide runtime.");
            task.getRuntimeVersion().set(extension.getRuntimeVersion());
            task.getPlatformOs().set(platform.os());
            task.getPlatformArch().set(platform.arch());
            task.getArchiveExtension().set(platform.archiveExtension());
            task.getExecutableName().set(platform.executableName());
            task.getRuntimeSource().set(source);
            task.getReleaseBaseUri().set(project.getProviders()
                    .systemProperty(TEST_RELEASE_BASE_URI_PROPERTY)
                    .orElse(DEFAULT_RELEASE_BASE_URI.toString()));
            task.getOffline().set(project.getGradle().getStartParameter().isOffline());
            task.getRuntimeDirectory().set(project.getLayout().dir(runtimeDirectory));
        });
    }

    static URI releaseBaseUri(Project project) {
        return URI.create(project.getProviders().systemProperty(TEST_RELEASE_BASE_URI_PROPERTY)
                .getOrElse(DEFAULT_RELEASE_BASE_URI.toString()));
    }
}
