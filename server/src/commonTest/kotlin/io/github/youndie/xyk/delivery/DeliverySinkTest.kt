package io.github.youndie.xyk.delivery

import io.github.youndie.xyk.delivery.domain.AttemptRecord
import io.github.youndie.xyk.delivery.domain.DeliveryRepository
import io.github.youndie.xyk.delivery.domain.DeliveryState
import io.github.youndie.xyk.delivery.domain.DeliveryTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single-attempt half of [feature-delivery](../../../../../../../../docs/features/feature-delivery.md).
 *
 * Every case here is about the seam between "what the subscriber did" and "what the worker is told",
 * because that seam is the whole sink: chronik retries what throws and retires what returns, so a
 * failure that returns normally would silently turn every delivery into a success and disable
 * retries entirely — the failure this suite exists to make impossible.
 */
class DeliverySinkTest {
    private class FakeRepository(
        private var target: DeliveryTarget?,
    ) : DeliveryRepository {
        val recorded = mutableListOf<Pair<AttemptRecord, String>>()

        override suspend fun target(deliveryId: String): DeliveryTarget? = target

        override suspend fun recordAttempt(
            record: AttemptRecord,
            newState: String,
        ) {
            recorded += record to newState
        }
    }

    private fun target(attemptsSoFar: Int = 0) =
        DeliveryTarget(
            deliveryId = "dlv-1",
            eventId = "evt-1",
            endpointId = "hook-1",
            subscriberUrl = "https://subscriber.invalid/hook",
            contentType = "application/json",
            body = """{"zen":"Non-blocking is better than blocking."}""".encodeToByteArray(),
            attemptsSoFar = attemptsSoFar,
        )

    private fun sink(
        repository: DeliveryRepository,
        outbound: OutboundPost,
        timeoutMillis: Long = 2_000,
        maxAttempts: Int = 5,
    ) = DeliverySink(repository, outbound, timeoutMillis, maxAttempts, nowEpochSeconds = { 1_700_000_000 })

    @Test
    fun `a 200 delivers the stored bytes unchanged with the promised headers`() =
        runTest {
            val repository = FakeRepository(target())
            var seenBody: ByteArray? = null
            var seenHeaders: Map<String, String> = emptyMap()
            var seenContentType: String? = null

            sink(
                repository,
                OutboundPost { _, contentType, headers, body ->
                    seenBody = body
                    seenHeaders = headers
                    seenContentType = contentType
                    OutboundPost.Response(200, "")
                },
            ).deliver("dlv-1")

            // BYTE FOR BYTE. The body was stored because a signature was computed over exactly these
            // bytes; a subscriber that verifies it in turn is broken by any re-encoding on the way
            // out, and the break looks like a wrong secret rather than like a reformatted body.
            assertEquals(
                """{"zen":"Non-blocking is better than blocking."}""",
                seenBody?.decodeToString(),
            )
            assertEquals("application/json", seenContentType)
            assertEquals("evt-1", seenHeaders["X-Xyk-Event"])
            assertEquals("hook-1", seenHeaders["X-Xyk-Endpoint"])
            assertEquals("1", seenHeaders["X-Xyk-Attempt"])

            val (record, state) = repository.recorded.single()
            assertEquals(DeliveryState.DELIVERED, state)
            assertEquals(200, record.status)
            assertEquals(1, record.attempt)
        }

    @Test
    fun `a 500 throws so the worker retries — and the row is written first`() =
        runTest {
            val repository = FakeRepository(target())
            val failure =
                assertFailsWith<DeliveryFailed> {
                    sink(repository, OutboundPost { _, _, _, _ -> OutboundPost.Response(500, "upstream down") })
                        .deliver("dlv-1")
                }

            assertEquals(500, failure.status)
            // The record exists DESPITE the throw — the one case an operator most needs to see is
            // the one an early exception would leave with no trace.
            val (record, state) = repository.recorded.single()
            assertEquals(500, record.status)
            assertEquals(DeliveryState.PENDING, state)
            assertTrue(record.detail.contains("upstream down"))
        }

    @Test
    fun `a 302 is a failure rather than a hop`() =
        runTest {
            val repository = FakeRepository(target())
            var calls = 0
            val failure =
                assertFailsWith<DeliveryFailed> {
                    sink(
                        repository,
                        OutboundPost { _, _, _, _ ->
                            calls++
                            OutboundPost.Response(302, "moved")
                        },
                    ).deliver("dlv-1")
                }

            assertEquals(302, failure.status)
            assertEquals(
                302,
                repository.recorded
                    .single()
                    .first.status,
            )
            // Following it would deliver somebody's payload to an address no operator approved. The
            // sink cannot stop an engine that redirects on its own — that is the binding's job — but
            // it can refuse to treat the 3xx as anything but a failure, and it must not post twice.
            assertEquals(1, calls, "the sink posted more than once for one attempt")
        }

    @Test
    fun `a subscriber that never answers is bounded by the timeout and named as such`() =
        runTest {
            val repository = FakeRepository(target())
            val failure =
                assertFailsWith<DeliveryFailed> {
                    sink(
                        repository,
                        OutboundPost { _, _, _, _ ->
                            // Longer than any tick would tolerate. Under `runTest` this costs no
                            // wall-clock time and still exercises the real `withTimeout`.
                            delay(10 * 60 * 1000)
                            OutboundPost.Response(200, "")
                        },
                        timeoutMillis = 2_000,
                    ).deliver("dlv-1")
                }

            // No status, because nothing answered — and that is the point of recording `null` rather
            // than a sentinel like 0 or 408: "it never answered" and "it answered 408" are different
            // incidents and send an operator to different places.
            assertNull(failure.status)
            assertTrue(failure.detail.contains("timed out"), "a timeout must say so: ${failure.detail}")
            assertNull(
                repository.recorded
                    .single()
                    .first.status,
            )
        }

    @Test
    fun `a refused connection is a failed attempt and not a crash`() =
        runTest {
            val repository = FakeRepository(target())
            val failure =
                assertFailsWith<DeliveryFailed> {
                    sink(repository, OutboundPost { _, _, _, _ -> throw IllegalStateException("connection refused") })
                        .deliver("dlv-1")
                }

            assertNull(failure.status)
            assertTrue(failure.detail.contains("connection refused"))
            assertEquals(1, repository.recorded.size, "a transport failure must still leave a row")
        }

    @Test
    fun `the last attempt dead-letters rather than staying pending`() =
        runTest {
            val repository = FakeRepository(target(attemptsSoFar = 4))
            assertFailsWith<DeliveryFailed> {
                sink(
                    repository,
                    OutboundPost { _, _, _, _ -> OutboundPost.Response(500, "") },
                    maxAttempts = 5,
                ).deliver("dlv-1")
            }

            val (record, state) = repository.recorded.single()
            assertEquals(5, record.attempt)
            // A delivery out of attempts that still reads `pending` is a journal that lies about
            // what is still going to happen.
            assertEquals(DeliveryState.DEAD, state)
        }

    @Test
    fun `a delivery that no longer exists retires the timer instead of retrying forever`() =
        runTest {
            val repository = FakeRepository(target = null)
            var posted = false

            // Returns normally: chronik retires a timer whose sink returned. A throw here would put
            // the timer into a retry loop against a row that is never coming back.
            sink(
                repository,
                OutboundPost { _, _, _, _ ->
                    posted = true
                    OutboundPost.Response(200, "")
                },
            ).deliver("gone")

            assertTrue(!posted, "nothing should be posted for a delivery that does not exist")
            assertTrue(repository.recorded.isEmpty(), "there is nothing to record an attempt against")
        }
}
