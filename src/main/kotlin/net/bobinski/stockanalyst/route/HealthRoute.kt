package net.bobinski.stockanalyst.route

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoute() {
    get("/health") {
        call.respondText("ok")
    }
}
