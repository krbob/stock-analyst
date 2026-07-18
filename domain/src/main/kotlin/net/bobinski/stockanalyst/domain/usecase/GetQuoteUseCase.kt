package net.bobinski.stockanalyst.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.error.BackendDataException.Reason
import net.bobinski.stockanalyst.domain.model.AnalyticsStatus
import net.bobinski.stockanalyst.domain.model.DataAdjustment
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.Quote
import net.bobinski.stockanalyst.domain.model.applyConversion
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
            val historyDeferred = async { stockDataProvider.getHistory(symbol, Period._10y) }

            val info = infoDeferred.await()
            val name = info?.name ?: throw BackendDataException.unknownSymbol(symbol)

            val conversionPlan = stockDataProvider.planCurrencyConversion(symbol, info.currency, currency)
            val conversionSymbol = conversionPlan.conversionSymbol

            val convInfoDeferred = conversionSymbol?.let { conversion ->
                async {
                    try {
                        stockDataProvider.getInfo(conversion)
                    } catch (e: BackendDataException) {
                        if (e.reason == Reason.RATE_LIMITED || e.reason == Reason.SERVICE_UNAVAILABLE) throw e
                        logger.warn("Failed to fetch spot conversion info for {}", conversion, e)
                        null
                    }
                }
            }

            var period = Period._10y
            var history = historyDeferred.await()
            var lacking = false

            if (history.size <= 1) {
                period = Period._5y
                history = stockDataProvider.getHistory(symbol, period)
            }
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
            val quoteReferenceDate = if (info.price != null) {
                info.marketDate ?: currentTimeProvider.localDate()
            } else {
                history.maxOf(HistoricalPrice::date)
            }
            val calculationFrom = quoteReferenceDate
                .minus(5, DateTimeUnit.YEAR)
                .minus(QUOTE_HISTORY_BUFFER_DAYS, DateTimeUnit.DAY)
            history = history.filter { it.date >= calculationFrom }
            if (history.isEmpty()) throw BackendDataException.missingHistory(symbol)

            val conversionHistory = conversionSymbol?.let {
                val convHistory = stockDataProvider.getHistory(it, period)
                if (convHistory.isEmpty()) throw BackendDataException.insufficientConversion(it)
                val convMinDate = convHistory.minOf { p -> p.date }
                history = history.filter { p -> p.date >= convMinDate }
                if (history.isEmpty()) throw BackendDataException.insufficientConversion(it)
                convHistory.filter {
                    it.date >= calculationFrom.minus(MAX_CONVERSION_RATE_AGE_DAYS, DateTimeUnit.DAY)
                }.ifEmpty { listOf(convHistory.maxBy(HistoricalPrice::date)) }
            }
            val conversionInfo = convInfoDeferred?.await()
            val priceSnapshot = QuotePriceSnapshot.create(
                history = history,
                conversionHistory = conversionHistory,
                nativeSpotPrice = info.price?.let(conversionPlan::normalizeSpotPrice),
                spotConversionRate = conversionInfo?.price,
                marketDate = info.marketDate,
                fallbackDate = currentTimeProvider.localDate(),
                conversionMarketDate = conversionInfo?.marketDate
            )
            val convRate = priceSnapshot.effectiveConversionRate
            val marketTimestamp = sequenceOf(info, conversionInfo)
                .filter { candidate -> candidate?.marketDate == priceSnapshot.terminalDate }
                .mapNotNull { candidate -> candidate?.marketTimestamp }
                .minOrNull()
            val gain = if (lacking) unavailableGains() else calculateGains(priceSnapshot)
            val analyticsLimitations = gain.analyticsLimitations()
            val analyticsStatus = when (analyticsLimitations.size) {
                0 -> AnalyticsStatus.COMPLETE
                GAIN_ANALYTICS_COUNT -> AnalyticsStatus.UNAVAILABLE
                else -> AnalyticsStatus.PARTIAL
            }
            val nativePriceStatus = marketDataProvenance(
                currentTimeProvider = currentTimeProvider,
                marketDate = info.marketDate.takeIf { info.price != null }
                    ?: priceSnapshot.nativeObservationDate.takeIf { info.price == null },
                marketTimestampEpochSeconds = info.marketTimestamp,
                currency = conversionPlan.responseCurrency,
                adjustment = DataAdjustment.SPLIT_ADJUSTED,
                coverageFrom = info.marketDate,
                coverageTo = info.marketDate,
                cadence = MarketDataCadence.DAILY
            ).status
            val conversionPriceStatus = priceSnapshot.conversionObservationDate?.let { observationDate ->
                val reportedMarketDate = if (priceSnapshot.usesSpotConversion) {
                    conversionInfo?.marketDate
                } else {
                    observationDate
                }
                marketDataProvenance(
                    currentTimeProvider = currentTimeProvider,
                    marketDate = reportedMarketDate,
                    marketTimestampEpochSeconds = conversionInfo?.marketTimestamp
                        .takeIf { priceSnapshot.usesSpotConversion },
                    currency = conversionPlan.responseCurrency,
                    adjustment = DataAdjustment.RAW,
                    coverageFrom = reportedMarketDate,
                    coverageTo = reportedMarketDate,
                    cadence = MarketDataCadence.DAILY
                ).status
            }
            val priceStatus = worstPriceStatus(nativePriceStatus, conversionPriceStatus)
            val priceProvenance = marketDataProvenance(
                currentTimeProvider = currentTimeProvider,
                marketDate = priceSnapshot.terminalDate,
                marketTimestampEpochSeconds = marketTimestamp,
                currency = conversionPlan.responseCurrency,
                adjustment = DataAdjustment.SPLIT_ADJUSTED,
                coverageFrom = priceSnapshot.history.minOfOrNull(HistoricalPrice::date),
                coverageTo = priceSnapshot.terminalDate,
                cadence = MarketDataCadence.DAILY
            )

            Quote(
                symbol = symbol,
                name = name,
                currency = conversionPlan.responseCurrency,
                date = priceSnapshot.terminalDate,
                lastPrice = priceSnapshot.effectiveSpotPrice,
                gain = gain,
                dividendYield = Double.NaN.takeIf { lacking }
                    ?: calculateYield.yearly(
                        priceSnapshot.history,
                        priceSnapshot.conversionHistory,
                        priceSnapshot.terminalDate
                    ),
                dividendGrowth = if (lacking) null
                    else calculateDividendGrowth(
                        priceSnapshot.history,
                        priceSnapshot.conversionHistory,
                        priceSnapshot.terminalDate
                    ),
                peRatio = info.peRatio,
                pbRatio = info.pbRatio,
                eps = info.eps?.let { convRate?.times(it) ?: it },
                roe = info.roe,
                marketCap = info.marketCap?.let { convRate?.times(it) ?: it },
                recommendation = info.recommendation,
                analystCount = info.analystCount,
                fiftyTwoWeekHigh = info.fiftyTwoWeekHigh
                    ?.let(conversionPlan::normalizeSpotPrice)
                    ?.let { convRate?.times(it) ?: it },
                fiftyTwoWeekLow = info.fiftyTwoWeekLow
                    ?.let(conversionPlan::normalizeSpotPrice)
                    ?.let { convRate?.times(it) ?: it },
                beta = info.beta,
                sector = info.sector,
                industry = info.industry,
                earningsDate = info.earningsDate,
                previousClose = info.previousClose
                    ?.let(conversionPlan::normalizeSpotPrice)
                    ?.let { convRate?.times(it) ?: it },
                provenance = priceProvenance.copy(
                    status = if (analyticsStatus == AnalyticsStatus.COMPLETE) {
                        priceStatus
                    } else {
                        DataStatus.PARTIAL
                    },
                    priceStatus = priceStatus,
                    analyticsStatus = analyticsStatus,
                    analyticsLimitations = analyticsLimitations
                )
            ).roundValues()
        }

    private fun calculateGains(snapshot: QuotePriceSnapshot) = Quote.Gain(
        daily = calculateGain.daily(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate),
        weekly = calculateGain.weekly(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate),
        monthly = calculateGain.monthly(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate),
        quarterly = calculateGain.quarterly(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate),
        halfYearly = calculateGain.halfYearly(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate),
        ytd = calculateGain.ytd(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate),
        yearly = calculateGain.yearly(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate),
        fiveYear = calculateGain.fiveYear(snapshot.history, snapshot.conversionHistory, snapshot.terminalDate)
    )

    private fun unavailableGains() = Quote.Gain(
        daily = Double.NaN,
        weekly = Double.NaN,
        monthly = Double.NaN,
        quarterly = Double.NaN,
        halfYearly = Double.NaN,
        ytd = Double.NaN,
        yearly = Double.NaN,
        fiveYear = Double.NaN
    )

    private fun Quote.Gain.analyticsLimitations(): List<String> = listOf(
        "gain.daily" to daily,
        "gain.weekly" to weekly,
        "gain.monthly" to monthly,
        "gain.quarterly" to quarterly,
        "gain.halfYearly" to halfYearly,
        "gain.ytd" to ytd,
        "gain.yearly" to yearly,
        "gain.fiveYear" to fiveYear
    ).filter { (_, value) -> value == null || !value.isFinite() }
        .map { it.first }

    private fun worstPriceStatus(first: DataStatus, second: DataStatus?): DataStatus =
        listOfNotNull(first, second).maxBy { PRICE_STATUS_PRIORITY.getValue(it) }

    private fun calculateDividendGrowth(
        history: Collection<HistoricalPrice>,
        conversionHistory: Collection<HistoricalPrice>?,
        asOfDate: LocalDate
    ): Double? {
        val oneYearAgo = asOfDate.minus(1, DateTimeUnit.YEAR)
        val twoYearsAgo = asOfDate.minus(2, DateTimeUnit.YEAR)

        fun dividendSum(from: LocalDate, to: LocalDate): Double =
            history.filter { it.date > from && it.date <= to }.sumOf { hp ->
                hp.dividend.applyConversion(conversionHistory?.priceFor(hp.date))
            }

        val recent = dividendSum(oneYearAgo, asOfDate)
        val previous = dividendSum(twoYearsAgo, oneYearAgo)

        if (previous == 0.0) return null
        return recent / previous - 1
    }

    private companion object {
        const val GAIN_ANALYTICS_COUNT = 8
        const val QUOTE_HISTORY_BUFFER_DAYS = 14
        const val MAX_CONVERSION_RATE_AGE_DAYS = 4

        val PRICE_STATUS_PRIORITY = mapOf(
            DataStatus.FRESH to 0,
            DataStatus.PARTIAL to 1,
            DataStatus.STALE to 2,
            DataStatus.ERROR to 3
        )
    }
}
