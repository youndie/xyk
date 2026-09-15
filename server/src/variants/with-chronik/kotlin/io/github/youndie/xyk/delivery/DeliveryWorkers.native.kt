package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.chronik.ChronikClock
import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.TimerSink
import io.github.youndie.chronik.TimerWorker
import io.github.youndie.chronik.sqlx4k.sqlite.SqliteTimerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * N `TimerWorker`s over the shared SQLite store, each with its own owner.
 *
 * **Fan-out is N workers rather than a concurrent sink, and that follows from chronik's contract
 * rather than from taste.** `tick()` claims a batch and then walks it sequentially, `await`ing the
 * sink one timer at a time; a sink that returned early to gain concurrency would break the retry
 * contract, because `deliver` throwing is the *only* signal the worker has that an attempt failed
 * ([research §1.3](../../../../../../../../docs/research/research-architecture.md)). So parallelism
 * is several claimants, which the lease makes safe.
 *
 * **The owner must differ per worker.** Two workers sharing one owner can claim inside each other's
 * lease and deliver the same timer twice — which is the one duplicate at-least-once does not excuse,
 * because it is not a crash but two loops doing the same work on purpose.
 */
private class ChronikDeliveryWorkers(
    db: ISQLite,
    sink: DeliverySink,
    override val count: Int,
    private val pollIntervalSeconds: Long,
    leaseSeconds: Long,
    maxAttempts: Int,
    nowEpochSeconds: () -> Long,
) : DeliveryWorkers {
    private val ticks = AtomicLong(0)
    private val failure = AtomicReference<String?>(null)
    private val jobs = mutableListOf<Job>()

    override val completedTicks: Long get() = ticks.value

    override val lastFailure: String? get() = failure.value

    private val store = SqliteTimerStore(db)

    private val timerSink =
        TimerSink { fired ->
            // The payload IS the delivery id and nothing else. chronik stores a string and never
            // reads it; putting a serialised object there would make the timers table a second copy
            // of the deliveries table, drifting from the first the moment either changes.
            sink.deliver(fired.payload)
        }

    private val workers =
        (0 until count).map { index ->
            TimerWorker(
                store = store,
                sink = timerSink,
                clock = ChronikClock { EpochSeconds(nowEpochSeconds()) },
                // Distinct, and stable across a restart so that a crashed worker's own lease is
                // reclaimed by its successor as well as by its peers.
                owner = "xyk-$index",
                leaseSeconds = leaseSeconds,
                pollInterval = pollIntervalSeconds.seconds,
                maxAttempts = maxAttempts,
                onWorkerFailure = { stage, cause -> failure.value = describe(stage, cause) },
            )
        }

    /**
     * **The poll loop is ours, not `TimerWorker.start`'s, and the reason is the tick counter.**
     *
     * chronik exposes no "a tick completed" hook — every callback it has is about something going
     * wrong — and `start()` swallows non-cancellation exceptions and keeps polling, which is correct
     * for a poll loop and means a store that cannot be read looks exactly like a service with
     * nothing to do. `tick()` is public precisely so the loop can be owned; owning it is what lets
     * readiness see the difference from outside.
     *
     * The first draft of this counted `onLostRace` instead. That callback fires only when a worker
     * claims nothing **and** due timers exist — a lost race, not a pass — so the counter would have
     * sat at zero on a perfectly healthy idle service and made readiness fail for the opposite of
     * the reason it exists. Read in chronik's source rather than guessed at the second attempt.
     */
    override fun start(scope: CoroutineScope) {
        workers.forEach { worker ->
            jobs +=
                scope.launch {
                    while (isActive) {
                        try {
                            worker.tick()
                            // Counted after the pass returns, so a tick that threw is not one.
                            ticks.incrementAndGet()
                        } catch (cancellation: CancellationException) {
                            // The shutdown, arriving. Rethrown so the job actually ends rather than
                            // spinning through a cancelled scope reporting failures.
                            throw cancellation
                        } catch (transient: Exception) {
                            failure.value = describe("poll", transient)
                        }
                        delay(pollIntervalSeconds.seconds)
                    }
                }
        }
    }

    override suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
    }

    private fun describe(
        stage: String,
        cause: Throwable,
    ): String = "$stage: ${cause::class.simpleName}: ${cause.message?.take(200)}"
}

actual fun deliveryWorkers(
    db: ISQLite,
    sink: DeliverySink,
    count: Int,
    pollIntervalSeconds: Long,
    leaseSeconds: Long,
    maxAttempts: Int,
    nowEpochSeconds: () -> Long,
): DeliveryWorkers? =
    if (count <= 0) {
        null
    } else {
        ChronikDeliveryWorkers(db, sink, count, pollIntervalSeconds, leaseSeconds, maxAttempts, nowEpochSeconds)
    }
