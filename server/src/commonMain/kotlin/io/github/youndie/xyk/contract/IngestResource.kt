package io.github.youndie.xyk.contract

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `POST /hooks/{endpointId}` — the only inbound route, and one shape for every sender.
 *
 * A typed resource rather than a string path: it is a contract that cannot drift from
 * `docs/api/endpoint-ingest.md` silently. A per-vendor path (`/hooks/github/...`) was rejected —
 * which scheme verifies a request is a property of the endpoint row, and putting it in the URL too
 * would let the two disagree.
 */
@Resource("/hooks/{endpointId}")
class HookResource(
    val endpointId: String,
)

/** What a sender gets back on success. The id is what a subscriber deduplicates on later. */
@Serializable
class AcceptedResponse(
    val event: String,
)

/** Every refusal on this route has this shape, and the string is the machine-readable part. */
@Serializable
class ErrorResponse(
    val error: String,
)
