package net.bobinski.portfolio

import io.ktor.server.application.Application
import io.ktor.server.application.install
import net.bobinski.portfolio.core.dependency.CoreModule
import net.bobinski.portfolio.domain.dependency.DomainModule
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureDependencies() {
    install(Koin) {
        slf4jLogger()
        modules(CoreModule, DomainModule, BackendProviderModule)
    }
}
