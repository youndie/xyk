package io.github.youndie.xyk.registry.domain

/** An endpoint as the registry shows it — never with a secret in it. */
class EndpointRecord(
    val id: String,
    val scheme: String,
    val enabled: Boolean,
    val description: String,
    val createdAt: Long,
    val secretFingerprints: List<String>,
    val subscriberCount: Int,
)

class SubscriberRecord(
    val id: String,
    val url: String,
    val enabled: Boolean,
)

/**
 * The port. Everything here is a row operation; the rules live in `Rules.kt` and the use cases.
 *
 * `disable` rather than `delete` is in the signature on purpose: there is no operation that destroys
 * an endpoint, because deleting one would orphan every event that references it, and the journal is
 * the product.
 */
interface RegistryRepository {
    suspend fun list(): List<EndpointRecord>

    suspend fun find(id: String): EndpointRecord?

    suspend fun create(
        id: String,
        scheme: String,
        description: String,
        secret: String,
        fingerprint: String,
        createdAt: Long,
        schemeConfig: String?,
    )

    suspend fun addSecret(
        endpointId: String,
        secret: String,
        fingerprint: String,
        createdAt: Long,
    )

    suspend fun setEnabled(
        endpointId: String,
        enabled: Boolean,
    )

    suspend fun setDescription(
        endpointId: String,
        description: String,
    )

    suspend fun listSubscribers(endpointId: String): List<SubscriberRecord>

    suspend fun addSubscriber(
        id: String,
        endpointId: String,
        url: String,
        createdAt: Long,
    )

    /** Returns false when there was no such subscriber. In-flight deliveries are untouched. */
    suspend fun removeSubscriber(id: String): Boolean

    /**
     * How many requests each endpoint turned away, by reason, including the global bucket for
     * requests to endpoints that do not exist.
     *
     * One query for every endpoint rather than one per row: the list page is read while ingest is
     * writing, and a query per endpoint is the shape that only becomes visible once there are
     * enough endpoints to hurt.
     */
    suspend fun rejections(): Map<String, Map<String, Long>>

    /** Any enabled endpoint, for the journal's empty state to point at. Null when there are none. */
    suspend fun anyEnabledId(): String?
}
