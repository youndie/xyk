package io.github.youndie.xyk

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The container, wired at the first dependency rather than at the third.
 *
 * There is almost nothing in it today, which is exactly when to introduce it: the moment when "now
 * it is time for DI" arrives never does, because each next repository is cheaper to append to a
 * parameter list than to introduce a container for — and six milestones later the application
 * function is threading five of them through by hand.
 *
 * The storage module is deliberately separate from the feature modules that will join it: it is the
 * only place a driver is named, so a feature module never mentions sqlx4k.
 */
fun storageModule(db: ISQLite): Module =
    module {
        single { db }
    }

fun configModule(config: ServerConfig): Module =
    module {
        single { config }
    }
