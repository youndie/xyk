package io.github.youndie.xyk.ingest.domain

import io.github.youndie.xyk.sink.AcceptedRecord
import io.github.youndie.xyk.sink.EventSink
import io.github.youndie.xyk.suspendRunCatching
import io.github.youndie.xyk.verify.SignedRequest
import io.github.youndie.xyk.verify.Verdict
import io.github.youndie.xyk.verify.VerificationFailure
import io.github.youndie.xyk.verify.Verifier

/**
 * Accepting one webhook: find the endpoint, prove the request, store it with its deliveries.
 *
 * The failures a route has to tell apart are **typed**, not messages: an unknown endpoint and a bad
 * signature are different statuses, and a stale timestamp is a different incident from a wrong
 * secret even though both end in `401`. A route matching on an exception message is how those get
 * merged by accident.
 */
class AcceptEventUseCase(
    private val repository: EventRepository,
    private val verifiers: Map<String, Verifier>,
    /**
     * Where an accepted event goes besides this service's own subscribers, or `null` for the
     * default deployment, which has no second destination.
     */
    private val sink: EventSink? = null,
    /**
     * What to do when the sink refuses. Handed in rather than printed here, because the one thing
     * this must not become is a failure nobody can see and nobody can assert.
     */
    private val onPublishFailure: (AcceptedEvent, Throwable) -> Unit = { _, _ -> },
) {
    suspend operator fun invoke(params: Params): Result<AcceptedEvent> {
        val endpoint =
            repository.findEndpoint(params.endpointId)
                ?: return Result.failure(Error.UnknownEndpoint())

        // An endpoint whose scheme nothing implements is a configuration error, and it is refused
        // like an unknown endpoint rather than accepted unverified. The alternative — falling back
        // to "no verification" — is the one mistake in this file that could not be undone.
        val verifier =
            verifiers[endpoint.scheme]
                ?: return Result.failure(Error.UnknownEndpoint())

        return when (
            val verdict =
                verifier.verify(params.request, endpoint.secrets, endpoint.schemeConfig)
        ) {
            is Verdict.Refused -> {
                Result.failure(Error.NotVerified(verdict.failure))
            }

            is Verdict.Passed -> {
                // `suspendRunCatching`, not `runCatching`: the standard one would swallow the
                // cancellation that stops this service and hand the route a failure instead.
                val stored =
                    suspendRunCatching {
                        repository.accept(
                            endpoint = endpoint,
                            receivedAt = params.request.nowEpochSeconds,
                            scheme = endpoint.scheme,
                            secretFingerprint = verdict.fingerprint,
                            contentType = params.contentType,
                            body = params.request.body,
                        )
                    }.recoverCatching { failure -> throw Error.NotStored(failure) }

                // AFTER the transaction has committed, and only then. The row is what makes a
                // publish that never happened visible from outside — an event in the table with
                // nothing on the topic behind it — and that is only true in this order.
                stored.onSuccess { accepted -> publish(accepted, endpoint, params) }
                stored
            }
        }
    }

    /**
     * Hands the stored event to the sink, and **does not fail the request when the sink does**.
     *
     * The event is accepted: it is in `events`, it has its delivery rows, and the sender has been
     * promised nothing about Kafka. Turning a refused publish into a `500` would ask the sender to
     * send it again, and a second copy of a webhook is a worse outcome than a record that is missing
     * from a topic and said so out loud.
     *
     * Cancellation is the exception and is left to propagate. It means the process is stopping
     * underneath this request, which the drain is supposed to prevent — the one place where a row
     * without a record is a defect rather than a decision.
     */
    private suspend fun publish(
        accepted: AcceptedEvent,
        endpoint: IngestEndpoint,
        params: Params,
    ) {
        val destination = sink ?: return
        val body = params.request.body
        suspendRunCatching {
            destination.publish(
                AcceptedRecord(
                    eventId = accepted.id,
                    endpointId = endpoint.id,
                    receivedAt = params.request.nowEpochSeconds,
                    bodyBytes = body.size.toLong(),
                    contentType = params.contentType,
                ),
            )
        }.onFailure { failure -> onPublishFailure(accepted, failure) }
    }

    class Params(
        val endpointId: String,
        val request: SignedRequest,
        val contentType: String?,
    )

    sealed class Error : Exception() {
        /** Unknown, disabled, or configured with a scheme nothing implements — one answer for all. */
        class UnknownEndpoint : Error()

        class NotVerified(
            val failure: VerificationFailure,
        ) : Error()

        class NotStored(
            override val cause: Throwable,
        ) : Error()
    }
}
