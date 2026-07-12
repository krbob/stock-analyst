package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider

internal data class CurrencyConversionPlan(
    val responseCurrency: String?,
    val conversionSymbol: String?,
    val nativePriceScale: Double
) {
    /**
     * Yahoo reports exchange-quoted values for some London listings in pence while using GBP for
     * fundamental amounts such as EPS and market capitalisation. Keep the scale limited to quoted
     * price fields and historical candles; applying it to every monetary field would understate
     * those fundamentals by 100x.
     */
    fun normalizeNativePrice(value: Double): Double = value * nativePriceScale

    fun normalizeNativePrices(prices: Collection<HistoricalPrice>): List<HistoricalPrice> =
        if (nativePriceScale == 1.0) {
            prices.toList()
        } else {
            prices.map { price ->
                price.copy(
                    open = normalizeNativePrice(price.open),
                    close = normalizeNativePrice(price.close),
                    low = normalizeNativePrice(price.low),
                    high = normalizeNativePrice(price.high),
                    dividend = normalizeNativePrice(price.dividend)
                )
            }
        }
}

private data class NativeCurrencyUnit(
    val currency: String,
    val priceScale: Double
)

internal fun StockDataProvider.planCurrencyConversion(
    symbol: String,
    nativeCurrency: String?,
    requestedCurrency: String?
): CurrencyConversionPlan {
    val native = nativeCurrency?.toNativeCurrencyUnit()
    val target = requestedCurrency?.uppercase()

    if (target == null || target == native?.currency) {
        return CurrencyConversionPlan(
            responseCurrency = native?.currency ?: target,
            conversionSymbol = null,
            nativePriceScale = native?.priceScale ?: 1.0
        )
    }

    if (native == null) {
        throw BackendDataException.currencyUnavailable(symbol)
    }

    return CurrencyConversionPlan(
        responseCurrency = target,
        conversionSymbol = resolveConversionSymbol(native.currency, target),
        nativePriceScale = native.priceScale
    )
}

private fun String.toNativeCurrencyUnit(): NativeCurrencyUnit = when {
    this == "GBp" || equals("GBX", ignoreCase = true) -> NativeCurrencyUnit(
        currency = "GBP",
        priceScale = 0.01
    )
    else -> NativeCurrencyUnit(currency = uppercase(), priceScale = 1.0)
}
