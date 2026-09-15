package io.github.youndie.xyk.registry

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.xyk.registry.data.Sqlx4kRegistryRepository
import io.github.youndie.xyk.registry.domain.CreateEndpointUseCase
import io.github.youndie.xyk.registry.domain.RegistryRepository
import io.github.youndie.xyk.registry.domain.RotateSecretUseCase
import io.github.youndie.xyk.verify.Verifier
import org.koin.core.module.Module
import org.koin.dsl.module

fun registryModule(
    db: ISQLite,
    allowUnverified: Boolean,
): Module =
    module {
        single<RegistryRepository> { Sqlx4kRegistryRepository(db) }
        single {
            CreateEndpointUseCase(
                repository = get(),
                // Resolved at call time, not captured: the set of implemented schemes grows with
                // B-09, and a validator holding a copy from start-up would refuse a scheme the
                // ingest path had learned to verify.
                implementedSchemes = { get<List<Verifier>>().map { it.scheme }.toSet() },
                allowUnverified = allowUnverified,
            )
        }
        single { RotateSecretUseCase(get()) }
    }
