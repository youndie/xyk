package io.github.youndie.xyk.health

import io.github.youndie.xyk.delivery.DeliveryWorkers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The probe that notices a delivery loop which has stopped without dying.
 *
 * `TimerWorker.start` catches everything that is not a cancellation and keeps polling, so a store
 * that cannot be read produces no crash, no restart and no alert — it produces a service that looks
 * idle. This check is the only thing in the system that can tell those apart, which makes "does it
 * actually go red" a question worth asking of the check itself rather than assuming.
 */
class DeliveryCheckTest {
    /** A clock the test moves by hand: sleeping through a stall window would measure the machine. */
    private class FakeTimeSource : TimeSource {
        var now: Duration = Duration.ZERO

        override fun markNow(): TimeMark {
            val takenAt = now
            return object : TimeMark {
                override fun elapsedNow(): Duration = now - takenAt
            }
        }
    }

    private class FakeWorkers(
        override var completedTicks: Long = 0,
        override var lastFailure: String? = null,
    ) : DeliveryWorkers {
        override val count: Int = 2

        override fun start(scope: CoroutineScope) = Unit

        override suspend fun stop() = Unit
    }

    @Test
    fun `a loop that keeps ticking stays ready`() =
        runTest {
            val time = FakeTimeSource()
            val workers = FakeWorkers()
            val check = deliveryCheck(workers, stallAfter = 30.seconds, timeSource = time)

            repeat(10) {
                workers.completedTicks++
                time.now += 20.seconds
                // No throw is the assertion: twenty seconds between passes is well past the window,
                // and movement is what the probe reads rather than a rate.
                check.check()
            }
        }

    @Test
    fun `an idle service with nothing to deliver is still ready`() =
        runTest {
            val time = FakeTimeSource()
            // A pass over an empty queue returns zero and still counts, which is the difference
            // between watching ticks and watching deliveries: an idle Sunday must not read as an
            // outage.
            val workers = FakeWorkers()
            val check = deliveryCheck(workers, stallAfter = 30.seconds, timeSource = time)

            repeat(5) {
                workers.completedTicks++
                time.now += 1.seconds
                check.check()
            }
        }

    @Test
    fun `a stalled loop fails within the window rather than eventually`() =
        runTest {
            val time = FakeTimeSource()
            val workers = FakeWorkers(completedTicks = 7)
            val check = deliveryCheck(workers, stallAfter = 30.seconds, timeSource = time)

            check.check()

            // Just inside the window: still ready. The check must not be jumpy — a single slow tick
            // is not an outage, and a probe that flaps restarts healthy pods.
            time.now += 29.seconds
            check.check()

            // Past it, with the counter never having moved.
            time.now += 2.seconds
            val failure = assertFailsWith<IllegalStateException> { check.check() }
            assertTrue(
                failure.message.orEmpty().contains("have not completed a pass"),
                "the message must say what stopped: ${failure.message}",
            )
        }

    @Test
    fun `the worker's own complaint is carried into the failure`() =
        runTest {
            val time = FakeTimeSource()
            val workers =
                FakeWorkers(
                    completedTicks = 1,
                    lastFailure = "poll: IllegalStateException: database is locked",
                )
            val check = deliveryCheck(workers, stallAfter = 10.seconds, timeSource = time)

            check.check()
            time.now += 11.seconds

            val failure = assertFailsWith<IllegalStateException> { check.check() }
            // "Readiness is failing" sends an operator looking; "database is locked" sends them to
            // the cause. The probe knows the second and there is no other place it reaches.
            assertTrue(
                failure.message.orEmpty().contains("database is locked"),
                "the last failure must reach the probe's message: ${failure.message}",
            )
        }

    @Test
    fun `recovery clears the failure rather than latching it`() =
        runTest {
            val time = FakeTimeSource()
            val workers = FakeWorkers(completedTicks = 3)
            val check = deliveryCheck(workers, stallAfter = 10.seconds, timeSource = time)

            check.check()
            time.now += 11.seconds
            assertFailsWith<IllegalStateException> { check.check() }

            // The store came back. A probe that stayed red until a restart would turn a transient
            // lock into an outage that needs a human.
            workers.completedTicks++
            check.check()
        }
}
