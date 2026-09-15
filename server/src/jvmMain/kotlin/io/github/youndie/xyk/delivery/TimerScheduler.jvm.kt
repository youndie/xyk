package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

/** The JVM build does not deliver, so it does not schedule either — see [deliveryWorkers]. */
actual fun timerScheduler(db: ISQLite): TimerScheduler? = null
