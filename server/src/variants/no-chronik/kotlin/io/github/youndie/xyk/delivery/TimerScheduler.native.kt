package io.github.youndie.xyk.delivery

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

/** Built without chronik: there is nothing to schedule with, and `main` says so at start-up. */
actual fun timerScheduler(db: ISQLite): TimerScheduler? = null
