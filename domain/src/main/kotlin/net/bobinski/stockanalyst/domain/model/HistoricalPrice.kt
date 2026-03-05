package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.serialization.Serializable
import org.ta4j.core.Bar
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.num.DecimalNum
import org.ta4j.core.num.NaN
import java.time.DayOfWeek
import java.time.Duration
import java.time.temporal.WeekFields
import java.util.TreeMap
import kotlin.time.toJavaInstant

@Serializable
data class HistoricalPrice(
    val date: LocalDate,
    val open: Double,
    val close: Double,
    val low: Double,
    val high: Double,
    val volume: Long,
    val dividend: Double
)

fun Collection<HistoricalPrice>.toBarSeries(conversion: Collection<HistoricalPrice>?): BarSeries {
    val conversionLookup = conversion?.toSortedLookup()
    return BaseBarSeriesBuilder().withBars(sortedBy { it.date }.mapNotNull { day ->
        day.toBar(conversionLookup?.priceFor(day.date))
    }).build()
}

private fun Collection<HistoricalPrice>.toSortedLookup(): TreeMap<LocalDate, Double> =
    associateTo(TreeMap()) { it.date to it.close }

private fun TreeMap<LocalDate, Double>.priceFor(date: LocalDate): Double =
    floorEntry(date)?.value ?: Double.NaN

private fun HistoricalPrice.toBar(conversion: Double?): Bar? {
    if (setOf(open, close, low, high).any { it.isNaN() }) {
        return null
    }
    if (conversion != null && !conversion.isFinite()) return null
    val endTime = date.atStartOfDayIn(TimeZone.UTC).toJavaInstant()
    return BaseBar(
        /* timePeriod = */ Duration.ofDays(1),
        /* beginTime = */ endTime.minus(Duration.ofDays(1)),
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

fun Collection<HistoricalPrice>.daily() = sortedByDescending { it.date }

fun Collection<HistoricalPrice>.weekly(): List<HistoricalPrice> =
    aggregateBy { "${it.date.year}-${it.date.weekNumber}" }

fun Collection<HistoricalPrice>.monthly(): List<HistoricalPrice> =
    aggregateBy { "${it.date.year}-${it.date.month.number}" }

private fun Collection<HistoricalPrice>.aggregateBy(keySelector: (HistoricalPrice) -> String): List<HistoricalPrice> {
    return sortedBy { it.date }
        .groupBy(keySelector)
        .values
        .map { days ->
            HistoricalPrice(
                date = days.last().date,
                open = days.first().open,
                close = days.last().close,
                high = days.maxOf { it.high },
                low = days.minOf { it.low },
                volume = days.sumOf { it.volume },
                dividend = days.sumOf { it.dividend }
            )
        }
        .sortedByDescending { it.date }
}

val LocalDate.weekNumber: Int
    get() = java.time.LocalDate.of(year, month.number, day)
        .get(WeekFields.of(DayOfWeek.MONDAY, 1).weekOfYear())
