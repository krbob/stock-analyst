package net.bobinski.portfolio.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.bobinski.portfolio.core.time.CurrentTimeProvider
import net.bobinski.portfolio.domain.error.BackendDataException
import net.bobinski.portfolio.domain.model.Analysis
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.provider.StockDataProvider
import net.bobinski.portfolio.domain.provider.StockDataProvider.Period

class AnalyzeStockUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider,
    private val calculateGain: CalculateGain,
    private val calculateYield: CalculateYield
) {

    suspend operator fun invoke(symbol: String, conversion: String? = null): Analysis =
        coroutineScope {
            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, Period._5y) }
            val convInfoDeferred = conversion?.let { async { stockDataProvider.getInfo(it) } }

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            var period = Period._5y
            var history = historyDeferred.await()
            var lacking = false

            if (history.size <= 1) {
                period = Period._2y
                history = stockDataProvider.getHistory(symbol, period)
            }
            if (history.size <= 1) {
                period = Period._1y
                history = stockDataProvider.getHistory(symbol, period)
            }
            if (history.size <= 1) {
                lacking = true
                history = stockDataProvider.getHistory(symbol, Period._1d)
            }
            if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

            val conversionInfo = convInfoDeferred?.await()
            val conversionHistory = conversion?.let {
                stockDataProvider.getHistory(it, period).also { convHistory ->
                    if (convHistory.size < history.size) {
                        throw BackendDataException.insufficientConversion(it)
                    }
                }
            }

            Analysis(
                symbol = symbol,
                name = name + (conversionInfo?.let { " ${it.name}" } ?: ""),
                date = currentTimeProvider.localDate(),
                lastPrice = CalculateLastPrice(history, conversionHistory),
                gain = Analysis.Gain(
                    daily = Double.NaN,
                    weekly = Double.NaN,
                    monthly = Double.NaN,
                    quarterly = Double.NaN,
                    yearly = Double.NaN
                ).takeIf { lacking } ?: Analysis.Gain(
                    daily = calculateGain.daily(history, conversionHistory),
                    weekly = calculateGain.weekly(history, conversionHistory),
                    monthly = calculateGain.monthly(history, conversionHistory),
                    quarterly = calculateGain.quarterly(history, conversionHistory),
                    yearly = calculateGain.yearly(history, conversionHistory)
                ),
                rsi = Analysis.Rsi(daily = Double.NaN, weekly = Double.NaN, monthly = Double.NaN)
                    .takeIf { lacking }
                    ?: Analysis.Rsi(
                        daily = CalculateRsi.daily(history, conversionHistory),
                        weekly = CalculateRsi.weekly(history, conversionHistory),
                        monthly = CalculateRsi.monthly(history, conversionHistory)
                    ),
                dividendYield = Double.NaN.takeIf { lacking }
                    ?: calculateYield.yearly(history, conversionHistory),
                peRatio = info.peRatio,
                pbRatio = info.pbRatio,
                eps = info.eps,
                roe = info.roe,
                marketCap = info.marketCap
            ).roundValues()
        }
}
