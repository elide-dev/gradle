package dev.elide.gradle.internal.exception

internal class ElideGenericFailureException : Exception(
    "An internal elide cli error has occurred. Check logs with either `--debug` flag or " +
    "`elide.diagnostics.debug = true` for more information."
)