package io.github.youndie.xyk

import io.github.youndie.xyk.db.SQLITE_POOL

/**
 * Everything the process is told by its environment, read once and checked once.
 *
 * Kotlin/Native has no `System.getenv`, so the read itself is [readEnv], an `expect` with one actual
 * per build.
 */
data class ServerConfig(
    val port: Int,
    val host: String,
    val sqlitePath: String,
    val allowUnverified: Boolean,
    /** Seconds between forced journal checkpoints. `0` turns the sweep off — the control arm. */
    val walCheckpointSeconds: Long,
    /** The size at which the sweep stops waiting for the clock. */
    val walMaxBytes: Long,
    /** Hard ceiling offered to the GC, in bytes; 0 leaves the runtime unbounded. */
    val heapBytes: Long,
    /** Bodies above this are refused with `413`, and refused before they are read. */
    val maxBodyBytes: Long,
    /**
     * How long payloads are kept, in days. **`0` means for ever, and it is the default.**
     *
     * Not seven days, not thirty: deleting somebody's data on a schedule nobody chose is the one
     * mistake here that cannot be undone, so the horizon is unset until an owner sets it
     * ([B-19](../../../../../../docs/backlog/B-19-secret-handling.md)). What ships is the machinery,
     * not a policy.
     */
    val retentionDays: Long,
    val deliveryTimeoutMillis: Long,
    val deliveryMaxAttempts: Int,
    val deliveryWorkers: Int,
    val deliveryStallSeconds: Long,
    val sqlitePoolSize: Int,
    /**
     * Stripe's recency window, in seconds, for endpoints that do not carry their own.
     *
     * `0` disables the check rather than tightening it — the vendor's own wording — so the value is
     * refused below.
     */
    val stripeToleranceSeconds: Long,
    /**
     * What the registry prints as the hook URL when an endpoint is created.
     *
     * Configuration rather than a `Host` header: the address an operator should hand to GitHub is a
     * deployment fact, and building it out of a request header means whoever sends the request
     * decides what we tell people to POST to.
     */
    val publicBaseUrl: String,
    /**
     * Where the Kafka sink publishes, or `null` — **and `null` is the default and the shipping
     * shape**. A gateway whose job is to receive, store and deliver has no second destination; one
     * appears when somebody names a broker, and until then nothing in the binary so much as opens a
     * socket.
     */
    val kafkaBootstrapServers: String?,
    /** The topic accepted events are published to, when there is a broker to publish to. */
    val kafkaTopic: String,
    /**
     * The one endpoint this service knows until the registry exists (B-07).
     *
     * `null` when none is configured, and that is a legitimate state: the service starts, serves its
     * probes and refuses every hook with `404`. The alternative — inventing an endpoint with a
     * generated secret nobody was told — would be a public write endpoint whose key is in a log line.
     */
    val bootstrapEndpoint: BootstrapEndpoint?,
) {
    companion object {
        const val DEFAULT_PORT: Int = 8080
        const val DEFAULT_HOST: String = "0.0.0.0"

        /**
         * Often enough that the journal never becomes the thing making reads slow, rare enough that
         * the pause it costs is invisible. It is a starting point, not a measurement: what decides
         * it is the soak in B-24, and the number here moves when that runs.
         */
        const val DEFAULT_WAL_CHECKPOINT_SECONDS: Long = 60

        /**
         * **Off by default, and that is the honest state rather than a chosen one.** What a heap
         * ceiling should be depends on how much of this process is *not* heap — the allocator's
         * per-thread pages, the Rust half of sqlx4k, glibc's arenas — and on this platform that
         * remainder is usually the larger term. No run has measured it here yet; B-30 is where it
         * gets a number. Until then a service that set one by guess would trade a kernel kill for
         * an `OutOfMemory` and call it an improvement.
         */
        const val DEFAULT_HEAP_BYTES: Long = 0

        /**
         * Where accepted events go when a broker is configured and nothing else is said.
         *
         * A dotted name rather than a bare one: a topic called `events` on somebody's shared cluster
         * is a collision waiting to happen, and the prefix says whose events these are.
         */
        const val DEFAULT_KAFKA_TOPIC: String = "xyk.events"

        /** Seven days of payloads, the owner's answer of 2026-09-16. `0` means never purge. */
        const val DEFAULT_RETENTION_DAYS: Long = 7

        /** Two seconds: long enough for a slow subscriber, short enough that fifty of them fit in a tick. */
        const val DEFAULT_DELIVERY_TIMEOUT_MS: Long = 2_000

        /** Five attempts, which with a 1 s base and a 300 s cap spans about five minutes. */
        const val DEFAULT_DELIVERY_MAX_ATTEMPTS: Int = 5

        /**
         * Four workers, measured rather than guessed
         * ([delivery-workers.md](../../../../../../../../docs/research/measurements-2026-09-16/delivery-workers.md)).
         *
         * Against a subscriber answering in 100 ms, delivery throughput is **6.3, 12.8, 27.2 and
         * 46.7 per second at 1, 2, 4 and 8 workers** — near-linear, with resident memory flat at
         * 29–33 MB throughout. The hypothesis this replaces said the curl engine's single-threaded
         * dispatcher would cap it at two; it does not, because a single-threaded event loop
         * multiplexes rather than serialising.
         *
         * Four rather than eight because the curve was still linear at eight: the last point
         * measured is a poor place to sit, and nothing here bounds what a subscriber will tolerate —
         * that number belongs to them. `XYK_DELIVERY_WORKERS` moves it.
         */
        const val DEFAULT_DELIVERY_WORKERS: Int = 4

        /** Two minutes: past the worst case of a full batch at the default timeout, with room. */
        const val DEFAULT_DELIVERY_STALL_SECONDS: Long = 120

        /** 32 MiB. Same status: a starting point that B-24 replaces with a measured one. */
        const val DEFAULT_WAL_MAX_BYTES: Long = 32L * 1024 * 1024

        /**
         * 1 MiB. GitHub's own limit is 25 MB and Stripe's payloads are kilobytes; a megabyte takes
         * everything either of them sends and refuses the thing this limit exists for, which is a
         * body sized to fill memory rather than to be read.
         */
        const val DEFAULT_MAX_BODY_BYTES: Long = 1024L * 1024

        /**
         * Readiness fails above this. It is deliberately **four times** the sweep's trigger: the
         * sweep acting is normal, and a probe that failed at the same number would take the service
         * out of the load balancer every time the journal did its job.
         */
        const val WAL_CEILING_MULTIPLE: Long = 4
    }

    /** The size at which the journal stops being a working file and becomes an incident. */
    val walCeilingBytes: Long get() = walMaxBytes * WAL_CEILING_MULTIPLE
}

