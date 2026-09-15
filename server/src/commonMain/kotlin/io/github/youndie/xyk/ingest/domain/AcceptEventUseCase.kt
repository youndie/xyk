package io.github.youndie.xyk.ingest.domain

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
            }
        }
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
