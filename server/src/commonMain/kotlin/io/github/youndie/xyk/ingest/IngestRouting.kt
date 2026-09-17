package io.github.youndie.xyk.ingest

import io.github.youndie.xyk.contract.AcceptedResponse
import io.github.youndie.xyk.contract.ErrorResponse
import io.github.youndie.xyk.contract.HookResource
import io.github.youndie.xyk.ingest.domain.AcceptEventUseCase
import io.github.youndie.xyk.verify.SignedRequest
import io.github.youndie.xyk.verify.VerificationFailure
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * The inbound half, mounted without any gate: **the signature is the authentication**.
 *
 * The body is read once, as bytes, and handed on unchanged. Nothing here parses it — any framework
 * convenience that reserialises a JSON body changes whitespace or key order and destroys the
 * signature, which is the single most common way a webhook receiver breaks, and it breaks in the
 * direction of rejecting genuine traffic.
 */
fun Route.ingestRouting(
    acceptEvent: AcceptEventUseCase,
    maxBodyBytes: Long,
    rejections: RejectionCounters,
    nowEpochSeconds: () -> Long,
) {
    post<HookResource> { hook ->
        // Checked BEFORE the read. A limit that buffers first defends nothing, and the declared
        // length is free when it is there.
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBodyBytes) {
            rejections.record(hook.endpointId, RejectionReason.BODY_TOO_LARGE)
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("body too large"))
            return@post
        }

        // One byte past the limit, so a body that lies about its length is caught by the same check
        // rather than by a second one that could disagree with the first.
        val body =
            call
                .receiveChannel()
                .readBuffer(maxBodyBytes + 1)
                .readByteArray()
        if (body.size > maxBodyBytes) {
            rejections.record(hook.endpointId, RejectionReason.BODY_TOO_LARGE)
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("body too large"))
            return@post
        }

        val params =
            AcceptEventUseCase.Params(
                endpointId = hook.endpointId,
                request =
                    SignedRequest(
                        headers = { name -> call.request.headers[name] },
                        body = body,
                        nowEpochSeconds = nowEpochSeconds(),
                    ),
                contentType =
                    call.request
                        .contentType()
                        .takeIf { it != ContentType.Any }
                        ?.toString(),
            )

        acceptEvent(params).fold(
            onSuccess = { accepted ->
                // Written only after the transaction has committed. Answering earlier would make
                // the throughput criterion measure how fast this process can accept bytes it may
                // then lose.
                call.respond(HttpStatusCode.OK, AcceptedResponse(accepted.id))
            },
            onFailure = { failure ->
                // Counted against the endpoint when there is one, and against the global bucket when
                // the id in the URL is not real — otherwise anyone with a URL bar could create rows.
                rejections.record(failure.endpointIdToBlame(hook.endpointId), failure.reason())
                call.respondToFailure(failure)
            },
        )
    }
}

/** An unknown endpoint has no endpoint to blame; everything else is the endpoint that was asked for. */
private fun Throwable.endpointIdToBlame(requested: String): String? =
    if (this is AcceptEventUseCase.Error.UnknownEndpoint) null else requested

private fun Throwable.reason(): RejectionReason =
    when (this) {
        is AcceptEventUseCase.Error.UnknownEndpoint -> {
            RejectionReason.UNKNOWN_ENDPOINT
        }

        is AcceptEventUseCase.Error.NotVerified -> {
            when (failure) {
                VerificationFailure.MISSING -> RejectionReason.SIGNATURE_MISSING
                VerificationFailure.INVALID -> RejectionReason.SIGNATURE_INVALID
                VerificationFailure.STALE -> RejectionReason.SIGNATURE_STALE
            }
        }

        else -> {
            RejectionReason.NOT_STORED
        }
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondToFailure(failure: Throwable) {
    when (failure) {
        // The same answer, byte for byte, for an endpoint that never existed and one that is
        // disabled. A distinct status would turn this route into an oracle for which ids are real.
        is AcceptEventUseCase.Error.UnknownEndpoint -> {
            respond(HttpStatusCode.NotFound, ErrorResponse("unknown endpoint"))
        }

        is AcceptEventUseCase.Error.NotVerified -> {
            respond(
                // `401` and not `403`: the request failed to prove who it is, it was not refused
                // permission. It also matters to the sender — GitHub and Stripe treat `4xx` as
                // final and retry `5xx`, which is the behaviour we want for a bad signature.
                HttpStatusCode.Unauthorized,
                ErrorResponse(
                    when (failure.failure) {
                        VerificationFailure.MISSING -> "signature missing"

                        VerificationFailure.INVALID -> "signature invalid"

                        // Its own code, because a skewed clock and a wrong secret are different
                        // incidents and an operator who cannot tell them apart debugs the wrong one.
                        VerificationFailure.STALE -> "signature stale"
                    },
                ),
            )
        }

        is AcceptEventUseCase.Error.NotStored -> {
            respond(HttpStatusCode.InternalServerError, ErrorResponse("not stored"))
        }

        else -> {
            throw failure
        }
    }
}
