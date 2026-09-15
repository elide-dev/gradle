package dev.elide.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.List;
import java.util.regex.Pattern;

/** Resolves Elide runtime inputs using Gradle-managed providers without starting a process. */
public final class ElideRuntimeResolver {
    private static final URI DEFAULT_RELEASE_BASE_URI =
            URI.create("https://github.com/elide-dev/elide/releases/download");
    static final String TEST_RELEASE_BASE_URI_PROPERTY = "dev.elide.gradle.test.releaseBaseUri";
    private static final Logger LOGGER = Logging.getLogger(ElideRuntimeResolver.class);
    private ElideRuntimeResolver() {
    }

    public static ElideRuntimeResolution resolve(
            Project project, ElideExtension extension, Provider<ElideBuildConfiguration> buildConfiguration) {
        ElidePlatform platform = ElidePlatform.detect(
                System.getProperty("os.name"),
                System.getProperty("os.arch"));
        Provider<String> managedVersion = project.provider(() -> requireManagedVersion(extension));
        ElideVersionProbe versionProbe = versionProbe(project, buildConfiguration);
        Provider<ElideRuntimeSelection> selection = project.provider(() -> {
            ElideRuntimeMode mode = effectiveMode(extension);
            java.util.Optional<Path> explicit = extension.getElideBin().isPresent()
                    ? java.util.Optional.of(extension.getElideBin().get().getAsFile().toPath())
                    : java.util.Optional.empty();
            return ElideRuntimeLocator.locate(
                    mode,
                    explicit,
                    pathDirectories(project),
                    () -> managedExecutable(project, managedVersion.get(), platform),
                    platform,
                    () -> requiredRuntimeVersion(extension),
                    versionProbe);
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

    /**
     * The floor a PATH runtime must meet: the version the build would otherwise provision.
     *
     * <p>Resolving it can fail on its own -- a catalog-backed version throws for a missing catalog
     * or alias -- and modes that never provision must not be broken by that. Such a failure yields
     * {@code null}, meaning "no floor", which restores the pre-version-check behaviour of accepting
     * the first usable candidate rather than failing a build that never needed a managed runtime.
     */
    private static ElideVersion requiredRuntimeVersion(ElideExtension extension) {
        String configured;
        try {
            configured = extension.getRuntimeVersion().getOrNull();
        } catch (RuntimeException exception) {
            LOGGER.info("Unable to resolve the configured Elide runtime version, so the PATH runtime "
                    + "version check is skipped: {}", exception.getMessage());
            return null;
        }
        return ElideVersion.parse(configured == null ? "" : configured)
                .orElseGet(() -> ElideVersion.parse(ElideExtension.DEFAULT_RUNTIME_VERSION).orElseThrow());
    }

    /**
     * Probes a candidate with {@code --version}. Uses Gradle's own exec provider so the result is a
     * tracked configuration-cache input rather than a value baked into the cache entry, and memoizes
     * through the shared build service so the whole build probes each candidate at most once rather
     * than once per project.
     *
     * <p>Output is only trusted when the process exits {@code 0}; a foreign binary that rejects
     * {@code --version} and prints a usage banner must not have a number from that banner read as
     * its version.
     */
    private static ElideVersionProbe versionProbe(
            Project project, Provider<ElideBuildConfiguration> buildConfiguration) {
        return executable -> buildConfiguration.get().probedRuntimeVersion(executable, candidate -> {
            try {
                var output = project.getProviders().exec(spec -> {
                    spec.setExecutable(candidate.toFile());
                    spec.args("--version");
                    spec.setIgnoreExitValue(true);
                });
                if (output.getResult().get().getExitValue() != 0) {
                    return Optional.empty();
                }
                return Optional.ofNullable(output.getStandardOutput().getAsText().getOrNull())
                        .filter(text -> !text.isBlank());
            } catch (RuntimeException exception) {
                // An unreadable candidate is simply not usable; selection continues past it.
                return Optional.empty();
            }
        });
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
