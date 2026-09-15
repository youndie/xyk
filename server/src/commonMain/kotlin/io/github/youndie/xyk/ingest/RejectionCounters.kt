package io.github.youndie.xyk.ingest

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a request was turned away. One value per distinguishable incident, and no more. */
enum class RejectionReason {
    UNKNOWN_ENDPOINT,
    SIGNATURE_MISSING,
    SIGNATURE_INVALID,
    SIGNATURE_STALE,
    BODY_TOO_LARGE,
    NOT_STORED,
}

/**
 * How many requests each endpoint turned away, and why.
 *
 * **Counted in memory and flushed on a timer, rather than written per rejection.** A rejected
 * request must not cost a write: a flood of bad signatures is exactly the case these counters exist
 * to show, and at a few thousand a second an upsert per rejection would take SQLite's single writer
 * lock away from the genuine traffic beside it. The price is stated rather than hidden — **a crash
 * loses at most one flush interval of counts** — and it is the right trade for a number read during
 * an incident rather than billed against.
 *
 * A rejection for an endpoint that does not exist is counted against [GLOBAL], not against the id in
 * the URL. Anything else would let anyone with a URL bar create unbounded rows in somebody else's
 * database.
 */
class RejectionCounters {
    private val mutex = Mutex()
    private val counts = mutableMapOf<Pair<String, RejectionReason>, Long>()

    suspend fun record(
        endpointId: String?,
        reason: RejectionReason,
    ) {
        val key = (endpointId ?: GLOBAL) to reason
        mutex.withLock { counts[key] = (counts[key] ?: 0L) + 1L }
    }

    /** Takes the pending counts and clears them, so a flush cannot count the same rejection twice. */
    suspend fun drain(): Map<Pair<String, RejectionReason>, Long> =
        mutex.withLock {
            val snapshot = counts.toMap()
            counts.clear()
            snapshot
        }

    companion object {
        /**
         * Where rejections with no endpoint to blame are counted.
         *
         * A sentinel string rather than `NULL`: SQLite treats NULLs as distinct in a unique index,
         * so the upsert that keeps one row per (endpoint, reason) would insert a new row every time.
         */
        const val GLOBAL: String = "(unknown)"
    }
}
