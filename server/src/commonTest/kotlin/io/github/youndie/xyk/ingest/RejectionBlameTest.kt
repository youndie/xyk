package io.github.youndie.xyk.ingest

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.BootstrapEndpoint
import io.github.youndie.xyk.db.applyBootstrap
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.db.sql
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.ingest.domain.AcceptEventUseCase
import io.github.youndie.xyk.installErrorResponses
import io.github.youndie.xyk.registry.data.Sqlx4kRegistryRepository
import io.github.youndie.xyk.verify.GithubVerifier
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which row a refusal is counted against, through HTTP.
 *
 * The `413` is refused before the endpoint is looked up — that is what keeps a body limit from
 * buffering — so it is the one refusal whose endpoint nothing had checked. Until 2026-10-02 it was
 * counted against whatever id the URL named, which is exactly what `RejectionCounters` says must not
 * happen: every id anyone sent became a row, and an id the database cannot hold took the whole
 * flush down with it.
 */
class RejectionBlameTest {
    private val known = "hook-under-test"
    private val disabled = "hook-switched-off"

    private suspend fun database(): ISQLite {
        val db = openDatabase("/tmp/xyk-blame-${Random.nextLong()}.db", maxConnections = 2)
        for (id in listOf(known, disabled)) {
            db.applyBootstrap(BootstrapEndpoint(id, GithubVerifier.SCHEME, "s", emptyList()), nowEpochSeconds = 1_000)
        }
        db.execute(sql("UPDATE endpoints SET enabled = 0 WHERE id = ?;", disabled)).getOrThrow()
        return db
    }

    private fun ApplicationTestBuilder.ingest(
        db: ISQLite,
        counters: RejectionCounters,
    ) {
        val accept =
            AcceptEventUseCase(
                Sqlx4kEventRepository(db),
                mapOf(GithubVerifier.SCHEME to GithubVerifier()),
            )
        application {
            install(ContentNegotiation) { json() }
            installErrorResponses()
            install(Resources)
            routing { ingestRouting(accept, LIMIT.toLong(), counters) { 2_000 } }
        }
    }

    /** A body with no declared length, so the limit is found by counting rather than by the header. */
    private fun streamed(size: Int): OutgoingContent =
        object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(ByteArray(size))
            }
        }

    @Test
    fun `an oversized body is counted against an endpoint only when the route would have found it`() =
        testApplication {
            val db = database()
            val counters = RejectionCounters()
            ingest(db, counters)
            val nobody = "0123456789abcdef0123456789abcdef"

            for (id in listOf(known, nobody, disabled)) {
                val declared = client.post("/hooks/$id") { setBody(ByteArray(LIMIT + 1)) }
                assertEquals(HttpStatusCode.PayloadTooLarge, declared.status, declared.bodyAsText())
                val counted = client.post("/hooks/$id") { setBody(streamed(LIMIT + 1)) }
                assertEquals(HttpStatusCode.PayloadTooLarge, counted.status, counted.bodyAsText())
            }
            RejectionFlush(db, counters).stop()

            val stored = Sqlx4kRegistryRepository(db).rejections()
            // The endpoint that exists keeps its own row — that is the control: without it the
            // assertion on the keys would also pass for a route that counted nothing at all.
            assertEquals(2L, stored[known]?.get("BODY_TOO_LARGE"), "$stored")
            // A disabled endpoint is answered `404` like one that never existed, and counted like one.
            assertEquals(4L, stored[RejectionCounters.GLOBAL]?.get("BODY_TOO_LARGE"), "$stored")
            assertEquals(
                setOf(known, RejectionCounters.GLOBAL),
                stored.keys,
                "a refusal created a row for an id that names no endpoint",
            )
            db.close().getOrThrow()
        }

    @Test
    fun `a NUL in the id is refused before the body limit and counted nowhere`() =
        testApplication {
            val db = database()
            val counters = RejectionCounters()
            ingest(db, counters)

            val response = client.post("/hooks/$known%00tail") { setBody(ByteArray(LIMIT + 1)) }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("{\"error\":\"text must not contain NUL\"}", response.bodyAsText())
            assertTrue(counters.drain().isEmpty(), "a refused id reached the counters")
            db.close().getOrThrow()
        }

    private companion object {
        const val LIMIT = 16
    }
}
