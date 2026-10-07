package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.Serializable
import org.ta4j.core.Bar
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.num.DecimalNum
import org.ta4j.core.num.NaN
import java.time.Duration
import kotlin.time.toJavaInstant

@Serializable
data class HistoricalPrice(
    val date: LocalDate,
    val open: Double,
    val close: Double,
    val low: Double,
    val high: Double,
    val volume: Long,
    val dividend: Double,
    val timestamp: Long? = null,
    val splitRatio: Double? = null
) {
    val sortKey: Long get() = timestamp ?: date.atStartOfDayIn(TimeZone.UTC).epochSeconds
}

fun Collection<HistoricalPrice>.toBarSeries(
    conversion: Collection<HistoricalPrice>?,
    barDuration: Duration = Duration.ofDays(1)
): BarSeries {
    val conversionLookup = conversion?.let(::ConversionRates)
    return BaseBarSeriesBuilder().withBars(sortedBy { it.sortKey }.mapNotNull { day ->
        day.toBar(conversionLookup?.let { it.rateFor(day) ?: Double.NaN }, barDuration)
    }).build()
}

private fun HistoricalPrice.toBar(conversion: Double?, barDuration: Duration): Bar? {
    if (setOf(open, close, low, high).any { !it.isFinite() }) {
        return null
    }
    if (conversion != null && !conversion.isFinite()) return null
    val endTime = if (timestamp != null) {
        java.time.Instant.ofEpochSecond(timestamp)
    } else {
        date.atStartOfDayIn(TimeZone.UTC).toJavaInstant()
    }
    return BaseBar(
        /* timePeriod = */ barDuration,
        /* beginTime = */ endTime.minus(barDuration),
        /* endTime = */ endTime,
        /* openPrice = */ DecimalNum.valueOf(open.applyConversion(conversion)),
        /* highPrice = */ DecimalNum.valueOf(high.applyConversion(conversion)),
        /* lowPrice = */ DecimalNum.valueOf(low.applyConversion(conversion)),
        /* closePrice = */ DecimalNum.valueOf(close.applyConversion(conversion)),
        /* volume = */ DecimalNum.valueOf(volume),
        /* amount = */ NaN.NaN,
        /* trades = */ 0L
    )
}

fun List<HistoricalPrice>.convertPrices(
    conversion: Collection<HistoricalPrice>
): List<HistoricalPrice> {
    val lookup = ConversionRates(conversion)
    return mapNotNull { price ->
        val rate = lookup.rateFor(price)
        if (rate != null) {
            price.copy(
                open = price.open * rate,
                close = price.close * rate,
                low = price.low * rate,
                high = price.high * rate,
                dividend = price.dividend * rate
            )
        } else null
    }
}
