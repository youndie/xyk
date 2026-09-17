package io.github.youndie.xyk.sink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The measurement arm of the sink, asserted where it can be.
 *
 * What it has to be is three things, and each is why a run through it means anything: the publish
 * **returns before** the delegate is done, `close` **drains** rather than discards, and every record
 * is **announced before** the delegate is asked — which is what lets a loss be attributed to the
 * producer or to the gap in front of it.
 *
 * **Every body runs inside `withContext(Dispatchers.Default)`, and that is not decoration.** The sink
 * runs a coroutine of its own on a real dispatcher while `runTest` runs on virtual time, so a
 * `withTimeout` written directly in the test body fires the moment the scheduler is idle — before any
 * of the real work has had a turn. All four of these failed that way once.
 */
class QueuedEventSinkTest {
    private fun record(id: String) = AcceptedRecord(id, "hook", 1, 0, null)

    private companion object {
        /** Enough that a drain cannot finish between `close` returning and the next line. */
        const val RECORDS = 20
        const val SLOW_MS = 20L
    }

    @Test
    fun publish_returns_while_the_delegate_is_still_working() =
        runTest {
            withContext(Dispatchers.Default) {
                val held = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val sink =
                    QueuedEventSink(
                        delegate =
                            object : EventSink {
                                override suspend fun publish(record: AcceptedRecord) {
                                    entered.complete(Unit)
                                    held.await()
                                }

                                override suspend fun close() = Unit
                            },
                        capacity = 8,
                        onAsked = { },
                        onFailure = { _, _ -> },
                    )

                sink.publish(record("ev-1"))
                // The delegate is inside `publish` and is not coming out, and these calls return
                // anyway. With the shipping sink this line would wait for the broker.
                withTimeout(5_000) { entered.await() }
                sink.publish(record("ev-2"))

                held.complete(Unit)
                withTimeout(5_000) { sink.close() }
            }
        }

    @Test
    fun close_drains_what_is_queued_instead_of_discarding_it() =
        runTest {
            withContext(Dispatchers.Default) {
                val delivered = mutableListOf<String>()
                val sink =
                    QueuedEventSink(
                        delegate =
                            object : EventSink {
                                override suspend fun publish(record: AcceptedRecord) {
                                    // SLOW ON PURPOSE. With an instant delegate this test passed even
                                    // with the wait inside `close` removed: closing the channel lets
                                    // the drain continue, and it finished before the assertion looked.
                                    // A race the test happens to win is not an assertion about
                                    // draining, and the mutation is what said so — twenty records at
                                    // twenty milliseconds cannot be finished by accident.
                                    delay(SLOW_MS)
                                    delivered += record.eventId
                                }

                                override suspend fun close() = Unit
                            },
                        capacity = 64,
                        onAsked = { },
                        onFailure = { _, _ -> },
                    )

                repeat(RECORDS) { index -> sink.publish(record("ev-$index")) }
                withTimeout(10_000) { sink.close() }

                // This is the half of the producer contract the synchronous sink can never exercise:
                // records already accepted when the process is asked to stop.
                assertEquals(RECORDS, delivered.size, "close discarded queued records")
                assertEquals((0 until RECORDS).map { "ev-$it" }, delivered, "the queue did not keep its order")
            }
        }

    @Test
    fun every_record_is_announced_before_the_delegate_is_asked() =
        runTest {
            withContext(Dispatchers.Default) {
                val events = mutableListOf<String>()
                val sink =
                    QueuedEventSink(
                        delegate =
                            object : EventSink {
                                override suspend fun publish(record: AcceptedRecord) {
                                    events += "asked-delegate:${record.eventId}"
                                }

                                override suspend fun close() = Unit
                            },
                        capacity = 8,
                        onAsked = { id -> events += "announced:$id" },
                        onFailure = { _, _ -> },
                    )

                sink.publish(record("ev-1"))
                withTimeout(5_000) { sink.close() }

                // The order is the whole classifier. Announced-after would mean a record the process
                // stopped in the middle of looked like one it had never reached.
                assertEquals(listOf("announced:ev-1", "asked-delegate:ev-1"), events)
            }
        }

    @Test
    fun a_refused_record_is_named_and_does_not_stop_the_ones_behind_it() =
        runTest {
            withContext(Dispatchers.Default) {
                val refused = mutableListOf<String>()
                val delivered = mutableListOf<String>()
                val sink =
                    QueuedEventSink(
                        delegate =
                            object : EventSink {
                                override suspend fun publish(record: AcceptedRecord) {
                                    if (record.eventId == "ev-1") throw IllegalStateException("no broker")
                                    delivered += record.eventId
                                }

                                override suspend fun close() = Unit
                            },
                        capacity = 8,
                        onAsked = { },
                        onFailure = { id, _ -> refused += id },
                    )

                sink.publish(record("ev-1"))
                sink.publish(record("ev-2"))
                withTimeout(5_000) { sink.close() }

                assertEquals(listOf("ev-1"), refused, "the refusal was not reported")
                assertTrue(delivered.contains("ev-2"), "one refused record took the queue down with it")
            }
        }

    /**
     * The third pile, counted rather than inferred.
     *
     * `close` runs under a shutdown stage's deadline, so a drain that needs longer is cancelled and
     * the records still queued were accepted and never asked for. From outside they are
     * indistinguishable from the outbox case; the only thing that can tell them apart is this count,
     * taken while the call is being cut short.
     */
    @Test
    fun close_counts_what_the_deadline_left_behind() =
        runTest {
            withContext(Dispatchers.Default) {
                val undrained = CompletableDeferred<Int>()
                val sink =
                    QueuedEventSink(
                        delegate =
                            object : EventSink {
                                // Slower than the deadline below, so the drain cannot finish.
                                override suspend fun publish(record: AcceptedRecord) = delay(SLOW_MS)

                                override suspend fun close() = Unit
                            },
                        capacity = RECORDS,
                        onAsked = {},
                        onFailure = { _, _ -> },
                        onUndrained = { left -> undrained.complete(left) },
                    )

                repeat(RECORDS) { n -> sink.publish(record("e$n")) }

                // Cancels `close` the way a stage deadline does, mid-drain. The cancellation is
                // the point of the arm, so it is expected rather than swallowed.
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(SLOW_MS * 2) { sink.close() }
                }

                val left = withTimeout(SLOW_MS * RECORDS) { undrained.await() }
                assertTrue(left > 0, "the deadline cut the drain short and nothing was reported")
                assertTrue(
                    left < RECORDS,
                    "nothing drained at all, so this measures the harness rather than the count",
                )
            }
        }
}
