package dev.elide.gradle.internal.exception

internal class ElideUserFailureException : Exception(
    "An error in code / configuration is present. Check logs with either `--debug` flag or " +
    "`elide.diagnostics.debug = true` for more information."
)