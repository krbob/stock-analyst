package net.bobinski.portfolio.domain.model

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
import kotlin.time.ExperimentalTime
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

fun Collection<HistoricalPrice>.toBarSeries(conversion: Collection<HistoricalPrice>?): BarSeries =
    BaseBarSeriesBuilder().withBars(sortedBy { it.date }.mapNotNull { day ->
        day.toBar(conversion?.priceFor(day.date))
    }).build()

@OptIn(ExperimentalTime::class)
private fun HistoricalPrice.toBar(conversion: Double?): Bar? {
    if (setOf(open, close, low, high).any { it.isNaN() }) {
        return null
    }
    return BaseBar(
        /* timePeriod = */ Duration.ofDays(1),
        /* endTime = */ date.atStartOfDayIn(TimeZone.UTC).toJavaInstant(),
        /* openPrice = */ DecimalNum.valueOf(open.applyConversion(conversion)),
        /* highPrice = */ DecimalNum.valueOf(high.applyConversion(conversion)),
        /* lowPrice = */ DecimalNum.valueOf(low.applyConversion(conversion)),
        /* closePrice = */ DecimalNum.valueOf(close.applyConversion(conversion)),
        /* volume = */ DecimalNum.valueOf(volume),
        /* amount = */ NaN.NaN,
        /* trades = */ 0
    )
}

fun Collection<HistoricalPrice>.daily() = sortedByDescending { it.date }

fun Collection<HistoricalPrice>.weekly(): List<HistoricalPrice> =
    daily().intervalBy { it.date.weekNumber }

fun Collection<HistoricalPrice>.monthly(): List<HistoricalPrice> =
    daily().intervalBy { it.date.month.number }

private fun List<HistoricalPrice>.intervalBy(keySelector: (HistoricalPrice) -> Int): List<HistoricalPrice> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf(first())
    for (i in 1 until size) {
        if (keySelector(this[i]) != keySelector(result.last())) {
            result.add(this[i])
        }
    }
    return result
}

val LocalDate.weekNumber: Int
    get() = java.time.LocalDate.of(year, month.number, day)
        .get(WeekFields.of(DayOfWeek.MONDAY, 1).weekOfYear())
