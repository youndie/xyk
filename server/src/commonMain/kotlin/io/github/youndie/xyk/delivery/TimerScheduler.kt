package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

/**
 * Writes the timer that will make a delivery happen — **inside the caller's transaction**.
 *
 * That parameter is the whole reason chronik is in this design. A delivery row committed without its
 * timer is a webhook that was answered `200` and will never be sent, and it is invisible from every
 * angle: the journal shows it pending for ever, no probe fails, nothing is logged. chronik's store
 * is a `TransactionalTimerStore` precisely so the two commit together or neither does
 * ([research §1.2](../../../../../../../../docs/research/research-architecture.md)).
 *
 * The transaction is sqlx4k's rather than an abstraction of our own: it is the type the ingest
 * repository already has in hand, and wrapping it would only move the question of whether this is
 * *the same* transaction one layer further from where it can be answered.
 */
fun interface TimerScheduler {
    /**
     * Schedule [deliveryId] to be attempted at [dueAtEpochSeconds].
     *
     * The payload is the delivery id and nothing else — [DeliveryWorkers] reads it that way, and
     * anything richer would make the timers table a second copy of `deliveries` that drifts from the
     * first the moment either changes.
     */
    suspend fun schedule(
        transaction: Transaction,
        deliveryId: String,
        dueAtEpochSeconds: Long,
    )
}

/**
 * The scheduler this build can use, or `null` when it was built without chronik.
 *
 * `null` is not a quiet degradation: a process that accepts webhooks and schedules nothing is the
 * failure this half exists to prevent, so `main` refuses to pretend — it says the delivery half is
 * off, and the ingest path then writes no delivery rows either rather than writing rows nobody will
 * ever claim.
 */
expect fun timerScheduler(db: ISQLite): TimerScheduler?
