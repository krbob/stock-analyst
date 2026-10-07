package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import java.util.TreeMap

/** As-of FX observations, bounded to four calendar days to cover ordinary weekends. */
class ConversionRates(prices: Collection<HistoricalPrice>) {
    private val intraday = prices.any { it.timestamp != null }
    private val observations = prices.sortedBy { it.sortKey }
        .filter { it.close.isFinite() && it.close > 0 && (!intraday || it.timestamp != null) }
        .associateTo(TreeMap<Long, HistoricalPrice>()) { it.sortKey to it }

    fun rateFor(price: HistoricalPrice): Double? = rateFor(price.date, price.timestamp)

    fun rateFor(date: LocalDate, timestamp: Long? = null): Double? {
        val key = if (intraday && timestamp != null) timestamp else date.atStartOfDayIn(TimeZone.UTC).epochSeconds
        val observation = observations.floorEntry(key)?.value ?: return null
        val fresh = if (intraday && timestamp != null) {
            key - observation.sortKey <= MAX_AGE_SECONDS
        } else observation.date >= date.minus(4, DateTimeUnit.DAY)
        return observation.close.takeIf { fresh }
    }

    private companion object {
        const val MAX_AGE_SECONDS = 4 * 24 * 60 * 60L
    }
}
