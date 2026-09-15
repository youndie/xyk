package io.github.youndie.xyk.journal

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.BootstrapEndpoint
import io.github.youndie.xyk.db.applyBootstrap
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.delivery.TestTimerScheduler
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.ingest.data.countOf
import io.github.youndie.xyk.journal.data.Sqlx4kJournalRepository
import io.github.youndie.xyk.journal.domain.JournalFilter
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The journal against a real database, because every claim here is about SQL. */
class JournalRepositoryTest {
    private val endpointId = "journal-endpoint"

    private suspend fun fixture(subscribers: Int = 1): Pair<Sqlx4kJournalRepository, ISQLite> {
        val db = openDatabase("/tmp/xyk-journal-${Random.nextLong()}.db", maxConnections = 2)
        db.applyBootstrap(
            BootstrapEndpoint(
                id = endpointId,
                scheme = "github",
                secret = "s",
                subscriberUrls = (1..subscribers).map { "https://sink.invalid/$it" },
            ),
            nowEpochSeconds = 1_000,
        )
        return Sqlx4kJournalRepository(db, TestTimerScheduler()) to db
    }

    private suspend fun store(
        db: ISQLite,
        body: ByteArray,
        at: Long,
    ): String {
        val events = Sqlx4kEventRepository(db, TestTimerScheduler())
        val endpoint = assertNotNull(events.findEndpoint(endpointId))
        return events
            .accept(
                endpoint = endpoint,
                receivedAt = at,
                scheme = "github",
                secretFingerprint = "fp",
                contentType = "application/json",
                body = body,
            ).id
    }

    @Test
    fun `paging by keyset returns every event exactly once while more are arriving`() =
        runTest {
            val (journal, db) = fixture()
            val ids = (1..10).map { store(db, "{\"n\":$it}".encodeToByteArray(), at = 1_000L + it) }

            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = journal.page(JournalFilter(limit = 3), cursor)
                seen += page.events.map { it.id }
                cursor = page.nextCursor
                pages++
                // An insert between pages, which is the whole reason this is keyset and not offset:
                // with an offset, the row that moved would be shown twice or skipped.
                if (pages == 2) store(db, "{\"late\":1}".encodeToByteArray(), at = 900)
            } while (cursor != null && pages < 20)

            assertEquals(seen.size, seen.toSet().size, "an event was returned twice")
            assertTrue(ids.all { it in seen }, "an event was skipped while paging")
            db.close().getOrThrow()
        }

    @Test
    fun `the last page has no cursor`() =
        runTest {
            val (journal, db) = fixture()
            repeat(3) { store(db, "{}".encodeToByteArray(), at = 1_000L + it) }

            val page = journal.page(JournalFilter(limit = 50), null)

            assertEquals(3, page.events.size)
            assertNull(page.nextCursor, "a full result advertised another page")
            db.close().getOrThrow()
        }

    @Test
    fun `a payload comes back byte for byte through the chunked read`() =
        runTest {
            val (journal, db) = fixture()
            // Bigger than one chunk, and full of the bytes a text path would mangle.
            val body = ByteArray(200_000) { index -> (index % 256 - 128).toByte() }
            val id = store(db, body, at = 1_000)

            val assembled = mutableListOf<Byte>()
            var offset = 0L
            while (offset < body.size) {
                val chunk = journal.payloadChunk(id, offset, 64 * 1024)
                if (chunk.isEmpty()) break
                assembled += chunk.toList()
                offset += chunk.size
            }

            assertContentEquals(body, assembled.toByteArray())
            assertEquals(body.size.toLong(), assertNotNull(journal.payloadMeta(id)).bytes)
            db.close().getOrThrow()
        }

    @Test
    fun `a chunk past the end is empty rather than an error`() =
        runTest {
            val (journal, db) = fixture()
            val id = store(db, "short".encodeToByteArray(), at = 1_000)

            assertEquals(0, journal.payloadChunk(id, 100, 64).size)
            db.close().getOrThrow()
        }

    @Test
    fun `redelivery schedules one delivery per enabled subscriber`() =
        runTest {
            val (journal, db) = fixture(subscribers = 2)
            val id = store(db, "{}".encodeToByteArray(), at = 1_000)
            assertEquals(2, db.countOf("SELECT count(*) FROM deliveries;").toInt())

            val scheduled = journal.redeliver(id, nowEpochSeconds = 2_000)

            assertEquals(2, scheduled)
            assertEquals(4, db.countOf("SELECT count(*) FROM deliveries;").toInt())
            db.close().getOrThrow()
        }

    @Test
    fun `redelivery of an event nobody subscribes to schedules nothing`() =
        runTest {
            val (journal, db) = fixture(subscribers = 0)
            val id = store(db, "{}".encodeToByteArray(), at = 1_000)

            // Zero, which the route turns into a `409` rather than a cheerful `202` about nothing.
            assertEquals(0, journal.redeliver(id, nowEpochSeconds = 2_000))
            db.close().getOrThrow()
        }

    @Test
    fun `the detail of an event carries its deliveries`() =
        runTest {
            val (journal, db) = fixture(subscribers = 2)
            val id = store(db, "{}".encodeToByteArray(), at = 1_000)

            val detail = assertNotNull(journal.detail(id))

            assertEquals(2, detail.deliveries.size)
            assertEquals(2, detail.event.pending)
            assertTrue(detail.deliveries.all { it.subscriberUrl?.startsWith("https://sink.invalid/") == true })
            db.close().getOrThrow()
        }

    @Test
    fun `an unknown event has no detail and no payload`() =
        runTest {
            val (journal, db) = fixture()
            assertNull(journal.detail("no-such-event"))
            assertNull(journal.payloadMeta("no-such-event"))
            db.close().getOrThrow()
        }
}
