package io.github.youndie.xyk

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.health.XykProbes
import io.github.youndie.xyk.ingest.RejectionCounters
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.ingest.domain.AcceptEventUseCase
import io.github.youndie.xyk.journal.DeliveryStatus
import io.github.youndie.xyk.journal.data.Sqlx4kJournalRepository
import io.github.youndie.xyk.registry.data.Sqlx4kRegistryRepository
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.registry.domain.RotateSecretUseCase
import io.github.youndie.xyk.verify.GithubVerifier
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a client is answered when its text cannot be stored, and when the server fails.
 *
 * Through HTTP and the real `module`, because both answers are decided in the pipeline rather than in
 * a handler: until 2026-10-02 nothing caught an exception a handler did not expect, and Ktor wrote
 * its message — a database error included — into the body of the `500`.
 */
class ErrorResponsesTest {
    private fun ApplicationTestBuilder.xyk(db: ISQLite) {
        val registry = Sqlx4kRegistryRepository(db)
        application {
            module(
                config = config(),
                probes = XykProbes(db),
                acceptEvent =
                    AcceptEventUseCase(
                        Sqlx4kEventRepository(db),
                        mapOf(GithubVerifier.SCHEME to GithubVerifier()),
                    ),
                rejections = RejectionCounters(),
                registry = registry,
                createEndpoint =
                    CreateEndpointUseCase(registry, { setOf(GithubVerifier.SCHEME) }, allowUnverified = false),
                rotateSecret = RotateSecretUseCase(registry),
                journal = Sqlx4kJournalRepository(db),
                deliveryStatus = { DeliveryStatus.NOT_CONFIGURED },
            )
            // A failure nobody planned for, carrying the kind of text the database used to put in
            // front of a client: a piece of the statement, and of the value in it.
            routing {
                get("/test/fails") { throw SQLError(SQLError.Code.Database, LEAKY_MESSAGE) }
            }
        }
    }

    private fun database(): ISQLite = openDatabase("/tmp/xyk-errors-${Random.nextLong()}.db", maxConnections = 2)

    private suspend fun ApplicationTestBuilder.createEndpoint(description: String = "plain"): String {
        val created =
            client.post("/api/endpoints") {
                contentType(ContentType.Application.Json)
                setBody("{\"scheme\":\"github\",\"secret\":\"s\",\"description\":\"$description\"}")
            }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return Regex("\"id\":\"([0-9a-f]+)\"").find(created.bodyAsText())!!.groupValues[1]
    }

    private suspend fun HttpResponse.assertAnswer(
        status: HttpStatusCode,
        body: String,
    ) {
        val text = bodyAsText()
        assertEquals(status, this.status, text)
        assertEquals(body, text)
    }

    @Test
    fun `a NUL in a field is a 400 naming the field and nothing is created`() =
        testApplication {
            val db = database()
            xyk(db)

            for ((field, json) in listOf(
                "description" to "{\"scheme\":\"github\",\"secret\":\"s\",\"description\":\"a\\u0000b\"}",
                "secret" to "{\"scheme\":\"github\",\"secret\":\"s\\u0000tail\"}",
                "schemeConfig.header" to
                    "{\"scheme\":\"github\",\"secret\":\"s\",\"schemeConfig\":{\"header\":\"X\\u0000\"}}",
            )) {
                client
                    .post("/api/endpoints") {
                        contentType(ContentType.Application.Json)
                        setBody(json)
                    }.assertAnswer(HttpStatusCode.BadRequest, "{\"error\":\"$field must not contain NUL\"}")
            }
            client.get("/api/endpoints").assertAnswer(HttpStatusCode.OK, "[]")
            db.close().getOrThrow()
        }

    @Test
    fun `a PATCH refused for one field applies none of the others`() =
        testApplication {
            val db = database()
            xyk(db)
            val id = createEndpoint()

            client
                .patch("/api/endpoints/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"enabled\":false,\"description\":\"a\\u0000b\"}")
                }.assertAnswer(HttpStatusCode.BadRequest, "{\"error\":\"description must not contain NUL\"}")

