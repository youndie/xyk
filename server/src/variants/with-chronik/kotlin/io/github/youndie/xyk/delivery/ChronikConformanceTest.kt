package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.chronik.TimerTransaction
import io.github.youndie.chronik.TransactionalTimerStore
import io.github.youndie.chronik.conformance.ConformanceKit
import io.github.youndie.chronik.conformance.TimerStoreSubject
import io.github.youndie.chronik.sqlx4k.sqlite.SqliteTimerStore
import io.github.youndie.chronik.sqlx4k.sqlite.asTimerTransaction
import io.github.youndie.xyk.db.migrateSchema
import io.github.youndie.xyk.db.openDatabase
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * chronik's corpus, run against **the driver that ships**.
 *
 * `SqliteTimerStore` comes from chronik and is presumably green against this corpus upstream. That
 * is not the claim being checked here. sqlx4k is two drivers — a Rust one on Kotlin/Native and
 * Xerial on the JVM ([research §1.11](../../../../../../../../../docs/research/research-architecture.md))
 * — and what ships in the image is the Rust one. So this test lives in the **native** suite: a corpus
 * green on Xerial says nothing about the binary in the container.
 *
 * It also checks what is genuinely ours rather than chronik's: that the table created by xyk's own
 * migration v5 is the table chronik's queries expect, that a pool of two is enough for a corpus that
 * runs a transaction inside a transaction's lifetime, and that the claim query behaves under this
 * driver's locking.
 *
 * **The kit collects findings and returns them rather than throwing**, so one run reports everything
 * that is wrong instead of the first thing — which is why the assertion below prints the whole list.
 */
class ChronikConformanceTest {
    private class Sqlx4kSubject(
        private val db: ISQLite,
    ) : TimerStoreSubject {
        override val store: TransactionalTimerStore = SqliteTimerStore(db)

        override suspend fun committed(body: suspend (TimerTransaction) -> Unit) {
            val transaction = db.begin().getOrThrow()
            body(transaction.asTimerTransaction())
            transaction.commit().getOrThrow()
        }

        override suspend fun rolledBack(body: suspend (TimerTransaction) -> Unit) {
            val transaction = db.begin().getOrThrow()
            body(transaction.asTimerTransaction())
            // A rollback, a crash and a dropped connection are one thing from storage's side; this
            // is the only one of the three a test can ask for.
            transaction.rollback().getOrThrow()
        }

        override suspend fun reset() {
            db.execute("DELETE FROM chronik_timers;").getOrThrow()
        }
    }

    @Test
    fun `the sqlx4k SQLite store satisfies chronik's corpus on the native driver`() =
        runTest {
            // A file rather than `:memory:`, and in a fresh directory per run: the pool opens more
            // than one connection, and two connections to `:memory:` are two different databases.
            val path = "/tmp/xyk-conformance-${Random.nextULong()}/timers.db"
            val db = openDatabase(path)
            db.migrateSchema()

            val kit = ConformanceKit()
            val findings = kit.run(Sqlx4kSubject(db))

            // THE CORPUS MUST HAVE RUN. An empty findings list from a kit that executed no case is
            // the same green as one from a store that passed every case, and this repository has
            // already paid once for a check that could not find its subject.
            assertTrue(kit.cases.isNotEmpty(), "the conformance corpus is empty — nothing was run")

            assertTrue(
                findings.isEmpty(),
                "chronik's corpus found ${findings.size} violation(s) in ${kit.cases.size} cases:\n" +
                    findings.joinToString("\n") { "  - ${it.rule}: ${it.detail}" },
            )
        }
}
