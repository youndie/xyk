package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.youndie.xyk.verify.toHex
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import org.kotlincrypto.random.CryptoRand

/**
 * What the journal and the admin API show instead of a secret: the first eight hex characters of an
 * HMAC-SHA256 of the secret under this installation's own key.
 *
 * It exists to tell two secrets apart, and since 2026-10-02 it is keyed (research D7). A plain hash
 * of the secret — what it was until then — gives the same fingerprint for the same secret on every
 * install, so two deployments sharing a secret could be matched by their pages, and anyone who sees a
 * fingerprint can check a guessed secret against it without asking the server. Under a key that
 * never leaves the database neither works: the same secret reads differently on every install, and a
 * guess cannot be checked without the key.
 *
 * Four bytes, as before. The pages and the documents describe eight characters, and the job — two
 * secrets of one endpoint told apart — needs no more.
 */
class SecretFingerprints(
    key: ByteArray,
) {
    private val key: ByteArray = key.copyOf()

    init {
        require(key.size == INSTALL_KEY_BYTES) { "an install key is $INSTALL_KEY_BYTES bytes, not ${key.size}" }
    }

    fun of(secret: String): String =
        HmacSHA256(key)
            .doFinal(secret.encodeToByteArray())
            .copyOf(FINGERPRINT_BYTES)
            .toHex()
}

/**
 * This installation's fingerprints, under the key migration 8 stored.
 *
 * Read, never made here: a key generated on a start that found none would silently re-fingerprint
 * every secret against the ones already stored, and the bootstrap endpoint — idempotent *by
 * fingerprint* — would gain a second copy of its secret on every restart. A database with no key is a
 * database somebody edited by hand, and that is said rather than repaired.
 */
suspend fun QueryExecutor.secretFingerprints(): SecretFingerprints {
    val stored =
        fetchAll(sql("SELECT key FROM install_key WHERE id = ?;", INSTALL_KEY_ROW))
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asString()
    checkNotNull(stored) { "the database has no install key; migration 8 writes one, so it was removed by hand" }
    return SecretFingerprints(stored.fromSqliteHex())
}

/**
 * Migration 8's step that SQL cannot take: draw the key, and re-fingerprint what is already stored.
 *
 * **The key comes from `CryptoRand` and goes into the database**, not into the environment: one more
 * variable is one more thing a deployment has to set and keep, and a key that changed between two
 * starts would make every stored fingerprint stale. It sits beside the secrets it fingerprints, which
 * is no weaker — whoever can read it can read the secrets themselves (Decision 3b).
 *
 * **Stored fingerprints are rewritten, not left behind.** `endpoint_secrets.fingerprint` is compared
 * at bootstrap and copied into `events.secret_fingerprint` at ingest, so a database upgraded without
 * this would keep plain hashes on every existing row and disagree with itself about the bootstrap
 * secret. The secrets are stored as given (Decision 3b), so each is recomputed exactly; an event
 * takes the new fingerprint of the secret whose old one it carries. One `UPDATE` per endpoint maps
 * every old fingerprint at once, so a new value that happens to equal another secret's old one cannot
 * be mapped twice. An event whose fingerprint names no stored secret keeps what it had.
 */
internal suspend fun Transaction.keyTheInstallation() {
    val key = ByteArray(INSTALL_KEY_BYTES).also { CryptoRand.Default.nextBytes(it) }
    execute(sql("INSERT INTO install_key (id, key) VALUES (?, ?);", INSTALL_KEY_ROW, key.toHex())).getOrThrow()
    val fingerprints = SecretFingerprints(key)

    val secrets =
        fetchAll(sql("SELECT id, endpoint_id, secret, fingerprint FROM endpoint_secrets ORDER BY endpoint_id;"))
            .getOrThrow()
            .rows
            .map { row ->
                StoredSecret(
                    id = row.get(0).asString(),
                    endpointId = row.get(1).asString(),
                    old = row.get(3).asString(),
                    new = fingerprints.of(row.get(2).asString()),
                )
            }

    for (secret in secrets) {
        execute(sql("UPDATE endpoint_secrets SET fingerprint = ? WHERE id = ?;", secret.new, secret.id)).getOrThrow()
    }

    for ((endpointId, ofEndpoint) in secrets.groupBy { it.endpointId }) {
        val mapping = ofEndpoint.distinctBy { it.old }
        val cases = mapping.joinToString(" ") { "WHEN ? THEN ?" }
        val values = mapping.flatMap { listOf(it.old, it.new) } + endpointId
        execute(
            sql(
                "UPDATE events SET secret_fingerprint = CASE secret_fingerprint $cases " +
                    "ELSE secret_fingerprint END WHERE endpoint_id = ? AND secret_fingerprint IS NOT NULL;",
                *values.toTypedArray(),
            ),
        ).getOrThrow()
    }
}

private class StoredSecret(
    val id: String,
    val endpointId: String,
    val old: String,
    val new: String,
)

/** 256 bits: HMAC-SHA256's own output size, and more than a fingerprint of four bytes will ever need. */
internal const val INSTALL_KEY_BYTES: Int = 32

private const val FINGERPRINT_BYTES: Int = 4

/** `install_key` holds one row, and this is its id. */
private const val INSTALL_KEY_ROW: Int = 1
