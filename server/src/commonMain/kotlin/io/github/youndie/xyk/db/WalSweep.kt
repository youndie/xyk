package io.github.youndie.xyk.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
     * Stops the loop and takes one last checkpoint.
     *
     * The last one matters on a deploy: a pod that stops with a full journal hands the next pod a
     * file it has to work through before it can serve, and that time is added to a cold start
     * nobody attributes to the previous process.
     */
    suspend fun stop() {
        job?.cancel()
        job = null
        try {
            wal.checkpoint()
        } catch (cancelled: CancellationException) {
            // The stop sequence has a deadline of its own; if it has run out, leaving is right.
            throw cancelled
        } catch (failure: Throwable) {
            // Reported rather than swallowed: a last checkpoint that could not run is the reason
            // the next process starts slowly, and nothing else would say so.
            onFailure(failure)
        }
    }
}
