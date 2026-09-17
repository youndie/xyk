package io.github.youndie.xyk.sink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one accepted event looks like on the topic.
 *
 * It is a contract with whoever reads the topic, so it lives in common code and is tested there —
 * the sink itself can only be exercised on a target that has a broker in front of it, and a shape
 * nobody can assert is a shape that changes by accident.
 *
 * **No body.** The bytes are in `events`, behind the journal and behind a retention horizon; a copy
 * on a topic would outlive that horizon somewhere nothing in this service can reach. A reader that
 * needs the payload has an event id and an endpoint to ask.
 */
@Serializable
class EventEnvelope(
    val event: String,
    val endpoint: String,
    val receivedAt: Long,
    val bodyBytes: Long,
    val contentType: String? = null,
)

/** Deliberately not `encodeDefaults = false`: a reader parsing this should not have to tell an absent key from a zero. */
private val sinkJson = Json { encodeDefaults = true }

fun AcceptedRecord.toEnvelopeBytes(): ByteArray =
    sinkJson
        .encodeToString(
            EventEnvelope(
                event = eventId,
                endpoint = endpointId,
                receivedAt = receivedAt,
                bodyBytes = bodyBytes,
                contentType = contentType,
            ),
        ).encodeToByteArray()
