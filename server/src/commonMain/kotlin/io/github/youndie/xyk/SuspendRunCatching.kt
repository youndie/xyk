package io.github.youndie.xyk

import kotlinx.coroutines.CancellationException

/**
 * `runCatching`, with cancellation left alone.
 *
 * The standard one catches `CancellationException` along with everything else, so a cancelled
 * coroutine keeps running inside the block and the caller is handed its own cancellation dressed up
 * as a failure. In a service that stops by cancelling scopes — which is what the kore sequence does
 * — that is the difference between a clean drain and work that carries on past it.
 */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
