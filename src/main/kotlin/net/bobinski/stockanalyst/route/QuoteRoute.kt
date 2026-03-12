package net.bobinski.stockanalyst.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.usecase.GetQuoteUseCase
import org.koin.ktor.ext.inject

fun Route.quoteRoute() {
    val getQuoteUseCase: GetQuoteUseCase by inject()

    get("/quote/{stock}") {
        val stock = call.parameters["stock"]
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing stock parameter.")

        if (!symbolPattern.matches(stock)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid symbol: $stock")
        }

        val currency = call.request.queryParameters["currency"]
        if (currency != null && !currencyPattern.matches(currency)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid currency code: $currency")
        }

        val result = try {
            getQuoteUseCase(stock, currency)
        } catch (e: BackendDataException) {
            return@get call.respondError(e.toHttpStatusCode(), e.message ?: "Error.")
        } catch (e: Exception) {
            return@get call.respondError(
                HttpStatusCode.InternalServerError,
                e.message ?: "Unexpected error occurred."
            )
        }

        call.respond(result)
    }
}
