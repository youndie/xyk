package io.github.youndie.xyk

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.xyk.db.Retention
import io.github.youndie.xyk.db.RetentionSweep
import io.github.youndie.xyk.db.WalCheckpoint
import io.github.youndie.xyk.db.WalSweep
import io.github.youndie.xyk.db.applyBootstrap
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.health.XykProbes
import io.github.youndie.xyk.ingest.RejectionCounters
import io.github.youndie.xyk.ingest.RejectionFlush
import io.github.youndie.xyk.ingest.domain.AcceptEventUseCase
import io.github.youndie.xyk.ingest.ingestModule
import io.github.youndie.xyk.journal.DeliveryStatus
import io.github.youndie.xyk.journal.domain.JournalRepository
import io.github.youndie.xyk.journal.journalModule
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.registry.domain.RegistryRepository
import io.github.youndie.xyk.registry.domain.RotateSecretUseCase
import io.github.youndie.xyk.registry.registryModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * How the process stops, as numbers.
 *
 * They add up to 2 + 10 + 3×2 = 18 seconds against the 30-second `terminationGracePeriodSeconds`
 * the chart will declare. **The sum has to stay under that number**: no platform tells a process its
 * real budget, kore takes the one it is told, and a plan that does not fit is a `SIGKILL` in the
 * middle of a stage. The two numbers are one number in two places and nothing compares them, so they
 * move together by hand.
 */
private val DEADLINES =
    ShutdownDeadlines(
        preDrainWait = 2.seconds,
        drain = 10.seconds,
        releaseGroup = 3.seconds,
        gracePeriod = 30.seconds,
    )

/**
 * The order the process starts in, which is the order it must stop in, reversed.
 *
 * Migrations, then the engine, then the gates — and on the way out: announce not-ready, drain the
 * engine, stop the delivery workers (B-11), close the pool. That order is the reason kore is here
 * at all: `EmbeddedServer.stop` runs its steps in the **opposite** order on Kotlin/Native and on the
 * JVM, so the idiomatic place to close a pool — `ApplicationStopping` — runs before the drain on
 * native, and nothing reports it. For this service that is a webhook answered `200` and never
 * delivered.
 */
