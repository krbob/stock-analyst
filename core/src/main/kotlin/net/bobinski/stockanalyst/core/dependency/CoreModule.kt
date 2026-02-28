package net.bobinski.stockanalyst.core.dependency

import kotlinx.serialization.json.Json
import net.bobinski.stockanalyst.core.serialization.AppJsonFactory
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.core.time.SystemCurrentTimeProvider
import org.koin.dsl.module

val CoreModule = module {
    single<Json> { AppJsonFactory.create() }
    single<CurrentTimeProvider> { SystemCurrentTimeProvider() }
}
