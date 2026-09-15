package io.github.youndie.xyk.journal

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.journal.data.Sqlx4kJournalRepository
import io.github.youndie.xyk.journal.domain.JournalRepository
import org.koin.core.module.Module
import org.koin.dsl.module

fun journalModule(db: ISQLite): Module =
    module {
        single<JournalRepository> { Sqlx4kJournalRepository(db) }
    }
