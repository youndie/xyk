package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.xyk.ServerConfig
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * How many connections the pool keeps.
 *
 * **Two, not the driver's ten, and it is not a throughput setting.** Every sqlx4k connection is an
 * OS thread with its own page cache and its own malloc arena — and, more expensively, one more
 * reader. SQLite's automatic checkpoint is only ever PASSIVE: it does not truncate the journal while
 * a single reader is alive, and xyk is read (the journal page) while it is written (ingest), so that
 * window would never open. Measured elsewhere at ten connections: the process is killed under both
 * 256Mi and 128Mi; at two it went twice as far on twice the database for a third of the memory.
 *
 * B-04 adds the explicit `wal_checkpoint(TRUNCATE)` this number is only half of.
 */
const val SQLITE_POOL: Int = 2

/**
 * Opens the database and brings the schema up to date — **before the engine binds a port**.
 *
 * It is called from `main` rather than from the Ktor module for the sake of the shutdown: the
 * release stage has to close this pool *after* the engine has drained, and a close wired to
 * `ApplicationStopping` runs before the drain on Kotlin/Native and after it on the JVM, from
 * identical source.
 */
fun openDatabase(config: ServerConfig): ISQLite = openDatabase(config.sqlitePath, config.sqlitePoolSize)

/**
 * The same, addressed by path — which is what a test can use.
 *
 * A **file** database, in every case including the tests. sqlx4k is two drivers, and the JVM half
 * refuses a pool larger than one against `:memory:` while a pool pinned to one deadlocks the moment
 * a transaction asks for a second connection. A file in a temporary directory is also the shape
 * production runs in, which is the stronger argument.
 */
fun openDatabase(
    path: String,
    maxConnections: Int = SQLITE_POOL,
): ISQLite {
    val options =
        ConnectionPool.Options
            .builder()
            .maxConnections(maxConnections)
            .build()

    // The volume in the cluster is mounted empty, so the directory is ours to create. okio rather
    // than a platform call: this runs on both builds.
    val dbPath = path.toPath()
    val fileSystem = FileSystem.SYSTEM
    if (!fileSystem.exists(dbPath)) {
        dbPath.parent?.let { parent ->
            if (!fileSystem.exists(parent)) fileSystem.createDirectories(parent)
        }
        fileSystem.write(dbPath) { /* an empty file is a valid empty database */ }
    }

    val db = sqlite(url = "sqlite://" + path, options = options)

    runBlocking {
        db.migrateSchema()
        // After the migrations and before anything serves: every connection the pool may open has to
        // carry `synchronous = NORMAL`, and the only way to reach them all is to hold them all at
        // once. See `pinSynchronousOnEveryConnection`.
        db.pinSynchronousOnEveryConnection(maxConnections)
    }

    return db
}
