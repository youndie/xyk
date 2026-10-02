package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.BootstrapEndpoint
import io.github.youndie.xyk.newId
import org.kotlincrypto.hash.sha2.SHA256

/**
 * Puts the configured endpoint, its secret and its subscribers into the tables — idempotently.
 *
 * This is B-06's stand-in for the registry of B-07, and it is deliberately written against the real
 * schema rather than around it: when the admin routes arrive they insert the same rows, and nothing
 * else in the service has to learn that endpoints are now created a different way.
 *
 * Idempotent by content: the endpoint is upserted, and a secret is inserted only if this exact one
 * is not already there. A restart therefore does not accumulate secrets, and changing the secret in
 * the environment adds one rather than replacing it — which is the rotation behaviour B-07 needs
 * anyway, arrived at here for free.
 */
suspend fun ISQLite.applyBootstrap(
    endpoint: BootstrapEndpoint,
    nowEpochSeconds: Long,
) {
    val id = endpoint.id
    transaction {
        execute(
            sql(
                "INSERT INTO endpoints (id, scheme, enabled, description, created_at, scheme_config) " +
                    "VALUES (?, ?, 1, 'bootstrap endpoint (B-06)', ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET scheme = excluded.scheme, enabled = 1, " +
                    "scheme_config = excluded.scheme_config;",
                id,
                endpoint.scheme,
                nowEpochSeconds,
                endpoint.schemeConfig,
            ),
        ).getOrThrow()

        val fingerprint = fingerprintOf(endpoint.secret)
        val existing =
            fetchAll(
                sql(
                    "SELECT count(*) FROM endpoint_secrets WHERE endpoint_id = ? AND fingerprint = ?;",
                    id,
                    fingerprint,
                ),
            ).getOrThrow()
                .rows
                .first()
                .get(0)
                .asLong()
        if (existing == 0L) {
            execute(
                sql(
                    "INSERT INTO endpoint_secrets (id, endpoint_id, secret, fingerprint, created_at, retires_at) " +
                        "VALUES (?, ?, ?, ?, ?, NULL);",
                    newId(),
                    id,
                    endpoint.secret,
                    fingerprint,
                    nowEpochSeconds,
                ),
            ).getOrThrow()
        }

        for (url in endpoint.subscriberUrls) {
            val known =
                fetchAll(sql("SELECT count(*) FROM subscribers WHERE endpoint_id = ? AND url = ?;", id, url))
                    .getOrThrow()
                    .rows
                    .first()
                    .get(0)
                    .asLong()
            if (known == 0L) {
                execute(
                    sql(
                        "INSERT INTO subscribers (id, endpoint_id, url, enabled, created_at) VALUES (?, ?, ?, 1, ?);",
                        newId(),
                        id,
                        url,
                        nowEpochSeconds,
                    ),
                ).getOrThrow()
            }
        }
    }
}

/**
 * What the journal shows instead of a secret: the first eight hex characters of its SHA-256.
 *
 * It exists to tell two secrets apart and for nothing else. It is not keyed, so the same secret
 * gives the same fingerprint at two installations; an HMAC under a per-install key would not, and
 * would need a key with somewhere to live. Nothing asks for that today — B-19 decided that secrets
 * are not encrypted at rest and said nothing about how a fingerprint is made.
 */
fun fingerprintOf(secret: String): String {
    val digest = SHA256().digest(secret.encodeToByteArray())
    val hex = StringBuilder()
    for (byte in digest.take(4)) {
        val value = byte.toInt() and 0xFF
        hex.append("0123456789abcdef"[value ushr 4])
        hex.append("0123456789abcdef"[value and 0x0F])
    }
    return hex.toString()
}
