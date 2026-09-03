package dev.elide.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/** Prepares one checksum-verified Elide distribution in Gradle User Home. */
@DisableCachingByDefault(because = "The runtime cache is shared between independent builds.")
public abstract class PrepareElideRuntimeTask extends DefaultTask {
    @Input
    public abstract Property<String> getRuntimeVersion();

    @Input
    public abstract Property<String> getPlatformOs();

    @Input
    public abstract Property<String> getPlatformArch();

    @Input
    public abstract Property<String> getArchiveExtension();

    @Input
    public abstract Property<String> getExecutableName();

    @Input
    public abstract Property<String> getReleaseBaseUri();

    @Input
    public abstract Property<Boolean> getOffline();

    @OutputDirectory
    public abstract DirectoryProperty getRuntimeDirectory();

    @Inject
    protected abstract ArchiveOperations getArchiveOperations();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void prepareRuntime() throws IOException, InterruptedException {
        Path runtimeDirectory = getRuntimeDirectory().get().getAsFile().toPath();
        Path versionDirectory = runtimeDirectory.getParent();
        Path lockFile = versionDirectory.resolve(runtimeDirectory.getFileName() + ".lock");
        Files.createDirectories(versionDirectory);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lock(channel)) {
            prepareWhileLocked(runtimeDirectory);
        }
    }

    private void prepareWhileLocked(Path runtimeDirectory) throws IOException, InterruptedException {
        ElidePlatform platform = new ElidePlatform(
                getPlatformOs().get(),
                getPlatformArch().get(),
                getArchiveExtension().get(),
                getExecutableName().get());
        Path executable = runtimeDirectory.resolve("bin").resolve(platform.executableName());
        if (isComplete(runtimeDirectory, executable)) {
            return;
        }
        if (getOffline().get()) {
            throw new GradleException("Elide runtime version " + getRuntimeVersion().get()
                    + " is not cached at " + runtimeDirectory);
        }

        Path staging = runtimeDirectory.resolveSibling(runtimeDirectory.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            ElideRelease release = new ElideRelease(
                    java.net.URI.create(getReleaseBaseUri().get()),
                    getRuntimeVersion().get(),
                    platform);
            Path archive = staging.resolve(platform.assetName());
            new ElideDownloader().downloadVerified(release, archive);
            String archiveChecksum = ElideDownloader.sha256(archive);
            extract(archive, staging, platform);
            Path stagedExecutable = staging.resolve("bin").resolve(platform.executableName());
            validateExecutable(stagedExecutable, platform);
            Files.deleteIfExists(archive);
            Files.writeString(staging.resolve(".complete"), archiveChecksum + "\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW);
            deleteRecursively(runtimeDirectory);
            promote(staging, runtimeDirectory);
        } finally {
            deleteRecursively(staging);
        }
    }

    private boolean isComplete(Path runtimeDirectory, Path executable) {
        Path complete = runtimeDirectory.resolve(".complete");
        if (!Files.isRegularFile(complete, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            ElideDownloader.parseSha256(Files.readString(complete, StandardCharsets.US_ASCII));
            return true;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private void extract(Path archive, Path staging, ElidePlatform platform) {
        getFileSystemOperations().copy(copy -> {
            copy.from(platform.archiveExtension().equals("zip")
                    ? getArchiveOperations().zipTree(archive.toFile())
                    : getArchiveOperations().tarTree(archive.toFile()));
            copy.into(staging.toFile());
            copy.setIncludeEmptyDirs(false);
            copy.eachFile(file -> requireSafeDestination(staging, file));
        });
        rejectSymbolicLinks(staging);
    }

    private static void requireSafeDestination(Path staging, FileCopyDetails file) {
        Path destination = staging.resolve(file.getRelativePath().getPathString()).normalize();
        if (!destination.startsWith(staging)) {
            throw new GradleException("Refusing Elide archive entry outside runtime staging directory");
        }
        for (Path parent = destination.getParent(); parent != null && !parent.equals(staging);
             parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) {
                throw new GradleException("Refusing Elide archive entry below symbolic link: " + file.getPath());
            }
        }
    }

    private static void rejectSymbolicLinks(Path staging) {
        try {
            Files.walkFileTree(staging, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (Files.isSymbolicLink(file)) {
                        throw new GradleException("Refusing symbolic link in Elide runtime archive: " + file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new GradleException("Unable to validate extracted Elide runtime", exception);
        }
    }

    private static void validateExecutable(Path executable, ElidePlatform platform) throws IOException {
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw new GradleException("Elide archive does not contain " + executable.getFileName());
        }
        if (!platform.os().equals("windows")) {
            try {
                Files.setPosixFilePermissions(executable, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException exception) {
                throw new GradleException("Unable to set executable permissions for " + executable, exception);
            }
        }
    }

    private static void promote(Path staging, Path runtimeDirectory) throws IOException {
        try {
            Files.move(staging, runtimeDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(staging, runtimeDirectory);
        }
    }

    private static FileLock lock(FileChannel channel) throws IOException, InterruptedException {
        while (true) {
            try {
                return channel.lock();
            } catch (OverlappingFileLockException ignored) {
                Thread.sleep(50);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
