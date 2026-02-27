package net.bobinski.portfolio.core.dependency

import kotlinx.serialization.json.Json
import net.bobinski.portfolio.core.serialization.AppJsonFactory
import net.bobinski.portfolio.core.time.CurrentTimeProvider
import net.bobinski.portfolio.core.time.SystemCurrentTimeProvider
import org.koin.dsl.module

val CoreModule = module {
    single<Json> { AppJsonFactory.create() }
    single<CurrentTimeProvider> { SystemCurrentTimeProvider() }
}
