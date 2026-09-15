package io.github.youndie.xyk.delivery.domain

/**
 * What one delivery needs in order to be attempted, read in a single query.
 *
 * The payload travels as bytes and is never parsed: it is the sender's body, stored byte for byte
 * because the signature was over those bytes, and a subscriber that verifies it in turn would be
 * broken by any re-encoding on the way out.
 */
data class DeliveryTarget(
    val deliveryId: String,
    val eventId: String,
    val endpointId: String,
    val subscriberUrl: String,
    val contentType: String?,
    val body: ByteArray,
    val attemptsSoFar: Int,
) {
    // `body` is an array, so the generated equals would compare references and the generated
    // hashCode would change per instance. Nothing here needs either, and a data class that lies
    // about equality is worse than one that refuses: both are written out rather than generated.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = deliveryId.hashCode()
}

/** One recorded attempt: what was sent, what came back, and how long it took. */
data class AttemptRecord(
    val deliveryId: String,
    val attempt: Int,
    val status: Int?,
    val durationMs: Long,
    val detail: String,
    val atEpochSeconds: Long,
)

/**
 * Storage for the outbound half.
 *
 * Deliberately narrow: chronik owns scheduling, retries and dead-lettering, so nothing here decides
 * when something happens — only what is written down when it does.
 */
interface DeliveryRepository {
    /** The delivery a timer points at, or `null` if it has been removed since the timer was set. */
    suspend fun target(deliveryId: String): DeliveryTarget?

    /**
     * Records one attempt **and** moves the delivery's own counters in the same transaction.
     *
     * One call rather than two because the pair must not come apart: an attempt row with no counter
     * bump reads as a delivery that was never tried, and a bump with no row is the state an operator
     * cannot explain.
     */
    suspend fun recordAttempt(
        record: AttemptRecord,
        newState: String,
    )
}

/** The three states a delivery can be in; the journal filters on exactly these strings. */
object DeliveryState {
    const val PENDING: String = "pending"
    const val DELIVERED: String = "delivered"
    const val DEAD: String = "dead"
}
