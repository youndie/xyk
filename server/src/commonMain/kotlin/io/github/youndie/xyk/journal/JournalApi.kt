package io.github.youndie.xyk.journal

import io.github.youndie.xyk.contract.DeliveryView
import io.github.youndie.xyk.contract.ErrorResponse
import io.github.youndie.xyk.contract.EventDetailView
import io.github.youndie.xyk.contract.EventView
import io.github.youndie.xyk.contract.EventsPage
import io.github.youndie.xyk.contract.EventsResource
import io.github.youndie.xyk.contract.PurgedResponse
import io.github.youndie.xyk.contract.RedeliveredResponse
import io.github.youndie.xyk.journal.domain.EventLine
import io.github.youndie.xyk.journal.domain.JournalFilter
import io.github.youndie.xyk.journal.domain.JournalRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.utils.io.writeFully

/** How much of a payload is in memory at once. */
private const val CHUNK_BYTES = 64 * 1024

/**
 * The JSON behind the page, and the one write the journal offers.
 *
 * These are typed resources where the pages are not: they are a contract something may generate a
 * client from, and a typed `@Resource` cannot drift from `docs/api/endpoint-journal.md` silently.
 */
fun Route.journalApi(
    repository: JournalRepository,
    nowEpochSeconds: () -> Long,
) {
    get<EventsResource> { query ->
        val page =
            repository.page(
                JournalFilter(
                    endpointId = query.endpoint,
                    state = query.state,
                    limit = query.limit ?: JournalFilter.DEFAULT_LIMIT,
                ),
                cursor = query.cursor,
            )
        call.respond(EventsPage(page.events.map { it.toView() }, page.nextCursor))
    }

    get<EventsResource.ById> { resource ->
        val detail = repository.detail(resource.id)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown event"))
        } else {
            call.respond(
                EventDetailView(
                    event = detail.event.toView(),
                    deliveries =
                        detail.deliveries.map {
                            DeliveryView(it.id, it.subscriberUrl, it.state, it.attempts, it.createdAt)
                        },
                ),
            )
        }
    }

    get<EventsResource.ById.Payload> { resource ->
        val eventId = resource.parent.id
        val meta = repository.payloadMeta(eventId)
        if (meta == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown event"))
            return@get
        }
        if (meta.purgedAt != null) {
            // `410` and not `404`: the event existed and its record is still here — only the bytes
            // are gone. Collapsing the two would make retention look like data loss.
            call.respond(HttpStatusCode.Gone, PurgedResponse("payload purged", meta.purgedAt))
            return@get
        }

        // THE ETAG IS THE EVENT ID, and that is stronger than a hash of the content rather than
        // weaker: an event is immutable once stored, so its id identifies its bytes exactly — while
        // hashing the body would mean reading all of it into memory, which is the one thing this
        // route is written to avoid.
        call.response.headers.append(HttpHeaders.ETag, "\"$eventId\"")

        val type = meta.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        call.respondBytesWriter(contentType = type ?: ContentType.Application.OctetStream) {
            var offset = 0L
            while (offset < meta.bytes) {
                val chunk = repository.payloadChunk(eventId, offset, CHUNK_BYTES)
                if (chunk.isEmpty()) break
                writeFully(chunk)
                offset += chunk.size
            }
        }
    }

    post<EventsResource.ById.Redeliver> { resource ->
        val eventId = resource.parent.id
        if (repository.payloadMeta(eventId) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown event"))
            return@post
        }
        val scheduled = repository.redeliver(eventId, nowEpochSeconds())
        if (scheduled == 0) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("no subscribers"))
        } else {
            // `202`, never `200`: a timer has been scheduled and nothing has been delivered yet.
            call.respond(HttpStatusCode.Accepted, RedeliveredResponse(eventId, scheduled))
        }
    }
}

private fun EventLine.toView(): EventView =
    EventView(
        id = id,
        endpoint = endpointId,
        receivedAt = receivedAt,
        scheme = scheme,
        secretFingerprint = secretFingerprint,
        contentType = contentType,
        bodyBytes = bodyBytes,
        deliveries = deliveries,
        pending = pending,
        dead = dead,
        purgedAt = purgedAt,
    )
