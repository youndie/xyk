package io.github.youndie.xyk.registry.domain

import io.github.youndie.xyk.db.fingerprintOf
import io.github.youndie.xyk.newId
import io.github.youndie.xyk.suspendRunCatching
import io.github.youndie.xyk.verify.SchemeConfig

/**
 * Creating an endpoint: validate the scheme, mint an unguessable id, store the first secret.
 *
 * A class rather than a call from the route because there are two rules and a generated id — which
 * is exactly the line the `ktor-server-feature` skill draws: a plain read with no rule is not worth
 * a class, and this is neither plain nor a read.
 */
class CreateEndpointUseCase(
    private val repository: RegistryRepository,
    private val implementedSchemes: () -> Set<String>,
    private val allowUnverified: Boolean,
) {
    suspend operator fun invoke(params: Params): Result<String> {
        schemeProblem(params.scheme, implementedSchemes(), allowUnverified)
            ?.let { problem -> return Result.failure(Error.BadScheme(problem)) }
        if (params.secret.isBlank() && params.scheme != NO_VERIFICATION) {
            return Result.failure(Error.BadScheme("a secret is required for scheme ${params.scheme}"))
        }
        // The generic scheme is the one that cannot work without configuration: with no header named
        // it would refuse every request it ever received, which is a `404` an operator has no way to
        // explain. Refused here instead, while there is somebody to tell.
        schemeConfigProblem(params.scheme, params.schemeConfig)
            ?.let { problem -> return Result.failure(Error.BadScheme(problem)) }

        val id = newId()
        return suspendRunCatching {
            repository.create(
                id = id,
                scheme = params.scheme,
                description = params.description,
                secret = params.secret,
                fingerprint = fingerprintOf(params.secret),
                createdAt = params.nowEpochSeconds,
                schemeConfig = params.schemeConfig?.let { SchemeConfig.encode(it) },
            )
            id
        }
    }

    class Params(
        val scheme: String,
        val secret: String,
        val description: String,
        val nowEpochSeconds: Long,
        val schemeConfig: SchemeConfig? = null,
    )

    sealed class Error : Exception() {
        class BadScheme(
            override val message: String,
        ) : Error()
    }
}

/**
 * Rotation **adds** a secret; it never replaces one.
 *
 * Stripe signs with every active secret for up to 24 hours while one is being rolled, so an endpoint
 * that dropped the old secret the instant a new one arrived would reject genuine traffic for a day.
 * Retirement is therefore a second, later act — `retires_at` on the row — and B-19 decides who
 * performs it.
 */
class RotateSecretUseCase(
    private val repository: RegistryRepository,
) {
    suspend operator fun invoke(
        endpointId: String,
        secret: String,
        nowEpochSeconds: Long,
    ): Result<String> {
        if (secret.isBlank()) return Result.failure(IllegalArgumentException("secret must not be blank"))
        val fingerprint = fingerprintOf(secret)
        return suspendRunCatching {
            repository.addSecret(endpointId, secret, fingerprint, nowEpochSeconds)
            fingerprint
        }
    }
}
