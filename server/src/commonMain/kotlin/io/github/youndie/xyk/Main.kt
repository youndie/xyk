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
import io.github.youndie.xyk.db.lastCheckpoint
import io.github.youndie.xyk.db.openDatabase
import io.github.youndie.xyk.delivery.DeliverySink
import io.github.youndie.xyk.delivery.data.Sqlx4kDeliveryRepository
import io.github.youndie.xyk.delivery.deliveryWorkers
import io.github.youndie.xyk.delivery.outboundPost
import io.github.youndie.xyk.delivery.timerScheduler
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
import io.github.youndie.xyk.sink.QueuedEventSink
import io.github.youndie.xyk.sink.kafkaEventSink
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.system.exitProcess
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
    // A MISCONFIGURATION GETS A SENTENCE, NOT A CORE DUMP — the same lesson as the port (B-27),
    // applied where it recurs rather than patched once. `getServerConfig()` throws on a required
    // value that is missing, on a number out of range, and on a value that is present and
    // unreadable; all three are things an operator typed, and none of them is a bug in this
    // process. Uncaught, each one ends as `Uncaught Kotlin exception` over seven frames of stack
    // and `Aborted (core dumped)`, which buries the one line that says what to change.
    val config =
        try {
            getServerConfig()
        } catch (invalid: IllegalArgumentException) {
            println("xyk: ${invalid.message}")
            println("xyk: nothing was started; fix the environment and try again")
            exitProcess(BAD_CONFIG)
        }

    // Here, before the engine: a server that opened its port ahead of a ready schema would answer
    // the first requests with errors, and those requests are webhooks nobody sends twice.
    val db = openDatabase(config)

    // Printed rather than logged, and printed early: it names which engine variant this binary was
    // linked with, which is the one fact a size measurement of it cannot be read without.
    println("xyk: " + httpEngineMarker())

    println("xyk: " + applyHeapCeiling(config.heapBytes))

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
            nowEpochSeconds = { hostNowEpochSeconds() },
            onPurged = { count -> println("xyk: retention purged $count payloads") },
            onFailure = { failure -> println("xyk: retention failed: ${failure::class.simpleName}") },
        )

    // THE DELIVERY HALF, BUILT BEFORE THE PROBES because readiness watches it. Both pieces can be
    // absent and that is a shipping configuration rather than a failure: a build without
    // `ktor-client-curl` has no way to POST, and a host where chronik publishes no variant has no
    // way to schedule. What must not happen is a service that accepts webhooks and silently never
    // delivers them, so whichever is missing is said out loud, once, at start-up.
    val outbound = outboundPost()
    val scheduler = timerScheduler(db)
    val workers =
        outbound?.let { post ->
            deliveryWorkers(
                db = db,
                sink =
                    DeliverySink(
                        repository = Sqlx4kDeliveryRepository(db),
                        outbound = post,
                        timeoutMillis = config.deliveryTimeoutMillis,
                        maxAttempts = config.deliveryMaxAttempts,
                        nowEpochSeconds = { hostNowEpochSeconds() },
                    ),
                count = config.deliveryWorkers,
                pollIntervalSeconds = 1,
                leaseSeconds = 30,
                maxAttempts = config.deliveryMaxAttempts,
                // The clock is read HERE, at the composition root, with the suppression and its
                // reason in one place. A timer's due-at is compared against this host's own clock by
                // the process that scheduled it — it is a local schedule, not a time anybody else
                // has an opinion about — but the rule is worth obeying anyway, because a clock
                // reachable from inside a worker is a clock a test cannot move.
                nowEpochSeconds = ::hostNowEpochSeconds,
            )
        }
    when {
        scheduler == null -> {
            println(
                "xyk: delivery is OFF — this build has no chronik variant for its target; events are journalled only",
            )
        }

        workers != null -> {
            println(
                "xyk: delivery is on — ${workers.count} worker(s), " +
                    "${config.deliveryTimeoutMillis}ms per attempt, ${config.deliveryMaxAttempts} attempts",
            )
        }

        outbound == null -> {
            println("xyk: delivery is OFF — this binary links no outbound HTTP engine (-Pxyk.httpClient=true adds one)")
        }

        else -> {
            println("xyk: delivery is OFF — this build has no chronik variant for its target")
        }
    }

    // THE SECOND DESTINATION, AND IT IS ABSENT UNLESS SOMEBODY NAMED A BROKER. Built before Koin
    // because the ingest module takes it, and said out loud either way: a sink that is configured
    // and silently not linked would be a deployment publishing nothing while its operator watches a
    // topic.
    val sink =
        config.kafkaBootstrapServers?.let { servers ->
            kafkaEventSink(servers, config.kafkaTopic)?.let { direct ->
                // ZERO IS THE SHIPPING VALUE and leaves the sink exactly as it was: the publish
                // happens inside the request. Above zero this is a measurement arm — see
                // `QueuedEventSink`, and kafkakn's B-23 for what it is measuring.
                if (config.kafkaQueue == 0) {
                    direct
                } else {
                    QueuedEventSink(
                        delegate = direct,
                        capacity = config.kafkaQueue,
                        // Printed with the event id, immediately before the producer is asked. It is
                        // what lets a run tell "the producer lost it" from "the process stopped
                        // before the producer was asked", and the second of those is an outbox
                        // question rather than one about the producer.
                        onAsked = { eventId -> println("xyk: kafka queue asked $eventId") },
                        onFailure = { eventId, failure ->
                            println(
                                "xyk: kafka sink refused $eventId — " +
                                    "${failure::class.simpleName}: ${failure.message}",
                            )
                        },
                    )
                }
            }
        }
    config.kafkaBootstrapServers?.let { servers ->
        if (sink == null) {
            println("xyk: kafka sink is OFF — $servers is configured, but this build has no kafkakn variant")
        } else if (config.kafkaQueue == 0) {
            println("xyk: kafka sink is on — ${config.kafkaTopic} at $servers, acks=all, no queue")
        } else {
            println(
                "xyk: kafka sink is on — ${config.kafkaTopic} at $servers, acks=all, " +
                    "QUEUED ${config.kafkaQueue} deep (a measurement arm, not a deployment)",
            )
        }
    }

    val probes = XykProbes(db, wal, config.walCeilingBytes, workers, config.deliveryStallSeconds.seconds)

    // The one endpoint of B-06, put into the real tables so that B-07 replaces the *way* they are
    // created and nothing else.
    config.bootstrapEndpoint?.let { endpoint ->
        runBlocking { db.applyBootstrap(endpoint, hostNowEpochSeconds()) }
    }

    val koin =
        startKoin {
            modules(
                configModule(config),
                storageModule(db),
                ingestModule(
                    db,
                    config.stripeToleranceSeconds,
                    scheduler,
                    sink,
                    // Printed with the event id, because the id is the only thing that connects this
                    // line to the row in `events` that has nothing on the topic behind it.
                    onPublishFailure = { eventId, failure ->
                        println("xyk: kafka sink refused $eventId — ${failure::class.simpleName}: ${failure.message}")
                    },
                ),
                registryModule(db, config.allowUnverified),
                journalModule(db, scheduler),
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

    // THE PORT IS CLAIMED AND RELEASED BEFORE THE ENGINE IS STARTED, and this is the difference
    // between one sentence and a core dump.
    //
    // `start(wait = false)` returns before the connector is bound, so a taken port does not fail at
    // the call — it fails inside whichever coroutine notices, and on Kotlin/Native an unhandled
    // exception there aborts the process. What the operator gets is
    // `JobCancellationException: LazyStandaloneCoroutine is cancelling` with `EADDRINUSE` buried in
    // a `Caused by`, **after** Ktor has logged `Application started`, plus a core dump. Six deaths
    // in twelve restarts on a benchmark host, every one of them a run that began while the previous
    // instance still held the port (B-27).
    //
    // Catching it downstream does not work — `resolvedConnectors()` was tried and the process is
    // already dying by the time it could answer. So the question is asked *before* the engine
    // exists, by binding the address ourselves and letting go of it.
    //
    // **This has a race and it is the right trade.** Between the release and Ktor's own bind
    // another process could take the port, and then the old crash returns. What it converts is the
    // case that actually happens — an instance that is already running, or one still shutting down
    // — from an unreadable abort into a line naming the address.
    preflightBind(config.host, config.port)

    // NOT `wait = true`. The main thread has to reach the await below, or the signal arrives at a
    // process that has no sequence to run and is killed at the end of the grace period instead —
    // which from outside is indistinguishable from a clean stop.
    server.start(wait = false)

    val checksScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // A scope of its own, not `checksScope`, and the reason is the stop sequence rather than
    // tidiness: the readiness check is `SELECT 1` against the pool, so it is a *reader*, and a
    // reader alive during `wal_checkpoint(TRUNCATE)` is what that checkpoint waits for. Separating
    // the scopes is what makes it possible to end the readers first without touching the loops that
    // have their own ordered participants below.
    val probesScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    probes.start(probesScope)
    // Started after the engine, so the first tick cannot race the schema or the port. A delivery
    // attempted before this process can answer its own probes would be an attempt nobody could
    // explain from the outside.
    workers?.start(checksScope)
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

            // BETWEEN THE DRAIN AND THE POOL, and this position is the whole reason kore is in this
            // design. A worker mid-POST is holding a webhook that was already accepted with a
            // `200`; stopping it before the engine drains would cut deliveries for events still
            // arriving, and closing the pool before it would lose the attempt row rather than the
            // attempt — the record of the one thing an operator would come looking for.
            workers?.let { running -> consumer(participant("delivery workers") { running.stop() }) }

            // AFTER THE DRAIN, AND THAT POSITION IS THE WHOLE QUESTION. A publish belongs to a
            // request that has already been accepted; closing the producer while the engine still
            // has that request in flight would cut a record this service promised to carry. The
            // drain is what makes "in flight" a bounded set, and this stage is the first moment
            // after it.
            //
            // It can exceed the stage deadline and that is a known, named outcome rather than a
            // surprise: `close` flushes, and a flush against a broker that is not answering takes up
            // to `message.timeout.ms` — ten seconds — against a `releaseGroup` of three. A shutdown
            // that loses nothing and overruns is a timing defect, and it is meant to be reported as
            // one rather than rounded either way.
            sink?.let { destination -> consumer(participant("kafka sink") { destination.close() }) }

            // A READER, AND CANCELLATION IS NOT ENOUGH. The store check is `SELECT 1` on the
            // pool, so a probe in flight holds a read lock; `HealthRegistry.stop()` cancels without
            // joining, and the statement is inside an FFI call cancellation does not reach. Only
            // the join establishes that no reader is left by the time this stage ends.
            consumer(
                participant("health checks") {
                    probes.stop()
                    probesScope.coroutineContext.job.cancelAndJoin()
                },
            )

            // Before the pool and after the drain: the last checkpoint runs while the database is
            // still open, and it decides how much journal the *next* process has to work through
            // before it can serve — time that would otherwise be added to a cold start nobody
            // attributes to this one.
            // The last rejections of a deploy are the ones an operator is most likely to go looking
            // for, so they are flushed here rather than lost with the process.
            consumer(participant("retention sweep") { retentionSweep.stop() })
            consumer(participant("rejection counters") { rejectionFlush.stop() })

            // Last, because everything above it writes through this pool — and the last checkpoint
            // is *inside* this participant rather than beside the ones above it.
            //
            // PARTICIPANTS IN ONE STAGE RUN CONCURRENTLY. kore orders stages, not the participants
            // within a stage: `runStage` launches all of them and joins. So registering the
            // checkpoint after the flush ordered nothing, and `wal_checkpoint(TRUNCATE)` ran
            // alongside `RejectionFlush.stop()`'s final write and the probes' `SELECT 1` — a
            // truncating checkpoint cannot take its lock while either is in flight, so it waited,
            // and the wait cost more than the stage had.
            //
            // Measured, not reasoned: on a GitHub runner this took `make build` red with
            // `RELEASE_CONSUMERS DEADLINE_EXCEEDED in 3.000228711s` and a 2.006354202s pool close
            // behind it. On the build machine under `--cpus 0.5` it reproduced in 3 rounds of 30,
            // at 3.000s against 3–31 ms in the rounds that missed the collision — the shape of a
            // lock being waited on rather than of a slow machine.
            //
            // The order between the two below is the reason they are composed and not registered:
            // the checkpoint has to happen while the database is still open, and the database has
            // to close after it.
            pool(
                participant("sqlite") {
                    sweep.stop()
                    db.close().getOrThrow()
                    // AFTER the close, not before it: the truncating checkpoint has to wait for
                    // every other connection to this database, and the pool's own second connection
                    // — idle, holding nothing of ours — is enough to hold it off past the stage's
                    // whole deadline. `lastCheckpoint` opens one of its own once the pool is gone.
                    val state = lastCheckpoint(config.sqlitePath)
                    if (state.busy) {
                        println("wal: last checkpoint left ${state.framesInLog} frames, ${state.bytes} bytes")
                    }
                },
            )

            telemetry(
                participant("DI container") {
                    checksScope.cancel()
                    koin.close()
                    stopKoin()
                },
            )
        }
    }
}

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
private fun hostNowEpochSeconds(): Long = Clock.System.now().epochSeconds

