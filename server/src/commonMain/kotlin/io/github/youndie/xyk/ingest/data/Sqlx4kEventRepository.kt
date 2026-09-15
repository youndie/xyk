package io.github.youndie.xyk.ingest.data

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.db.toSqliteBlobLiteral
import io.github.youndie.xyk.ingest.domain.AcceptedEvent
import io.github.youndie.xyk.ingest.domain.EventRepository
import io.github.youndie.xyk.ingest.domain.IngestEndpoint
import io.github.youndie.xyk.newId
import io.github.youndie.xyk.verify.EndpointSecret
import io.github.youndie.xyk.verify.SchemeConfig

/** SQLite behind the ingest port. The only file in this feature that knows any SQL. */
class Sqlx4kEventRepository(
    private val db: ISQLite,
) : EventRepository {
    override suspend fun findEndpoint(endpointId: String): IngestEndpoint? {
        val quoted = endpointId.quoted()
        val row =
            db
                .fetchAll("SELECT scheme, scheme_config FROM endpoints WHERE id = $quoted AND enabled = 1;")
                .getOrThrow()
                .rows
                .firstOrNull() ?: return null

        val secrets =
            db
                .fetchAll(
                    "SELECT secret, fingerprint FROM endpoint_secrets WHERE endpoint_id = $quoted " +
                        "ORDER BY created_at DESC;",
                ).getOrThrow()
                .rows
                .map { EndpointSecret(it.get(0).asString(), it.get(1).asString()) }

        val subscribers =
            db
                .fetchAll("SELECT id FROM subscribers WHERE endpoint_id = $quoted AND enabled = 1;")
                .getOrThrow()
                .rows
                .map { it.get(0).asString() }

        return IngestEndpoint(
            id = endpointId,
            scheme = row.get(0).asString(),
            secrets = secrets,
            subscriberIds = subscribers,
            schemeConfig = SchemeConfig.parse(row.get(1).asStringOrNull()),
        )
    }

    /**
     * One transaction, and everything that could fail is inside it.
     *
     * The endpoint lookup deliberately happened before and outside: it reads nearly-static data, and
     * holding SQLite's single writer lock across it would serialise every ingest behind one read.
     */
    override suspend fun accept(
        endpoint: IngestEndpoint,
        receivedAt: Long,
        scheme: String,
        secretFingerprint: String?,
        contentType: String?,
        body: ByteArray,
    ): AcceptedEvent {
        val eventId = newId()
        db.transaction {
            execute(
                "INSERT INTO events " +
                    "(id, endpoint_id, received_at, scheme, secret_fingerprint, content_type, body, body_bytes) " +
                    "VALUES (${eventId.quoted()}, ${endpoint.id.quoted()}, $receivedAt, ${scheme.quoted()}, " +
                    "${secretFingerprint.quotedOrNull()}, ${contentType.quotedOrNull()}, " +
                    "${body.toSqliteBlobLiteral()}, ${body.size});",
            ).getOrThrow()

            for (subscriberId in endpoint.subscriberIds) {
                execute(
                    "INSERT INTO deliveries (id, event_id, subscriber_id, state, attempts, created_at) " +
                        "VALUES (${newId().quoted()}, ${eventId.quoted()}, ${subscriberId.quoted()}, " +
                        "'pending', 0, $receivedAt);",
                ).getOrThrow()
            }

            // B-03 puts `chronik.schedule(tx, ...)` here, in this same transaction — one timer per
            // delivery. Until chronik publishes a native artifact the delivery rows are written and
            // nothing claims them, which is why B-10 cannot start before B-03 either.
        }
        return AcceptedEvent(id = eventId, deliveries = endpoint.subscriberIds.size)
    }

    /**
     * Quoting for the values that reach SQL as text.
     *
     * Every one of them is an id this process generated or a name out of a fixed set — not a webhook
     * body, which goes through a hex literal instead ([toSqliteBlobLiteral]). The doubling is
     * SQLite's own escape and it is here because sqlx4k renders statements into SQL text rather than
     * preparing them, so there is no parameter to bind to.
     */
    private fun String.quoted(): String = "'" + replace("'", "''") + "'"

    private fun String?.quotedOrNull(): String = this?.quoted() ?: "NULL"
}

/** Reads a count out of the first column of the first row. Used by the tests and the journal. */
internal suspend fun ISQLite.countOf(sql: String): Long =
    fetchAll(sql)
        .getOrThrow()
        .rows
        .first()
        .get(0)
        .asLong()
