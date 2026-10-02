package io.github.youndie.xyk.db

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.xyk.BootstrapEndpoint
import io.github.youndie.xyk.ingest.data.countOf
import io.github.youndie.xyk.registry.data.Sqlx4kRegistryRepository
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.verify.GithubVerifier
import io.github.youndie.xyk.verify.toHex
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A secret's fingerprint is an HMAC under the installation's own key (research D7): different for the
 * same secret on two installations, the same for it across restarts of one, and — on a database an
 * older binary left behind — rewritten by the upgrade, the events' copies included.
 *
 * Against real database files, because the key lives in one and "the same across restarts" is a
 * claim about what a second open of the file finds.
 */
class SecretFingerprintTest {
    private fun freshPath(): String = "/tmp/xyk-fingerprint-${Random.nextLong()}.db"

    private suspend fun ISQLite.createEndpoint(secret: String): String =
        CreateEndpointUseCase(
            Sqlx4kRegistryRepository(this),
            secretFingerprints(),
            { setOf(GithubVerifier.SCHEME) },
            allowUnverified = false,
        )(CreateEndpointUseCase.Params(GithubVerifier.SCHEME, secret, "", 10)).getOrThrow()

    private suspend fun ISQLite.shownFingerprints(endpointId: String): List<String> =
        assertNotNull(Sqlx4kRegistryRepository(this).find(endpointId)).secretFingerprints

    @Test
    fun `one secret on two installations has two fingerprints`() =
        runTest {
            val installations = listOf(openDatabase(freshPath()), openDatabase(freshPath()))

            val shown = installations.map { db -> db.shownFingerprints(db.createEndpoint(SECRET)).single() }

            assertNotEquals(shown[0], shown[1], "the same secret reads the same on two installations")
            installations.forEach { it.close().getOrThrow() }
        }

    @Test
    fun `a fingerprint is not the plain hash of the secret`() =
        runTest {
            val db = openDatabase(freshPath())

            val shown = db.shownFingerprints(db.createEndpoint(SECRET)).single()

            assertEquals(8, shown.length, "the pages and the documents describe eight hex characters")
            assertNotEquals(plainHash(SECRET), shown, "a guessed secret can be checked against the page")
            db.close().getOrThrow()
        }

    @Test
    fun `a fingerprint is the same after a restart, and so is the bootstrap secret`() =
        runTest {
            val path = freshPath()
            val bootstrap = BootstrapEndpoint("hook-restart", GithubVerifier.SCHEME, SECRET, emptyList())
            val first = openDatabase(path)
            val id = first.createEndpoint(SECRET)
            first.applyBootstrap(bootstrap, nowEpochSeconds = 10)
            val before = first.shownFingerprints(id)
            first.close().getOrThrow()

            val second = openDatabase(path)
            second.applyBootstrap(bootstrap, nowEpochSeconds = 20)

            assertEquals(before, second.shownFingerprints(id), "a restart changed a stored fingerprint")
            assertEquals(before, listOf(second.secretFingerprints().of(SECRET)), "a restart changed the key")
            // Bootstrap is idempotent by fingerprint. Under a key that moved between starts it would
            // not find its own secret, and every restart would add another copy of it.
            assertEquals(
                1,
                second.countOf("SELECT count(*) FROM endpoint_secrets WHERE endpoint_id = 'hook-restart';").toInt(),
                "a restart added the bootstrap secret again",
            )
            second.close().getOrThrow()
        }

