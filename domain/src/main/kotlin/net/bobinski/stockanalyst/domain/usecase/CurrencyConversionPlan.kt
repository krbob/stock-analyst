package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.provider.StockDataProvider

internal data class CurrencyConversionPlan(
    val responseCurrency: String?,
    val conversionSymbol: String?,
    val spotPriceScale: Double
) {
    /**
     * Yahoo info reports exchange-quoted spot fields in subunits (GBp/ZAc/ILA), while yfinance's
     * repair pipeline standardises historical OHLC and dividends to GBP/ZAR/ILS. Keep this scale
     * strictly on info-derived price fields; historical series must not be scaled a second time.
     */
    fun normalizeSpotPrice(value: Double): Double = value * spotPriceScale
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
            spotPriceScale = native?.priceScale ?: 1.0
        )
    }

    if (native == null) {
        throw BackendDataException.currencyUnavailable(symbol)
    }

    return CurrencyConversionPlan(
        responseCurrency = target,
        conversionSymbol = resolveConversionSymbol(native.currency, target),
        spotPriceScale = native.priceScale
    )
}

private fun String.toNativeCurrencyUnit(): NativeCurrencyUnit = when {
    this == "GBp" || equals("GBX", ignoreCase = true) -> NativeCurrencyUnit(
        currency = "GBP",
        priceScale = 0.01
    )
    this == "ZAc" || equals("ZAC", ignoreCase = true) -> NativeCurrencyUnit(
        currency = "ZAR",
        priceScale = 0.01
    )
    equals("ILA", ignoreCase = true) -> NativeCurrencyUnit(
        currency = "ILS",
        priceScale = 0.01
    )
    else -> NativeCurrencyUnit(currency = uppercase(), priceScale = 1.0)
}
