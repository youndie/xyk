package io.github.youndie.xyk.ingest

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.BootstrapEndpoint
import io.github.youndie.xyk.db.applyBootstrap
import io.github.youndie.xyk.db.fromSqliteHex
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.delivery.TestTimerScheduler
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.ingest.data.countOf
import io.github.youndie.xyk.ingest.domain.AcceptEventUseCase
import io.github.youndie.xyk.sink.AcceptedRecord
import io.github.youndie.xyk.sink.EventSink
import io.github.youndie.xyk.verify.GithubVerifier
import io.github.youndie.xyk.verify.SignedRequest
import io.github.youndie.xyk.verify.VerificationFailure
import io.github.youndie.xyk.verify.toHex
import kotlinx.coroutines.test.runTest
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The ingest path end to end, against a real database.
 *
 * These are the scenarios of `docs/features/feature-ingest.md` that do not need the registry, and
 * they are written against what the code answers rather than what it was meant to answer.
 */
class AcceptEventTest {
    private val secret = "it's a secret"
    private val endpointId = "hook-under-test"

    private suspend fun fixture(
        subscribers: Int,
        sink: EventSink? = null,
        onPublishFailure: (String, Throwable) -> Unit = { _, _ -> },
    ): Pair<AcceptEventUseCase, ISQLite> {
        val db = openDatabase("/tmp/xyk-ingest-${Random.nextLong()}.db", maxConnections = 2)
        db.applyBootstrap(
            BootstrapEndpoint(
                id = endpointId,
                scheme = GithubVerifier.SCHEME,
                // An apostrophe on purpose: every value on this path reaches SQL as text.
                secret = secret,
                subscriberUrls = (1..subscribers).map { "https://example.invalid/sink/$it" },
            ),
            nowEpochSeconds = 1_000,
        )
        val useCase =
            AcceptEventUseCase(
                repository = Sqlx4kEventRepository(db, TestTimerScheduler()),
                verifiers = mapOf(GithubVerifier.SCHEME to GithubVerifier()),
                sink = sink,
                onPublishFailure = { accepted, failure -> onPublishFailure(accepted.id, failure) },
            )
        return useCase to db
    }

    private fun signed(
        body: ByteArray,
        withSecret: String = secret,
    ): SignedRequest {
        val digest = HmacSHA256(withSecret.encodeToByteArray()).doFinal(body).toHex()
        return SignedRequest(
            headers = { name -> if (name == GithubVerifier.HEADER) "sha256=$digest" else null },
            body = body,
            nowEpochSeconds = 2_000,
        )
    }

    private fun params(request: SignedRequest) =
        AcceptEventUseCase.Params(endpointId, request, contentType = "application/json")

    @Test
    fun `a genuine webhook is stored byte for byte with one delivery per subscriber`() =
        runTest {
            val (accept, db) = fixture(subscribers = 2)
            // Not a tidy JSON fixture: a NUL, a 0xFF, CRLF, a BOM and an apostrophe — everything
            // that a text-shaped storage path would quietly change.
            val body =
                byteArrayOf(0, -1, 13, 10) +
                    "{\"a\":\" it's ✓\"}".encodeToByteArray() +
                    byteArrayOf(0, 127, -128)

            val accepted = accept(params(signed(body))).getOrThrow()

            assertEquals(2, accepted.deliveries)
            assertEquals(1, db.countOf("SELECT count(*) FROM events;").toInt())
            assertEquals(2, db.countOf("SELECT count(*) FROM deliveries WHERE state = 'pending';").toInt())

            val stored =
                db
                    .fetchAll("SELECT hex(body) FROM events WHERE id = '${accepted.id}';")
                    .getOrThrow()
                    .rows
                    .first()
                    .get(0)
                    .asString()
                    .fromSqliteHex()
            assertContentEquals(body, stored, "the stored body is not what arrived")
            assertEquals(
                body.size.toLong(),
                db.countOf("SELECT body_bytes FROM events WHERE id = '${accepted.id}';"),
            )
            db.close().getOrThrow()
        }

    @Test
    fun `a body altered by one byte is refused and nothing is stored`() =
        runTest {
            val (accept, db) = fixture(subscribers = 1)
            val body = "{\"ok\":true}".encodeToByteArray()
            val request = signed(body)
            val tampered = SignedRequest(request.headers, body + "!".encodeToByteArray(), request.nowEpochSeconds)

            val failure = accept(params(tampered)).exceptionOrNull()

            val notVerified = assertIs<AcceptEventUseCase.Error.NotVerified>(failure)
            assertEquals(VerificationFailure.INVALID, notVerified.failure)
            assertEquals(0, db.countOf("SELECT count(*) FROM events;").toInt())
            db.close().getOrThrow()
        }

    @Test
    fun `a missing signature header is its own failure and not an invalid one`() =
        runTest {
            val (accept, db) = fixture(subscribers = 1)
            val request = SignedRequest({ null }, "{}".encodeToByteArray(), 2_000)

            val failure = accept(params(request)).exceptionOrNull()

            assertEquals(
                VerificationFailure.MISSING,
                assertIs<AcceptEventUseCase.Error.NotVerified>(failure).failure,
            )
            db.close().getOrThrow()
        }

