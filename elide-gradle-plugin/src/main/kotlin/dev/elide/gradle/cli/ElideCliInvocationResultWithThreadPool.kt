package dev.elide.gradle.cli

import java.util.concurrent.Executor

public data class ElideCliInvocationResultWithThreadPool<T>(
    public val executor: Executor,
    public val result: ElideCliInvocationResult<T>,
)