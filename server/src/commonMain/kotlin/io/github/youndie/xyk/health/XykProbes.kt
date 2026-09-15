package io.github.youndie.xyk.health

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.kore.health.HealthCheck
import io.github.youndie.kore.health.HealthRegistry
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.health.storeCheck
import io.github.youndie.xyk.db.WalCheckpoint
import io.github.youndie.xyk.delivery.DeliveryWorkers
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The three questions a deployment asks this process.
 *
 * Three, not one route answering all of them: they fail for different reasons and are read by
 * different machinery, and wiring them together is how a readiness failure comes to restart a pod
 * instead of taking it out of the load balancer.
 *
 * * **startup** — a latch, closed once the migrations have run and the engine is serving. After that
 *   it never answers `503` again: Kubernetes runs the startup probe only at startup, and a later
 *   failure there would restart a pod that is trying to stop.
 * * **readiness** — the store check below plus the shutdown latch. This is the one that is *meant*
 *   to fail: it goes false first in the stop sequence, so traffic stops arriving before anything is
 *   closed.
 * * **liveness** — nothing declares this process wedged today, and that is the honest state rather
 *   than a route reporting the opposite.
 *
 * The store check is `SELECT 1`, not `pool.acquire()`: a pool hands back an idle connection while
 * the store behind it is gone, and only a statement that reaches SQLite says the database answered.
 *
 * **The journal check is the second one, and it looks at a file rather than at the database.**
 * `page_count * page_size` does not count pages sitting in the write-ahead log, so a size guard
 * built on it looks straight past the file that is filling the disk. B-11 adds the third check, the
 * delivery-worker tick counter — that worker swallows its own exceptions and keeps polling, so
 * silence is its only symptom and nothing else in the system would notice.
 */
class XykProbes(
    db: ISQLite,
    wal: WalCheckpoint? = null,
    ceilingBytes: Long = Long.MAX_VALUE,
    workers: DeliveryWorkers? = null,
    workerStallAfter: Duration = DEFAULT_WORKER_STALL,
) {
    private val checks =
        HealthRegistry(
            listOfNotNull(
                storeCheck("sqlite") { db.fetchAll("SELECT 1;").getOrThrow() },
                wal?.let { journalCheck(it, ceilingBytes) },
                // Only when this build has workers. A build that does not deliver must not report
                // itself unready for not delivering.
                workers?.let { deliveryCheck(it, workerStallAfter) },
            ),
        )

    val startup: StartupGate = StartupGate()
    val readiness: ReadinessGate = ReadinessGate(checks)
    val liveness: LivenessGate = LivenessGate()

    /**
     * Starts the polling loop. **Nothing else does.** The registry caches results and refreshes them
     * on a loop of its own, so without this call `/health/ready` answers out of checks that have
     * never run — `UNKNOWN`, for ever, which reads as a broken dependency rather than a missing
     * call.
     */
    fun start(scope: CoroutineScope) {
        checks.start(scope)
    }

    fun stop() {
        checks.stop()
    }
}

/**
 * Fails when the journal has stopped being a working file and become an incident.
 *
 * Not a `storeCheck`: it asks nothing of the store, it reads a file size. Saying so in the type is
 * worth the six lines — a reader of `/health/ready` seeing "sqlite" fail and "journal" fail knows
 * two different things.
 */
internal fun journalCheck(
    wal: WalCheckpoint,
    ceilingBytes: Long,
): HealthCheck =
    object : HealthCheck {
        override val name: String = "journal"
        override val timeout: Duration = 1.seconds

        override suspend fun check() {
            val bytes = wal.walBytes()
            check(bytes < ceilingBytes) {
                "write-ahead log is $bytes bytes, ceiling is $ceilingBytes — the sweep is not keeping up"
            }
        }
    }

/**
 * How long a worker may go without completing a pass before readiness calls it stalled.
 *
 * Ten seconds against a one-second poll: generous enough that a slow tick — a full batch of fifty
 * against the two-second timeout is a hundred seconds in the worst case — is not the thing that
 * trips it... which is exactly why this number is a *configuration* rather than a constant, and why
 * the value below is the floor for a service whose batches are small. A deployment with large
 * batches raises it, and the arithmetic is `batchSize × deliveryTimeout` plus a margin.
 */
val DEFAULT_WORKER_STALL: Duration = 120.seconds

/**
 * Readiness watches the tick counter, because nothing else would notice.
 *
 * `TimerWorker.start` catches everything that is not a cancellation, reports it and keeps polling
 * (research §1.3). That is right for a poll loop and it means **a store that cannot be read looks
 * exactly like a service with nothing to do**: no crash, no restart, no alert, and webhooks quietly
 * accumulating undelivered. The counter is the only difference visible from outside, so it is what
 * the probe reads.
 *
 * It checks **movement, not a rate**. A service with no due timers still ticks — the pass returns
 * zero — so a stalled counter means the loop itself has stopped, which is the one thing that is
 * always wrong. Counting deliveries instead would make an idle Sunday look like an outage.
 */
internal fun deliveryCheck(
    workers: DeliveryWorkers,
    stallAfter: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
): HealthCheck =
    object : HealthCheck {
        private var lastTicks: Long = -1
        private var lastMovedAt: TimeMark = timeSource.markNow()

        override val name: String = "delivery"
        override val timeout: Duration = 1.seconds

        override suspend fun check() {
            val ticks = workers.completedTicks
            if (ticks != lastTicks) {
                lastTicks = ticks
                lastMovedAt = timeSource.markNow()
                return
            }

            val stalledFor = lastMovedAt.elapsedNow()
            check(stalledFor < stallAfter) {
                // The worker's own last complaint is appended when there is one: "readiness is
                // failing" is half an answer and sends an operator looking, while
                // "poll: IllegalStateException: database is locked" sends them to the cause.
                "delivery workers have not completed a pass in $stalledFor " +
                    "(${workers.count} worker(s), $ticks tick(s) total)" +
                    (workers.lastFailure?.let { "; last failure — $it" } ?: "")
            }
        }
    }
