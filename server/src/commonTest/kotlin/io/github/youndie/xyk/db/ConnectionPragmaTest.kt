package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `PRAGMA synchronous` is connection state, and a pragma sent through the pool reaches one
 * connection.
 *
 * This is the probe that settles it — N concurrent holders, each asked what its own connection
 * thinks — and it is a test rather than a one-off script because the failure is silent: the service
 * would commit most of its writes with a full fsync while every comment in the codebase said
 * otherwise. `1` is NORMAL, `2` is FULL, which is SQLite's default and what an unpinned connection
 * reports.
 */
class ConnectionPragmaTest {
    private fun freshPath(): String = "/tmp/xyk-pragma-${Random.nextLong()}.db"

    @Test
    fun `every connection in the pool carries synchronous NORMAL`() =
        runTest {
            val pool = 3
            val db = openDatabase(freshPath(), maxConnections = pool)

            val levels =
                db.onEveryConnection(pool) { connection ->
                    connection
                        .fetchAll("PRAGMA synchronous;")
                        .getOrThrow()
                        .rows
                        .first()
                        .get(0)
                        .asLong()
                }

            assertEquals(List(pool) { 1L }, levels, "at least one connection is still on FULL")
            db.close().getOrThrow()
        }

    @Test
    fun `the probe can tell the difference — a pragma sent through the pool does not reach them all`() =
        runTest {
            // The positive control. Without it, the test above passes on a pool that hands the same
            // connection out three times, and proves nothing at all. Here the pragma is deliberately
            // sent the naive way, to a fresh pool, and at least one connection must disagree.
            val pool = 3
            val db = openDatabase(freshPath(), maxConnections = pool)
            db.execute("PRAGMA synchronous = FULL;").getOrThrow()

            val levels =
                db.onEveryConnection(pool) { connection ->
                    connection
                        .fetchAll("PRAGMA synchronous;")
                        .getOrThrow()
                        .rows
                        .first()
                        .get(0)
                        .asLong()
                }

            assertEquals(
                setOf(1L, 2L),
                levels.toSet(),
                "the naive pragma reached every connection, or none — either way this probe cannot " +
                    "see the difference it exists to see, and the test above means nothing",
            )
            db.close().getOrThrow()
        }
}
