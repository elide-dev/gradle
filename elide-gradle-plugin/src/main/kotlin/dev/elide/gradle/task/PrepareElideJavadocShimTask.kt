package dev.elide.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Task that creates a shell script to replace the default `javadoc` executable with a shim
 * that redirects to `elide javadoc ...`. This is used to integrate Elide's Javadoc processor
 * into Gradle builds.
 */
public abstract class PrepareElideJavadocShimTask : DefaultTask() {

    init {
        group = "Elide"
        description = "Creates a shim script to invoke `elide javadoc` instead of the standard Javadoc tool."
    }

    /** Absolute path to the Elide CLI binary. */
    @get:Input
    public abstract val cliPath: Property<String>

    /** Location where the shim script will be written. */
    @get:OutputFile
    public abstract val shimFile: RegularFileProperty

    @TaskAction
    public fun generateShim() {
        val shimFile = shimFile.get().asFile
        val cli = cliPath.get()

        val script = """
            #!/usr/bin/env bash
            exec "$cli" javadoc "$@"
        """.trimIndent()

        shimFile.parentFile.mkdirs()
        shimFile.writeText(script)

        if (!shimFile.setExecutable(true)) {
            throw IllegalStateException(
                "Unable to set executable bit on shim script at ${shimFile.absolutePath}"
            )
        }
    }
}