package dev.elide.gradle.task.download

import dev.elide.gradle.configuration.binary.ElideBinaryResolutionSource
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import kotlin.io.path.deleteIfExists

public abstract class ExtractElideBinaryTask : Copy() {
    /**
     * The directory where the downloaded files (archive and signature) will be saved.
     * This directory is cleared before each download attempt.
     *
     * @see ElideBinaryResolutionSource
     */
    @get:OutputDirectory
    public abstract val targetDirectory: DirectoryProperty

    /**
     * The output file representing the downloaded Elide binary archive (`elide.zip`).
     * Declared as internal not to compute hashes every time.
     *
     * @see DownloadElideBinaryTask.archiveFile
     */
    @get:Internal
    private val archiveFile: Property<RegularFile> = targetDirectory.file("elide.zip") as Property<RegularFile>

    /**
     * The output file representing the signature bundle for the Elide binary archive (`elide.zip.sigstore`).
     * Declared as output for Gradle incremental build support.
     *
     * @see DownloadElideBinaryTask.sigstoreFile
     */
    @get:Internal
    private val sigstoreFile: Property<RegularFile> =
        targetDirectory.file("elide.zip.sigstore") as Property<RegularFile>

    init {
        onlyIf("Archive file does not exist") { archiveFile.get().asFile.exists() }

        val archiveFile = archiveFile.get().asFile.toPath()

        from(archiveFile)
        into(targetDirectory)

        doLast {
            archiveFile.deleteIfExists()
            sigstoreFile.get().asFile.delete()
        }
    }
}