package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

/**
 * Built without chronik — which is every host where chronik publishes no variant, and today that
 * means the Mac.
 *
 * The ingest half, the journal and the registry are unaffected: this is the whole point of the
 * delivery half being behind one `expect` rather than spread through `commonMain`.
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
