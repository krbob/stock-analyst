package net.bobinski.stockanalyst

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import net.bobinski.stockanalyst.route.compareRoute
import net.bobinski.stockanalyst.route.historyRoute
import net.bobinski.stockanalyst.route.quoteRoute
import net.bobinski.stockanalyst.route.searchRoute

fun Application.configureRouting() {
    routing {
        quoteRoute()
        compareRoute()
        historyRoute()
        searchRoute()
    }
}
