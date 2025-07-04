package dev.elide.gradle.task.download

import de.undercouch.gradle.tasks.download.Download
import dev.elide.gradle.cli.ElideCli
import dev.elide.gradle.configuration.binary.ElideBinaryConfiguration
import dev.elide.gradle.configuration.binary.ElideBinaryResolutionSource
import dev.elide.gradle.internal.Platform
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal

public abstract class DownloadElideBinaryTask : Download() {

    /**
     * The strategy used to resolve the Elide binary source.
     * Controls whether the binary should be downloaded, used locally, or other resolution methods.
     *
     * @see ElideBinaryConfiguration.resolutionSource
     */
    @get:Input
    public abstract val resolutionSource: Property<ElideBinaryResolutionSource>

    /**
     * The target version of the Elide binary to download.
     * Changing this will invalidate the cache and trigger re-download.
     *
     * @see ElideBinaryConfiguration.version
     */
    @get:Input
    public abstract val targetVersion: Property<String>

    /**
     * The directory where the downloaded files (archive and signature) will be saved.
     * This directory is cleared before each download attempt.
     *
     * By default, [targetDirectory] is the defined download path in [ElideBinaryResolutionSource] + [targetVersion]
     *
     * @see ElideBinaryResolutionSource
     */
    @get:Internal
    public abstract val targetDirectory: DirectoryProperty

    /**
     * The output file representing the downloaded Elide binary archive (`elide.zip`).
     * Declared as output so Gradle can track task up-to-date checks properly.
     *
     * @see DownloadElideBinaryTask.archiveFile
     */
    @get:Internal
    protected val archiveFile: Property<RegularFile> = targetDirectory.file("elide.zip") as Property<RegularFile>

    /**
     * The output file representing the signature bundle for the Elide binary archive (`elide.zip.sigstore`).
     * Declared as output for Gradle incremental build support.
     *
     * @see DownloadElideBinaryTask.sigstoreFile
     */
    @get:Internal
    protected val sigstoreFile: Property<RegularFile> = targetDirectory.file("elide.zip.sigstore") as Property<RegularFile>

    init {
        group = "Elide"

        // we assume nothing changes on another side, checking hash every build is quite
        // expensive, so we want to avoid it
        outputs.upToDateWhen {
            archiveFile.get().asFile.exists() && sigstoreFile.get().asFile.exists()
        }

        onlyIf("resolutionSource is not defined") {
            resolutionSource.orNull != null
        }
        onlyIf("resolutionSource is local-only") {
            resolutionSource.orNull !is ElideBinaryResolutionSource.LocalOnly
        }
        onlyIf("resolutionSource is prefer-local and local version is matched") {
            ElideCli.resolvePathToCli(logger, project.providers).isPresent
        }
        onlyIf("`elide.binary.version` is undefined") {
            targetVersion.orNull != null
        }

        doFirst {
            targetDirectory.get().asFileTree.forEach { it.delete() }
        }

        val osClassifier = Platform.platformClassifier

        src(
            listOf(
                "https://elide.zip/cli/v1/snapshot/${osClassifier}/${targetVersion.get()}/elide.zip",
                "https://github.com/elide-dev/elide/releases/download/${targetVersion.get()}/elide-${targetVersion.get()}-$osClassifier.zip.sigstore"
            )
        )
        dest(targetDirectory)
        overwrite(false)

        eachFile {
            path = when {
                sourceURL.path.endsWith(".sigstore") -> sigstoreFile.get().asFile.absolutePath
                sourceURL.path.endsWith(".zip") -> archiveFile.get().asFile.absolutePath
                else -> error("Should not reach this state; Expected either .sigstore or .zip files to be downloaded.")
            }
        }
    }
}