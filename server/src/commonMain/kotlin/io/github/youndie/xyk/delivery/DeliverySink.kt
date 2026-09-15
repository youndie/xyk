package io.github.youndie.xyk.delivery

import io.github.youndie.xyk.delivery.domain.AttemptRecord
import io.github.youndie.xyk.delivery.domain.DeliveryRepository
import io.github.youndie.xyk.delivery.domain.DeliveryState
import io.github.youndie.xyk.delivery.domain.DeliveryTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

/**
 * One delivery attempt: resolve, POST, bound it, write the row, return or throw.
 *
 * **Throwing is the interface.** chronik's `TimerWorker` reads a thrown exception as a failed
 * attempt and schedules the next one by its own backoff; returning normally means delivered. So
 * every failure here — a `500`, a `302`, a refused connection, a timeout — leaves by the same door,
 * and the only thing that must never happen is a failure that returns.
 *
 * **What is ours and what is chronik's.** Scheduling, the `base × 2^(attempt-1)` gaps, the attempt
 * cap and dead-lettering are chronik's and are not reimplemented here. The timeout is ours, because
 * chronik has none and `tick()` is a *sequential* loop over the batch: without a bound, one
 * subscriber that accepts a connection and never answers stalls every other delivery in the same
 * tick. With it, a tick costs at most `batchSize × timeout`, which is a number an operator can
 * reason about ([feature-delivery](../../../../../../../../docs/features/feature-delivery.md)).
 */
class DeliverySink(
    private val repository: DeliveryRepository,
    private val outbound: OutboundPost,
    private val timeoutMillis: Long,
    private val maxAttempts: Int,
    private val nowEpochSeconds: () -> Long,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * @throws DeliveryFailed when the attempt did not succeed — which is how the worker learns to
     *   retry. The attempt row is already written by then.
     */
    suspend fun deliver(deliveryId: String) {
        val target =
            repository.target(deliveryId)
                ?: // Not an error and not a retry: the delivery is gone, so there is nothing to
                // deliver and nothing to record against. Returning normally retires the timer,
                // which is the correct outcome — a retry loop against a deleted row would never
                // end.
                return

        val attempt = target.attemptsSoFar + 1
        val startedAt = timeSource.markNow()

        val outcome =
            try {
                // `withTimeout` bounds this because the call underneath it suspends — a Ktor client
                // request does. It would NOT bound a blocking call, which is the trap this project
                // has met elsewhere: a blocking read inside a coroutine ignores cancellation and the
                // timeout expires without stopping anything. The engine binding must be a suspending
                // client for this line to mean what it says.
                withTimeout(timeoutMillis) {
                    val response =
                        outbound.post(
                            url = target.subscriberUrl,
                            contentType = target.contentType,
                            headers = headersFor(target, attempt),
                            body = target.body,
                        )
                    if (response.status in 200..299) {
                        Outcome(response.status, "", failed = false)
                    } else {
                        // Everything that is not 2xx, and 3xx deliberately among them: following a
                        // redirect would deliver somebody's payload to an address no operator
                        // approved.
                        Outcome(response.status, response.bodyPrefix.take(RESPONSE_PREFIX_BYTES), failed = true)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                // Named apart from a transport error on purpose: "it never answered in 2 s" and "the
                // connection was refused" send an operator to different places, and a single
                // "failed" would send them to neither.
                Outcome(status = null, detail = "timed out after ${timeoutMillis}ms", failed = true)
            } catch (cancellation: CancellationException) {
                // NOT AN ATTEMPT. The scope this runs in is cancelled when the process is stopping,
                // and swallowing that would do two wrong things at once: record a failed attempt
                // that nobody made, and let a cancelled coroutine go on running past the drain.
                // It is rethrown below the timeout catch because `TimeoutCancellationException` is
                // itself a `CancellationException` — the specific one first, then this.
                throw cancellation
            } catch (transport: Exception) {
                Outcome(
                    status = null,
                    detail = "${transport::class.simpleName}: ${transport.message.orEmpty().take(
                        RESPONSE_PREFIX_BYTES,
                    )}",
                    failed = true,
                )
            }

        val duration = startedAt.elapsedNow().inWholeMilliseconds

        // WRITTEN BEFORE THE THROW, always. The one case an operator most needs to see is a failing
        // delivery, and that is precisely the case where an exception on the way out would skip the
        // record if the order were the other way round.
        repository.recordAttempt(
            AttemptRecord(
                deliveryId = target.deliveryId,
                attempt = attempt,
                status = outcome.status,
                durationMs = duration,
                detail = outcome.detail,
                atEpochSeconds = nowEpochSeconds(),
            ),
            newState =
                when {
                    !outcome.failed -> DeliveryState.DELIVERED

                    // The state is written here rather than left for chronik because the journal
                    // reads it, and a delivery that has run out of attempts must not keep showing
                    // as pending. chronik stops scheduling at the same count; this is the same
                    // number read from the same configuration, not a second policy.
                    attempt >= maxAttempts -> DeliveryState.DEAD

                    else -> DeliveryState.PENDING
                },
        )

        if (outcome.failed) {
            throw DeliveryFailed(
                deliveryId = target.deliveryId,
                attempt = attempt,
                status = outcome.status,
                detail = outcome.detail,
            )
        }
    }

    /**
     * The headers a subscriber is promised.
     *
     * The event id and the attempt number are the two a subscriber needs to deduplicate, and
     * deduplication is its job rather than ours: at-least-once is the guarantee, and a crash between
     * the POST and the mark delivers the same event twice by design.
     */
    private fun headersFor(
        target: DeliveryTarget,
        attempt: Int,
    ): Map<String, String> =
        mapOf(
            "X-Xyk-Event" to target.eventId,
            "X-Xyk-Endpoint" to target.endpointId,
            "X-Xyk-Delivery" to target.deliveryId,
            "X-Xyk-Attempt" to attempt.toString(),
        )

    private data class Outcome(
        val status: Int?,
        val detail: String,
        val failed: Boolean,
    )
}

/**
 * A failed attempt, in the form chronik's worker understands.
 *
 * It carries the numbers rather than a sentence so that whatever logs it can decide how to say it;
 * the sentence a person reads is built from these at the edge.
 */
class DeliveryFailed(
    val deliveryId: String,
    val attempt: Int,
    val status: Int?,
    val detail: String,
) : Exception("delivery $deliveryId attempt $attempt failed" + (status?.let { " with $it" } ?: "") + ": $detail")
