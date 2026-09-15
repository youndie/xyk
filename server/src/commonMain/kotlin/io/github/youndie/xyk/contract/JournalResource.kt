package io.github.youndie.xyk.contract

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/events` — the journal as JSON. */
@Resource("/api/events")
class EventsResource(
    val endpoint: String? = null,
    val state: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
) {
    @Resource("{id}")
    class ById(
        val parent: EventsResource = EventsResource(),
        val id: String,
    ) {
        @Resource("payload")
        class Payload(
            val parent: ById,
        )

        @Resource("redeliver")
        class Redeliver(
            val parent: ById,
        )
    }
}

/**
 * One event, without its body.
 *
 * `attempts` and `bodyBytes` carry no default **on purpose**: this service configures its serializer
 * with `encodeDefaults = false`, which drops a field equal to its default — and a counter that can
 * legitimately be zero would then disappear, leaving a reader unable to tell "none" from "the field
 * is gone".
 */
@Serializable
class EventView(
    val id: String,
    val endpoint: String,
    val receivedAt: Long,
    val scheme: String,
    val secretFingerprint: String? = null,
    val contentType: String? = null,
    val bodyBytes: Long,
    val deliveries: Int,
    val pending: Int,
    val dead: Int,
    val purgedAt: Long? = null,
)

@Serializable
class EventsPage(
    val events: List<EventView>,
    /** Absent on the last page. Opaque: it is the sort key, and nothing else should read it. */
    val cursor: String? = null,
)

@Serializable
class DeliveryView(
    val id: String,
    val subscriber: String? = null,
    val state: String,
    val attempts: Int,
    val createdAt: Long,
)

@Serializable
class EventDetailView(
    val event: EventView,
    val deliveries: List<DeliveryView>,
)

@Serializable
class RedeliveredResponse(
    val event: String,
    val scheduled: Int,
)

@Serializable
class PurgedResponse(
    val error: String,
    val purgedAt: Long,
)
