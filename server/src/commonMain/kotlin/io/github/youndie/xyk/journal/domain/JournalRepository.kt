package io.github.youndie.xyk.journal.domain

/** One delivery of one event to one subscriber, as the journal shows it. */
class DeliveryLine(
    val id: String,
    val subscriberUrl: String?,
    val state: String,
    val attempts: Int,
    val createdAt: Long,
)

/** An event as a row in the list. The body is **not** here: a list page must not read bodies. */
class EventLine(
    val id: String,
    val endpointId: String,
    val receivedAt: Long,
    val scheme: String,
    val secretFingerprint: String?,
    val contentType: String?,
    /** The size the payload **arrived** with. A purge does not change it. */
    val bodyBytes: Long,
    /** When the payload was purged by retention, or null while the bytes are still there. */
    val purgedAt: Long?,
    val deliveries: Int,
    val pending: Int,
    val dead: Int,
)

/** One event with everything that happened to it. */
class EventDetail(
    val event: EventLine,
    val deliveries: List<DeliveryLine>,
)

/** What the list is narrowed to. Every field is a coordinate of one incident, not a query language. */
class JournalFilter(
    val endpointId: String? = null,
    val state: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 200
    }
}

/** What a payload is, without reading it. */
class PayloadMeta(
    val bytes: Long,
    val contentType: String?,
    val purgedAt: Long?,
)

/** One page of the list, and where the next one starts. */
class EventPage(
    val events: List<EventLine>,
    /** Null when this was the last page. Opaque to the caller by design. */
    val nextCursor: String?,
)

interface JournalRepository {
    suspend fun recent(filter: JournalFilter): List<EventLine>

    suspend fun detail(eventId: String): EventDetail?

    /**
     * A page of the list, continuing from [cursor].
     *
     * **Keyset, not offset.** The list is written to while it is read, and an offset page under
     * insert traffic shows some rows twice and skips others — on a page whose only job is to be
     * believed.
     */
    suspend fun page(
        filter: JournalFilter,
        cursor: String?,
    ): EventPage

    suspend fun payloadMeta(eventId: String): PayloadMeta?

    /**
     * [length] bytes of the payload from [offset].
     *
     * Chunked rather than whole: sqlx4k cannot stream a BLOB, so the rows are paged with SQLite's
     * `substr`, which slices the blob inside the database instead of handing the whole thing over.
     */
    suspend fun payloadChunk(
        eventId: String,
        offset: Long,
        length: Int,
    ): ByteArray

    /**
     * Schedules a fresh delivery of an event to every enabled subscriber of its endpoint.
     *
     * Returns how many were scheduled — zero when nobody is subscribed, which the route turns into a
     * `409` rather than a cheerful `202` about nothing.
     */
    suspend fun redeliver(
        eventId: String,
        nowEpochSeconds: Long,
    ): Int
}
