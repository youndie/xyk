package io.github.youndie.xyk.db

import io.github.youndie.xyk.BootstrapEndpoint
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.ingest.data.countOf
import io.github.youndie.xyk.journal.data.Sqlx4kJournalRepository
import io.github.youndie.xyk.journal.domain.JournalFilter
import io.github.youndie.xyk.newId
import io.github.youndie.xyk.registry.data.Sqlx4kRegistryRepository
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.verify.GithubVerifier
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Text an operator or a sender controls goes into SQLite exactly as written, and nothing in it runs.
 *
 * Against a real database on both targets, because the two halves of sqlx4k are two drivers: the
 * JVM binds through JDBC, the native build through its own FFI, and a value can survive one and not
 * the other — NUL is exactly such a value.
 */
class BoundValuesTest {
    private fun freshPath(): String = "/tmp/xyk-bound-${Random.nextLong()}.db"

    /**
     * Each is a way a value pasted into SQL text has gone wrong somewhere: a quote that ends the
     * literal, an escape SQLite does not have, a comment, a second statement, the placeholders of
     * three dialects, every UTF-8 width, and a length no fixture would think of.
     */
    private val hostile =
        listOf(
            "it's",
            "\"double\"",
            "back\\slash",
            "ends in a backslash\\",
            "-- a comment",
            "semi; colon",
            REWRITES_THE_SCHEME,
            "'; DELETE FROM endpoints; --",
            "\\'; DELETE FROM endpoints; --",
            "? :name \$1",
            "Кириллица, 日本語, 🙂",
            "x".repeat(100_000),
        )

    @Test
    fun `hostile text is stored as written and nothing in it runs`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val registry = Sqlx4kRegistryRepository(db)
            val create = CreateEndpointUseCase(registry, { setOf(GithubVerifier.SCHEME) }, allowUnverified = false)
            val journal = Sqlx4kJournalRepository(db)

            for (value in hostile) {
                val id = create(CreateEndpointUseCase.Params(GithubVerifier.SCHEME, value, value, 10)).getOrThrow()
                registry.setDescription(id, value)
                registry.addSubscriber(newId(), id, "https://example.invalid/$value", 10)

                val record = assertNotNull(registry.find(id))
                assertEquals(value, record.description, "the description changed on the way in")
                assertEquals(GithubVerifier.SCHEME, record.scheme, "a description rewrote another column")
                assertEquals(listOf(fingerprintOf(value)), record.secretFingerprints)
                assertEquals("https://example.invalid/$value", registry.listSubscribers(id).single().url)

                // As a filter, too: a value in a WHERE clause is as much a value as one in VALUES.
                assertTrue(journal.recent(JournalFilter(endpointId = value)).isEmpty())
            }

