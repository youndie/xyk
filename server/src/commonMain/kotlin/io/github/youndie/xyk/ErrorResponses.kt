package io.github.youndie.xyk

import io.github.youndie.xyk.contract.ErrorResponse
import io.github.youndie.xyk.db.UnstorableText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.defaultExceptionStatusCode
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException

/**
 * What a request is answered with when a handler did not expect what happened.
 *
 * **Without this, Ktor answers an unexpected exception with its message as the body** — which for a
 * database failure is the database's own sentence about the statement it was handed. A client has
 * no use for that and should not be shown it. So:
 *
 * - text the database cannot hold ([UnstorableText]) is the client's error, `400`, with a fixed
 *   sentence;
 * - Ktor's own client errors keep the status and the plain-text sentence Ktor gives them — a body
 *   that cannot be decoded is still the `400` `docs/api/endpoint-admin.md` names;
 * - everything else is `500 {"error":"internal error"}`, and the cause goes to the log, which is the
 *   one place that needs it.
 *
 * Cancellation is not an error and goes back to the engine untouched: it means the call or the
 * process is stopping, and answering it as a `500` would turn a drain into a burst of failures.
 */
fun Application.installErrorResponses() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            val clientStatus = defaultExceptionStatusCode(cause)
            when {
                cause is UnstorableText -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(UnstorableText.MESSAGE))
                }

                clientStatus != null -> {
                    call.respondText(cause.message ?: clientStatus.description, status = clientStatus)
                }

                else -> {
                    // The class and the message, as everywhere else in this service's log. Values
                    // reach SQL as bound parameters, so a database message names the statement's
                    // shape and never what was in it.
                    println(
                        "xyk: ${call.request.httpMethod.value} ${call.request.path()} failed — " +
                            "${cause::class.simpleName}: ${cause.message}",
                    )
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(INTERNAL_ERROR))
                }
            }
        }
    }
}

/** The whole body of an unexpected `500`. Deliberately says nothing about what went wrong. */
const val INTERNAL_ERROR: String = "internal error"