/**
 * The hard-coded endpoint of B-06, replaced by rows in B-07.
 *
 * It is configuration rather than a migration so that a redeploy can change the secret, and so that
 * the secret is never in a file this repository tracks.
 */
class BootstrapEndpoint(
    val id: String,
    val scheme: String,
    val secret: String,
    val subscriberUrls: List<String>,
    /** Raw JSON, exactly as `endpoints.scheme_config` stores it. Null for the schemes that need none. */
    val schemeConfig: String? = null,
)

/**
 * Reads one environment variable, or null.
 *
 * `expect` rather than a helper because the two platforms have nothing in common here: the JVM has
 * `System.getenv`, and Kotlin/Native has `getenv` out of posix.
 */
expect fun readEnv(name: String): String?

/**
 * Builds the configuration, and **dies on a required value that is missing or empty**.
 *
 * Failing on purpose is the feature. A webhook gateway that started without a database path would
 * answer `200` to a sender and write the events into a file in whatever directory it happened to be
 * launched from — indistinguishable from healthy until somebody looks for an event that is not
 * there.
 */
fun getServerConfig(): ServerConfig {
    val sqlitePath = readEnv("XYK_DB_PATH").orEmpty()
    require(sqlitePath.isNotBlank()) { "XYK_DB_PATH is required" }

    return ServerConfig(
        port = envInt("XYK_PORT", ServerConfig.DEFAULT_PORT),
        host = readEnv("XYK_HOST") ?: ServerConfig.DEFAULT_HOST,
        sqlitePath = sqlitePath,
        // An endpoint that verifies nothing is a public write endpoint on somebody's database, so
        // creating one takes a deliberate act at start-up as well as at the route (endpoint-admin).
        allowUnverified = readEnv("XYK_ALLOW_UNVERIFIED")?.toBooleanStrictOrNull() ?: false,
        walCheckpointSeconds =
            envLong("XYK_WAL_CHECKPOINT_SECONDS", ServerConfig.DEFAULT_WAL_CHECKPOINT_SECONDS),
        heapBytes = envLong("XYK_HEAP_BYTES", ServerConfig.DEFAULT_HEAP_BYTES),
        walMaxBytes =
            envLong("XYK_WAL_MAX_BYTES", ServerConfig.DEFAULT_WAL_MAX_BYTES),
        maxBodyBytes =
            envLong("XYK_MAX_BODY_BYTES", ServerConfig.DEFAULT_MAX_BODY_BYTES),
        stripeToleranceSeconds =
            (envLong("XYK_STRIPE_TOLERANCE_SECONDS", DEFAULT_STRIPE_TOLERANCE))
                .also { require(it > 0) { "XYK_STRIPE_TOLERANCE_SECONDS must be above zero: 0 disables the check" } },
        // SEVEN DAYS, and it is a default rather than a setting somebody must choose. xyk stores raw
        // bodies by design, so every day nobody thinks about retention is a day the liability grows,
        // and a service whose safe configuration requires an act of configuration is usually unsafe.
        // A week is long enough that the journal answers the question it exists for — what did that
        // sender send, and did it arrive — across a working week, and short enough that a copy of
        // somebody's customer data does not accumulate for ever in a volume nobody audits.
        //
        // `0` still means *never purge* and is still expressible. It was the default until
        // 2026-09-16; a deployment upgrading across that line and relying on it begins purging
        // bodies older than a week, which is why the change is in the research document as a
        // decision (§3) and not only in a diff.
        retentionDays =
            (envLong("XYK_RETENTION_DAYS", ServerConfig.DEFAULT_RETENTION_DAYS))
                .also { require(it >= 0) { "XYK_RETENTION_DAYS cannot be negative" } },
        // THE TIMEOUT IS NOT A TUNING KNOB, it is what bounds a tick. chronik's `tick()` walks its
        // batch sequentially, so the worst case for one tick is `batchSize × this`, and a
        // subscriber that accepts a connection and never answers costs exactly this much of every
        // other delivery's latency rather than all of it. Zero would mean unbounded, which is the
        // one value that must not be expressible.
        deliveryTimeoutMillis =
            (envLong("XYK_DELIVERY_TIMEOUT_MS", ServerConfig.DEFAULT_DELIVERY_TIMEOUT_MS))
                .also { require(it > 0) { "XYK_DELIVERY_TIMEOUT_MS must be positive" } },
        // The same number chronik is configured with. It is read here as well because the journal's
        // state depends on it — a delivery out of attempts must read `dead` rather than `pending` —
        // and two places reading one variable is better than this half inventing its own policy.
        deliveryMaxAttempts =
            (envInt("XYK_DELIVERY_MAX_ATTEMPTS", ServerConfig.DEFAULT_DELIVERY_MAX_ATTEMPTS))
                .also { require(it > 0) { "XYK_DELIVERY_MAX_ATTEMPTS must be positive" } },
        // ZERO IS LEGAL and it means "do not deliver from this process" — a deployment that only
        // receives and journals is a real one. It is not the default, because a service that
        // silently delivers nothing is the failure this whole half exists to avoid; opting out has
        // to be an act.
        deliveryWorkers =
            (envInt("XYK_DELIVERY_WORKERS", ServerConfig.DEFAULT_DELIVERY_WORKERS))
                .also { require(it >= 0) { "XYK_DELIVERY_WORKERS cannot be negative" } },
        // Configurable because the right value is arithmetic on the deployment's own numbers:
        // `batchSize × deliveryTimeout` plus a margin, and a batch of fifty at two seconds is a
        // hundred. It is also what makes the probe checkable inside a container in seconds rather
        // than in minutes — a guard nobody can watch fail is a guard nobody has watched.
        // THE DEFAULT IS STILL TWO, and the reason is unchanged: every connection is one more
        // reader, and SQLite's automatic checkpoint never truncates the journal while a reader is
        // alive (B-04). The override exists because the pool is one of two named suspects for the
        // journal page's collapse (B-25), and a suspect that cannot be varied cannot be measured.
        // Raising it in a deployment trades journal truncation for read concurrency — a decision
        // with a measurement behind it or not at all.
        sqlitePoolSize =
            (envInt("XYK_SQLITE_POOL", SQLITE_POOL))
                .also { require(it >= 1) { "XYK_SQLITE_POOL must be at least 1" } },
        deliveryStallSeconds =
            (envLong("XYK_DELIVERY_STALL_SECONDS", ServerConfig.DEFAULT_DELIVERY_STALL_SECONDS))
                .also { require(it > 0) { "XYK_DELIVERY_STALL_SECONDS must be positive" } },
        publicBaseUrl = (readEnv("XYK_PUBLIC_BASE_URL") ?: "http://localhost:8080").trimEnd('/'),
        // Kafka's own name for the key, kept verbatim inside the variable: an operator who has a
        // broker address has it because Kafka's documentation called it `bootstrap.servers`, and a
        // gateway that renamed it would make them search for a word only this service uses.
        kafkaBootstrapServers = readEnv("XYK_KAFKA_BOOTSTRAP_SERVERS")?.takeIf { it.isNotBlank() },
        kafkaTopic =
            (readEnv("XYK_KAFKA_TOPIC") ?: ServerConfig.DEFAULT_KAFKA_TOPIC)
                .also { require(it.isNotBlank()) { "XYK_KAFKA_TOPIC cannot be blank" } },
        bootstrapEndpoint = readBootstrapEndpoint(),
    )
}

