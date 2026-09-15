package io.github.youndie.xyk.ingest

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Moves the in-memory rejection counts into the database, on a timer and at shutdown.
 *
 * One statement per (endpoint, reason) that actually changed, not per rejection — which is the whole
 * reason the counters are in memory in the first place.
 */
class RejectionFlush(
    private val db: ISQLite,
    private val counters: RejectionCounters,
    private val interval: Duration = 10.seconds,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job {
        val started =
            scope.launch {
                while (isActive) {
                    delay(interval)
                    flushOnce()
                }
            }
        job = started
        return started
    }

    /**
     * Stops the loop and flushes what is left.
     *
     * In the stop sequence this runs **before the pool closes** and after the engine has drained:
     * the last rejections of a deploy are the ones an operator is most likely to be looking for.
     */
    suspend fun stop() {
        job?.cancel()
        job = null
        flushOnce()
    }

    private suspend fun flushOnce() {
        val pending = counters.drain()
        if (pending.isEmpty()) return
        try {
            db.transaction {
                for ((key, count) in pending) {
                    val (endpointId, reason) = key
                    execute(
                        "INSERT INTO rejections (endpoint_id, reason, count, last_at) " +
                            "VALUES ('${endpointId.replace("'", "''")}', '${reason.name}', $count, 0) " +
                            "ON CONFLICT(endpoint_id, reason) DO UPDATE SET count = count + $count;",
                    ).getOrThrow()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The counts are gone either way — `drain` took them — and re-adding them to a map that
            // is being written to concurrently would double-count on the next pass. Losing a
            // diagnostic count is the smaller harm, and it is reported rather than swallowed.
            onFailure(failure)
        }
    }
}