fun main() {
    val config = getServerConfig()

    // Here, before the engine: a server that opened its port ahead of a ready schema would answer
    // the first requests with errors, and those requests are webhooks nobody sends twice.
    val db = openDatabase(config)

    // Printed rather than logged, and printed early: it names which engine variant this binary was
    // linked with, which is the one fact a size measurement of it cannot be read without.
    println("xyk: " + httpEngineMarker())

    val wal = WalCheckpoint(db, config.sqlitePath)
    val sweep =
        WalSweep(
            wal = wal,
            intervalSeconds = config.walCheckpointSeconds,
            ceilingBytes = config.walMaxBytes,
            onBusy = { state -> println("wal: checkpoint left ${state.framesInLog} frames, ${state.bytes} bytes") },
            onFailure = { failure -> println("wal: checkpoint failed: ${failure::class.simpleName}") },
        )

    val retentionSweep =
        RetentionSweep(
            retention = Retention(db),
            retentionDays = config.retentionDays,
            nowEpochSeconds = { startedAtEpochSeconds() },
            onPurged = { count -> println("xyk: retention purged $count payloads") },
            onFailure = { failure -> println("xyk: retention failed: ${failure::class.simpleName}") },
        )

    val probes = XykProbes(db, wal, config.walCeilingBytes)

    // The one endpoint of B-06, put into the real tables so that B-07 replaces the *way* they are
    // created and nothing else.
    config.bootstrapEndpoint?.let { endpoint ->
        runBlocking { db.applyBootstrap(endpoint, startedAtEpochSeconds()) }
    }

    val koin =
        startKoin {
            modules(
                configModule(config),
                storageModule(db),
                ingestModule(db, config.stripeToleranceSeconds),
                registryModule(db, config.allowUnverified),
                journalModule(db),
            )
        }
    val acceptEvent = koin.koin.get<AcceptEventUseCase>()
    val rejections = koin.koin.get<RejectionCounters>()
    val rejectionFlush =
        RejectionFlush(db, rejections) { failure ->
            println("xyk: rejection flush failed: ${failure::class.simpleName}")
        }
    val registry = koin.koin.get<RegistryRepository>()
    val createEndpoint = koin.koin.get<CreateEndpointUseCase>()
    val rotateSecret = koin.koin.get<RotateSecretUseCase>()
    val journal = koin.koin.get<JournalRepository>()

    // Said every start, deliberately. The admin routes have no gate of their own — that is the
    // documented contract, and a contract nobody is reminded of is a surprise waiting for whoever
    // exposes this port without a proxy in front of it.
    println("xyk: /api/** has no authentication of its own — the deployment is responsible")

    val server =
        embeddedServer(
            CIO,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = config.port
                        host = config.host
                    },
                )
                // The engine gets the same numbers the drain stage uses. Ktor's own default is one
                // second, which is shorter than a great many real requests.
                shutdownGracePeriod = DEADLINES.drain.inWholeMilliseconds
                shutdownTimeout = (DEADLINES.drain + 5.seconds).inWholeMilliseconds
            },
            module = {
                module(
                    config,
                    probes,
                    acceptEvent,
                    rejections,
                    registry,
                    createEndpoint,
                    rotateSecret,
                    journal,
                    // No worker exists yet (B-11), and the page says so rather than showing states
                    // that nothing maintains. This becomes a real reading when the workers land.
                    { DeliveryStatus.NOT_CONFIGURED },
                )
            },
        )

    // NOT `wait = true`. The main thread has to reach the await below, or the signal arrives at a
    // process that has no sequence to run and is killed at the end of the grace period instead —
    // which from outside is indistinguishable from a clean stop.
    server.start(wait = false)

    val checksScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    probes.start(checksScope)
    sweep.start(checksScope)
    rejectionFlush.start(checksScope)
    // Returns null and logs nothing when no horizon is configured, which is the default.
    if (retentionSweep.start(checksScope) != null) {
        println("xyk: payloads are purged after ${config.retentionDays} days")
    }
    probes.startup.markStarted()

    runBlocking {
        runUntilSignal(
            DEADLINES,
            // Inside the callback rather than on the line after the call: on the JVM this function
            // returning means the shutdown hook has returned and the process is already leaving.
            onFinished = { run -> println(run.transcript) },
        ) {
            announce(AnnounceNotReady(probes.readiness))
            drain(EngineDrain(server, DEADLINES.drain, DEADLINES.drain + 5.seconds))

            // B-11 adds the delivery workers here, between the drain and the pool: they are what
            // holds work that was accepted and not yet delivered.

            // Before the pool and after the drain: the last checkpoint runs while the database is
            // still open, and it decides how much journal the *next* process has to work through
            // before it can serve — time that would otherwise be added to a cold start nobody
            // attributes to this one.
            // The last rejections of a deploy are the ones an operator is most likely to go looking
            // for, so they are flushed here rather than lost with the process.
            consumer(participant("retention sweep") { retentionSweep.stop() })
            consumer(participant("rejection counters") { rejectionFlush.stop() })
            consumer(participant("wal sweep") { sweep.stop() })

            // Last, because everything above it writes through this pool.
            pool(databaseParticipant(db))

            telemetry(
                participant("health checks") {
                    probes.stop()
                    checksScope.cancel()
                    koin.close()
                    stopKoin()
                },
            )
        }
    }
}

private fun databaseParticipant(db: ISQLite) = participant("sqlite pool") { db.close().getOrThrow() }

/**
 * A participant out of a name and a lambda.
 *
 * The parameters are `label` and `block` rather than `name` and `stop`: inside the object those two
 * names belong to the members being overridden, and `stop()` calling `stop` would be the function
 * calling itself.
 */
private fun participant(
    label: String,
    block: suspend () -> Unit,
): ShutdownParticipant =
    object : ShutdownParticipant {
        override val name: String = label

        override suspend fun stop() {
            block()
        }
    }

/**
 * When this process started, as seconds.
 *
 * The rule this suppresses is right in general — a time that has to agree with somebody else's
 * belongs on the wire — and this is the exception it allows for: nothing compares these timestamps
 * with another machine's. They are `created_at` on rows this process writes about itself.
 */
@Suppress(
    "ktlint:kapkan:wall-clock",
    "created_at on locally written rows is not a time anybody else has an opinion about",
)
private fun startedAtEpochSeconds(): Long = Clock.System.now().epochSeconds
