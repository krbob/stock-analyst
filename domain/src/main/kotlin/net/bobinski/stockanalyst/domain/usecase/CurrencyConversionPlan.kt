package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.provider.StockDataProvider

internal data class CurrencyConversionPlan(
    val responseCurrency: String?,
    val conversionSymbol: String?
)

internal fun StockDataProvider.planCurrencyConversion(
    symbol: String,
    nativeCurrency: String?,
    requestedCurrency: String?
): CurrencyConversionPlan {
    val native = nativeCurrency?.uppercase()
    val target = requestedCurrency?.uppercase()

    if (target == null || target == native) {
        return CurrencyConversionPlan(
            responseCurrency = native ?: target,
            conversionSymbol = null
        )
    }

    if (native == null) {
        throw BackendDataException.currencyUnavailable(symbol)
    }

    return CurrencyConversionPlan(
        responseCurrency = target,
        conversionSymbol = resolveConversionSymbol(native, target)
    )
}
