package io.github.youndie.xyk.health

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.kore.health.HealthCheck
import io.github.youndie.kore.health.HealthRegistry
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.health.storeCheck
import io.github.youndie.xyk.db.WalCheckpoint
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
) {
    private val checks =
        HealthRegistry(
            listOfNotNull(
                storeCheck("sqlite") { db.fetchAll("SELECT 1;").getOrThrow() },
                wal?.let { journalCheck(it, ceilingBytes) },
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
