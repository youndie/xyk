package io.github.youndie.xyk.ingest.data

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.db.toSqliteBlobLiteral
import io.github.youndie.xyk.delivery.TimerScheduler
import io.github.youndie.xyk.ingest.domain.AcceptedEvent
import io.github.youndie.xyk.ingest.domain.EventRepository
import io.github.youndie.xyk.ingest.domain.IngestEndpoint
import io.github.youndie.xyk.newId
import io.github.youndie.xyk.verify.EndpointSecret
import io.github.youndie.xyk.verify.SchemeConfig

/** SQLite behind the ingest port. The only file in this feature that knows any SQL. */
class Sqlx4kEventRepository(
    private val db: ISQLite,
    /**
     * The timer writer, or `null` in a build that cannot deliver.
     *
     * When it is `null` **no delivery rows are written either**, and that is deliberate rather than
     * tidy: a `deliveries` row with no timer is a row nothing will ever claim, shown as `pending`
     * in the journal for ever. A build that does not deliver should record that it received the
     * event and stop there — an honest empty column beats a queue that never moves.
     */
    private val scheduler: TimerScheduler? = null,
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

            // ONE TIMER PER DELIVERY ROW, IN THIS TRANSACTION. The pair must commit together or
            // not at all: a delivery row without its timer is a webhook answered `200` that will
            // never be sent, and nothing anywhere would say so — the journal shows it pending, no
            // probe fails, no line is logged. That is the failure chronik's transactional store
            // exists for, and this loop is where it is spent.
            if (scheduler != null) {
                for (subscriberId in endpoint.subscriberIds) {
                    val deliveryId = newId()
                    execute(
                        "INSERT INTO deliveries (id, event_id, subscriber_id, state, attempts, created_at) " +
                            "VALUES (${deliveryId.quoted()}, ${eventId.quoted()}, ${subscriberId.quoted()}, " +
                            "'pending', 0, $receivedAt);",
                    ).getOrThrow()
                    // Due now. The first attempt should happen as soon as a worker looks, and the
                    // backoff for everything after it is chronik's — starting a fresh delivery in
                    // the future would be a second retry policy next to the real one.
                    scheduler.schedule(this, deliveryId, receivedAt)
                }
            }
        }
        return AcceptedEvent(
            id = eventId,
            // What was actually written, not what could have been: a build that cannot deliver
            // reports zero rather than a number that describes another build's behaviour.
            deliveries = if (scheduler == null) 0 else endpoint.subscriberIds.size,
        )
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
