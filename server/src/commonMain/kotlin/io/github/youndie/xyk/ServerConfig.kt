package io.github.youndie.xyk

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
        port = readEnv("XYK_PORT")?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT,
        host = readEnv("XYK_HOST") ?: ServerConfig.DEFAULT_HOST,
        sqlitePath = sqlitePath,
        // An endpoint that verifies nothing is a public write endpoint on somebody's database, so
        // creating one takes a deliberate act at start-up as well as at the route (endpoint-admin).
        allowUnverified = readEnv("XYK_ALLOW_UNVERIFIED")?.toBooleanStrictOrNull() ?: false,
        walCheckpointSeconds =
            readEnv("XYK_WAL_CHECKPOINT_SECONDS")?.toLongOrNull()
                ?: ServerConfig.DEFAULT_WAL_CHECKPOINT_SECONDS,
        walMaxBytes =
            readEnv("XYK_WAL_MAX_BYTES")?.toLongOrNull() ?: ServerConfig.DEFAULT_WAL_MAX_BYTES,
        maxBodyBytes =
            readEnv("XYK_MAX_BODY_BYTES")?.toLongOrNull() ?: ServerConfig.DEFAULT_MAX_BODY_BYTES,
        stripeToleranceSeconds =
            (readEnv("XYK_STRIPE_TOLERANCE_SECONDS")?.toLongOrNull() ?: DEFAULT_STRIPE_TOLERANCE)
                .also { require(it > 0) { "XYK_STRIPE_TOLERANCE_SECONDS must be above zero: 0 disables the check" } },
        retentionDays =
            (readEnv("XYK_RETENTION_DAYS")?.toLongOrNull() ?: 0L)
                .also { require(it >= 0) { "XYK_RETENTION_DAYS cannot be negative" } },
        publicBaseUrl = (readEnv("XYK_PUBLIC_BASE_URL") ?: "http://localhost:8080").trimEnd('/'),
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
