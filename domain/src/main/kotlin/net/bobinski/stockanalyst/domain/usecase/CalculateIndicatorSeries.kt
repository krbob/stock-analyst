package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.model.BollingerValue
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.IndicatorCatalog
import net.bobinski.stockanalyst.domain.model.Indicators
import net.bobinski.stockanalyst.domain.model.MacdValue
import net.bobinski.stockanalyst.domain.model.SingleValue
import net.bobinski.stockanalyst.domain.model.toBarSeries
import org.ta4j.core.BarSeries
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.averages.EMAIndicator
import org.ta4j.core.indicators.averages.SMAIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandFacade
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import java.time.Duration

object CalculateIndicatorSeries {

    val validKeys = IndicatorCatalog.validKeys

    private data class BarTime(val date: kotlinx.datetime.LocalDate, val timestamp: Long?)

    fun compute(
        data: Collection<HistoricalPrice>,
        requested: Set<String>,
        barDuration: Duration = Duration.ofDays(1),
        conversion: Collection<HistoricalPrice>? = null
    ): Indicators {
        val keys = requested.intersect(validKeys)
        if (keys.isEmpty()) return Indicators()

        val sorted = data.sortedBy { it.sortKey }
        val bars = sorted.toBarSeries(conversion, barDuration)
        val close = ClosePriceIndicator(bars)
        val barTimes = sorted.mapNotNull { day ->
            if (setOf(day.open, day.close, day.low, day.high).any { it.isNaN() }) null
            else BarTime(day.date, day.timestamp)
        }

        return Indicators(
            sma50 = if ("sma50" in keys) sma(bars, close, barTimes, 50) else null,
            sma200 = if ("sma200" in keys) sma(bars, close, barTimes, 200) else null,
            ema50 = if ("ema50" in keys) ema(bars, close, barTimes, 50) else null,
            ema200 = if ("ema200" in keys) ema(bars, close, barTimes, 200) else null,
            bb = if ("bb" in keys) bollingerBands(bars, barTimes) else null,
            rsi = if ("rsi" in keys) rsi(bars, close, barTimes) else null,
            macd = if ("macd" in keys) macd(bars, close, barTimes) else null,
        )
    }

    private fun sma(bars: BarSeries, close: ClosePriceIndicator, barTimes: List<BarTime>, period: Int): List<SingleValue> {
        val indicator = SMAIndicator(close, period)
        return extractSeries(bars, barTimes, period) { i -> indicator.getValue(i).doubleValue() }
    }

    private fun ema(bars: BarSeries, close: ClosePriceIndicator, barTimes: List<BarTime>, period: Int): List<SingleValue> {
        val indicator = EMAIndicator(close, period)
        return extractSeries(bars, barTimes, period) { i -> indicator.getValue(i).doubleValue() }
    }

    private fun bollingerBands(bars: BarSeries, barTimes: List<BarTime>): List<BollingerValue> {
        val facade = BollingerBandFacade(bars, 20, 2.0)
        val upper = facade.upper()
        val middle = facade.middle()
        val lower = facade.lower()
        val minIndex = 19 // need 20 bars for BB
        return (minIndex..bars.endIndex).map { i ->
            BollingerValue(
                date = barTimes[i].date,
                upper = upper.getValue(i).doubleValue(),
                middle = middle.getValue(i).doubleValue(),
                lower = lower.getValue(i).doubleValue(),
                timestamp = barTimes[i].timestamp
            )
        }
    }

    private fun rsi(bars: BarSeries, close: ClosePriceIndicator, barTimes: List<BarTime>): List<SingleValue> {
        val indicator = RSIIndicator(close, 14)
        return extractSeries(bars, barTimes, 14) { i -> indicator.getValue(i).doubleValue() }
    }

    private fun macd(bars: BarSeries, close: ClosePriceIndicator, barTimes: List<BarTime>): List<MacdValue> {
        val macdInd = MACDIndicator(close, 12, 26)
        val signalInd = EMAIndicator(macdInd, 9)
        val minIndex = 33 // 26 for MACD + 9 for signal - 2
        return (minIndex..bars.endIndex).mapNotNull { i ->
            if (i >= barTimes.size) return@mapNotNull null
            val m = macdInd.getValue(i).doubleValue()
            val s = signalInd.getValue(i).doubleValue()
            MacdValue(
                date = barTimes[i].date,
                macd = m,
                signal = s,
                histogram = m - s,
                timestamp = barTimes[i].timestamp
            )
        }
    }

    private fun extractSeries(
        bars: BarSeries,
        barTimes: List<BarTime>,
        minBars: Int,
        getValue: (Int) -> Double
    ): List<SingleValue> {
        val startIndex = (minBars - 1).coerceAtLeast(0)
        return (startIndex..bars.endIndex).mapNotNull { i ->
            if (i >= barTimes.size) return@mapNotNull null
            val v = getValue(i)
            if (v.isNaN()) return@mapNotNull null
            SingleValue(date = barTimes[i].date, value = v, timestamp = barTimes[i].timestamp)
        }
    }
}
