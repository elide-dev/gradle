package dev.elide.gradle.task.download

import dev.elide.gradle.configuration.binary.ElideBinaryResolutionSource
import dev.sigstore.KeylessVerificationException
import dev.sigstore.KeylessVerifier
import dev.sigstore.VerificationOptions
import dev.sigstore.bundle.Bundle
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

public abstract class VerifyElideBinaryTask : DefaultTask() {

    init {
        group = "Elide"
        description = "Verifies the downloaded by `downloadElideCli` task archive's signature."
    }

    /**
     * The directory where the downloaded files (archive and signature) will be saved.
     * This directory is cleared before each download attempt.
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
    @get:PathSensitive(PathSensitivity.RELATIVE)
    private val archiveFile: Property<RegularFile> = targetDirectory.file("elide.zip") as Property<RegularFile>

    /**
     * The output file representing the signature bundle for the Elide binary archive (`elide.zip.sigstore`).
     * Declared as output for Gradle incremental build support.
     *
     * @see DownloadElideBinaryTask.sigstoreFile
     */
    @get:Internal
    @get:PathSensitive(PathSensitivity.RELATIVE)
    private val sigstoreFile: Property<RegularFile> =
        targetDirectory.file("elide.zip.sigstore") as Property<RegularFile>

    init {
        onlyIf("archive file and sigstore file is not present") { archiveFile.get().asFile.exists() && sigstoreFile.get().asFile.exists() }
    }

    @TaskAction
    public fun verify() {
        val bundle = Bundle.from(sigstoreFile.get().asFile.toPath(), Charsets.UTF_8)
        val verifier = KeylessVerifier.builder()
            .sigstorePublicDefaults()
            .build()

        try {
            verifier.verify(
                archiveFile.get().asFile.toPath(),
                bundle,
                VerificationOptions.builder().build()
            )
        } catch (e: KeylessVerificationException) {
            archiveFile.get().asFile.delete()
            sigstoreFile.get().asFile.delete()
            // clean up invalid archive and .sigstore
            throw IllegalStateException(
                "Downloaded `${archiveFile.get().asFile.absolutePath}` failed to be verified",
                e
            )
        }

        sigstoreFile.get().asFile.delete()
    }
}