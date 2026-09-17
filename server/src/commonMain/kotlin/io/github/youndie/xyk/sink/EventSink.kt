package io.github.youndie.xyk.sink

/**
 * An event that is **already stored**, handed on to whatever else wants to know about it.
 *
 * It carries the event's identity and its shape, not its body. The bytes are durable in `events`
 * and readable through the journal; copying them onto a topic would duplicate the one thing this
 * service has a retention policy for, in a place that policy does not reach.
 */
class AcceptedRecord(
    val eventId: String,
    val endpointId: String,
    val receivedAt: Long,
    val bodyBytes: Long,
    val contentType: String?,
)

/**
 * A second destination for an accepted event, beyond this service's own subscribers.
 *
 * **The row is written before [publish] is called, and that order is the contract.** A sink that
 * published first and stored afterwards would answer for records nobody can find again; this way an
 * event that was accepted is in the database whatever the sink does, and the gap between the two is
 * visible from outside — a row with nothing on the topic behind it.
 *
 * Closing it is separate from stopping the service's own halves because it must happen **after the
 * engine has drained**: a publish is part of a request that was already accepted, and cutting it
 * would lose exactly the record this interface exists to carry.
 */
interface EventSink {
    /**
     * Publishes [record], returning when the far side has taken responsibility for it.
     *
     * Throws when it could not. The caller does not turn that into a failed request — the event is
     * stored either way — but it must not be swallowed silently either.
     */
    suspend fun publish(record: AcceptedRecord)

    suspend fun close()
}

/**
 * The sink this build was linked with, or `null` when it was linked without one — which is the
 * default and a shipping configuration rather than a failure.
 *
 * `null` for two different reasons, and `main` says which: nothing is configured
 * (`XYK_KAFKA_BOOTSTRAP_SERVERS` is unset), or this build has no kafkakn variant for its target.
 * kafkakn publishes `jvm` and `linuxX64` and nothing else, so on the Mac the native suite compiles
 * without the sink rather than failing to resolve — the same shape, and the same reason, as
 * chronik's absence next door.
 */
expect fun kafkaEventSink(
    bootstrapServers: String,
    topic: String,
): EventSink?
