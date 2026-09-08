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
        Provider<String> managedVersion = project.provider(() -> requireManagedVersion(extension));
        Provider<ElideRuntimeSelection> selection = project.provider(() -> {
            ElideRuntimeMode mode = effectiveMode(extension);
            java.util.Optional<Path> explicit = extension.getElideBin().isPresent()
                    ? java.util.Optional.of(extension.getElideBin().get().getAsFile().toPath())
                    : java.util.Optional.empty();
            java.util.Optional<Path> installed = ElideRuntimeLocator.findInstalled(
                    explicit, pathDirectories(project), platform);
            if (mode != ElideRuntimeMode.MANAGED && installed.isPresent()) {
                return new ElideRuntimeSelection(
                        explicit.filter(installed.get()::equals).isPresent()
                                ? ElideRuntimeSource.EXPLICIT
                                : ElideRuntimeSource.PATH,
                        installed.get());
            }
            if (mode == ElideRuntimeMode.PATH) {
                throw new IllegalStateException("Elide PATH runtime was requested but no executable was found");
            }
            return new ElideRuntimeSelection(
                    ElideRuntimeSource.MANAGED,
                    managedExecutable(project, managedVersion.get(), platform));
        });
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
        Provider<String> managedVersion = project.provider(() -> requireManagedVersion(extension));
        Provider<java.io.File> runtimeDirectory = managedVersion.map(version ->
                managedExecutable(project, version, platform).getParent().getParent().toFile());
        return project.getTasks().register(
                ElideTaskName.ELIDE_RUNTIME_PREPARE, PrepareElideRuntimeTask.class, task -> {
            task.setGroup("Elide");
            task.setDescription("Downloads and verifies the managed Elide runtime.");
            task.getRuntimeVersion().set(managedVersion);
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

    private static String requireManagedVersion(ElideExtension extension) {
        String version = extension.getRuntimeVersion().getOrNull();
        if (version == null || version.isBlank()) {
            throw new org.gradle.api.GradleException("Elide " + effectiveMode(extension)
                    + " runtime requires a concrete version; configure elide.runtime.version "
                    + "or versionFrom in settings");
        }
        return version;
    }
}
