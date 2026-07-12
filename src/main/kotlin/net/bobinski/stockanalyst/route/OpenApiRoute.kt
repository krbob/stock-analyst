package net.bobinski.stockanalyst.route

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.openApiRoute() {
    get("/openapi/v1.json") {
        call.respondText(OPEN_API_V1, ContentType.Application.Json)
    }
}

private val OPEN_API_V1: String by lazy {
    requireNotNull(object {}.javaClass.getResource("/openapi/stock-analyst-v1.json")) {
        "Missing bundled OpenAPI document."
    }.readText()
}
