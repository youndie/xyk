package io.github.youndie.xyk.registry.data

import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.registry.domain.EndpointRecord
import io.github.youndie.xyk.registry.domain.RegistryRepository
import io.github.youndie.xyk.registry.domain.SubscriberRecord

/** SQLite behind the registry port. */
class Sqlx4kRegistryRepository(
    private val db: ISQLite,
) : RegistryRepository {
    override suspend fun list(): List<EndpointRecord> =
        db
            .fetchAll(SELECT_ENDPOINTS + " ORDER BY e.created_at DESC;")
            .getOrThrow()
            .rows
            .map { row -> row.toRecord() }

    override suspend fun find(id: String): EndpointRecord? =
        db
            .fetchAll(SELECT_ENDPOINTS + " WHERE e.id = ${id.quoted()};")
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.toRecord()

    override suspend fun create(
        id: String,
        scheme: String,
        description: String,
        secret: String,
        fingerprint: String,
        createdAt: Long,
        schemeConfig: String?,
    ) {
        db.transaction {
            execute(
                "INSERT INTO endpoints (id, scheme, enabled, description, created_at, scheme_config) " +
                    "VALUES (${id.quoted()}, ${scheme.quoted()}, 1, ${description.quoted()}, $createdAt, " +
                    "${schemeConfig?.quoted() ?: "NULL"});",
            ).getOrThrow()
            if (secret.isNotBlank()) {
                execute(insertSecret(id, secret, fingerprint, createdAt)).getOrThrow()
            }
        }
    }

    override suspend fun addSecret(
        endpointId: String,
        secret: String,
        fingerprint: String,
        createdAt: Long,
    ) {
        db.execute(insertSecret(endpointId, secret, fingerprint, createdAt)).getOrThrow()
    }

    override suspend fun setEnabled(
        endpointId: String,
        enabled: Boolean,
    ) {
        db
            .execute(
                "UPDATE endpoints SET enabled = ${if (enabled) 1 else 0} WHERE id = ${endpointId.quoted()};",
            ).getOrThrow()
    }

    override suspend fun setDescription(
        endpointId: String,
        description: String,
    ) {
        db
            .execute(
                "UPDATE endpoints SET description = ${description.quoted()} WHERE id = ${endpointId.quoted()};",
            ).getOrThrow()
    }

    override suspend fun listSubscribers(endpointId: String): List<SubscriberRecord> =
        db
            .fetchAll(
                "SELECT id, url, enabled FROM subscribers WHERE endpoint_id = ${endpointId.quoted()} " +
                    "ORDER BY created_at;",
            ).getOrThrow()
            .rows
            .map { row ->
                SubscriberRecord(
                    id = row.get(0).asString(),
                    url = row.get(1).asString(),
                    enabled = row.get(2).asInt() != 0,
                )
            }

    override suspend fun addSubscriber(
        id: String,
        endpointId: String,
        url: String,
        createdAt: Long,
    ) {
        db
            .execute(
                "INSERT INTO subscribers (id, endpoint_id, url, enabled, created_at) " +
                    "VALUES (${id.quoted()}, ${endpointId.quoted()}, ${url.quoted()}, 1, $createdAt);",
            ).getOrThrow()
    }

    override suspend fun removeSubscriber(id: String): Boolean {
        val existed =
            db
                .fetchAll("SELECT count(*) FROM subscribers WHERE id = ${id.quoted()};")
                .getOrThrow()
                .rows
                .first()
                .get(0)
                .asLong() > 0
        if (existed) db.execute("DELETE FROM subscribers WHERE id = ${id.quoted()};").getOrThrow()
        return existed
    }

    override suspend fun rejections(): Map<String, Map<String, Long>> {
        val out = mutableMapOf<String, MutableMap<String, Long>>()
        db
            .fetchAll("SELECT endpoint_id, reason, count FROM rejections;")
            .getOrThrow()
            .rows
            .forEach { row ->
                val endpointId = row.get(0).asString()
                out.getOrPut(endpointId) { mutableMapOf() }[row.get(1).asString()] = row.get(2).asLong()
            }
        return out
    }

    override suspend fun anyEnabledId(): String? =
        db
            .fetchAll("SELECT id FROM endpoints WHERE enabled = 1 ORDER BY created_at LIMIT 1;")
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asString()

    private fun insertSecret(
        endpointId: String,
        secret: String,
        fingerprint: String,
        createdAt: Long,
    ): String =
        "INSERT INTO endpoint_secrets (id, endpoint_id, secret, fingerprint, created_at, retires_at) " +
            "VALUES (${io.github.youndie.xyk.newId().quoted()}, ${endpointId.quoted()}, " +
            "${secret.quoted()}, ${fingerprint.quoted()}, $createdAt, NULL);"

    private fun io.github.smyrgeorge.sqlx4k.ResultSet.Row.toRecord(): EndpointRecord =
        EndpointRecord(
            id = get(0).asString(),
            scheme = get(1).asString(),
            enabled = get(2).asInt() != 0,
            description = get(3).asString(),
            createdAt = get(4).asLong(),
            // Group-concatenated rather than a second query per row: the list page would otherwise
            // be one query plus one per endpoint, and that shape only shows up once there are
            // endpoints to notice it with.
            secretFingerprints =
                get(5)
                    .asStringOrNull()
                    .orEmpty()
                    .split(",")
                    .filter { it.isNotEmpty() },
            subscriberCount = get(6).asInt(),
        )

    private fun String.quoted(): String = "'" + replace("'", "''") + "'"

    private companion object {
        const val SELECT_ENDPOINTS =
            "SELECT e.id, e.scheme, e.enabled, e.description, e.created_at, " +
                "(SELECT group_concat(s.fingerprint) FROM endpoint_secrets s WHERE s.endpoint_id = e.id), " +
                "(SELECT count(*) FROM subscribers b WHERE b.endpoint_id = e.id) " +
                "FROM endpoints e"
    }
}
