package dev.elide.gradle.internal

import org.gradle.api.logging.Logger

internal inline fun Logger.elideDebug(message: () -> String) {
    if (isDebugEnabled) {
        elideDebug(message())
    }
}

internal fun Logger.elideInfo(message: String) {
    info("[ELIDE] $message")
}

internal fun Logger.elideLifecycle(message: String) {
    lifecycle("[ELIDE] $message")
}


internal fun Logger.elideDebug(message: String) {
    debug("[ELIDE] $message")
}

internal fun Logger.elideWarn(message: String) {
    warn("[ELIDE] $message")
}

internal fun Logger.elideError(message: String) {
    error("[ELIDE] $message")
}
