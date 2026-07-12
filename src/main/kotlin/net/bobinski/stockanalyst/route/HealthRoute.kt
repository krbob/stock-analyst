package net.bobinski.stockanalyst.route

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoute(readinessCheck: suspend () -> Boolean = { true }) {
    get("/health") {
        call.respondText("ok")
    }
    get("/healthz") {
        call.respondText(UP_RESPONSE, ContentType.Application.Json)
    }
    get("/readyz") {
        val ready = readinessCheck()
        call.respondText(
            text = if (ready) UP_RESPONSE else DOWN_RESPONSE,
            contentType = ContentType.Application.Json,
            status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        )
    }
}

private const val UP_RESPONSE = "{\"status\":\"UP\"}"
private const val DOWN_RESPONSE = "{\"status\":\"DOWN\"}"
