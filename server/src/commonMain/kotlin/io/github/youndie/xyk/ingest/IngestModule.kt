package io.github.youndie.xyk.ingest

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.delivery.TimerScheduler
import io.github.youndie.xyk.ingest.data.Sqlx4kEventRepository
import io.github.youndie.xyk.ingest.domain.AcceptEventUseCase
import io.github.youndie.xyk.ingest.domain.EventRepository
import io.github.youndie.xyk.sink.EventSink
import io.github.youndie.xyk.verify.GenericHmacVerifier
import io.github.youndie.xyk.verify.GithubVerifier
import io.github.youndie.xyk.verify.NoVerificationVerifier
import io.github.youndie.xyk.verify.StripeVerifier
import io.github.youndie.xyk.verify.TelegramVerifier
import io.github.youndie.xyk.verify.Verifier
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The feature's wiring. It names no driver — the storage binding below is the only line that does,
 * and it is here rather than in a storage module because there is one driver and one build that
 * ships. When a second appears, this binding moves and nothing else in the feature changes.
 *
 * **All five schemes (B-09).** The map is keyed by what `endpoints.scheme` holds, so a sixth is a
 * line here and a class next door — and the registry reads its accepted set from this same list, so
 * nothing has to be told twice.
 *
 * `none` is in the list unconditionally: it is not a back door, because creating an endpoint that
 * uses it takes `XYK_ALLOW_UNVERIFIED` at start-up **and** an explicit scheme at creation. Leaving
 * it out of the list when the flag is off would instead make an already-created `none` endpoint
 * answer `404`, which is a worse way to say "not allowed".
 */
fun ingestModule(
    db: ISQLite,
    stripeToleranceSeconds: Long,
    scheduler: TimerScheduler?,
    /** `null` in every deployment that has not named a broker, which is the default one. */
    sink: EventSink? = null,
    onPublishFailure: (String, Throwable) -> Unit = { _, _ -> },
): Module =
    module {
        single<EventRepository> { Sqlx4kEventRepository(db, scheduler) }
        single { RejectionCounters() }
        single<List<Verifier>> {
            listOf(
                GithubVerifier(),
                StripeVerifier(stripeToleranceSeconds),
                TelegramVerifier(),
                GenericHmacVerifier(),
                NoVerificationVerifier(),
            )
        }
        single {
            AcceptEventUseCase(
                repository = get(),
                verifiers = get<List<Verifier>>().associateBy { it.scheme },
                sink = sink,
                onPublishFailure = { accepted, failure -> onPublishFailure(accepted.id, failure) },
            )
        }
    }
