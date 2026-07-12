package net.bobinski.stockanalyst

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.bobinski.stockanalyst.route.compareRoute
import net.bobinski.stockanalyst.route.healthRoute
import net.bobinski.stockanalyst.route.historyRoute
import net.bobinski.stockanalyst.route.indicatorsRoute
import net.bobinski.stockanalyst.route.openApiRoute
import net.bobinski.stockanalyst.route.quoteRoute
import net.bobinski.stockanalyst.route.searchRoute

fun Application.configureRouting() {
    routing {
        healthRoute()
        openApiRoute()
        marketDataRoutes()
        route("/v1") {
            marketDataRoutes()
        }
    }
}

private fun Route.marketDataRoutes() {
    quoteRoute()
    compareRoute()
    historyRoute()
    indicatorsRoute()
    searchRoute()
}
