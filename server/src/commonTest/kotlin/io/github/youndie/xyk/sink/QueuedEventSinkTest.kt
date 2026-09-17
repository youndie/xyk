package io.github.youndie.xyk.sink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measurement arm of the sink, asserted where it can be.
 *
 * What it has to be is three things, and each is the reason a run through it means anything: the
 * publish **returns before** the delegate is done, `close` **drains** rather than discards, and every
 * record is **announced before** the delegate is asked — which is what lets a loss be attributed to
 * the producer or to the gap in front of it.
 *
 * `runTest` is here for its timeout, not for virtual time: the sink runs a coroutine of its own on a
 * real dispatcher, so these tests wait on real signals.
 */
class QueuedEventSinkTest {
    private fun record(id: String) = AcceptedRecord(id, "hook", 1, 0, null)

    @Test
    fun publish_returns_while_the_delegate_is_still_working() =
        runTest {
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
            // The delegate is inside `publish` and is not coming out, and this call returned anyway.
            // With the shipping sink this line would not be reached until the broker had answered.
            withTimeout(5_000) { entered.await() }
            sink.publish(record("ev-2"))

            held.complete(Unit)
            withTimeout(5_000) { sink.close() }
        }

    @Test
    fun close_drains_what_is_queued_instead_of_discarding_it() =
        runTest {
            val delivered = mutableListOf<String>()
            val sink =
                QueuedEventSink(
                    delegate =
                        object : EventSink {
                            override suspend fun publish(record: AcceptedRecord) {
                                delivered += record.eventId
                            }

                            override suspend fun close() = Unit
                        },
                    capacity = 64,
                    onAsked = { },
                    onFailure = { _, _ -> },
                )

            repeat(50) { index -> sink.publish(record("ev-$index")) }
            withTimeout(10_000) { sink.close() }

            // This is the half of the producer contract that the synchronous sink can never exercise:
            // records that are already accepted when the process is asked to stop.
            assertEquals(50, delivered.size, "close discarded queued records")
            assertEquals((0 until 50).map { "ev-$it" }, delivered, "the queue did not keep its order")
        }

    @Test
    fun every_record_is_announced_before_the_delegate_is_asked() =
        runTest {
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

    @Test
    fun a_refused_record_is_named_and_does_not_stop_the_ones_behind_it() =
        runTest {
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
