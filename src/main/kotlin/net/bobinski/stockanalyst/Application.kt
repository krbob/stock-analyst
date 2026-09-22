package net.bobinski.stockanalyst

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureDependencies()
    // Observe typed domain responses before ContentNegotiation serializes them.
    configureMonitoring()
    configureSerialization()
    configureErrorHandling()
    configureRouting()
}
