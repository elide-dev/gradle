package dev.elide.gradle.cli

import dev.elide.gradle.cli.ElideCliInvocationResult.*
import org.gradle.api.Transformer
import org.gradle.api.provider.Provider
import java.io.InputStream
import java.io.OutputStream
import java.util.function.Consumer
import java.util.stream.Stream
import kotlin.streams.asStream

/**
 * Represents a lazily evaluated invocation of the Elide CLI, encapsulating both the execution result and
 * live access to the process's standard output and error streams.
 *
 * @param T The type of the successful invocation result value.
 * @property operation A [Provider] that, when queried, executes the CLI invocation and returns its result.
 * @property stdOut The [OutputStream] receiving the live standard output of the CLI process.
 * @property stdErr The [OutputStream] receiving the live standard error output of the CLI process.
 *
 * This class supports functional-style transformations of the invocation result while preserving
 * streaming output access. It does not buffer output by default, enabling live logging or processing.
 *
 * Usage pattern:
 * ```
 * val invocation: ElideCliInvocation<MyResultType> = ...
 * val result = invocation.execute()
 * ```
 *
 * Transformations like `mapSuccess` and `map` create new invocations with transformed results,
 * allowing composable processing pipelines.
 *
 * The provided methods `withCapturedStdout()` and `withCapturedStdoutAndStderr()` enable optional
 * capturing of output streams into strings, while still streaming data live to the original streams.
 */
