package net.bobinski.stockanalyst

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import net.bobinski.stockanalyst.route.analysisRoute
import net.bobinski.stockanalyst.route.compareRoute
import net.bobinski.stockanalyst.route.dividendsRoute
import net.bobinski.stockanalyst.route.historyRoute
import net.bobinski.stockanalyst.route.priceRoute

fun Application.configureRouting() {
    routing {
        analysisRoute()
        priceRoute()
        compareRoute()
        dividendsRoute()
        historyRoute()
    }
}
