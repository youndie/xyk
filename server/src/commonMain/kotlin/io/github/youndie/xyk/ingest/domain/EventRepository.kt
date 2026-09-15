package io.github.youndie.xyk.ingest.domain

import io.github.youndie.xyk.verify.EndpointSecret
import io.github.youndie.xyk.verify.SchemeConfig

/** An endpoint as the ingest path needs it: what verifies a request, and where it goes afterwards. */
class IngestEndpoint(
    val id: String,
    val scheme: String,
    val secrets: List<EndpointSecret>,
    val subscriberIds: List<String>,
    /** What the endpoint tells its verifier beyond the secret; empty for most schemes. */
    val schemeConfig: SchemeConfig,
)

/** What was accepted, as the caller is told it. */
class AcceptedEvent(
    val id: String,
    val deliveries: Int,
)

/**
 * The port. **`accept` is one transaction and that is the contract, not an implementation detail.**
 *
 * The event row and one delivery row per enabled subscriber commit together or not at all. An event
 * stored without its deliveries is an event nobody is waiting for; a delivery without its event is a
 * delivery of nothing. Both are silent, and both are what a webhook gateway is bought to prevent.
 */
interface EventRepository {
    suspend fun findEndpoint(endpointId: String): IngestEndpoint?

    suspend fun accept(
        endpoint: IngestEndpoint,
        receivedAt: Long,
        scheme: String,
        secretFingerprint: String?,
        contentType: String?,
        body: ByteArray,
    ): AcceptedEvent
}
