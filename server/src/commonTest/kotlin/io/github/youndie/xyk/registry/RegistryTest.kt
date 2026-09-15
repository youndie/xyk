package io.github.youndie.xyk.registry

import io.github.youndie.xyk.db.fingerprintOf
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.registry.data.Sqlx4kRegistryRepository
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.registry.domain.NO_VERIFICATION
import io.github.youndie.xyk.registry.domain.RotateSecretUseCase
import io.github.youndie.xyk.registry.domain.schemeProblem
import io.github.youndie.xyk.registry.domain.subscriberUrlProblem
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryTest {
    private val implemented = setOf("github")

    private fun freshPath(): String = "/tmp/xyk-registry-${Random.nextLong()}.db"

    @Test
    fun `an unimplemented scheme is refused rather than stored`() {
        // Deliberately stricter than the API document's list of five. Accepting `stripe` today would
        // create an endpoint whose every request answers 404, because the ingest path refuses a
        // scheme nothing verifies — configuration that looks accepted and cannot work.
        assertEquals("unknown scheme: stripe", schemeProblem("stripe", implemented, allowUnverified = false))
        assertNull(schemeProblem("github", implemented, allowUnverified = false))
    }

    @Test
    fun `scheme none needs the flag`() {
        assertEquals(
            "scheme none is disabled",
            schemeProblem(NO_VERIFICATION, implemented, allowUnverified = false),
        )
        assertNull(schemeProblem(NO_VERIFICATION, implemented, allowUnverified = true))
    }

    @Test
    fun `a subscriber url must be absolute http or https`() {
        val message = "subscriber url must be absolute http or https"
        assertEquals(message, subscriberUrlProblem("/hook"))
        assertEquals(message, subscriberUrlProblem("example.com/hook"))
        assertEquals(message, subscriberUrlProblem("https://"))
        assertEquals(message, subscriberUrlProblem("ftp://example.com"))
        assertNull(subscriberUrlProblem("https://example.invalid/hook"))
        assertNull(subscriberUrlProblem("http://sink.internal:9000/x"))
    }

    @Test
    fun `a created endpoint shows a fingerprint and never the secret`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val repository = Sqlx4kRegistryRepository(db)
            val create = CreateEndpointUseCase(repository, { implemented }, allowUnverified = false)

            val id = create(CreateEndpointUseCase.Params("github", "it's a secret", "test", 10)).getOrThrow()

            val record = assertNotNull(repository.find(id))
            assertEquals(listOf(fingerprintOf("it's a secret")), record.secretFingerprints)
            assertTrue(record.enabled)
            assertEquals(0, record.subscriberCount)
            // The record type has no secret field at all: there is nothing here to forget to drop.
            db.close().getOrThrow()
        }

    @Test
    fun `rotation adds a secret and keeps the old one`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val repository = Sqlx4kRegistryRepository(db)
            val create = CreateEndpointUseCase(repository, { implemented }, allowUnverified = false)
            val rotate = RotateSecretUseCase(repository)
            val id = create(CreateEndpointUseCase.Params("github", "old", "", 10)).getOrThrow()

            rotate(id, "new", 20).getOrThrow()

            val fingerprints = assertNotNull(repository.find(id)).secretFingerprints
            assertEquals(2, fingerprints.size, "rotation replaced the secret instead of adding one")
            assertTrue(fingerprintOf("old") in fingerprints && fingerprintOf("new") in fingerprints)
            db.close().getOrThrow()
        }

    @Test
    fun `creating an unverified endpoint without the flag is a typed failure`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val create =
                CreateEndpointUseCase(Sqlx4kRegistryRepository(db), { implemented }, allowUnverified = false)

            val failure =
                create(CreateEndpointUseCase.Params(NO_VERIFICATION, "", "", 10)).exceptionOrNull()

            assertEquals(
                "scheme none is disabled",
                assertIs<CreateEndpointUseCase.Error.BadScheme>(failure).message,
            )
            db.close().getOrThrow()
        }

    @Test
    fun `disabling keeps the endpoint and its subscribers`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val repository = Sqlx4kRegistryRepository(db)
            val create = CreateEndpointUseCase(repository, { implemented }, allowUnverified = false)
            val id = create(CreateEndpointUseCase.Params("github", "s", "", 10)).getOrThrow()
            repository.addSubscriber("sub-1", id, "https://example.invalid/x", 10)

            repository.setEnabled(id, enabled = false)

            val record = assertNotNull(repository.find(id))
            assertTrue(!record.enabled)
            assertEquals(1, record.subscriberCount, "disabling took the subscribers with it")
            db.close().getOrThrow()
        }

    @Test
    fun `removing a subscriber that is not there says so`() =
        runTest {
            val db = openDatabase(freshPath(), maxConnections = 2)
            val repository = Sqlx4kRegistryRepository(db)
            assertTrue(!repository.removeSubscriber("no-such-subscriber"))
            db.close().getOrThrow()
        }
}
