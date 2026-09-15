package io.github.youndie.xyk.db

import io.github.youndie.chronik.sqlx4k.sqlite.chronikTimersSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * xyk's copy of chronik's timers DDL, held against the original.
 *
 * **Why there is a copy at all.** `chronik-sqlx4k-sqlite` publishes `jvm` and `linuxX64` and no
 * `macosArm64`. Calling `chronikTimersSchema()` from `commonMain` would make the migration list
 * itself compile only on Linux — and a migration list that is shorter on one host is a worse problem
 * than a duplicated string, because `user_version = 5` would then name two different schemas.
 *
 * **Why the copy is safe anyway.** This test. It runs in the JVM suite, where chronik's `jvm` variant
 * resolves on every host including the Mac, and it compares statement for statement. If upstream adds
 * a column or changes the index, the build goes red here, naming the line — which is the difference
 * between a copy and a copy with a guard.
 *
 * It deliberately does **not** compare a normalised or whitespace-stripped form: the string in
 * `Migrate.kt` is the one that reaches SQLite, so the thing worth asserting is that it is the same
 * string, not that it means the same thing.
 */
class ChronikSchemaParityTest {
    @Test
    fun `xyk's migration v5 is chronik's timers schema, statement for statement`() {
        assertEquals(
            chronikTimersSchema(),
            migrationV5,
            "xyk's copy of chronik's timers DDL has drifted from chronik's own. " +
                "Update migrationV5 in Migrate.kt to match, and check whether the change needs a " +
                "migration of its own rather than an edit to v5 — a step is appended, never edited.",
        )
    }
}
