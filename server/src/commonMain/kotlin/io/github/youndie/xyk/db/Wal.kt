package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/** What one forced checkpoint did, and how big the write-ahead log is afterwards. */
@Serializable
data class WalState(
    /** Size of the `-wal` file. Zero when there is none — nothing has been written yet. */
    val bytes: Long,
    /** True when readers held the checkpoint off and the log could not be reset. */
    val busy: Boolean,
    /** Frames in the log when the checkpoint ran. */
    val framesInLog: Long,
    /** Frames it managed to move into the database file. */
    val framesCheckpointed: Long,
)

/**
 * Forces the write-ahead log back to the start, because SQLite's own checkpoint cannot.
 *
 * **This is not tuning; it is the failure mode xyk is shaped around.** The journal page reads while
 * ingest writes, and SQLite's automatic checkpoint is only ever PASSIVE: it moves frames older than
 * the oldest **active reader** and never resets the file while one is alive. With overlapping
 * readers there is always one, so the log only grows — and a growing log makes reads slower, which
 * at a fixed arrival rate raises the number of requests in flight, the number of threads, and with
 * them the number of per-thread malloc arenas. The limit is reached by the second effect. Measured
 * elsewhere in this portfolio: a 931 MB log beside a 187 MB database, OOM-killed at 256 MiB.
 *
 * TRUNCATE rather than RESTART: RESTART also resets the log but leaves the file at its high-water
 * mark, and the high-water mark is the number this exists to keep down.
 *
 * A busy result is not an error — it means readers were in the way this time. It is reported rather
 * than retried: the next sweep is seconds away and the size it reports is the honest state.
 *
 * Taken from tracy's `WalCheckpoint`, which is where the mechanism was diagnosed and measured.
 */
class WalCheckpoint(
    private val db: ISQLite,
    private val dbPath: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    suspend fun checkpoint(): WalState {
        // `PRAGMA wal_checkpoint` answers with one row: busy, frames in the log, frames moved.
        val row =
            db
                .fetchAll("PRAGMA wal_checkpoint(TRUNCATE);")
                .getOrThrow()
                .rows
                .firstOrNull()
        return WalState(
            bytes = walBytes(),
            busy = (row?.get(0)?.asLong() ?: 0L) != 0L,
            framesInLog = row?.get(1)?.asLong() ?: 0L,
            framesCheckpointed = row?.get(2)?.asLong() ?: 0L,
        )
    }

    /**
     * The log on disk, which nothing else counts.
     *
     * `PRAGMA page_count * page_size` — the number any "database size" field reports — does **not**
     * include pages that are still in the journal. A server can therefore fill a disk while
     * reporting itself well under its cap, which is what 931 MB of journal beside a 183 MB database
     * looked like from the outside: nothing at all.
     */
    fun walBytes(): Long = fileSystem.metadataOrNull("$dbPath-wal".toPath())?.size ?: 0L
}
