package io.github.youndie.xyk.journal.data

import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.db.fromSqliteHex
import io.github.youndie.xyk.delivery.TimerScheduler
import io.github.youndie.xyk.journal.domain.DeliveryLine
import io.github.youndie.xyk.journal.domain.EventDetail
import io.github.youndie.xyk.journal.domain.EventLine
import io.github.youndie.xyk.journal.domain.EventPage
import io.github.youndie.xyk.journal.domain.JournalFilter
import io.github.youndie.xyk.journal.domain.JournalRepository
import io.github.youndie.xyk.journal.domain.PayloadMeta
import io.github.youndie.xyk.newId

/**
 * The journal over SQLite.
 *
 * **No query here reads a body.** `body_bytes` is a stored column precisely so that a list page
 * never touches the largest thing in the row; the payload has a route of its own (B-13).
 */
class Sqlx4kJournalRepository(
    private val db: ISQLite,
    /** The same writer the ingest path uses; `null` in a build that cannot deliver. */
    private val scheduler: TimerScheduler? = null,
) : JournalRepository {
    override suspend fun recent(filter: JournalFilter): List<EventLine> {
        val conditions = mutableListOf<String>()
        filter.endpointId?.let { conditions += "e.endpoint_id = ${it.quoted()}" }
        // The state of an event is the state of its deliveries: pending if any is waiting, dead if
        // any gave up, delivered otherwise — including an event nobody was waiting for.
        filter.state?.let { state ->
            conditions +=
                when (state) {
                    "pending" -> {
                        "EXISTS (SELECT 1 FROM deliveries d WHERE d.event_id = e.id AND d.state = 'pending')"
                    }

                    "dead" -> {
                        "EXISTS (SELECT 1 FROM deliveries d WHERE d.event_id = e.id AND d.state = 'dead')"
                    }

                    "delivered" -> {
                        "NOT EXISTS (SELECT 1 FROM deliveries d " +
                            "WHERE d.event_id = e.id AND d.state != 'delivered')"
                    }

                    // An unknown filter shows everything rather than nothing: a typo in a query
                    // string must not look like an empty journal.
                    else -> {
                        "1 = 1"
                    }
                }
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val limit = filter.limit.coerceIn(1, JournalFilter.MAX_LIMIT)

        return db
            .fetchAll(SELECT_EVENTS + where + " ORDER BY e.received_at DESC, e.id DESC LIMIT $limit;")
            .getOrThrow()
            .rows
            .map { it.toLine() }
    }

    override suspend fun detail(eventId: String): EventDetail? {
        val event =
            db
                .fetchAll(SELECT_EVENTS + " WHERE e.id = ${eventId.quoted()};")
                .getOrThrow()
                .rows
                .firstOrNull()
                ?.toLine() ?: return null

        val deliveries =
            db
                .fetchAll(
                    "SELECT d.id, s.url, d.state, d.attempts, d.created_at FROM deliveries d " +
                        "LEFT JOIN subscribers s ON s.id = d.subscriber_id " +
                        "WHERE d.event_id = ${eventId.quoted()} ORDER BY d.created_at;",
                ).getOrThrow()
                .rows
                .map { row ->
                    DeliveryLine(
                        id = row.get(0).asString(),
                        // LEFT JOIN: a subscriber can be removed while its deliveries stay, and the
                        // attempts must remain visible attributed to something.
                        subscriberUrl = row.get(1).asStringOrNull(),
                        state = row.get(2).asString(),
                        attempts = row.get(3).asInt(),
                        createdAt = row.get(4).asLong(),
                    )
                }

        return EventDetail(event, deliveries)
    }

    override suspend fun page(
        filter: JournalFilter,
        cursor: String?,
    ): EventPage {
        val conditions = mutableListOf<String>()
        filter.endpointId?.let { conditions += "e.endpoint_id = ${it.quoted()}" }
        // The cursor is the sort key itself: (received_at, id). Written out as the comparison rather
        // than as a row value, because SQLite's row-value support is newer than some of the builds
        // this may run on and the expansion is three lines.
        cursor?.let { raw ->
            val at = raw.substringBefore('.').toLongOrNull()
            val id = raw.substringAfter('.', missingDelimiterValue = "")
            if (at != null && id.isNotEmpty()) {
                conditions +=
                    "(e.received_at < $at OR (e.received_at = $at AND e.id < ${id.quoted()}))"
            }
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val limit = filter.limit.coerceIn(1, JournalFilter.MAX_LIMIT)

        // One more than asked for: the extra row is how the page knows there is a next one without
        // a second `count(*)` over a table that is being written to.
        val rows =
            db
                .fetchAll(SELECT_EVENTS + where + " ORDER BY e.received_at DESC, e.id DESC LIMIT ${limit + 1};")
                .getOrThrow()
                .rows
                .map { it.toLine() }

        val page = rows.take(limit)
        val next = if (rows.size > limit) page.lastOrNull()?.let { "${it.receivedAt}.${it.id}" } else null
        return EventPage(page, next)
    }

    override suspend fun payloadMeta(eventId: String): PayloadMeta? =
        db
            .fetchAll(
                "SELECT body_bytes, content_type, purged_at FROM events WHERE id = ${eventId.quoted()};",
            ).getOrThrow()
            .rows
            .firstOrNull()
            ?.let { row ->
                PayloadMeta(
                    bytes = row.get(0).asLong(),
                    contentType = row.get(1).asStringOrNull(),
                    purgedAt = row.get(2).asLongOrNull(),
                )
            }

    override suspend fun payloadChunk(
        eventId: String,
        offset: Long,
        length: Int,
    ): ByteArray {
        // SQLite's `substr` is 1-based on blobs, and it slices inside the database — which is the
        // whole point: the driver hands back a value, so a whole-blob read would put the payload in
        // memory twice (once as hex) before a single byte reached the socket.
        val hex =
            db
                .fetchAll(
                    "SELECT hex(substr(body, ${offset + 1}, $length)) FROM events " +
                        "WHERE id = ${eventId.quoted()};",
                ).getOrThrow()
                .rows
                .firstOrNull()
                ?.get(0)
                ?.asStringOrNull()
                .orEmpty()
        return if (hex.isEmpty()) ByteArray(0) else hex.fromSqliteHex()
    }

    override suspend fun redeliver(
        eventId: String,
        nowEpochSeconds: Long,
    ): Int {
        val subscribers =
            db
                .fetchAll(
                    "SELECT s.id FROM subscribers s JOIN events e ON e.endpoint_id = s.endpoint_id " +
                        "WHERE e.id = ${eventId.quoted()} AND s.enabled = 1;",
                ).getOrThrow()
                .rows
                .map { it.get(0).asString() }
        if (subscribers.isEmpty()) return 0

        // A build that cannot deliver must not pretend to redeliver: rows written with no timer
        // would sit `pending` for ever and the page would report a redelivery that never happens.
        if (scheduler == null) return 0

        db.transaction {
            for (subscriberId in subscribers) {
                val deliveryId = newId()
                execute(
                    "INSERT INTO deliveries (id, event_id, subscriber_id, state, attempts, created_at) " +
                        "VALUES (${deliveryId.quoted()}, ${eventId.quoted()}, ${subscriberId.quoted()}, " +
                        "'pending', 0, $nowEpochSeconds);",
                ).getOrThrow()
                // In the same transaction, for the same reason as the ingest path: the row and its
                // timer commit together or neither does.
                scheduler.schedule(this, deliveryId, nowEpochSeconds)
            }
        }
        return subscribers.size
    }

    private fun String.quoted(): String = "'" + replace("'", "''") + "'"

    private fun io.github.smyrgeorge.sqlx4k.ResultSet.Row.toLine(): EventLine =
        EventLine(
            id = get(0).asString(),
            endpointId = get(1).asString(),
            receivedAt = get(2).asLong(),
            scheme = get(3).asString(),
            secretFingerprint = get(4).asStringOrNull(),
            contentType = get(5).asStringOrNull(),
            bodyBytes = get(6).asLong(),
            // The size the payload ARRIVED with, even after a purge: "47 bytes, purged" is the
            // answer to what happened; "0 bytes" is a different and wrong one.
            purgedAt = get(7).asLongOrNull(),
            deliveries = get(8).asInt(),
            pending = get(9).asInt(),
            dead = get(10).asInt(),
        )

    private companion object {
        const val SELECT_EVENTS =
            "SELECT e.id, e.endpoint_id, e.received_at, e.scheme, e.secret_fingerprint, " +
                "e.content_type, e.body_bytes, e.purged_at, " +
                "(SELECT count(*) FROM deliveries d WHERE d.event_id = e.id), " +
                "(SELECT count(*) FROM deliveries d WHERE d.event_id = e.id AND d.state = 'pending'), " +
                "(SELECT count(*) FROM deliveries d WHERE d.event_id = e.id AND d.state = 'dead') " +
                "FROM events e"
    }
}