/**
 * Reads the bootstrap endpoint, and **refuses half of one**.
 *
 * An id without a secret would be an endpoint that verifies nothing; a secret without an id is a
 * secret in the environment of a process that will never use it. Both are configuration mistakes
 * that look like nothing at run time, so the process dies naming which half is missing.
 */
private const val DEFAULT_STRIPE_TOLERANCE: Long = 300

private fun readBootstrapEndpoint(): BootstrapEndpoint? {
    val id = readEnv("XYK_BOOTSTRAP_ENDPOINT_ID")?.takeIf { it.isNotBlank() }
    val secret = readEnv("XYK_BOOTSTRAP_SECRET")?.takeIf { it.isNotBlank() }
    if (id == null && secret == null) return null
    require(id != null) { "XYK_BOOTSTRAP_SECRET is set without XYK_BOOTSTRAP_ENDPOINT_ID" }
    require(secret != null) { "XYK_BOOTSTRAP_ENDPOINT_ID is set without XYK_BOOTSTRAP_SECRET" }

    return BootstrapEndpoint(
        id = id,
        scheme = readEnv("XYK_BOOTSTRAP_SCHEME") ?: "github",
        secret = secret,
        subscriberUrls =
            readEnv("XYK_BOOTSTRAP_SUBSCRIBERS")
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        schemeConfig = readEnv("XYK_BOOTSTRAP_SCHEME_CONFIG")?.takeIf { it.isNotBlank() },
    )
}

/**
 * A number from the environment, where **present-but-unreadable is a failure rather than absent**.
 *
 * `readEnv(name)?.toLongOrNull() ?: default` reads `XYK_MAX_BODY_BYTES=banana` as "not configured"
 * and starts happily on the default. That is the silent half of a misconfiguration: the operator set
 * something, the service ignored it, and nothing anywhere says so. It was found by rendering the
 * Helm chart and reading the environment it actually produces — Helm turns a bare `10485760` into
 * `1.048576e+07`, which this function now refuses instead of quietly replacing.
 */
private fun envLong(
    name: String,
    default: Long,
): Long {
    val raw = readEnv(name) ?: return default
    return raw.toLongOrNull()
        ?: throw IllegalArgumentException("$name is set to '$raw', which is not a number")
}

private fun envInt(
    name: String,
    default: Int,
): Int {
    val raw = readEnv(name) ?: return default
    return raw.toIntOrNull()
        ?: throw IllegalArgumentException("$name is set to '$raw', which is not a number")
}