    @Test
    fun `an upgrade rewrites every stored fingerprint, the events' copies too`() =
        runTest {
            val path = freshPath()

            // What the previous binary left behind: schema 7, plain hashes in both places.
            val old = databaseAtVersion(path, 7)
            old.insertSecret("s-1", "hook-1", "first")
            old.insertSecret("s-2", "hook-1", "second")
            // The same secret on another endpoint: its events are mapped by its own row.
            old.insertSecret("s-3", "hook-2", "first")
            old.insertEvent("e-first", "hook-1", plainHash("first"))
            old.insertEvent("e-second", "hook-1", plainHash("second"))
            old.insertEvent("e-unverified", "hook-1", null)
            old.insertEvent("e-other-endpoint", "hook-2", plainHash("first"))
            // A fingerprint that names no stored secret has nothing to be recomputed from.
            old.insertEvent("e-orphan", "hook-1", "0badf00d")
            old.close().getOrThrow()

            val db = openDatabase(path)
            val fingerprints = db.secretFingerprints()

            assertEquals(8, db.countOf("PRAGMA user_version;").toInt())
            assertEquals(1, db.countOf("SELECT count(*) FROM install_key;").toInt())
            for ((id, secret) in listOf("s-1" to "first", "s-2" to "second", "s-3" to "first")) {
                assertEquals(fingerprints.of(secret), db.storedFingerprint(id), "secret $id kept its plain hash")
            }
            assertEquals(fingerprints.of("first"), db.eventFingerprint("e-first"))
            assertEquals(fingerprints.of("second"), db.eventFingerprint("e-second"))
            assertEquals(fingerprints.of("first"), db.eventFingerprint("e-other-endpoint"))
            assertNull(db.eventFingerprint("e-unverified"), "an event nothing verified gained a fingerprint")
            assertEquals("0badf00d", db.eventFingerprint("e-orphan"))
            db.close().getOrThrow()
        }

    /** The file an older binary would have left: created, and migrated no further than [version]. */
    private suspend fun databaseAtVersion(
        path: String,
        version: Int,
    ): ISQLite {
        FileSystem.SYSTEM.write(path.toPath()) { }
        val options =
            ConnectionPool.Options
                .builder()
                .maxConnections(2)
                .build()
        val db = sqlite(url = "sqlite://$path", options = options)
        db.migrateSchemaTo(version)
        return db
    }

    private suspend fun ISQLite.insertSecret(
        id: String,
        endpointId: String,
        secret: String,
    ) {
        execute(
            sql(
                "INSERT INTO endpoints (id, scheme, enabled, description, created_at) VALUES (?, ?, 1, '', 1) " +
                    "ON CONFLICT(id) DO NOTHING;",
                endpointId,
                GithubVerifier.SCHEME,
            ),
        ).getOrThrow()
        execute(
            sql(
                "INSERT INTO endpoint_secrets (id, endpoint_id, secret, fingerprint, created_at) VALUES (?, ?, ?, ?, 1);",
                id,
                endpointId,
                secret,
                plainHash(secret),
            ),
        ).getOrThrow()
    }

    private suspend fun ISQLite.insertEvent(
        id: String,
        endpointId: String,
        fingerprint: String?,
    ) {
        execute(
            sql(
                "INSERT INTO events (id, endpoint_id, received_at, scheme, secret_fingerprint, body, body_bytes) " +
                    "VALUES (?, ?, 1, ?, ?, ?, 1);",
                id,
                endpointId,
                GithubVerifier.SCHEME,
                fingerprint,
                byteArrayOf(1),
            ),
        ).getOrThrow()
    }

    private suspend fun ISQLite.storedFingerprint(secretId: String): String =
        fetchAll(sql("SELECT fingerprint FROM endpoint_secrets WHERE id = ?;", secretId))
            .getOrThrow()
            .rows
            .single()
            .get(0)
            .asString()

    private suspend fun ISQLite.eventFingerprint(eventId: String): String? =
        fetchAll(sql("SELECT secret_fingerprint FROM events WHERE id = ?;", eventId))
            .getOrThrow()
            .rows
            .single()
            .get(0)
            .asStringOrNull()

    private companion object {
        const val SECRET = "one secret, two installations"

        /** What a fingerprint was until 2026-10-02: the first four bytes of a plain SHA-256. */
        fun plainHash(secret: String): String = SHA256().digest(secret.encodeToByteArray()).copyOf(4).toHex()
    }
}
