package io.github.youndie.xyk.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How often the sweep looks; the interval and the ceiling decide whether it acts. */
private val POLL: Duration = 1.seconds

/**
 * Keeps the write-ahead log from growing without bound, on **two** triggers.
 *
 * The clock alone is not enough, and that is a measured statement rather than caution: on a wound-up
 * loop the journal grows between passes, and elsewhere the failure arrived at minute 33 with a timer
 * and no size trigger. The size trigger is what makes this survive its own bad day — by the time the
 * clock comes round, a log growing because reads are slow is already the thing making them slow.
 * Reading the size is one `stat`.
 *
 * `intervalSeconds = 0` turns the sweep off. That is not a convenience: it is the control arm of
 * [B-24](../../../../../../../docs/backlog/B-24-soak-wal.md), which has to show the failure happening
 * before the mitigation can be said to prevent it.
 */
class WalSweep(
    private val wal: WalCheckpoint,
    private val intervalSeconds: Long,
    private val ceilingBytes: Long,
    private val onBusy: (WalState) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job? {
        if (intervalSeconds <= 0) return null
        val started =
            scope.launch {
                var quiet = Duration.ZERO
                while (isActive) {
                    delay(POLL)
                    quiet += POLL
                    val overflowing = wal.walBytes() >= ceilingBytes
                    if (!overflowing && quiet < intervalSeconds.seconds) continue
                    quiet = Duration.ZERO
                    try {
                        val state = wal.checkpoint()
                        // Only the runs that could not reset the log are worth a line. A checkpoint
                        // that worked is the normal case many times an hour, and logging it would be
                        // the service filling its own disk with the news that it is keeping its disk
                        // empty.
                        if (state.busy) onBusy(state)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        onFailure(failure)
                    }
                }
            }
        job = started
        return started
    }

    /**
     * Stops the loop, and only that.
     *
     * **The last checkpoint is deliberately not here.** It cannot run while the pool is open — the
     * pool's other connection holds the truncation off — so it belongs after `close()`, where
     * [lastCheckpoint] does it on a connection of its own.
     */
    suspend fun stop() {
        // `cancelAndJoin`, not `cancel`. Cancelling a loop says it must stop; only joining says it
        // has. The gap between the two is a statement still in flight inside SQLite — and the stop
        // participant returns in the meantime, so the work escapes into the *next* stage.
        job?.cancelAndJoin()
        job = null
    }
}
