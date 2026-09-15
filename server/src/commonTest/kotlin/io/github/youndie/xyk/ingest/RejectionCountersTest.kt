package io.github.youndie.xyk.ingest

import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.registry.data.Sqlx4kRegistryRepository
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RejectionCountersTest {
    private fun freshPath(): String = "/tmp/xyk-rejections-${Random.nextLong()}.db"

    @Test
    fun `draining clears so a flush cannot count the same rejection twice`() =
        runTest {
            val counters = RejectionCounters()
            counters.record("e1", RejectionReason.SIGNATURE_INVALID)
            counters.record("e1", RejectionReason.SIGNATURE_INVALID)
            counters.record("e1", RejectionReason.BODY_TOO_LARGE)

            val first = counters.drain()
            val second = counters.drain()

            assertEquals(2L, first["e1" to RejectionReason.SIGNATURE_INVALID])
            assertEquals(1L, first["e1" to RejectionReason.BODY_TOO_LARGE])
            assertTrue(second.isEmpty(), "a second drain returned counts that were already flushed")
        }

    @Test
    fun `a rejection with no endpoint to blame goes to the global bucket`() =
        runTest {
            val counters = RejectionCounters()
            counters.record(null, RejectionReason.UNKNOWN_ENDPOINT)

            // Anything else would let anyone with a URL bar create unbounded rows in the database.
            assertEquals(
                1L,
                counters.drain()[RejectionCounters.GLOBAL to RejectionReason.UNKNOWN_ENDPOINT],
            )
        }

    @Test
    fun `counts accumulate across flushes rather than replacing each other`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val counters = RejectionCounters()
            val flush = RejectionFlush(db, counters)
            val registry = Sqlx4kRegistryRepository(db)

            counters.record("e1", RejectionReason.SIGNATURE_INVALID)
            flush.stop()
            counters.record("e1", RejectionReason.SIGNATURE_INVALID)
            counters.record("e1", RejectionReason.SIGNATURE_STALE)
            counters.record(null, RejectionReason.UNKNOWN_ENDPOINT)
            flush.stop()

            val stored = registry.rejections()
            assertEquals(2L, stored["e1"]?.get("SIGNATURE_INVALID"), "the second flush replaced the first")
            assertEquals(1L, stored["e1"]?.get("SIGNATURE_STALE"))
            assertEquals(1L, stored[RejectionCounters.GLOBAL]?.get("UNKNOWN_ENDPOINT"))
            db.close().getOrThrow()
        }

    @Test
    fun `a flush with nothing pending writes nothing`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val flush = RejectionFlush(db, RejectionCounters())

            flush.stop()

            assertTrue(Sqlx4kRegistryRepository(db).rejections().isEmpty())
            db.close().getOrThrow()
        }
}
