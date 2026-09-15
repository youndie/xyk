package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CoroutineScope

/**
 * The outbound workers, as a handle the composition root can start and stop.
 *
 * **Nothing in this file names a chronik type**, for the same reason [OutboundPost] names no Ktor
 * type: `chronik-sqlx4k-sqlite` publishes `jvm` and `linuxX64` and no `macosArm64`, so a
 * `commonMain` that imported `TimerWorker` would stop compiling on the Mac — and with it the
 * migration list, the verifiers and everything else that has nothing to do with delivery. The
 * implementation lives in a variant source directory that is only on the build when chronik has a
 * variant for this host.
 */
interface DeliveryWorkers {
    /** How many workers are running. Zero is a legitimate answer for a build that does not deliver. */
    val count: Int

    /**
     * Completed ticks, summed across workers.
     *
     * **This is the number readiness watches, and the reason it exists is chronik's own
     * robustness.** `TimerWorker.start` catches everything that is not a cancellation, hands it to
     * `onWorkerFailure` and keeps polling — which is right for a poll loop and means a store that
     * cannot be read looks exactly like a service with nothing to do. Ticks are the only thing that
     * tells those two apart from outside.
     */
    val completedTicks: Long

    /** The last failure a worker reported, kept so a failing service can say why rather than just fail. */
    val lastFailure: String?

    fun start(scope: CoroutineScope)

    /**
     * Stops the workers and waits for the tick in flight.
     *
     * Called from kore's release stage **after the engine has drained and before the pool closes**:
     * a delivery that is mid-POST has already been accepted, and cutting the pool underneath it
     * would lose the attempt row rather than the attempt.
     */
    suspend fun stop()
}

/**
 * The workers this build can run, or `null` when it was built without chronik or without an engine.
 *
 * Both halves have to be present for delivery to mean anything: chronik to schedule and claim, an
 * outbound engine to POST. A build missing either answers `null`, and `main` says so at start-up —
 * the alternative is a service that accepts webhooks and silently never delivers them, which looks
 * healthy from every angle.
 */
expect fun deliveryWorkers(
    db: ISQLite,
    sink: DeliverySink,
    count: Int,
    pollIntervalSeconds: Long,
    leaseSeconds: Long,
    maxAttempts: Int,
    nowEpochSeconds: () -> Long,
): DeliveryWorkers?
