package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.StockHistory
import net.bobinski.stockanalyst.domain.model.trimTo
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period

class GetStockHistoryUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {

    suspend operator fun invoke(
        symbol: String,
        period: Period,
        interval: Interval? = null,
        indicators: Set<String> = emptySet()
    ): StockHistory =
        coroutineScope {
            val interval = interval ?: intervalFor(period)
            val warmup = warmupBars(indicators)
            val fetchPeriod = if (warmup > 0) extendedPeriod(period, interval, warmup) else period

            val infoDeferred = async { stockDataProvider.getInfo(symbol) }
            val historyDeferred = async { stockDataProvider.getHistory(symbol, fetchPeriod, interval) }

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            val history = historyDeferred.await()
            if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

            val sortedPrices = history.sortedBy { it.date }

            val needsTrim = fetchPeriod != period
            val cutoff = if (needsTrim) periodStartDate(period) else null

            val computed = if (indicators.isNotEmpty()) {
                val raw = CalculateIndicatorSeries.compute(sortedPrices, indicators)
                if (cutoff != null) raw.trimTo(cutoff) else raw
            } else null

            val displayPrices = if (cutoff != null) {
                sortedPrices.filter { it.date >= cutoff }
            } else sortedPrices

            StockHistory(
                symbol = symbol,
                name = name,
                period = period.value,
                interval = interval.value,
                prices = displayPrices,
                indicators = computed
            )
        }

    private fun intervalFor(period: Period): Interval = when (period) {
        Period._5y, Period._10y -> Interval.WEEKLY
        Period.max -> Interval.MONTHLY
        else -> Interval.DAILY
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

        val extraDays = when (interval) {
            Interval.DAILY -> (warmupBars * 1.5).toInt()
            Interval.WEEKLY -> warmupBars * 7
            Interval.MONTHLY -> warmupBars * 31
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
            Period._3mo to 90, Period._6mo to 180, Period._1y to 365,
            Period._2y to 730, Period._5y to 1825, Period._10y to 3650,
            Period.max to Int.MAX_VALUE
        )

        return candidates.firstOrNull { it.second >= neededDays }?.first ?: Period.max
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