    @Test
    fun `an unknown endpoint is refused without touching the store`() =
        runTest {
            val (accept, db) = fixture(subscribers = 1)
            val body = "{}".encodeToByteArray()

            val failure =
                accept(
                    AcceptEventUseCase.Params("no-such-endpoint", signed(body), null),
                ).exceptionOrNull()

            assertIs<AcceptEventUseCase.Error.UnknownEndpoint>(failure)
            assertEquals(0, db.countOf("SELECT count(*) FROM events;").toInt())
            db.close().getOrThrow()
        }

    @Test
    fun `an endpoint with no subscribers still records the event`() =
        runTest {
            val (accept, db) = fixture(subscribers = 0)

            val accepted = accept(params(signed("{}".encodeToByteArray()))).getOrThrow()

            assertEquals(0, accepted.deliveries)
            assertEquals(1, db.countOf("SELECT count(*) FROM events;").toInt())
            db.close().getOrThrow()
        }

    @Test
    fun `the same webhook sent twice becomes two events`() =
        runTest {
            val (accept, db) = fixture(subscribers = 1)
            val body = "{\"id\":1}".encodeToByteArray()

            val first = accept(params(signed(body))).getOrThrow()
            val second = accept(params(signed(body))).getOrThrow()

            assertTrue(first.id != second.id, "a redelivery collapsed into one event")
            assertEquals(2, db.countOf("SELECT count(*) FROM events;").toInt())
            db.close().getOrThrow()
        }

    @Test
    fun `a second secret is accepted while the first still works`() =
        runTest {
            // Rotation, arrived at through the bootstrap being idempotent by content rather than by
            // id. B-07 makes this a route; the behaviour is already the one Stripe needs.
            val (accept, db) = fixture(subscribers = 1)
            db.applyBootstrap(
                BootstrapEndpoint(endpointId, GithubVerifier.SCHEME, "the new one", emptyList()),
                nowEpochSeconds = 1_500,
            )

            assertTrue(accept(params(signed("{}".encodeToByteArray()))).isSuccess, "the old secret stopped working")
            assertTrue(
                accept(params(signed("{}".encodeToByteArray(), withSecret = "the new one"))).isSuccess,
                "the new secret was not accepted",
            )
            assertEquals(2, db.countOf("SELECT count(*) FROM endpoint_secrets;").toInt())
            db.close().getOrThrow()
        }

    /**
     * The sink, and the one thing about it that is pre-registered rather than chosen afterwards:
     * **the row exists before the publish is attempted.** That order is what makes a record that
     * never reached the topic visible from outside — an event in the table with nothing behind it —
     * and the assertion is inside the fake, at the moment of the call, because asserting it after
     * the fact would pass for either order.
     */
    @Test
    fun `an accepted event is published after its row is committed, under the id the sender was given`() =
        runTest {
            val published = mutableListOf<AcceptedRecord>()
            var rowsAtPublish = -1L
            lateinit var db: ISQLite
            val sink =
                object : EventSink {
                    override suspend fun publish(record: AcceptedRecord) {
                        rowsAtPublish = db.countOf("SELECT count(*) FROM events WHERE id = '${record.eventId}';")
                        published += record
                    }

                    override suspend fun close() = Unit
                }
            val (accept, database) = fixture(subscribers = 1, sink = sink)
            db = database
            val body = "{\"ok\":true}".encodeToByteArray()

            val accepted = accept(params(signed(body))).getOrThrow()

            assertEquals(1, published.size, "the accepted event was not published")
            assertEquals(accepted.id, published.single().eventId)
            assertEquals(endpointId, published.single().endpointId)
            assertEquals(body.size.toLong(), published.single().bodyBytes)
            assertEquals(1L, rowsAtPublish, "the publish ran before the row was committed")
            db.close().getOrThrow()
        }

    @Test
    fun `a sink that refuses does not fail the request, and names the event it dropped`() =
        runTest {
            val refused = mutableListOf<String>()
            val sink =
                object : EventSink {
                    override suspend fun publish(record: AcceptedRecord): Unit =
                        throw IllegalStateException("no broker")

                    override suspend fun close() = Unit
                }
            val (accept, db) =
                fixture(
                    subscribers = 1,
                    sink = sink,
                    onPublishFailure = { eventId, _ -> refused += eventId },
                )

            val accepted = accept(params(signed("{}".encodeToByteArray())))

            assertTrue(accepted.isSuccess, "a refused publish failed a request that was already stored")
            assertEquals(1, db.countOf("SELECT count(*) FROM events;").toInt())
            assertEquals(listOf(accepted.getOrThrow().id), refused, "the dropped event was not named")
            db.close().getOrThrow()
        }

    @Test
    fun `a request that fails verification is never published`() =
        runTest {
            var publishes = 0
            val sink =
                object : EventSink {
                    override suspend fun publish(record: AcceptedRecord) {
                        publishes++
                    }

                    override suspend fun close() = Unit
                }
            val (accept, db) = fixture(subscribers = 1, sink = sink)
            val body = "{\"ok\":true}".encodeToByteArray()
            val request = signed(body)

            accept(params(SignedRequest(request.headers, body + "!".encodeToByteArray(), request.nowEpochSeconds)))

            assertEquals(0, publishes, "an unverified request reached the topic")
            db.close().getOrThrow()
        }
}
