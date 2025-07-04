package dev.elide.gradle.cli

public sealed interface ElideCliInvocationResult<out T> {
    public data class Success<T>(
        val value: T,
    ) : ElideCliInvocationResult<T>

    /** Represents a process that completed but exited with a non-zero exit code. */
    public data class ExitFailure(
        val exitCode: Int,
    ) : ElideCliInvocationResult<Nothing>, ElideCliInvocationFailure

    /** Represents an unexpected error during execution (e.g. IO failure). */
    public data class Error(
        val exception: Exception,
    ) : ElideCliInvocationResult<Nothing>, ElideCliInvocationFailure
}

public sealed interface ElideCliInvocationFailure

public fun <T> ElideCliInvocationResult<T>.getSuccessValueOrElse(block: (ElideCliInvocationFailure) -> T): T {
    return (this as? ElideCliInvocationResult.Success<T>)?.value ?: block(this as ElideCliInvocationResult.ExitFailure)
}