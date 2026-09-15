package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.chronik.EpochSeconds
import io.github.youndie.chronik.Timer
import io.github.youndie.chronik.sqlx4k.sqlite.SqliteTimerStore
import io.github.youndie.chronik.sqlx4k.sqlite.asTimerTransaction

/**
 * chronik's store, writing the timer through the caller's own sqlx4k transaction.
 *
 * `asTimerTransaction()` is the handle chronik asks for, and the reason it exists rather than
 * chronik reading the ambient transaction out of the `CoroutineContext` is the failure that would
 * otherwise be invisible: with ambient state, "am I inside the caller's transaction?" is answered by
 * code that compiles identically either way, and when the answer is no, the business row commits,
 * the timer does not, everything reports success, and the delivery simply never happens.
 *
 * `store.insert` is called rather than `Chronik.schedule` because the only thing `schedule` adds is
 * a clock this layer does not need: the due time is decided by the caller, which is the ingest path
 * that already knows when the event arrived.
 */
actual fun timerScheduler(db: ISQLite): TimerScheduler? {
    val store = SqliteTimerStore(db)
    return TimerScheduler { transaction, deliveryId, dueAtEpochSeconds ->
        store.insert(
            transaction.asTimerTransaction(),
            // The timer's id is the delivery's. They are one thing in two tables, and giving the
            // timer an id of its own would mean a join to answer "is this delivery scheduled?" —
            // a question the journal asks on every page.
            Timer(id = deliveryId, dueAt = EpochSeconds(dueAtEpochSeconds), payload = deliveryId),
        )
    }
}
