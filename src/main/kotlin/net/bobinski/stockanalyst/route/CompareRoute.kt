package net.bobinski.stockanalyst.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.bobinski.stockanalyst.domain.usecase.CompareStocksUseCase
import org.koin.ktor.ext.inject

private val SYMBOL_PATTERN = Regex("^[a-zA-Z0-9.\\-=^]{1,20}$")
private val CURRENCY_PATTERN = Regex("^[A-Za-z]{3}$")
private const val MAX_SYMBOLS = 10

fun Route.compareRoute() {
    val compareStocksUseCase: CompareStocksUseCase by inject()

    get("/compare") {
        val symbolsParam = call.request.queryParameters["symbols"]
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing symbols parameter.")

        val symbols = symbolsParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        if (symbols.isEmpty()) {
            return@get call.respondError(HttpStatusCode.BadRequest, "No symbols provided.")
        }
        if (symbols.size > MAX_SYMBOLS) {
            return@get call.respondError(
                HttpStatusCode.BadRequest,
                "Too many symbols. Maximum is $MAX_SYMBOLS."
            )
        }

        val invalidSymbol = symbols.firstOrNull { !SYMBOL_PATTERN.matches(it) }
        if (invalidSymbol != null) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid symbol: $invalidSymbol")
        }

        val currency = call.request.queryParameters["currency"]
        if (currency != null && !CURRENCY_PATTERN.matches(currency)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid currency code: $currency")
        }

        val result = try {
            compareStocksUseCase(symbols, currency)
        } catch (e: Exception) {
            return@get call.respondError(
                HttpStatusCode.InternalServerError,
                e.message ?: "Unexpected error occurred."
            )
        }

        call.respond(result)
    }
}
