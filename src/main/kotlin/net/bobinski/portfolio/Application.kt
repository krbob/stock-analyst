package net.bobinski.portfolio

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureDependencies()
    configureSerialization()
    configureMonitoring()
    configureRouting()
}
