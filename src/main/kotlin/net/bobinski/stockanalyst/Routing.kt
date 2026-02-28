package net.bobinski.stockanalyst

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import net.bobinski.stockanalyst.route.analysisRoute

fun Application.configureRouting() {
    routing {
        analysisRoute()
    }
}
