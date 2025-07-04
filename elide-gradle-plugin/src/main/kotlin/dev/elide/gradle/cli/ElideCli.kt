package dev.elide.gradle.cli

import dev.elide.gradle.internal.elideInfo
import dev.elide.gradle.internal.mapNotNull
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import kotlin.io.path.*

public class ElideCli(
    private val cwd: DirectoryProperty,
    private val bin: Provider<Path>,
    private val providerFactory: ProviderFactory,
) {
    /**
     * Runs `elide --version` in terminal getting the result.
     *
     * @param bin Path to a binary file.
     * @return [String] of the output, excluding error output.
     */
    public fun getVersion(executor: Executor): ElideCliInvocationResult<String> {
        return createInvocation(args = listOf("--version"), executor)
            .withCapturedStdout()
            .mapSuccess { input ->
                input.trim()
            }.execute()
    }

    public fun createInvocation(args: List<String>, executor: Executor): ElideCliInvocation<Unit> {
        try {
            val stdout = ProxyInputStream()
            val stderr = ProxyInputStream()

            val action: Provider<ElideCliInvocationResultWithThreadPool<Unit>> = providerFactory.provider {
                val pathToElide = bin.get().pathString
                val allArgs = listOf(pathToElide) + args
                val cwdFile = cwd.get().asFile

                val builder = ProcessBuilder(allArgs).directory(cwdFile)
                val proc = builder.start()

                stdout.target = proc.inputStream
                stderr.target = proc.errorStream
                val exitCode = proc.waitFor()

                val result = try {
                    if (exitCode == 0) {
                        ElideCliInvocationResult.Success(Unit)
                    } else {
                        ElideCliInvocationResult.ExitFailure(exitCode)
                    }
                } catch (e: Exception) {
                    ElideCliInvocationResult.Error(e)
                }

                ElideCliInvocationResultWithThreadPool(executor, result)
            }

            // Return invocation with PipedInputStreams (to be read by consumer)
            return ElideCliInvocation(action, stdout, stderr)
        } catch (e: InterruptedException) {
            throw IllegalStateException("Failed to wait for Elide process", e)
        } catch (e: IOException) {
            throw IllegalStateException("Failed to start Elide captured process", e)
        }
    }

    internal companion object {
        const val ELIDE_BINARY_NAME: String = "elide"

        /**
         * Resolves a path to elide from $PATH or home directory.
         * Unless present, returns nothing.
         */
        internal fun resolvePathToCli(
            logger: Logger,
            providers: ProviderFactory,
        ): Provider<Path> {
            fun logNonExecutable(path: Path) = logger.elideInfo(
                "Elide binary found at '${path.absolutePathString()}' in \$PATH, but it is not executable. " +
                    "Maybe try `chmod +x ${path.absolutePathString()}`? Skipping."
            )

            val elideFromProperties: Provider<Path> = providers.gradleProperty("dev.elide.gradle.binPath")
                .map {
                    Path(it).also { path ->
                        require(path.exists()) {
                            "Elide binary specified explicitly via `dev.elide.gradle.binPath` does not exist."
                        }
                        require(!path.isDirectory()) {
                            "Elide binary specified via `dev.elide.gradle.binPath` is a directory, but binary executable is expected."
                        }
                        require(path.isExecutable()) {
                            "Elide binary specified via `dev.elide.gradle.binPath` is not an executable. Maybe try `chmod +x ${path.absolutePathString()}`?"
                        }
                    }
                }

            val elideFromPath = providers.environmentVariable("PATH")
                .mapNotNull { value ->
                    value.split(File.pathSeparator)
                        .asSequence()
                        .map { Path(it).resolve(ELIDE_BINARY_NAME) }
                        .onEach {
                            // Let's notify user that elide that is resolved
                            // is not executable.
                            if (!it.isExecutable())
                                logNonExecutable(it)
                        }
                        .filter { it.exists() && it.isExecutable() }
                        .firstOrNull()
                }

            val elideFromUserHome = providers.systemProperty("user.home")
                .mapNotNull { pathText ->
                    Path(pathText).resolve("elide")
                        .resolve(ELIDE_BINARY_NAME)
                        .takeIf { it.exists() && it.isRegularFile() }
                        ?.let { file ->
                            if (!file.isExecutable()) {
                                logNonExecutable(file)
                            } else {
                                return@mapNotNull file
                            }
                        }
                    null
                }

            return elideFromProperties
                .orElse(elideFromPath)
                .orElse(elideFromUserHome)
        }
    }

    private class ProxyInputStream(
        var target: InputStream? = null
    ) : InputStream() {

        override fun read(): Int {
            val current = target
            return if (current != null) {
                current.read()
            } else {
                // Block or idle until assigned — here we just simulate no-read
                // Returning 0 would mean "read 0 bytes" (but InputStream.read() returns a single byte or -1)
                // So we wait until assigned
                while (target == null) {
                    Thread.sleep(10)
                }
                target!!.read()
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val current = target
            return if (current != null) {
                current.read(b, off, len)
            } else {
                while (target == null) {
                    Thread.sleep(10)
                }
                target!!.read(b, off, len)
            }
        }

        override fun available(): Int {
            return target?.available() ?: 0
        }

        override fun close() {
            target?.close()
        }

        override fun skip(n: Long): Long {
            return target?.skip(n) ?: 0
        }

        override fun mark(readlimit: Int) {
            target?.mark(readlimit)
        }

        override fun reset() {
            target?.reset()
        }

        override fun markSupported(): Boolean {
            return target?.markSupported() ?: false
        }
    }
}