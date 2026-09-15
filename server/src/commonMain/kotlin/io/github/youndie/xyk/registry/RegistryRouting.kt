package io.github.youndie.xyk.registry

import io.github.youndie.xyk.contract.AddSubscriberRequest
import io.github.youndie.xyk.contract.CreateEndpointRequest
import io.github.youndie.xyk.contract.CreatedEndpoint
import io.github.youndie.xyk.contract.EndpointView
import io.github.youndie.xyk.contract.EndpointsResource
import io.github.youndie.xyk.contract.ErrorResponse
import io.github.youndie.xyk.contract.PatchEndpointRequest
import io.github.youndie.xyk.contract.SubscriberResource
import io.github.youndie.xyk.contract.SubscriberView
import io.github.youndie.xyk.newId
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.registry.domain.EndpointRecord
import io.github.youndie.xyk.registry.domain.RegistryRepository
import io.github.youndie.xyk.registry.domain.RotateSecretUseCase
import io.github.youndie.xyk.registry.domain.subscriberUrlProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Endpoints, secrets and subscribers.
 *
 * **Mounted without a gate, and that is the documented contract** (`docs/api/endpoint-admin.md`):
 * the deployment protects these routes — forward-auth, an ingress rule, a network policy. Written
 * here so nobody reads the absence of `authenticate { }` as an oversight. `main` says it out loud at
 * start-up for the same reason.
 */
fun Route.registryRouting(
    repository: RegistryRepository,
    createEndpoint: CreateEndpointUseCase,
    rotateSecret: RotateSecretUseCase,
    publicBaseUrl: String,
    nowEpochSeconds: () -> Long,
) {
    get<EndpointsResource> {
        val rejections = repository.rejections()
        call.respond(repository.list().map { it.toView(rejections[it.id].orEmpty()) })
    }

    post<EndpointsResource> {
        val body = call.receive<CreateEndpointRequest>()
        createEndpoint(
            CreateEndpointUseCase.Params(
                scheme = body.scheme,
                secret = body.secret,
                description = body.description,
                nowEpochSeconds = nowEpochSeconds(),
                schemeConfig = body.schemeConfig,
            ),
        ).fold(
            onSuccess = { id ->
                call.respond(
                    HttpStatusCode.Created,
                    CreatedEndpoint(id = id, url = "$publicBaseUrl/hooks/$id"),
                )
            },
            onFailure = { failure ->
                when (failure) {
                    is CreateEndpointUseCase.Error.BadScheme -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(failure.message))
                    }

                    else -> {
                        throw failure
                    }
                }
            },
        )
    }

    get<EndpointsResource.ById> { resource ->
        val endpoint = repository.find(resource.id)
        if (endpoint == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown endpoint"))
        } else {
            call.respond(endpoint.toView(repository.rejections()[endpoint.id].orEmpty()))
        }
    }

    patch<EndpointsResource.ById> { resource ->
        if (repository.find(resource.id) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown endpoint"))
            return@patch
        }
        val body = call.receive<PatchEndpointRequest>()
        body.enabled?.let { repository.setEnabled(resource.id, it) }
        body.description?.let { repository.setDescription(resource.id, it) }
        body.secret?.let { secret ->
            rotateSecret(resource.id, secret, nowEpochSeconds()).onFailure { failure ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(failure.message ?: "secret rejected"),
                )
                return@patch
            }
        }
        call.respond(repository.find(resource.id)!!.toView(emptyMap()))
    }

    delete<EndpointsResource.ById> { resource ->
        // DISABLES. The verb is a lie the HTTP vocabulary forces on us: deleting the row would
        // orphan every event that references it, and the journal is the product.
        if (repository.find(resource.id) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown endpoint"))
            return@delete
        }
        repository.setEnabled(resource.id, enabled = false)
        call.respond(repository.find(resource.id)!!.toView(emptyMap()))
    }

    get<EndpointsResource.ById.Subscribers> { resource ->
        val endpointId = resource.parent.id
        if (repository.find(endpointId) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown endpoint"))
            return@get
        }
        call.respond(
            repository.listSubscribers(endpointId).map {
                SubscriberView(it.id, it.url, it.enabled)
            },
        )
    }

    post<EndpointsResource.ById.Subscribers> { resource ->
        val endpointId = resource.parent.id
        if (repository.find(endpointId) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown endpoint"))
            return@post
        }
        val body = call.receive<AddSubscriberRequest>()
        subscriberUrlProblem(body.url)?.let { problem ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
            return@post
        }
        val id = newId()
        repository.addSubscriber(id, endpointId, body.url.trim(), nowEpochSeconds())
        call.respond(HttpStatusCode.Created, SubscriberView(id, body.url.trim(), enabled = true))
    }

    delete<SubscriberResource> { resource ->
        // Timers already scheduled are NOT cancelled: they fire, fail against a subscriber that is
        // gone, and dead-letter. A timer disappearing without a record is the one thing the journal
        // exists to prevent.
        if (repository.removeSubscriber(resource.id)) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown subscriber"))
        }
    }
}

private fun EndpointRecord.toView(rejections: Map<String, Long>): EndpointView =
    EndpointView(
        id = id,
        scheme = scheme,
        enabled = enabled,
        description = description,
        createdAt = createdAt,
        // Fingerprints, never secrets. This mapping is the only place that distinction is enforced,
        // which is why `EndpointRecord` does not carry a secret at all — there is nothing here to
        // forget to drop.
        secretFingerprints = secretFingerprints,
        subscribers = subscriberCount,
        rejections = rejections,
    )
