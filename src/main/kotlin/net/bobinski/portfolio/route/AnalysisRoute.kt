package net.bobinski.portfolio.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.bobinski.portfolio.domain.error.BackendDataException
import net.bobinski.portfolio.domain.error.BackendDataException.Reason
import net.bobinski.portfolio.domain.usecase.AnalyzeStockUseCase
import org.koin.ktor.ext.inject

private val SYMBOL_PATTERN = Regex("^[a-zA-Z0-9.\\-=^]{1,20}$")

fun Route.analysisRoute() {
    val analyzeStockUseCase: AnalyzeStockUseCase by inject()

    get("/analysis/{stock}") {
        val stock = call.parameters["stock"]
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing stock parameter.")

        if (!SYMBOL_PATTERN.matches(stock)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid symbol: $stock")
        }

        val conversion = call.request.queryParameters["conversion"]
        if (conversion != null && !SYMBOL_PATTERN.matches(conversion)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid conversion symbol: $conversion")
        }

        val result = try {
            analyzeStockUseCase(stock, conversion)
        } catch (e: BackendDataException) {
            val status = when (e.reason) {
                Reason.NOT_FOUND -> HttpStatusCode.NotFound
                Reason.INSUFFICIENT_DATA -> HttpStatusCode.UnprocessableEntity
                Reason.BACKEND_ERROR -> HttpStatusCode.BadGateway
            }
            return@get call.respondError(status, e.message ?: "Error.")
        } catch (e: Exception) {
            return@get call.respondError(
                HttpStatusCode.InternalServerError,
                e.message ?: "Unexpected error occurred."
            )
        }

        call.respond(result)
    }
}
