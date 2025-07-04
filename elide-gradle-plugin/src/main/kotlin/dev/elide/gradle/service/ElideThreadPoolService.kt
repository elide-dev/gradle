package dev.elide.gradle.service

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal abstract class ElideThreadPoolService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    val executor: ExecutorService = Executors.newCachedThreadPool()

    override fun close() {
        executor.shutdown()
    }
}