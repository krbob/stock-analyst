package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.StockHistory
import net.bobinski.stockanalyst.domain.model.convertPrices
import net.bobinski.stockanalyst.domain.model.trimTo
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import java.time.Duration

class GetStockHistoryUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {

    suspend operator fun invoke(
        symbol: String,
        period: Period,
        interval: Interval? = null,
        indicators: Set<String> = emptySet(),
        currency: String? = null,
        dividends: Boolean = false
    ): StockHistory =
        coroutineScope {
            val interval = interval ?: intervalFor(period)
            val warmup = warmupBars(indicators)
            val fetchPeriod = if (warmup > 0) extendedPeriod(period, interval, warmup) else period
            val barDuration = barDuration(interval)

            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, fetchPeriod, interval) }
            // yfinance doesn't include dividends in weekly/monthly candles — fetch daily in parallel
            val needsDividendFill = dividends && (interval == Interval.WEEKLY || interval == Interval.MONTHLY)
            val dailyDividendsDeferred = if (needsDividendFill) {
                async { stockDataProvider.getHistory(symbol, fetchPeriod, Interval.DAILY) }
            } else null

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            val nativeCurrency = info.currency?.uppercase()
            val targetCurrency = currency?.uppercase()
            val conversionSymbol = if (targetCurrency != null && nativeCurrency != null
                && targetCurrency != nativeCurrency) {
                stockDataProvider.resolveConversionSymbol(nativeCurrency, targetCurrency)
            } else null

            val conversionHistory = conversionSymbol?.let {
                val convHistory = stockDataProvider.getHistory(it, fetchPeriod)
                if (convHistory.isEmpty()) throw BackendDataException.insufficientConversion(it)
                convHistory
            }

            var history = historyDeferred.await()
            if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

            if (conversionHistory != null) {
                val convMinDate = conversionHistory.minOf { it.date }
                history = history.filter { it.date >= convMinDate }
                if (history.isEmpty()) throw BackendDataException.insufficientConversion(conversionSymbol)
            }

            val pricesWithDividends: Collection<HistoricalPrice> = if (dailyDividendsDeferred != null) {
                val dailyPrices = dailyDividendsDeferred.await()
                injectDividends(history, dailyPrices)
            } else history

            val sortedPrices = pricesWithDividends.sortedBy { it.sortKey }

            val needsTrim = fetchPeriod != period
            val cutoff = if (needsTrim) periodStartDate(period) else null

            val computed = if (indicators.isNotEmpty()) {
                val raw = CalculateIndicatorSeries.compute(sortedPrices, indicators, barDuration, conversionHistory)
                if (cutoff != null) raw.trimTo(cutoff) else raw
            } else null

            val displayPrices = if (cutoff != null) {
                sortedPrices.filter { it.date >= cutoff }
            } else sortedPrices

            val finalPrices = if (conversionHistory != null) {
                displayPrices.convertPrices(conversionHistory)
            } else displayPrices

            StockHistory(
                symbol = symbol,
                name = name,
                period = period.value,
                interval = interval.value,
                prices = finalPrices,
                indicators = computed,
                currency = targetCurrency ?: nativeCurrency
            )
        }

    private fun intervalFor(period: Period): Interval = when (period) {
        Period._5y, Period._10y -> Interval.WEEKLY
        Period.max -> Interval.MONTHLY
        else -> Interval.DAILY
    }

    private fun barDuration(interval: Interval): Duration = when {
        interval.isIntraday -> Duration.ofMinutes(interval.durationMinutes.toLong())
        interval == Interval.WEEKLY -> Duration.ofDays(7)
        interval == Interval.MONTHLY -> Duration.ofDays(30)
        else -> Duration.ofDays(1)
    }

    private fun warmupBars(indicators: Set<String>): Int {
        var max = 0
        if ("sma200" in indicators || "ema200" in indicators) max = maxOf(max, 200)
        if ("sma50" in indicators || "ema50" in indicators) max = maxOf(max, 50)
        if ("macd" in indicators) max = maxOf(max, 33)
        if ("bb" in indicators) max = maxOf(max, 20)
        if ("rsi" in indicators) max = maxOf(max, 14)
        return max
    }

    private fun extendedPeriod(period: Period, interval: Interval, warmupBars: Int): Period {
        if (period == Period.max) return period

        val extraDays = when {
            interval.isIntraday -> {
                // ~78 bars per trading day for 5m, ~390 for 1m; use 2x safety margin
                val barsPerDay = (6.5 * 60 / interval.durationMinutes).toInt().coerceAtLeast(1)
                val tradingDays = (warmupBars / barsPerDay) + 1
                (tradingDays * 2).coerceAtLeast(2) // calendar days with weekends
            }
            interval == Interval.DAILY -> (warmupBars * 1.5).toInt()
            interval == Interval.WEEKLY -> warmupBars * 7
            interval == Interval.MONTHLY -> warmupBars * 31
            else -> (warmupBars * 1.5).toInt()
        }

        val periodDays = mapOf(
            Period._1d to 1, Period._5d to 5, Period._1mo to 30,
            Period._3mo to 90, Period._6mo to 180, Period._1y to 365,
            Period._2y to 730, Period._5y to 1825, Period._10y to 3650,
            Period.ytd to 365,
        )

        val originalDays = periodDays[period] ?: return Period.max
        val neededDays = originalDays + extraDays

        val candidates = listOf(
            Period._5d to 5, Period._1mo to 30,
            Period._3mo to 90, Period._6mo to 180, Period._1y to 365,
            Period._2y to 730, Period._5y to 1825, Period._10y to 3650,
            Period.max to Int.MAX_VALUE
        )

        return candidates.firstOrNull { it.second >= neededDays }?.first ?: Period.max
    }

    /**
     * yfinance returns dividend=0 for weekly/monthly candles.
     * Inject dividends from daily data by summing daily dividends that fall within each bar's date range.
     */
    private fun injectDividends(
        bars: Collection<HistoricalPrice>,
        dailyPrices: Collection<HistoricalPrice>
    ): List<HistoricalPrice> {
        val dailyDividends = dailyPrices.filter { it.dividend > 0 }
        if (dailyDividends.isEmpty()) return bars.toList()

        val sorted = bars.sortedBy { it.date }
        return sorted.mapIndexed { index, bar ->
            val startDate = if (index > 0) sorted[index - 1].date else LocalDate(1900, 1, 1)
            val divSum = dailyDividends
                .filter { it.date > startDate && it.date <= bar.date }
                .sumOf { it.dividend }
            if (divSum > 0) bar.copy(dividend = divSum) else bar
        }
    }

    private fun periodStartDate(period: Period): LocalDate {
        val today = currentTimeProvider.localDate()
        return when (period) {
            Period._1d -> today.minus(1, DateTimeUnit.DAY)
            Period._5d -> today.minus(5, DateTimeUnit.DAY)
            Period._1mo -> today.minus(1, DateTimeUnit.MONTH)
            Period._3mo -> today.minus(3, DateTimeUnit.MONTH)
            Period._6mo -> today.minus(6, DateTimeUnit.MONTH)
            Period._1y -> today.minus(1, DateTimeUnit.YEAR)
            Period._2y -> today.minus(2, DateTimeUnit.YEAR)
            Period._5y -> today.minus(5, DateTimeUnit.YEAR)
            Period._10y -> today.minus(10, DateTimeUnit.YEAR)
            Period.ytd -> LocalDate(today.year, 1, 1)
            Period.max -> LocalDate(1900, 1, 1)
        }
    }
}
