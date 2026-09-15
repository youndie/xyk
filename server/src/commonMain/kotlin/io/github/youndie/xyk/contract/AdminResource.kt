package io.github.youndie.xyk.contract

import io.github.youndie.xyk.verify.SchemeConfig
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/endpoints` — the collection. */
@Resource("/api/endpoints")
class EndpointsResource {
    /** `/api/endpoints/{id}` — one endpoint. `DELETE` here **disables**; see the admin document. */
    @Resource("{id}")
    class ById(
        val parent: EndpointsResource = EndpointsResource(),
        val id: String,
    ) {
        @Resource("subscribers")
        class Subscribers(
            val parent: ById,
        )
    }
}

/** `/api/subscribers/{id}` — removal only; a subscriber is always created under its endpoint. */
@Resource("/api/subscribers/{id}")
class SubscriberResource(
    val id: String,
)

@Serializable
class CreateEndpointRequest(
    val scheme: String,
    val secret: String,
    val description: String = "",
    /** Only the schemes that need it: `hmac-sha256` always, `stripe` when it wants its own window. */
    val schemeConfig: SchemeConfig? = null,
)

@Serializable
class PatchEndpointRequest(
    val enabled: Boolean? = null,
    /** A **new** secret. It is added; the previous one keeps working until it retires. */
    val secret: String? = null,
    val description: String? = null,
)

/**
 * An endpoint as the API returns it. **There is no `secret` field and there will not be one** — the
 * fingerprints are what tell two secrets apart.
 */
@Serializable
class EndpointView(
    val id: String,
    val scheme: String,
    val enabled: Boolean,
    val description: String,
    val createdAt: Long,
    val secretFingerprints: List<String>,
    val subscribers: Int,
    /**
     * What this endpoint turned away, by reason.
     *
     * It is on the endpoint rather than in a report of its own because the question it answers —
     * "nothing is arriving: is nothing being sent, or is everything being refused?" — is asked about
     * one endpoint at a time, and the two look identical without it.
     */
    val rejections: Map<String, Long> = emptyMap(),
)

@Serializable
class CreatedEndpoint(
    val id: String,
    val url: String,
)

@Serializable
class SubscriberView(
    val id: String,
    val url: String,
    val enabled: Boolean,
)

@Serializable
class AddSubscriberRequest(
    val url: String,
)
