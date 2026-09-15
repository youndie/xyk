package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.BootstrapEndpoint
import io.github.youndie.xyk.delivery.TestTimerScheduler
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.journal.data.Sqlx4kJournalRepository
import io.github.youndie.xyk.journal.domain.JournalFilter
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retention removes bytes and keeps records — and the test that matters most is the one where
 * **nothing is purged**, because a service that starts deleting data nobody asked it to delete is
 * the one failure here that cannot be undone.
 */
class RetentionTest {
    private val day = 86_400L

    private suspend fun fixture(): Triple<Sqlx4kJournalRepository, Retention, ISQLite> {
        val db = openDatabase("/tmp/xyk-retention-${Random.nextLong()}.db", maxConnections = 2)
        db.applyBootstrap(
            BootstrapEndpoint("retention-endpoint", "github", "s", emptyList()),
            nowEpochSeconds = 0,
        )
        return Triple(Sqlx4kJournalRepository(db, TestTimerScheduler()), Retention(db), db)
    }

    private suspend fun store(
        db: ISQLite,
        at: Long,
    ): String {
        val events = Sqlx4kEventRepository(db, TestTimerScheduler())
        val endpoint = assertNotNull(events.findEndpoint("retention-endpoint"))
        return events
            .accept(endpoint, at, "github", "fp", "application/json", "{\"body\":\"secret-ish\"}".encodeToByteArray())
            .id
    }

    @Test
    fun `a purged payload leaves the event and its size behind`() =
        runTest {
            val (journal, retention, db) = fixture()
            val old = store(db, at = 10 * day)
            val recent = store(db, at = 30 * day)
            val originalSize = assertNotNull(journal.payloadMeta(old)).bytes

            val purged = retention.purgeOlderThan(cutoffEpochSeconds = 20 * day)

            assertEquals(1, purged)
            val meta = assertNotNull(journal.payloadMeta(old))
            assertEquals(20 * day, meta.purgedAt, "the event does not say when its bytes went")
            assertEquals(originalSize, meta.bytes, "the size the payload arrived with was overwritten")
            assertEquals(0, journal.payloadChunk(old, 0, 1024).size, "the bytes are still readable")

            // The record, which is the product, is untouched — and the recent one keeps its payload.
            assertEquals(2, journal.recent(JournalFilter(limit = 10)).size)
            assertNull(assertNotNull(journal.payloadMeta(recent)).purgedAt)
            assertTrue(journal.payloadChunk(recent, 0, 1024).isNotEmpty())
            db.close().getOrThrow()
        }

    @Test
    fun `purging twice does not count the same event again`() =
        runTest {
            val (_, retention, db) = fixture()
            store(db, at = 10 * day)

            assertEquals(1, retention.purgeOlderThan(20 * day))
            assertEquals(0, retention.purgeOlderThan(20 * day), "an already-purged event was purged again")
            db.close().getOrThrow()
        }

    @Test
    fun `with no horizon configured nothing is ever purged`() =
        runTest {
            // The control, and the most important test in this file: the default must not delete.
            val (journal, retention, db) = fixture()
            val id = store(db, at = 0)
            var purgedCount = 0
            val sweep =
                RetentionSweep(
                    retention = retention,
                    retentionDays = 0,
                    nowEpochSeconds = { 1_000 * day },
                    onPurged = { purgedCount += it },
                )

            sweep.runOnce()

            assertEquals(0, purgedCount)
            assertNull(assertNotNull(journal.payloadMeta(id)).purgedAt, "a service with no policy deleted data")
            assertTrue(journal.payloadChunk(id, 0, 1024).isNotEmpty())
            db.close().getOrThrow()
        }

    @Test
    fun `the sweep purges what is older than its horizon and nothing else`() =
        runTest {
            val (journal, retention, db) = fixture()
            val ancient = store(db, at = 1 * day)
            val yesterday = store(db, at = 99 * day)
            var reported = 0
            val sweep =
                RetentionSweep(
                    retention = retention,
                    retentionDays = 7,
                    nowEpochSeconds = { 100 * day },
                    onPurged = { reported += it },
                )

            sweep.runOnce()

            assertEquals(1, reported)
            assertNotNull(assertNotNull(journal.payloadMeta(ancient)).purgedAt)
            assertNull(assertNotNull(journal.payloadMeta(yesterday)).purgedAt)
            db.close().getOrThrow()
        }
}
