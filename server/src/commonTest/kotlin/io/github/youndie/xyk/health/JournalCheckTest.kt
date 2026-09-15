package io.github.youndie.xyk.health

import io.github.youndie.xyk.db.WalCheckpoint
import io.github.youndie.xyk.db.openDatabase
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Readiness has to fail on a journal that has run away, and **not** fail on one doing its job.
 *
 * The second half is the one worth a test: a ceiling set at the sweep's own trigger would take the
 * service out of the load balancer every time the sweep worked, and the check would look correct
 * while making every deploy worse.
 */
class JournalCheckTest {
    private fun freshPath(): String = "/tmp/xyk-journal-${Random.nextLong()}.db"

    @Test
    fun `it fails above the ceiling and passes below it`() =
        runTest {
            val path = freshPath()
            val db = openDatabase(path, maxConnections = 2)
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, blob TEXT);").getOrThrow()
            repeat(200) {
                db.execute("INSERT INTO t (blob) VALUES ('${"x".repeat(400)}');").getOrThrow()
            }
            val wal = WalCheckpoint(db, path)
            val bytes = wal.walBytes()
            assertTrue(bytes > 0, "nothing reached the journal, so neither branch is being tested")

            // Above: the incident.
            assertFailsWith<IllegalStateException> { journalCheck(wal, ceilingBytes = bytes).check() }

            // Below: the normal case, with the same journal.
            journalCheck(wal, ceilingBytes = bytes + 1).check()

            db.close().getOrThrow()
        }
}
