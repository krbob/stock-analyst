package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.DataAdjustment
import net.bobinski.stockanalyst.domain.model.IndicatorCatalog
import net.bobinski.stockanalyst.domain.model.LatestIndicators
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import java.time.Duration

class GetLatestIndicatorsUseCase(
    private val stockDataProvider: StockDataProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {

    private val allIndicators = IndicatorCatalog.validKeys

    suspend operator fun invoke(
        symbol: String,
        indicators: Set<String> = emptySet(),
        currency: String? = null,
        period: Period = Period._1y,
        interval: Interval? = null
    ): LatestIndicators = coroutineScope {
        val keys = if (indicators.isEmpty()) allIndicators else indicators
        val resolvedInterval = interval ?: intervalFor(period)
        val barDuration = barDuration(resolvedInterval)

        val infoDeferred = async { stockDataProvider.getInfo(symbol) }
        val historyDeferred = async { stockDataProvider.getHistory(symbol, period, resolvedInterval) }

        val info = infoDeferred.await()
        info?.name ?: throw BackendDataException.unknownSymbol(symbol)

        val conversionPlan = stockDataProvider.planCurrencyConversion(symbol, info.currency, currency)
        val conversionSymbol = conversionPlan.conversionSymbol

        var history = historyDeferred.await()
        if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

        var conversionTrimmedHistory = false
        val conversionHistory = conversionSymbol?.let {
            val convHistory = stockDataProvider.getHistory(it, period, resolvedInterval)
            if (convHistory.isEmpty()) throw BackendDataException.insufficientConversion(it)
            val convMinDate = convHistory.minOf { p -> p.date }
            val originalSize = history.size
            history = history.filter { p -> p.date >= convMinDate }
            conversionTrimmedHistory = history.size < originalSize
            if (history.isEmpty()) throw BackendDataException.insufficientConversion(it)
            convHistory
        }

        val sortedPrices = history.sortedBy { it.sortKey }

        val computed = CalculateIndicatorSeries.compute(
            sortedPrices, keys, barDuration, conversionHistory
        )
        val rsi = computed.rsi?.lastOrNull()?.value
        val macd = computed.macd?.lastOrNull()?.let {
            LatestIndicators.MacdSnapshot(
                macd = it.macd,
                signal = it.signal,
                histogram = it.histogram
            )
        }
        val bb = computed.bb?.lastOrNull()?.let {
            LatestIndicators.BollingerSnapshot(
                upper = it.upper,
                middle = it.middle,
                lower = it.lower
            )
        }
        val sma50 = computed.sma50?.lastOrNull()?.value
        val sma200 = computed.sma200?.lastOrNull()?.value
        val ema50 = computed.ema50?.lastOrNull()?.value
        val ema200 = computed.ema200?.lastOrNull()?.value
        val missingIndicator = keys.any { key ->
            when (key) {
                "rsi" -> rsi == null
                "macd" -> macd == null
                "bb" -> bb == null
                "sma50" -> sma50 == null
                "sma200" -> sma200 == null
                "ema50" -> ema50 == null
                "ema200" -> ema200 == null
                else -> true
            }
        }
        val coverageFrom = sortedPrices.minOfOrNull { it.date }
        val coverageTo = sortedPrices.maxOfOrNull { it.date }

        LatestIndicators(
            symbol = symbol,
            date = requireNotNull(coverageTo),
            rsi = rsi,
            macd = macd,
            bb = bb,
            sma50 = sma50,
            sma200 = sma200,
            ema50 = ema50,
            ema200 = ema200,
            provenance = marketDataProvenance(
                currentTimeProvider = currentTimeProvider,
                marketDate = coverageTo,
                marketTimestampEpochSeconds = sortedPrices.lastOrNull()?.timestamp,
                currency = conversionPlan.responseCurrency,
                adjustment = DataAdjustment.SPLIT_ADJUSTED,
                coverageFrom = coverageFrom,
                coverageTo = coverageTo,
                cadence = resolvedInterval.marketDataCadence(),
                partial = conversionTrimmedHistory || missingIndicator
            )
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
}
