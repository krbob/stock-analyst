package net.bobinski.portfolio

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import net.bobinski.portfolio.route.analysisRoute

fun Application.configureRouting() {
    routing {
        analysisRoute()
    }
}
