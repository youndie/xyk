package io.github.youndie.xyk.db

// `asLong` is an extension on the column, not a member of it — the import is what makes
// `PRAGMA user_version` readable at all.
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

/**
 * The schema, as a list of steps.
 *
 * There is no migration framework and there is not going to be one: a list of statements plus
 * `PRAGMA user_version` is the whole mechanism, and it runs inside a transaction before the engine
 * starts. A server that opened its port ahead of a ready schema would answer the first requests with
 * errors — and for this service those requests are webhooks nobody sends twice.
 *
 * A step is appended, never edited: the index in this list *is* the version number.
 */
private val migrationV1: List<String> =
    listOf(
        // An endpoint is the thing a sender posts to. The id is opaque and unguessable; it is not a
        // secret (the signature is), but it is not enumerable either.
        """
        CREATE TABLE endpoints (
            id TEXT PRIMARY KEY,
            scheme TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            description TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL
        );
        """.trimIndent(),
        // Secrets are a table rather than a column because rotation keeps both alive for a window:
        // Stripe signs with every active secret for up to 24 hours while one is being rolled, and an
        // endpoint that dropped the old one on rotation would reject genuine traffic for a day.
        """
        CREATE TABLE endpoint_secrets (
            id TEXT PRIMARY KEY,
            endpoint_id TEXT NOT NULL,
            secret TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            retires_at INTEGER
        );
        """.trimIndent(),
        "CREATE INDEX endpoint_secrets_endpoint ON endpoint_secrets(endpoint_id);",
        """
        CREATE TABLE subscribers (
            id TEXT PRIMARY KEY,
            endpoint_id TEXT NOT NULL,
            url TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL
        );
        """.trimIndent(),
        "CREATE INDEX subscribers_endpoint ON subscribers(endpoint_id);",
        // `body` is a real BLOB and `body_bytes` is its length, stored rather than computed: the
        // journal lists sizes without reading bodies, and reading a body to learn its size is the
        // one query that must never appear on a list page.
        """
        CREATE TABLE events (
            id TEXT PRIMARY KEY,
            endpoint_id TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            scheme TEXT NOT NULL,
            secret_fingerprint TEXT,
            content_type TEXT,
            body BLOB NOT NULL,
            body_bytes INTEGER NOT NULL
        );
        """.trimIndent(),
        // Ordered the way the journal reads it, newest first, so the list page is an index scan.
        "CREATE INDEX events_endpoint_received ON events(endpoint_id, received_at DESC, id DESC);",
        // One row per subscriber per event, written in the SAME transaction as the event. This is
        // the product: an event stored without its deliveries is an event nobody is waiting for.
        """
        CREATE TABLE deliveries (
            id TEXT PRIMARY KEY,
            event_id TEXT NOT NULL,
            subscriber_id TEXT NOT NULL,
            state TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        );
        """.trimIndent(),
        "CREATE INDEX deliveries_event ON deliveries(event_id);",
        "CREATE INDEX deliveries_state ON deliveries(state, created_at);",
    )

/**
 * The generic HMAC scheme needs a header, a prefix and an encoding, and those belong to the endpoint
 * rather than to the install. Nullable and JSON: three of the five schemes configure nothing, and a
 * column per field would be four columns that are null on almost every row.
 */
private val migrationV2: List<String> =
    listOf(
        "ALTER TABLE endpoints ADD COLUMN scheme_config TEXT;",
    )

/**
 * What was turned away, as counts rather than rows.
 *
 * Storing a rejected request would make the endpoint a free write endpoint after all — which is what
 * the rejection was for. What is kept is how many and why, per endpoint, so that "nothing arrived"
 * and "everything was refused" stop looking identical from the outside.
 */
private val migrationV3: List<String> =
    listOf(
        """
        CREATE TABLE rejections (
            endpoint_id TEXT NOT NULL,
            reason TEXT NOT NULL,
            count INTEGER NOT NULL,
            last_at INTEGER NOT NULL,
            PRIMARY KEY (endpoint_id, reason)
        );
        """.trimIndent(),
    )

/**
 * Retention: when the bytes went, not whether the event did.
 *
 * `body_bytes` deliberately keeps the size the payload **arrived** with — the journal's job is to say
 * what happened, and "47 bytes, purged on the 22nd" is the answer to that question while "0 bytes"
 * is not.
 */
private val migrationV4: List<String> =
    listOf(
        "ALTER TABLE events ADD COLUMN purged_at INTEGER;",
        // Every purge scans by age, and the sweep runs while ingest writes.
        "CREATE INDEX events_received_at ON events(received_at);",
    )

