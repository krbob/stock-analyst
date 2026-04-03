package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.Quote
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.model.latestPrice
import net.bobinski.stockanalyst.domain.model.priceFor
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.slf4j.LoggerFactory

class GetQuoteUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider,
    private val calculateGain: CalculateGain,
    private val calculateYield: CalculateYield
) {

    private val logger = LoggerFactory.getLogger(GetQuoteUseCase::class.java)

    suspend operator fun invoke(symbol: String, currency: String? = null): Quote =
        coroutineScope {
            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, Period._5y) }

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            val conversionPlan = stockDataProvider.planCurrencyConversion(symbol, info.currency, currency)
            val conversionSymbol = conversionPlan.conversionSymbol

            val convInfoDeferred = conversionSymbol?.let { conversion ->
                async {
                    try {
                        stockDataProvider.getInfo(conversion)
                    } catch (e: BackendDataException) {
                        logger.warn("Failed to fetch spot conversion info for {}", conversion, e)
                        null
                    }
                }
            }

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

            val conversionHistory = conversionSymbol?.let {
                val convHistory = stockDataProvider.getHistory(it, period)
                if (convHistory.isEmpty()) throw BackendDataException.insufficientConversion(it)
                val convMinDate = convHistory.minOf { p -> p.date }
                history = history.filter { p -> p.date >= convMinDate }
                if (history.isEmpty()) throw BackendDataException.insufficientConversion(it)
                convHistory
            }
            val conversionInfo = convInfoDeferred?.await()
            val conversionPrice = conversionInfo?.price
            val latestConvRate = conversionHistory?.latestPrice()
            val convRate = conversionPrice ?: latestConvRate

            Quote(
                symbol = symbol,
                name = name,
                currency = conversionPlan.responseCurrency,
                date = currentTimeProvider.localDate(),
                lastPrice = (info.price ?: CalculateLastPrice(history, null))
                    .applyConversion(conversionPrice),
                gain = Quote.Gain(
                    daily = Double.NaN,
                    weekly = Double.NaN,
                    monthly = Double.NaN,
                    quarterly = Double.NaN,
                    halfYearly = Double.NaN,
                    ytd = Double.NaN,
                    yearly = Double.NaN,
                    fiveYear = Double.NaN
                ).takeIf { lacking } ?: Quote.Gain(
                    daily = calculateGain.daily(history, conversionHistory),
                    weekly = calculateGain.weekly(history, conversionHistory),
                    monthly = calculateGain.monthly(history, conversionHistory),
                    quarterly = calculateGain.quarterly(history, conversionHistory),
                    halfYearly = calculateGain.halfYearly(history, conversionHistory),
                    ytd = calculateGain.ytd(history, conversionHistory),
                    yearly = calculateGain.yearly(history, conversionHistory),
                    fiveYear = calculateGain.fiveYear(history, conversionHistory)
                ),
                dividendYield = Double.NaN.takeIf { lacking }
                    ?: calculateYield.yearly(history, conversionHistory),
                dividendGrowth = if (lacking) null
                    else calculateDividendGrowth(history, conversionHistory),
                peRatio = info.peRatio,
                pbRatio = info.pbRatio,
                eps = info.eps?.let { convRate?.times(it) ?: it },
                roe = info.roe,
                marketCap = info.marketCap?.let { convRate?.times(it) ?: it },
                recommendation = info.recommendation,
                analystCount = info.analystCount,
                fiftyTwoWeekHigh = info.fiftyTwoWeekHigh?.let { convRate?.times(it) ?: it },
                fiftyTwoWeekLow = info.fiftyTwoWeekLow?.let { convRate?.times(it) ?: it },
                beta = info.beta,
                sector = info.sector,
                industry = info.industry,
                earningsDate = info.earningsDate,
                previousClose = info.previousClose?.let { conversionPrice?.times(it) ?: it }
            ).roundValues()
        }

    private fun calculateDividendGrowth(
        history: Collection<HistoricalPrice>,
        conversionHistory: Collection<HistoricalPrice>?
    ): Double? {
        val today = currentTimeProvider.localDate()
        val oneYearAgo = today.minus(1, DateTimeUnit.YEAR)
        val twoYearsAgo = today.minus(2, DateTimeUnit.YEAR)

        fun dividendSum(from: LocalDate, to: LocalDate): Double =
            history.filter { it.date > from && it.date <= to }.sumOf { hp ->
                hp.dividend.applyConversion(conversionHistory?.priceFor(hp.date))
            }

        val recent = dividendSum(oneYearAgo, today)
        val previous = dividendSum(twoYearsAgo, oneYearAgo)

        if (previous == 0.0) return null
        return recent / previous - 1
    }
}
