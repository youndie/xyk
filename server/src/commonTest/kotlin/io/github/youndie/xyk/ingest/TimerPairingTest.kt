package io.github.youndie.xyk.ingest

import io.github.youndie.xyk.db.migrateSchema
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.delivery.TestTimerScheduler
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.ingest.domain.IngestEndpoint
import io.github.youndie.xyk.verify.SchemeConfig
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A delivery row and its timer commit together, or neither does.
 *
 * **This is the invariant chronik is in this design for.** A row written without its timer is a
 * webhook that was answered `200` and will never be sent, and it is invisible from every angle the
 * operator has: the journal shows it `pending` for ever, no probe fails, nothing is logged. The
 * transaction is what prevents it, so the transaction is what is tested — by breaking the half that
 * is hardest to break in production.
 */
class TimerPairingTest {
    private suspend fun database() =
        openDatabase("/tmp/xyk-pairing-${Random.nextULong()}/xyk.db").also { it.migrateSchema() }

    private suspend fun seed(db: io.github.smyrgeorge.sqlx4k.sqlite.ISQLite) {
        db
            .execute(
                "INSERT INTO endpoints (id, scheme, enabled, description, created_at) " +
                    "VALUES ('hook-1', 'none', 1, '', 1700000000);",
            ).getOrThrow()
        db
            .execute(
                "INSERT INTO subscribers (id, endpoint_id, url, enabled, created_at) " +
                    "VALUES ('sub-1', 'hook-1', 'https://a.invalid/h', 1, 1700000000), " +
                    "('sub-2', 'hook-1', 'https://b.invalid/h', 1, 1700000000);",
            ).getOrThrow()
    }

    private fun endpoint() =
        IngestEndpoint(
            id = "hook-1",
            scheme = "none",
            secrets = emptyList(),
            schemeConfig = SchemeConfig.EMPTY,
            subscriberIds = listOf("sub-1", "sub-2"),
        )

    @Test
    fun `every delivery row has a timer scheduled for it`() =
        runTest {
            val db = database()
            seed(db)
            val scheduler = TestTimerScheduler()

            Sqlx4kEventRepository(db, scheduler).accept(
                endpoint = endpoint(),
                receivedAt = 1_700_000_000,
                scheme = "none",
                secretFingerprint = null,
                contentType = "application/json",
                body = "{}".encodeToByteArray(),
            )

            val ids =
                db
                    .fetchAll("SELECT id FROM deliveries ORDER BY id;")
                    .getOrThrow()
                    .rows
                    .map { it.get(0).asString() }

            assertEquals(2, ids.size)
            // The ids must be the SAME ids, not merely the same count: a scheduler called twice with
            // something else would satisfy a count and deliver nothing.
            assertEquals(ids.sorted(), scheduler.scheduled.map { it.first }.sorted())
            assertTrue(scheduler.scheduled.all { it.second == 1_700_000_000L }, "timers must be due now")
        }

    @Test
    fun `a timer that cannot be written takes the whole event with it`() =
        runTest {
            val db = database()
            seed(db)

            assertFailsWith<IllegalStateException> {
                Sqlx4kEventRepository(db, TestTimerScheduler(failWith = IllegalStateException("timers unavailable")))
                    .accept(
                        endpoint = endpoint(),
                        receivedAt = 1_700_000_000,
                        scheme = "none",
                        secretFingerprint = null,
                        contentType = "application/json",
                        body = "{}".encodeToByteArray(),
                    )
            }

            // NOTHING SURVIVES, including the event itself. Half of this would be worse than the
            // failure: an event with no deliveries reads in the journal as "nobody was subscribed",
            // which is a sentence about the configuration rather than about a storage failure — and
            // the ingest route answers `500`, so the sender retries and the webhook is not lost.
            val events =
                db
                    .fetchAll("SELECT count(*) FROM events;")
                    .getOrThrow()
                    .rows
                    .first()
                    .get(0)
                    .asString()
            val deliveries =
                db
                    .fetchAll("SELECT count(*) FROM deliveries;")
                    .getOrThrow()
                    .rows
                    .first()
                    .get(0)
                    .asString()
            assertEquals("0", events, "the event survived a failed schedule")
            assertEquals("0", deliveries, "a delivery row survived a failed schedule")
        }
}
