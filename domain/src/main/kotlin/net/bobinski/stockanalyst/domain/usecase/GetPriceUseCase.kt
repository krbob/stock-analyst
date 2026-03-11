package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.Price
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period

class GetPriceUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider,
    private val calculateGain: CalculateGain
) {

    suspend operator fun invoke(symbol: String, currency: String? = null): Price =
        coroutineScope {
            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, Period._1y) }

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            val nativeCurrency = info.currency?.uppercase()
            val targetCurrency = currency?.uppercase()
            val needsConversion = targetCurrency != null && nativeCurrency != null
                && targetCurrency != nativeCurrency
            val conversionSymbol = if (needsConversion) {
                stockDataProvider.resolveConversionSymbol(nativeCurrency!!, targetCurrency!!)
            } else null

            val convInfoDeferred = conversionSymbol?.let { async { stockDataProvider.getInfo(it) } }

            var history = historyDeferred.await()
            if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

            val conversionInfo = convInfoDeferred?.await()
            val conversionHistory = conversionSymbol?.let {
                val convHistory = stockDataProvider.getHistory(it, Period._1y)
                if (convHistory.isEmpty()) throw BackendDataException.insufficientConversion(it)
                val convMinDate = convHistory.minOf { p -> p.date }
                history = history.filter { p -> p.date >= convMinDate }
                if (history.isEmpty()) throw BackendDataException.insufficientConversion(it)
                convHistory
            }

            val lacking = history.size <= 1

            Price(
                symbol = symbol,
                name = name,
                currency = targetCurrency ?: nativeCurrency,
                date = currentTimeProvider.localDate(),
                lastPrice = (info.price ?: CalculateLastPrice(history, null))
                    .applyConversion(conversionInfo?.price),
                gain = Price.Gain(
                    daily = Double.NaN,
                    weekly = Double.NaN,
                    monthly = Double.NaN,
                    quarterly = Double.NaN,
                    halfYearly = Double.NaN,
                    ytd = Double.NaN,
                    yearly = Double.NaN,
                    fiveYear = Double.NaN
                ).takeIf { lacking } ?: Price.Gain(
                    daily = calculateGain.daily(history, conversionHistory),
                    weekly = calculateGain.weekly(history, conversionHistory),
                    monthly = calculateGain.monthly(history, conversionHistory),
                    quarterly = calculateGain.quarterly(history, conversionHistory),
                    halfYearly = calculateGain.halfYearly(history, conversionHistory),
                    ytd = calculateGain.ytd(history, conversionHistory),
                    yearly = calculateGain.yearly(history, conversionHistory),
                    fiveYear = calculateGain.fiveYear(history, conversionHistory)
                )
            ).roundValues()
        }
}
