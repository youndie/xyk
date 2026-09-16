package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val SECONDS_PER_DAY = 86_400L

/**
 * Removes payload bytes older than the horizon, and **keeps the event**.
 *
 * The record is the product: what arrived, when, whether it verified and what happened to it stays
 * for ever. What goes is the body — which for a payments webhook is somebody's customer data, and
 * for a busy install is most of the disk.
 *
 * Purging is a `UPDATE`, not a `DELETE`: `body` becomes empty, `purged_at` is stamped, and
 * `body_bytes` keeps the size the payload **arrived** with, so the journal can say "47 bytes, purged
 * on the 22nd" rather than "0 bytes" — which is a different and wrong statement.
 */
class Retention(
    private val db: ISQLite,
) {
    /** Returns how many events lost their payload. */
    suspend fun purgeOlderThan(cutoffEpochSeconds: Long): Int {
        val affected =
            db
                .fetchAll(
                    "SELECT count(*) FROM events WHERE received_at < $cutoffEpochSeconds AND purged_at IS NULL;",
                ).getOrThrow()
                .rows
                .first()
                .get(0)
                .asLong()
                .toInt()
        if (affected == 0) return 0

        db
            .execute(
                "UPDATE events SET body = X'', purged_at = $cutoffEpochSeconds " +
                    "WHERE received_at < $cutoffEpochSeconds AND purged_at IS NULL;",
            ).getOrThrow()
        return affected
    }
}

/**
 * Runs [Retention] on a timer — **only when a horizon has been set**.
 *
 * Zero days turns it off, and that is the default: a service that starts deleting data because
 * nobody configured it is the one failure in this file that cannot be undone. The sweep is hourly
 * rather than by the minute because the unit of the policy is days; there is nothing to gain from
 * being prompt about it, and a purge competes with ingest for the same writer lock.
 */
class RetentionSweep(
    private val retention: Retention,
    private val retentionDays: Long,
    private val nowEpochSeconds: () -> Long,
    private val interval: Duration = 60.minutes,
    private val onPurged: (Int) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job? {
        if (retentionDays <= 0) return null
        val started =
            scope.launch {
                while (isActive) {
                    delay(interval)
                    runOnce()
                }
            }
        job = started
        return started
    }

    suspend fun stop() {
        // `cancelAndJoin`, not `cancel`. Cancelling a loop says it must stop; only joining says it
        // has. The gap between the two is a statement still in flight inside SQLite — and the stop
        // participant returns in the meantime, so the work escapes into the *next* stage and
        // collides with the checkpoint there. See the stop order in `Main.kt`.
        job?.cancelAndJoin()
        job = null
    }

    suspend fun runOnce() {
        if (retentionDays <= 0) return
        try {
            val cutoff = nowEpochSeconds() - retentionDays * SECONDS_PER_DAY
            val purged = retention.purgeOlderThan(cutoff)
            if (purged > 0) onPurged(purged)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }
}