            assertEquals(hostile.size, registry.list().size, "a statement inside a value ran")
            db.close().getOrThrow()
        }

    /**
     * The harness above is only worth something if its payloads are live. Pasted into SQL without
     * the escape, the one built for it rewrites the scheme of the endpoint — the column that decides
     * how a webhook is verified — and the assertion above is what would see it.
     */
    @Test
    fun `control - the same payload pasted into SQL text does rewrite a column`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val registry = Sqlx4kRegistryRepository(db)
            val id =
                CreateEndpointUseCase(registry, { setOf(GithubVerifier.SCHEME) }, allowUnverified = false)(
                    CreateEndpointUseCase.Params(GithubVerifier.SCHEME, "s", "plain", 10),
                ).getOrThrow()

            db.execute("UPDATE endpoints SET description = '$REWRITES_THE_SCHEME' WHERE id = '$id';").getOrThrow()

            assertEquals("none", assertNotNull(registry.find(id)).scheme)
            db.close().getOrThrow()
        }

    @Test
    fun `text holding NUL is refused before the database sees it and nothing is stored`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val registry = Sqlx4kRegistryRepository(db)
            val create = CreateEndpointUseCase(registry, { setOf(GithubVerifier.SCHEME) }, allowUnverified = false)

            // Refused, not shortened: on Kotlin/Native a bound TEXT ends at its NUL, so storing would
            // keep "before" and drop the rest without a word.
            for (params in listOf(
                CreateEndpointUseCase.Params(GithubVerifier.SCHEME, "secret", "before\u0000after", 10),
                CreateEndpointUseCase.Params(GithubVerifier.SCHEME, "before\u0000after", "description", 10),
            )) {
                assertIs<UnstorableText>(create(params).exceptionOrNull())
            }
            assertEquals(0, registry.list().size, "a refused endpoint was stored anyway")
            assertEquals(0, db.countOf("SELECT count(*) FROM endpoint_secrets;").toInt())

            val id = create(CreateEndpointUseCase.Params(GithubVerifier.SCHEME, "s", "kept", 10)).getOrThrow()
            assertFailsWith<UnstorableText> { registry.setDescription(id, "before\u0000after") }
            assertFailsWith<UnstorableText> {
                registry.addSubscriber(newId(), id, "https://example.invalid/\u0000", 10)
            }
            assertEquals("kept", assertNotNull(registry.find(id)).description)
            assertEquals(0, registry.listSubscribers(id).size)

            // An id from a path or a query string is text from outside like any other. It is
            // refused rather than looked up: shortened, `<a real id>%00…` would find the real one.
            assertFailsWith<UnstorableText> { registry.find("$id\u0000tail") }
            assertFailsWith<UnstorableText> { Sqlx4kEventRepository(db).findEndpoint("$id\u0000tail") }
            assertFailsWith<UnstorableText> { Sqlx4kJournalRepository(db).detail("$id\u0000tail") }
            db.close().getOrThrow()
        }

    @Test
    fun `an empty body is stored as an empty blob`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val endpointId = "hook-empty-body"
            db.applyBootstrap(
                BootstrapEndpoint(endpointId, GithubVerifier.SCHEME, "it's a secret", emptyList()),
                nowEpochSeconds = 10,
            )
            val events = Sqlx4kEventRepository(db)
            val endpoint = assertNotNull(events.findEndpoint(endpointId))

            val accepted = events.accept(endpoint, 20, GithubVerifier.SCHEME, null, null, ByteArray(0))

            val hex =
                db
                    .fetchAll(sql("SELECT hex(body), body IS NULL, body_bytes FROM events WHERE id = ?;", accepted.id))
                    .getOrThrow()
                    .rows
                    .single()
            assertEquals("", hex.get(0).asString())
            assertEquals("0", hex.get(1).asString(), "an empty body was stored as NULL")
            assertEquals("0", hex.get(2).asString())
            db.close().getOrThrow()
        }

    @Test
    fun `a content type with a quote in it comes back as it was sent`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val endpointId = "hook-content-type"
            db.applyBootstrap(
                BootstrapEndpoint(endpointId, GithubVerifier.SCHEME, "s", emptyList()),
                nowEpochSeconds = 10,
            )
            val events = Sqlx4kEventRepository(db)
            val contentType = "application/json; note=\"it's\"; x='; DELETE FROM events; --"
            val body = byteArrayOf(0, 1, 2, -1)

            val accepted =
                events.accept(assertNotNull(events.findEndpoint(endpointId)), 20, "github", null, contentType, body)

            val detail = assertNotNull(Sqlx4kJournalRepository(db).detail(accepted.id))
            assertEquals(contentType, detail.event.contentType)
            assertContentEquals(body, Sqlx4kJournalRepository(db).payloadChunk(accepted.id, 0, body.size))
            assertEquals(1, db.countOf("SELECT count(*) FROM events;").toInt())
            db.close().getOrThrow()
        }

    private companion object {
        /** Closes the literal and assigns a second column — the classic, aimed at verification. */
        const val REWRITES_THE_SCHEME = "x', scheme = 'none"
    }
}
