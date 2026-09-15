package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The journal has to come back down, and the number that says so has to be readable from outside.
 *
 * Both halves matter. The failure this guards against is not "the log is large" but "every number
 * the service reports about its own size is about the database, and the database is not what is
 * growing".
 */
class WalCheckpointTest {
    private fun freshPath(): String = "/tmp/xyk-wal-${Random.nextLong()}.db"

    @Test
    fun `a checkpoint truncates the log and leaves the rows in the database`() =
        runTest {
            val path = freshPath()
            val db = openDatabase(path, maxConnections = 2)
            val wal = WalCheckpoint(db, path)
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, blob TEXT);").getOrThrow()
            repeat(200) {
                db.execute("INSERT INTO t (blob) VALUES ('${"x".repeat(400)}');").getOrThrow()
            }
            assertTrue(wal.walBytes() > 0, "nothing reached the log at all")

            val state = wal.checkpoint()

            assertTrue(!state.busy, "the checkpoint reported busy with no reader in sight")
            assertEquals(0, state.bytes, "the state reports a log that is no longer there")
            assertEquals(0, wal.walBytes(), "the log was not truncated")
            // The rows are the point: a truncated log that lost writes would pass every size check
            // above and be the worst possible outcome.
            assertEquals(
                200,
                db
                    .fetchAll("SELECT count(*) FROM t;")
                    .getOrThrow()
                    .rows
                    .first()
                    .get(0)
                    .asLong(),
            )
            db.close().getOrThrow()
        }

    @Test
    fun `a database with no log at all reads as zero rather than failing`() =
        runTest {
            // Not hypothetical: readiness asks for this number on a service that has been sent
            // nothing yet, and an exception here would fail a probe over an empty database.
            val db = openDatabase(freshPath())
            assertEquals(0, WalCheckpoint(db, "/tmp/xyk-no-such-${Random.nextLong()}.db").walBytes())
            db.close().getOrThrow()
        }
}
