package net.bobinski.stockanalyst.route

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.bobinski.stockanalyst.RequestMetricsRegistry
import net.bobinski.stockanalyst.RuntimeMetrics
import org.koin.ktor.ext.inject

fun Route.metricsRoute() {
    val registry: RequestMetricsRegistry by inject()
    val runtimeMetrics: RuntimeMetrics by inject()

    get("/metrics") {
        call.respondText(registry.scrape() + runtimeMetrics.scrape(), PROMETHEUS_CONTENT_TYPE)
    }
}

private val PROMETHEUS_CONTENT_TYPE =
    ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
