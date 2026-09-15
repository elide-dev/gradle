package dev.elide.gradle;

import org.gradle.api.GradleException;
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
import java.util.function.Consumer;
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
                    () -> requiredRuntimeVersion(extension, mode, buildConfiguration),
                    versionProbe,
                    rejectionReporter(buildConfiguration));
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
     * <p>Two distinct things can go wrong, and they are treated differently.
     *
     * <p>Resolution can fail outright -- a catalog-backed version throws for a missing catalog or
     * alias. Only {@code PATH} tolerates that, because it never provisions, so a version it will
     * never download must not break it.
     *
     * <p>A version that resolves but is not a semantic version -- a rich version such as
     * {@code [1.5,2.0)} -- yields no floor in any mode, with a warning.
     *
     * @return the floor, or {@code null} when there is none and the version check must be skipped
     */
    private static ElideVersion requiredRuntimeVersion(
            ElideExtension extension, ElideRuntimeMode mode,
            Provider<ElideBuildConfiguration> buildConfiguration) {
        String configured;
        try {
            configured = extension.getRuntimeVersion().getOrNull();
        } catch (RuntimeException exception) {
            // Gradle wraps the failure in its own property-query exception, so the resolver's
            // own exception type is the cause rather than the thrown type. Anything else is an
            // unrelated failure and must not be swallowed.
            if (mode != ElideRuntimeMode.PATH || !causedByVersionResolution(exception)) {
                throw exception;
            }
            warnOnce(buildConfiguration, "Unable to resolve the configured Elide runtime version, so "
                    + "the PATH runtime version check is skipped: " + exception.getMessage());
            return null;
        }
        if (configured == null || configured.isBlank()) {
            return ElideVersion.parse(ElideExtension.DEFAULT_RUNTIME_VERSION).orElseThrow();
        }
        Optional<ElideVersion> parsed = ElideVersion.parseConfigured(configured);
        if (parsed.isEmpty()) {
            warnOnce(buildConfiguration, "Configured Elide runtime version '" + configured
                    + "' is not a semantic version, so the PATH runtime version check is skipped.");
            return null;
        }
        return parsed.get();
    }

    /**
     * Selection is re-evaluated on every read of the provider wrapping it, and several consumers
     * read it per project, so an unguarded warning repeats itself many times over in a
     * multi-project build.
     */
    private static void warnOnce(Provider<ElideBuildConfiguration> buildConfiguration, String message) {
        if (buildConfiguration.get().shouldReportOnce(message)) {
            LOGGER.warn(message);
        }
    }

    private static boolean causedByVersionResolution(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ElideVersionResolutionException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports a skipped PATH runtime once per build. Selection is re-evaluated on every read of the
     * provider wrapping it, and that provider is read by several consumers, so logging from inside
     * selection would repeat the same line many times over in a multi-project build.
     */
    private static Consumer<String> rejectionReporter(Provider<ElideBuildConfiguration> buildConfiguration) {
        return message -> {
            if (buildConfiguration.get().shouldReportOnce(message)) {
                LOGGER.lifecycle(message);
            }
        };
    }

    /**
     * Probes a candidate with {@code --version}. Uses Gradle's own exec provider so the result is a
     * tracked configuration-cache input rather than a value baked into the cache entry, and memoizes
     * through the shared build service so the whole build probes each candidate at most once rather
     * than once per project.
     *
     * <p>The probe itself is bounded in time and output; see {@link ElideVersionProbeSource}.
     */
    private static ElideVersionProbe versionProbe(
            Project project, Provider<ElideBuildConfiguration> buildConfiguration) {
        return executable -> buildConfiguration.get().probedRuntimeVersion(executable, candidate -> {
            try {
                return Optional.ofNullable(project.getProviders().of(ElideVersionProbeSource.class, spec ->
                                spec.getParameters().getExecutablePath().set(candidate.toString()))
                        .getOrNull());
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
        // The location must resolve even when the configured version does not. Gradle calculates
        // this output property for every build that has the task in its graph, before onlyIf can
        // skip it, so deriving it straight from managedVersion made an unresolvable version fail a
        // PATH build that never provisions anything -- the exact failure the floor tolerance in
        // requiredRuntimeVersion exists to prevent.
        Provider<java.io.File> runtimeDirectory = project.provider(() -> {
            if (source.getOrNull() != ElideRuntimeSource.MANAGED) {
                return project.getLayout().getBuildDirectory()
                        .dir("elide/unprovisioned-runtime").get().getAsFile();
            }
            return managedExecutable(project, managedVersion.get(), platform)
                    .getParent().getParent().toFile();
        });
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
