package io.github.youndie.xyk

import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.xyk.health.XykProbes
import io.github.youndie.xyk.ingest.RejectionCounters
import io.github.youndie.xyk.ingest.domain.AcceptEventUseCase
import io.github.youndie.xyk.ingest.ingestRouting
import io.github.youndie.xyk.journal.DeliveryStatus
import io.github.youndie.xyk.journal.domain.JournalRepository
import io.github.youndie.xyk.journal.journalApi
import io.github.youndie.xyk.journal.journalRouting
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.registry.domain.RegistryRepository
import io.github.youndie.xyk.registry.domain.RotateSecretUseCase
import io.github.youndie.xyk.registry.registryRouting
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Everything this server is, given a database that is already open and the gates that answer for it.
 *
 * **The database is a parameter rather than something opened here, and that is the shutdown
 * talking.** Opening it here would leave `main` with no handle on the pool and therefore no way to
 * close it *after* the engine has drained — the ordering `ApplicationStopping` gets backwards on
 * Kotlin/Native and right on the JVM, from identical source.
 */
fun Application.module(
    config: ServerConfig,
    probes: XykProbes,
    acceptEvent: AcceptEventUseCase,
    rejections: RejectionCounters,
    registry: RegistryRepository,
    createEndpoint: CreateEndpointUseCase,
    rotateSecret: RotateSecretUseCase,
    journal: JournalRepository,
    deliveryStatus: () -> DeliveryStatus,
) {
    // BEFORE the probes and before any route. An interceptor installed later would let through every
    // call that arrived first, and the one request this must not miss is the first one after
    // readiness has gone false. It leaves kore's own routes alone: a `503` from `/health/live` is a
    // failed liveness probe, which restarts the pod in the middle of the shutdown it is reporting.
    installShutdownRefusal(isShuttingDown = { probes.readiness.isShuttingDown })
    installKoreProbes(probes.startup, probes.readiness, probes.liveness)

    // The version and the commit, compiled in by the Gradle plugin because Kotlin/Native has neither
    // resources nor a manifest to read them from.
    installKoreVersion(KoreBuildIdentity)

    install(ContentNegotiation) {
        json(
            Json {
                // A field equal to its default disappears from the response, which is what we want
                // for optional shapes — and a trap for counters that can legitimately be zero
                // (`attempts`, `walBytes`). Those carry no default, so an absent key never has to be
                // told apart from a zero.
                encodeDefaults = false
                ignoreUnknownKeys = true
            },
        )
    }

    install(Resources)

    // THE TIER IS DECIDED HERE, AT THE MOUNT, and for this route the tier is "none": the signature
    // is the authentication. Putting an `authenticate { }` around it would refuse GitHub.
    //
    // The use case is passed in rather than injected at the call site: `koin-ktor` is not a
    // dependency here (see `libs.versions.toml`), and a handler that resolves its own dependencies
    // is a handler that cannot be tested without a container.
    routing {
        ingestRouting(acceptEvent, config.maxBodyBytes, rejections) { serverNowEpochSeconds() }

        // NO GATE, and it is the documented contract rather than an oversight: the deployment
        // protects these routes. `main` says so at start-up, out loud, every time.
        registryRouting(
            repository = registry,
            createEndpoint = createEndpoint,
            rotateSecret = rotateSecret,
            publicBaseUrl = config.publicBaseUrl,
        ) { serverNowEpochSeconds() }

        journalRouting(
            repository = journal,
            deliveryStatus = deliveryStatus,
            hookUrlFor = { id -> config.publicBaseUrl + "/hooks/" + id },
            anyEndpointId = { registry.anyEnabledId() },
        )
        journalApi(journal) { serverNowEpochSeconds() }
    }
}

/**
 * This host's clock, read in one place.
 *
 * Reading the local clock is normally the wrong thing — a time that has to agree with somebody
 * else's belongs on the wire — and here it is the point: Stripe signs a timestamp and expects the
 * receiver to compare it against *its own* clock inside a tolerance. That comparison is what makes
 * a replayed payload stale, and a skewed host rejects genuine traffic because of it (research,
 * Risk 5). It lives at the composition root so that the rule is suppressed once, with its reason,
 * and every handler takes the time as a parameter it can be given in a test.
 */
@Suppress(
    "ktlint:kapkan:wall-clock",
    "the Stripe tolerance is a deliberate comparison against this host's clock",
)
private fun serverNowEpochSeconds(): Long = Clock.System.now().epochSeconds
