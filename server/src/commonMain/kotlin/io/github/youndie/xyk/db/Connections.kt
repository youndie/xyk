package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Puts `PRAGMA synchronous = NORMAL` on **every** connection the pool may open, not on the one that
 * happened to answer first.
 *
 * `synchronous` is connection state, and sqlx4k offers no hook that runs on connect — its own
 * `after_connect` sets `journal_mode` and nothing else, and the connection URL accepts only the four
 * parameters SQLite defines for URI filenames. So a plain `db.execute("PRAGMA …")` lands wherever
 * the pool sends it and the other connections keep SQLite's default of FULL: an fsync per commit on
 * most of the writes, while the comment above it claims otherwise. Measured elsewhere with a probe
 * of six concurrent transactions on a pool of six: `sync=1` on one of them, `sync=2` on five.
 *
 * **This pins the pool as it is at start-up and nothing more.** A connection closed later and
 * reopened comes back with FULL, and this will not have been called again. It is a workaround; the
 * fix belongs in sqlx4k, as a hook that runs on connect.
 */
suspend fun ISQLite.pinSynchronousOnEveryConnection(maxConnections: Int) {
    require(maxConnections > 0) { "maxConnections must be greater than 0" }
    onEveryConnection(maxConnections) { it.execute("PRAGMA synchronous = NORMAL;").getOrThrow() }
}

/**
 * Runs [block] once on each of [count] connections, with all of them held at the same time.
 *
 * Holding them all is the whole mechanism: a pool asked for connections one after another answers
 * with the same one, and anything set on it would look like it had been set everywhere.
 *
 * `acquire()` rather than a transaction, because SQLite refuses this pragma inside one — "Safety
 * level may not be changed inside a transaction".
 */
internal suspend fun <T> ISQLite.onEveryConnection(
    count: Int,
    block: suspend (Connection) -> T,
): List<T> =
    coroutineScope {
        val allHeld = CompletableDeferred<Unit>()
        val counter = Mutex()
        var held = 0
        (1..count)
            .map {
                async {
                    val connection = acquireWaitingOutBusy()
                    try {
                        val n = counter.withLock { ++held }
                        if (n == count) allHeld.complete(Unit)
                        allHeld.await()
                        block(connection)
                    } finally {
                        // For a pooled connection this is the release, not a disconnect.
                        connection.close().getOrThrow()
                    }
                }
            }.awaitAll()
    }

/** How long the retry below is willing to wait in total, and how often it looks. */
private const val BUSY_ATTEMPTS = 100
private val BUSY_PAUSE = 20.milliseconds

/**
 * Acquires a connection, waiting out `SQLITE_BUSY` instead of failing on it.
 *
 * **Measured, not defensive.** Opening several pooled connections to the same file at once makes the
 * JVM driver answer `[SQLITE_BUSY] The database file is locked` *while creating* one of them — the
 * failure comes out of sqlx4k's own `connectionFactory`, which runs `PRAGMA journal_mode` on every
 * new connection, and that pragma needs a write lock the sibling connections are holding. It failed
 * one run in two of `jvmTest` and never once on the native target: sqlx4k is two drivers, and this
 * is the JVM one.
 *
 * It cannot be fixed with `busy_timeout`, which is what SQLite offers for exactly this: the failure
 * happens inside the driver before any statement of ours reaches the connection, and the connection
 * URL accepts only the four parameters SQLite defines for URI filenames. So the wait is here.
 *
 * Only the start-up pin and the probe go through this path, so a slow retry costs nothing that
 * serves traffic.
 */
private suspend fun ISQLite.acquireWaitingOutBusy(): Connection {
    repeat(BUSY_ATTEMPTS) {
        val attempt = acquire()
        val failure = attempt.exceptionOrNull() ?: return attempt.getOrThrow()
        val message = failure.message.orEmpty()
        if (!message.contains("SQLITE_BUSY") && !message.contains("database is locked")) {
            throw failure
        }
        delay(BUSY_PAUSE)
    }
    return acquire().getOrThrow()
}
