package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

/**
 * The JVM build does not deliver, for the same reason it has no outbound engine: it exists for a
 * fast test cycle over common code and never ships, and a delivery path exercised only on a runtime
 * nobody deploys is the more expensive kind of green.
 */
actual fun deliveryWorkers(
    db: ISQLite,
    sink: DeliverySink,
    count: Int,
    pollIntervalSeconds: Long,
    leaseSeconds: Long,
    maxAttempts: Int,
    nowEpochSeconds: () -> Long,
): DeliveryWorkers? = null