/**
 * chronik's timers table — **its SQL, copied here on purpose, and guarded by a test**.
 *
 * chronik ships no DDL and executes none: `chronikTimersSchema()` hands back these two statements as
 * text for the application's own migration list, so that the table's lifecycle, its version number
 * and its ordering stay with the rest of the schema ([research §1.1](../../../../../../../../docs/research/research-architecture.md)).
 *
 * **Why the text is duplicated rather than called.** `chronik-sqlx4k-sqlite` publishes `jvm` and
 * `linuxX64` and nothing else. Calling `chronikTimersSchema()` from here would make this file — and
 * therefore the whole migration list — compile only on Linux, and a migration list that is shorter
 * on one host is worse than a duplicated string: `user_version = 5` would then mean two different
 * schemas depending on where the binary was built.
 *
 * The duplication is held to its source by `ChronikSchemaParityTest`, which runs where chronik
 * resolves and fails the build if upstream changes a line. A copy with a test against the original
 * is a different thing from a copy.
 */
internal val migrationV5: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS chronik_timers (
            id TEXT PRIMARY KEY,
            due_at INTEGER NOT NULL,
            payload TEXT NOT NULL,
            state TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            locked_until INTEGER,
            locked_by TEXT
        );
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_chronik_timers_state_due_at ON chronik_timers (state, due_at);",
    )

/**
 * One row per delivery attempt — the history the `attempts` counter on `deliveries` cannot hold.
 *
 * The counter answers "how many times"; an operator at two in the morning is asking "what did it
 * say", and that needs the status, the duration and the first bytes of what came back. The response
 * is stored as a bounded prefix rather than whole: it is a string written by somebody else, arriving
 * on every failed attempt, and an unbounded one would make a misbehaving subscriber a way to fill
 * this disk.
 */
private val migrationV6: List<String> =
    listOf(
        """
        CREATE TABLE delivery_attempts (
            id TEXT PRIMARY KEY,
            delivery_id TEXT NOT NULL,
            attempt INTEGER NOT NULL,
            status INTEGER,
            duration_ms INTEGER NOT NULL,
            detail TEXT NOT NULL DEFAULT '',
            at INTEGER NOT NULL
        );
        """.trimIndent(),
        // Every read of this table is "the attempts of one delivery, in order", which is the one
        // query the journal's detail page makes.
        "CREATE INDEX delivery_attempts_delivery ON delivery_attempts(delivery_id, attempt);",
    )

private val allMigrations: List<List<String>> =
    listOf(migrationV1, migrationV2, migrationV3, migrationV4, migrationV5, migrationV6)

/**
 * Brings the database up to [allMigrations]`.size`.
 *
 * **It is not called `migrate`, and that is not style.** `ISQLite` already has a member called
 * `migrate()` — sqlx4k's own, which runs `.sql` files from a directory — and a member always wins
 * over an extension. Named `migrate`, this function compiles, is never called, and the service
 * starts on an empty schema: no error, no table, `user_version` still 0. It cost the better part of
 * an hour here; tracy's is called `migrateDb` and now that looks deliberate rather than arbitrary.
 *
 * `PRAGMA user_version` is **read**, not written with a zero: `PRAGMA user_version = 0` is a write,
 * and asking that way resets the version on every start — which then runs every migration again on
 * every start, failing every statement, silently, if the results are discarded. Every statement
 * here is checked with `getOrThrow()` for the same reason: a migration that fails must stop the
 * start rather than be stepped over, because a server on a half-migrated schema breaks later and
 * somewhere else.
 */
suspend fun ISQLite.migrateSchema() {
    // WAL first, and outside the transaction. It is the one pragma a single call settles: SQLite
    // writes it into the database header, so every connection that opens the file inherits it.
    execute("PRAGMA journal_mode = WAL;").getOrThrow()

    transaction {
        val currentVersion =
            fetchAll("PRAGMA user_version;")
                .getOrThrow()
                .rows
                .firstOrNull()
                ?.get(0)
                ?.asLong()
                ?.toInt() ?: 0

        val targetVersion = allMigrations.size
        if (currentVersion >= targetVersion) return@transaction

        for (version in (currentVersion + 1)..targetVersion) {
            allMigrations[version - 1].forEach { sql -> execute(sql).getOrThrow() }
            execute("PRAGMA user_version = $version;").getOrThrow()
            println("xyk: migrated to schema version $version")
        }
    }
}
