package dev.elide.gradle.task.download

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.absolutePathString
import kotlin.io.path.isExecutable
import kotlin.io.path.setPosixFilePermissions

@DisableCachingByDefault(because = "Computing inputs are heavier than checking file on executability.")
public abstract class PrepareDownloadedElideBinary : DefaultTask() {
    init {
        group = "Elide"
        description = "Sets executable flag on the downloaded binary"
    }

    private companion object {
        val binPerms: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        )
    }
    /**
     * Downloaded and extracted Elide's binary.
     *
     * Internal is because computing inputs are heavier than a checking file on executability and actually setting it.
     *
     * @see ExtractElideBinaryTask
     */
    @get:Internal
    public abstract val downloadedElideBinary: RegularFileProperty

    init {
        onlyIf("Downloaded binary is not found.") { downloadedElideBinary.asFile.get().exists() }
        onlyIf("Downloaded binary is already executable.") { !downloadedElideBinary.asFile.get().toPath().isExecutable() }
    }

    @TaskAction
    public fun prepare() {
        val file = downloadedElideBinary.asFile.get().toPath()

        try {
            if (!System.getProperty("os.name").contains("windows", ignoreCase = true))
                file.setPosixFilePermissions(binPerms)
        } catch (e: Exception) {
            throw IllegalStateException("Unable to make ${file.absolutePathString()} executable; try `sudo chmod +x ${file.absolutePathString()}` yourself.", e)
        }
    }
}