public class ElideCliInvocation<T : Any> internal constructor(
    private val operation: Provider<ElideCliInvocationResultWithThreadPool<T>>,
    public val stdOut: InputStream,
    public val stdErr: InputStream,
) {
    /**
     * Executes the CLI invocation by querying the underlying [operation] provider.
     * This triggers the actual process execution and returns the result.
     *
     * @return The result of the invocation, either [ElideCliInvocationResult.Success] or [ElideCliInvocationResult.ExitFailure].
     */
    public fun execute(): ElideCliInvocationResult<T> = operation.get().result

    /**
     * Transforms the successful result value using the provided [transformer].
     * If the invocation failed, the failure is propagated unchanged.
     *
     * @param R The type of the transformed success value.
     * @param transformer The transformation function from [T] to [R].
     * @return A new [ElideCliInvocation] with the transformed result type.
     */
    public fun <R : Any> mapSuccess(
        transformer: Transformer<R, T>,
    ): ElideCliInvocation<R> {
        return map { (_, result) ->
            when (result) {
                is ExitFailure, is Error -> result as ElideCliInvocationResult<R>
                is Success<T> -> Success(transformer.transform(result.value)) as ElideCliInvocationResult<R>
            }
        }
    }

    /**
     * Consumes the standard output (stdout) of the invocation by passing a [Stream] of lines
     * to the given [consumer]. The lines are read lazily and provided within the executor's
     * context to ensure asynchronous execution.
     *
     * This method does not block the calling thread; instead, it schedules the consumption
     * of stdout on the associated executor. The original invocation result is returned unchanged.
     *
     * **Warning**: [stdOut] as well as [stdErr] is one-shot by default.
     *
     * @param consumer A [Consumer] that accepts a [Stream] of stdout lines.
     * @return A new [ElideCliInvocation] with the same result type [T], where stdout consumption
     *         is performed asynchronously as a side effect.
     */
    public fun consumeStdout(consumer: Consumer<Stream<String>>): ElideCliInvocation<T> {
        return map { (executor, result) ->
            executor.execute {
                stdOut.bufferedReader().useLines { lines ->
                    consumer.accept(lines.asStream())
                }
            }

            result
        }
    }

    /**
     * Consumes the standard error (stderr) output of the invocation by passing a [Stream] of lines
     * to the given [consumer]. The lines are read lazily and provided within the executor's
     * context to ensure asynchronous execution.
     *
     * This method does not block the calling thread; instead, it schedules the consumption
     * of stderr on the associated executor. The original invocation result is returned unchanged.
     *
     * @param consumer A [Consumer] that accepts a [Stream] of stderr lines.
     * @return A new [ElideCliInvocation] with the same result type [T], where stderr consumption
     *         is performed asynchronously as a side effect.
     */
    public fun consumeStderr(consumer: Consumer<Stream<String>>): ElideCliInvocation<T> {
        return map { (executor, result) ->
            executor.execute {
                stdErr.bufferedReader().useLines { lines ->
                    consumer.accept(lines.asStream())
                }
            }

            result
        }
    }

    /**
     * Invokes the provided [consumer] if the invocation was successful,
     * passing it the success value.
     *
     * This allows side effect handling on success without modifying the result.
     *
     * @param consumer a [Consumer] that accepts the success value of type [T].
     * @return a new [ElideCliInvocation] with the same result type [T].
     */
    public fun onSuccess(consumer: Consumer<T>): ElideCliInvocation<T> {
        return map { (_, result) ->
            if (result is Success<T>) {
                consumer.accept(result.value)
            }
            result
        }
    }

    /**
     * Invokes the provided [consumer] if the invocation failed with a non-zero exit code,
     * passing it the exit code.
     *
     * This allows side-effect handling on process failures without modifying the result.
     *
     * @param consumer a [Consumer] that accepts the non-success exit code.
     * @return a new [ElideCliInvocation] with the same result type [T].
     */
    public fun onNonZeroExitCode(consumer: Consumer<Int>): ElideCliInvocation<T> {
        return map { (_, result) ->
            if (result is ExitFailure) {
                consumer.accept(result.exitCode)
            }
            result
        }
    }

    /**
     * Invokes the provided [consumer] if the invocation resulted in an exception,
     * passing it the thrown [Exception].
     *
     * This allows side-effect handling on execution errors without modifying the result.
     *
     * @param consumer a [Consumer] that accepts the [Exception] thrown during invocation.
     * @return a new [ElideCliInvocation] with the same result type [T].
     */
    public fun onException(consumer: Consumer<Exception>): ElideCliInvocation<T> {
        return map { (_, result) ->
            if (result is Error) {
                consumer.accept(result.exception)
            }
            result
        }
    }

    /**
     * Transforms the entire invocation result using the provided [transformer].
     *
     * @param R The type of the transformed invocation result's success value.
     * @param transformer The transformation function from [ElideCliInvocationResult<T>] to [ElideCliInvocationResult<R>].
     * @return A new [ElideCliInvocation] with the transformed result type.
     */
    public fun <R : Any> map(
        transformer: Transformer<ElideCliInvocationResult<R>, ElideCliInvocationResultWithThreadPool<T>>
    ): ElideCliInvocation<R> {
        return ElideCliInvocation(
            operation = operation.map { input ->
                val result = transformer.transform(input)
                ElideCliInvocationResultWithThreadPool(input.executor, result)
            },
            stdOut = stdOut,
            stdErr = stdErr,
        )
    }

    /**
     * Returns a new [ElideCliInvocation] that captures the standard output into a [String]
     * while still streaming it live to the original [stdOut].
     *
     * The captured output is available as the success value of the transformed invocation result.
     *
     * @return A new [ElideCliInvocation] with the success value being the captured standard output as a trimmed string.
     */
    public fun withCapturedStdout(): ElideCliInvocation<String> {
        return map { (pool, result) ->
            val captureBuffer = StringBuilder()

            pool.execute {
                stdOut.bufferedReader().useLines { lines ->
                    lines.forEach {
                        captureBuffer.append(it)
                    }
                }
            }

            when (result) {
                is ExitFailure, is Error -> result
                is Success<*> -> {
                    val output = captureBuffer.toString()
                    Success(output.trim())
                }
            } as ElideCliInvocationResult<String>
        }
    }

    /**
     * Returns a new [ElideCliInvocation] that captures the standard and error output into a [String].
     *
     * The captured output is available as the success value of the transformed invocation result.
     *
     * @return A new [ElideCliInvocation] with the success value being the captured standard output as a trimmed string.
     */
    public fun withCapturedStdoutAndStderr(): ElideCliInvocation<String> {
        return map { (pool, result) ->
            val captureBuffer = StringBuffer()

            pool.execute {
                stdOut.bufferedReader().useLines { lines ->
                    lines.forEach {
                        captureBuffer.append(it)
                    }
                }
            }

            pool.execute {
                stdErr.bufferedReader().useLines { lines ->
                    lines.forEach {
                        captureBuffer.append(it)
                    }
                }
            }

            when (result) {
                is ExitFailure, is Error -> result
                is Success<*> -> {
                    val output = captureBuffer.toString()
                    Success(output.trim())
                }
            } as ElideCliInvocationResult<String>
        }
    }

    @JvmOverloads
    public fun buffered(stdOutBufferSize: Int = DEFAULT_BUFFER_SIZE, stdErrBufferSize: Int = DEFAULT_BUFFER_SIZE): ElideCliInvocation<T> {
        return ElideCliInvocation(operation, stdOut.buffered(stdOutBufferSize), stdErr.buffered(stdOutBufferSize))
    }
}

