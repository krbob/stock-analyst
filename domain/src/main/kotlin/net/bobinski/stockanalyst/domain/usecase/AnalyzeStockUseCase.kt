package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.Analysis
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.model.latestPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period

class AnalyzeStockUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider,
    private val calculateGain: CalculateGain,
    private val calculateYield: CalculateYield
) {

    suspend operator fun invoke(symbol: String, currency: String? = null): Analysis =
        coroutineScope {
            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, Period._5y) }

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
            val conversionHistory = conversionSymbol?.let {
                val convHistory = stockDataProvider.getHistory(it, period)
                if (convHistory.isEmpty()) throw BackendDataException.insufficientConversion(it)
                val convMinDate = convHistory.minOf { p -> p.date }
                history = history.filter { p -> p.date >= convMinDate }
                if (history.isEmpty()) throw BackendDataException.insufficientConversion(it)
                convHistory
            }
            val conversionPrice = conversionInfo?.price
            val latestConvRate = conversionHistory?.latestPrice()
            val convRate = conversionPrice ?: latestConvRate

            val nanMacd = Analysis.Macd(Double.NaN, Double.NaN, Double.NaN)
            val nanBollinger = Analysis.BollingerBands(Double.NaN, Double.NaN, Double.NaN)
            val nanMa = Analysis.MovingAverages(Double.NaN, Double.NaN, Double.NaN, Double.NaN)

            Analysis(
                symbol = symbol,
                name = name,
                currency = targetCurrency ?: nativeCurrency,
                date = currentTimeProvider.localDate(),
                lastPrice = (info.price ?: CalculateLastPrice(history, null))
                    .applyConversion(conversionPrice),
                gain = Analysis.Gain(
                    daily = Double.NaN,
                    weekly = Double.NaN,
                    monthly = Double.NaN,
                    quarterly = Double.NaN,
                    halfYearly = Double.NaN,
                    ytd = Double.NaN,
                    yearly = Double.NaN,
                    fiveYear = Double.NaN
                ).takeIf { lacking } ?: Analysis.Gain(
                    daily = calculateGain.daily(history, conversionHistory),
                    weekly = calculateGain.weekly(history, conversionHistory),
                    monthly = calculateGain.monthly(history, conversionHistory),
                    quarterly = calculateGain.quarterly(history, conversionHistory),
                    halfYearly = calculateGain.halfYearly(history, conversionHistory),
                    ytd = calculateGain.ytd(history, conversionHistory),
                    yearly = calculateGain.yearly(history, conversionHistory),
                    fiveYear = calculateGain.fiveYear(history, conversionHistory)
                ),
                rsi = Analysis.Rsi(daily = Double.NaN, weekly = Double.NaN, monthly = Double.NaN)
                    .takeIf { lacking }
                    ?: Analysis.Rsi(
                        daily = CalculateRsi.daily(history),
                        weekly = CalculateRsi.weekly(history),
                        monthly = CalculateRsi.monthly(history)
                    ),
                macd = nanMacd.takeIf { lacking } ?: CalculateMacd.daily(history),
                bollingerBands = nanBollinger.takeIf { lacking }
                    ?: CalculateBollingerBands.daily(history),
                movingAverages = nanMa.takeIf { lacking }
                    ?: CalculateMovingAverages.daily(history),
                atr = Double.NaN.takeIf { lacking } ?: CalculateAtr.daily(history),
                dividendYield = Double.NaN.takeIf { lacking }
                    ?: calculateYield.yearly(history, conversionHistory),
                dividendGrowth = calculateDividendGrowth(
                    info.dividendRate, info.trailingAnnualDividendRate
                ),
                peRatio = info.peRatio,
                pbRatio = info.pbRatio,
                eps = info.eps?.let { convRate?.times(it)?.toFloat() ?: it },
                roe = info.roe,
                marketCap = info.marketCap?.let { convRate?.times(it) ?: it },
                recommendation = info.recommendation,
                analystCount = info.analystCount,
                fiftyTwoWeekHigh = info.fiftyTwoWeekHigh,
                fiftyTwoWeekLow = info.fiftyTwoWeekLow,
                beta = info.beta,
                sector = info.sector,
                industry = info.industry,
                earningsDate = info.earningsDate
            ).roundValues()
        }

    private fun calculateDividendGrowth(
        dividendRate: Float?,
        trailingRate: Float?
    ): Double? {
        if (dividendRate == null || trailingRate == null || trailingRate == 0f) return null
        return (dividendRate / trailingRate - 1).toDouble()
    }
}
