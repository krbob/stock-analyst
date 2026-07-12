package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.model.latestPrice

/**
 * Immutable terminal snapshot used by every quote calculation.
 *
 * Provider history may lag behind spot data for the duration of its cache TTL. The snapshot
 * upserts spot price and spot FX onto one market date in copied series so lastPrice, gains and
 * yield all see the same endpoint. An existing market-date candle is replaced instead of adding
 * a duplicate; the provider-owned collections are never modified.
 */
internal class QuotePriceSnapshot private constructor(
    val terminalDate: LocalDate,
    val effectiveSpotPrice: Double,
    val effectiveConversionRate: Double?,
    val history: List<HistoricalPrice>,
    val conversionHistory: List<HistoricalPrice>?
) {
    companion object {
        fun create(
            history: Collection<HistoricalPrice>,
            conversionHistory: Collection<HistoricalPrice>?,
            nativeSpotPrice: Double?,
            spotConversionRate: Double?,
            marketDate: LocalDate?,
            fallbackDate: LocalDate,
            conversionMarketDate: LocalDate? = null
        ): QuotePriceSnapshot {
            require(history.isNotEmpty()) { "Quote history cannot be empty" }

            val sourceHistory = history.toList()
            val latestHistoryDate = sourceHistory.maxOf { it.date }
            val resolvedNativeSpot = nativeSpotPrice ?: sourceHistory.latestPrice()
            val requestedTerminalDate = if (nativeSpotPrice != null) {
                marketDate ?: fallbackDate
            } else {
                latestHistoryDate
            }
            val sourceConversionHistory = conversionHistory?.toList()
            val resolvedConversionRate = spotConversionRate
                ?: sourceConversionHistory?.latestPrice()?.takeIf { it.isFinite() }
            require(sourceConversionHistory == null || resolvedConversionRate != null) {
                "Quote conversion history must provide a finite terminal rate"
            }
            val requestedConversionDate = when {
                sourceConversionHistory == null -> null
                spotConversionRate != null -> conversionMarketDate ?: fallbackDate
                else -> sourceConversionHistory.maxOf { it.date }
            }
            val latestConversionDate = sourceConversionHistory?.maxOf { it.date }
            val terminalDate = listOfNotNull(
                requestedTerminalDate,
                latestHistoryDate,
                requestedConversionDate,
                latestConversionDate
            ).max()

            val snapshotHistory = sourceHistory.withTerminalClose(terminalDate, resolvedNativeSpot)
            val snapshotConversionHistory = sourceConversionHistory?.let { conversion ->
                conversion.withTerminalClose(terminalDate, checkNotNull(resolvedConversionRate))
            }

            return QuotePriceSnapshot(
                terminalDate = terminalDate,
                effectiveSpotPrice = resolvedNativeSpot.applyConversion(resolvedConversionRate),
                effectiveConversionRate = resolvedConversionRate,
                history = snapshotHistory,
                conversionHistory = snapshotConversionHistory
            )
        }
    }
}

private fun Collection<HistoricalPrice>.withTerminalClose(
    terminalDate: LocalDate,
    terminalClose: Double
): List<HistoricalPrice> {
    val existing = filter { it.date == terminalDate }.maxByOrNull { it.sortKey }
    val terminalPrice = existing?.copy(
        close = terminalClose,
        low = minOf(existing.low, terminalClose),
        high = maxOf(existing.high, terminalClose)
    ) ?: HistoricalPrice(
        date = terminalDate,
        open = terminalClose,
        close = terminalClose,
        low = terminalClose,
        high = terminalClose,
        volume = 0L,
        dividend = 0.0
    )

    return (filter { it.date != terminalDate } + terminalPrice).sortedBy { it.sortKey }
}