            val after = client.get("/api/endpoints/$id").bodyAsText()
            assertTrue("\"enabled\":true" in after && "\"description\":\"plain\"" in after, after)

            client
                .post("/api/endpoints/$id/subscribers") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"url\":\"https://example.invalid/\\u0000\"}")
                }.assertAnswer(HttpStatusCode.BadRequest, "{\"error\":\"url must not contain NUL\"}")
            db.close().getOrThrow()
        }

    @Test
    fun `a NUL in an id or a filter is a 400 and not a database error`() =
        testApplication {
            val db = database()
            xyk(db)
            val id = createEndpoint()
            val refused = "{\"error\":\"text must not contain NUL\"}"

            client.get("/api/endpoints/$id%00tail").assertAnswer(HttpStatusCode.BadRequest, refused)
            client.get("/api/events?endpoint=$id%00tail").assertAnswer(HttpStatusCode.BadRequest, refused)
            client.get("/api/events/$id%00tail").assertAnswer(HttpStatusCode.BadRequest, refused)
            client
                .post("/hooks/$id%00tail") { setBody("{}") }
                .assertAnswer(HttpStatusCode.BadRequest, refused)
            db.close().getOrThrow()
        }

    @Test
    fun `an unexpected failure is a 500 that says nothing about itself`() =
        testApplication {
            val db = database()
            xyk(db)

            val response = client.get("/test/fails")

            response.assertAnswer(HttpStatusCode.InternalServerError, "{\"error\":\"internal error\"}")
            assertFalse("token" in response.bodyAsText())
            db.close().getOrThrow()
        }

    @Test
    fun `a body that cannot be decoded is still Ktor's own 400`() =
        testApplication {
            val db = database()
            xyk(db)

            val response =
                client.post("/api/endpoints") {
                    contentType(ContentType.Application.Json)
                    setBody("{")
                }

            val text = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(text.startsWith("Failed to convert request body to class"), text)
            db.close().getOrThrow()
        }

    /**
     * Text in another charset is decoded before it is bound (J59, research §1.14), so a quote and a
     * backslash arrive as characters, next to letters that only glibc's converters could read.
     */
    @Test
    fun `a quote and a backslash in a windows-1251 body come back as they were sent`() =
        testApplication {
            val db = database()
            xyk(db)
            val id = createEndpoint()
            val privet = bytesOf(0xEF, 0xF0, 0xE8, 0xE2, 0xE5, 0xF2)

            val patched =
                client.patch("/api/endpoints/$id") {
                    contentType(ContentType.parse("application/json; charset=windows-1251"))
                    setBody(
                        "{\"description\":\"it's \\\\ ".encodeToByteArray() + privet + "\"}".encodeToByteArray(),
                    )
                }

            assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
            assertTrue(
                "\"description\":\"it's \\\\ привет\"" in client.get("/api/endpoints/$id").bodyAsText(),
                client.get("/api/endpoints/$id").bodyAsText(),
            )
            db.close().getOrThrow()
        }

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun config(): ServerConfig =
        ServerConfig(
            port = 0,
            host = "127.0.0.1",
            sqlitePath = "unused",
            allowUnverified = false,
            walCheckpointSeconds = 0,
            walMaxBytes = 0,
            heapBytes = 0,
            maxBodyBytes = 1_048_576,
            retentionDays = 0,
            deliveryTimeoutMillis = 1_000,
            deliveryMaxAttempts = 1,
            deliveryWorkers = 0,
            deliveryStallSeconds = 60,
            sqlitePoolSize = 2,
            stripeToleranceSeconds = 300,
            publicBaseUrl = "http://127.0.0.1",
            kafkaBootstrapServers = null,
            kafkaTopic = ServerConfig.DEFAULT_KAFKA_TOPIC,
            kafkaQueue = 0,
            bootstrapEndpoint = null,
        )

    private companion object {
        const val LEAKY_MESSAGE = "unrecognized token: \"'the start of a value"
    }
}
