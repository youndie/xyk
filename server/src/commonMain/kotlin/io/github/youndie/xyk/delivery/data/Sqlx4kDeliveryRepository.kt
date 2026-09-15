package io.github.youndie.xyk.delivery.data

import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.db.fromSqliteHex
import io.github.youndie.xyk.delivery.domain.AttemptRecord
import io.github.youndie.xyk.delivery.domain.DeliveryRepository
import io.github.youndie.xyk.delivery.domain.DeliveryTarget
import io.github.youndie.xyk.newId

/**
 * The outbound half's storage, over SQLite.
 *
 * **The body is read here and nowhere else on this path.** It is the one query in the service that
 * deliberately loads a whole payload into memory, because a POST needs all of it; every list and
 * page reads `body_bytes` instead. That is also why the sink is called once per delivery rather than
 * once per batch — a batch of fifty would hold fifty payloads at once.
 */
class Sqlx4kDeliveryRepository(
    private val db: ISQLite,
) : DeliveryRepository {
    override suspend fun target(deliveryId: String): DeliveryTarget? =
        db
            .fetchAll(
                "SELECT d.id, d.event_id, e.endpoint_id, s.url, e.content_type, hex(e.body), d.attempts " +
                    "FROM deliveries d " +
                    "JOIN events e ON e.id = d.event_id " +
                    "JOIN subscribers s ON s.id = d.subscriber_id " +
                    // A disabled subscriber is not a target. The delivery stays in the journal with
                    // its history; what stops is sending to an address an operator has switched off,
                    // and the timer retires rather than retrying against it for five rounds.
                    "WHERE d.id = ${deliveryId.quoted()} AND s.enabled = 1 AND e.purged_at IS NULL;",
            ).getOrThrow()
            .rows
            .firstOrNull()
            ?.let { row ->
                val hex = row.get(5).asStringOrNull().orEmpty()
                DeliveryTarget(
                    deliveryId = row.get(0).asString(),
                    eventId = row.get(1).asString(),
                    endpointId = row.get(2).asString(),
                    subscriberUrl = row.get(3).asString(),
                    contentType = row.get(4).asStringOrNull(),
                    body = if (hex.isEmpty()) ByteArray(0) else hex.fromSqliteHex(),
                    attemptsSoFar = row.get(6).asInt(),
                )
            }

    override suspend fun recordAttempt(
        record: AttemptRecord,
        newState: String,
    ) {
        // ONE TRANSACTION for the row and the counters. Apart, the two states this can stop in are
        // both unexplainable from the journal: an attempt nobody counted, or a count with nothing
        // behind it.
        db.transaction {
            execute(
                "INSERT INTO delivery_attempts (id, delivery_id, attempt, status, duration_ms, detail, at) " +
                    "VALUES (${newId().quoted()}, ${record.deliveryId.quoted()}, ${record.attempt}, " +
                    "${record.status?.toString() ?: "NULL"}, ${record.durationMs}, " +
                    "${record.detail.quoted()}, ${record.atEpochSeconds});",
            ).getOrThrow()
            execute(
                "UPDATE deliveries SET attempts = ${record.attempt}, state = ${newState.quoted()} " +
                    "WHERE id = ${record.deliveryId.quoted()};",
            ).getOrThrow()
        }
    }

    /** The attempts of one delivery, oldest first — what the journal's detail page shows. */
    suspend fun attempts(deliveryId: String): List<AttemptRecord> =
        db
            .fetchAll(
                "SELECT delivery_id, attempt, status, duration_ms, detail, at FROM delivery_attempts " +
                    "WHERE delivery_id = ${deliveryId.quoted()} ORDER BY attempt;",
            ).getOrThrow()
            .rows
            .map { row ->
                AttemptRecord(
                    deliveryId = row.get(0).asString(),
                    attempt = row.get(1).asInt(),
                    status = row.get(2).asLongOrNull()?.toInt(),
                    durationMs = row.get(3).asLong(),
                    detail = row.get(4).asString(),
                    atEpochSeconds = row.get(5).asLong(),
                )
            }

    private fun String.quoted(): String = "'" + replace("'", "''") + "'"
}
