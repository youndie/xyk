package io.github.youndie.xyk.ingest.data

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.db.sql
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
        val row =
            db
                .fetchAll(sql("SELECT scheme, scheme_config FROM endpoints WHERE id = ? AND enabled = 1;", endpointId))
                .getOrThrow()
                .rows
                .firstOrNull() ?: return null

        val secrets =
            db
                .fetchAll(
                    sql(
                        "SELECT secret, fingerprint FROM endpoint_secrets WHERE endpoint_id = ? " +
                            "ORDER BY created_at DESC;",
                        endpointId,
                    ),
                ).getOrThrow()
                .rows
                .map { EndpointSecret(it.get(0).asString(), it.get(1).asString()) }

        val subscribers =
            db
                .fetchAll(sql("SELECT id FROM subscribers WHERE endpoint_id = ? AND enabled = 1;", endpointId))
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
            // The body is bound as a BLOB, byte for byte: it is the one value an attacker writes in full,
            // and the signature was computed over exactly these bytes. It is read back through `hex()`
            // all the same — see `fromSqliteHex` for why the read does not trust the driver.
            execute(
                sql(
                    "INSERT INTO events " +
                        "(id, endpoint_id, received_at, scheme, secret_fingerprint, content_type, body, body_bytes) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?);",
                    eventId,
                    endpoint.id,
                    receivedAt,
                    scheme,
                    secretFingerprint,
                    contentType,
                    body,
                    body.size,
                ),
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
                        sql(
                            "INSERT INTO deliveries (id, event_id, subscriber_id, state, attempts, created_at) " +
                                "VALUES (?, ?, ?, 'pending', 0, ?);",
                            deliveryId,
                            eventId,
                            subscriberId,
                            receivedAt,
                        ),
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
}

/** Reads a count out of the first column of the first row. Used by the tests and the journal. */
internal suspend fun ISQLite.countOf(sql: String): Long =
    fetchAll(sql)
        .getOrThrow()
        .rows
        .first()
        .get(0)
        .asLong()
