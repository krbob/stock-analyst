package net.bobinski.stockanalyst.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.usecase.GetStockHistoryUseCase
import org.koin.ktor.ext.inject

fun Route.historyRoute() {
    val getStockHistoryUseCase: GetStockHistoryUseCase by inject()

    get("/history/{stock}") {
        val stock = call.parameters["stock"]
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing stock parameter.")

        if (!symbolPattern.matches(stock)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid symbol: $stock")
        }

        val periodParam = call.request.queryParameters["period"] ?: "1y"
        val period = validPeriods[periodParam]
            ?: return@get call.respondError(
                HttpStatusCode.BadRequest,
                "Invalid period: $periodParam. Valid values: ${validPeriods.keys.joinToString()}"
            )

        val intervalParam = call.request.queryParameters["interval"]
        val interval = if (intervalParam != null) {
            validIntervals[intervalParam]
                ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "Invalid interval: $intervalParam. Valid values: ${validIntervals.keys.joinToString()}"
                )
        } else null

        val parsedIndicators = parseIndicators(call.request.queryParameters["indicators"])
        if (parsedIndicators.invalid.isNotEmpty()) {
            return@get call.respondError(
                HttpStatusCode.BadRequest,
                "Invalid indicators: ${parsedIndicators.invalid.joinToString()}. Valid values: $validIndicatorsText"
            )
        }
        val indicators = parsedIndicators.values

        val currency = call.request.queryParameters["currency"]
        if (currency != null && !currencyPattern.matches(currency)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "Invalid currency code: $currency")
        }

        val dividends = when (val raw = call.request.queryParameters["dividends"]) {
            null -> false
            "true" -> true
            "false" -> false
            else -> return@get call.respondError(
                HttpStatusCode.BadRequest,
                "Invalid dividends flag: $raw. Valid values: true, false"
            )
        }

        val result = try {
            getStockHistoryUseCase(stock, period, interval, indicators, currency, dividends)
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