/**
 * The exit code for a port that was already taken.
 *
 * Distinct from `1` on purpose: a supervisor that restarts on any non-zero code will restart this
 * one for ever against a port that is not coming back, and an operator reading `docker inspect`
 * deserves a code that means something more specific than "it failed".
 */
private const val BIND_FAILED: Int = 78

/**
 * The exit code for an environment this process cannot act on.
 *
 * The same `EX_CONFIG` as a port that is taken, and for the same reason: a supervisor that restarts
 * on any non-zero code would restart this one for ever against a value that is not going to change
 * by itself.
 */
private const val BAD_CONFIG: Int = 78

/**
 * Takes the address for a moment, to find out whether it can be taken at all.
 *
 * Prints one line and exits when it cannot. No stack: a stack is for a failure nobody predicted, and
 * a port already in use is the most predictable deployment mistake there is — the operator needs the
 * address and nothing else.
 */
private fun preflightBind(
    host: String,
    port: Int,
) {
    runBlocking {
        val selector = SelectorManager(Dispatchers.Default)
        try {
            aSocket(selector).tcp().bind(host, port).close()
        } catch (cancelled: CancellationException) {
            // Nothing can cancel this today — it runs on the main thread before any scope, signal
            // handler or job exists. It is rethrown anyway because that claim is about the code as
            // it is now, and the next person to add a scope above this line will not come back to
            // check.
            throw cancelled
        } catch (failure: Exception) {
            println("xyk: cannot bind $host:$port — ${failure.message ?: failure::class.simpleName}")
            println("xyk: nothing was started and nothing was served")
            selector.close()
            exitProcess(BIND_FAILED)
        }
        selector.close()
    }
}
