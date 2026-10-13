package net.bobinski.stockanalyst

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.bobinski.stockanalyst.route.compareRoute
import net.bobinski.stockanalyst.route.healthRoute
import net.bobinski.stockanalyst.route.historyRoute
import net.bobinski.stockanalyst.route.indicatorsRoute
import net.bobinski.stockanalyst.route.metricsRoute
import net.bobinski.stockanalyst.route.openApiRoute
import net.bobinski.stockanalyst.route.quoteRoute
import net.bobinski.stockanalyst.route.searchRoute
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val backendProvider: BackendProvider by inject()

    routing {
        healthRoute {
            backendProvider.isReady()
        }
        metricsRoute()
        openApiRoute()
        marketDataRoutes()
        route("/v1") {
            marketDataRoutes()
        }
        route("/{unmatched...}") {
            handle { call.respondRouteFallback() }
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
