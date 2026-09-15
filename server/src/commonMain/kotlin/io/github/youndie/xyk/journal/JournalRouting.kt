package io.github.youndie.xyk.journal

import io.github.youndie.xyk.journal.domain.JournalFilter
import io.github.youndie.xyk.journal.domain.JournalRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The operator's half: a list, an event, and nothing else.
 *
 * Plain `get` rather than a typed `@Resource`, unlike every other route here — these are pages, the
 * paths are read by a person out of a browser bar, and the query parameters are optional filters
 * rather than a contract anything generates a client from. The JSON routes that *are* a contract are
 * B-13 and get resources.
 */
fun Route.journalRouting(
    repository: JournalRepository,
    deliveryStatus: () -> DeliveryStatus,
    hookUrlFor: (String) -> String,
    anyEndpointId: suspend () -> String?,
) {
    get("/") {
        call.respondRedirect("/journal")
    }

    get("/journal") {
        val filter =
            JournalFilter(
                endpointId = call.request.queryParameters["endpoint"],
                state = call.request.queryParameters["state"],
                limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: JournalFilter.DEFAULT_LIMIT,
            )
        val events = repository.recent(filter)
        call.respondText(
            JournalPage.list(
                events = events,
                filter = filter,
                delivery = deliveryStatus(),
                hookUrlFor = hookUrlFor,
                // Only asked for when the page is empty, which is the only state that uses it.
                knownEndpointId = if (events.isEmpty()) anyEndpointId() else null,
            ),
            ContentType.Text.Html,
        )
    }

    get("/journal/{eventId}") {
        val eventId = call.parameters["eventId"].orEmpty()
        val detail = repository.detail(eventId)
        if (detail == null) {
            // An empty state naming the id, not a bare 404 page: the id in the URL is usually
            // pasted from somewhere, and "this one is not here" is the useful half of the answer.
            call.respondText(
                JournalPage.notFound(eventId),
                ContentType.Text.Html,
                HttpStatusCode.NotFound,
            )
        } else {
            call.respondText(JournalPage.detail(detail, deliveryStatus()), ContentType.Text.Html)
        }
    }
}
