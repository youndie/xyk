package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.Transaction

/**
 * A scheduler for tests: records what it was asked to schedule, and can be told to fail.
 *
 * It writes nothing. The timers table belongs to chronik's store, which has no `macosArm64` variant
 * and therefore cannot be reached from `commonTest` at all — so what this fake can prove is the
 * pairing (one schedule per delivery row, with that row's id) and, through [failWith], the thing
 * that matters more: that the two are in one transaction and a timer that cannot be written takes
 * the delivery row down with it.
 */
class TestTimerScheduler(
    private val failWith: Throwable? = null,
) : TimerScheduler {
    val scheduled = mutableListOf<Pair<String, Long>>()

    override suspend fun schedule(
        transaction: Transaction,
        deliveryId: String,
        dueAtEpochSeconds: Long,
    ) {
        failWith?.let { throw it }
        scheduled += deliveryId to dueAtEpochSeconds
    }
}
