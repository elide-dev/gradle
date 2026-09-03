package dev.elide.gradle;

import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves Elide runtime inputs using Gradle-managed providers without starting a process. */
public final class ElideRuntimeResolver {
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

        return new ElideRuntimeResolution(executable, selection.source(), Optional.empty());
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
}
