package net.bobinski.stockanalyst.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.bobinski.stockanalyst.domain.usecase.SearchTickerUseCase
import org.koin.ktor.ext.inject

fun Route.searchRoute() {
    val searchTickerUseCase: SearchTickerUseCase by inject()

    get("/search/{query}") {
        val query = call.parameters["query"]
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing query parameter.")

        if (query.length > 50) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Query too long.")
        }

        val results = try {
            searchTickerUseCase(query)
        } catch (e: Exception) {
            return@get call.respondError(
                HttpStatusCode.InternalServerError,
                e.message ?: "Unexpected error occurred."
            )
        }

        call.respond(results)
    }
}
