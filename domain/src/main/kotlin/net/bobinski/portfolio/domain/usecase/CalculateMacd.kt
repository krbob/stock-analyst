package net.bobinski.portfolio.domain.usecase

import net.bobinski.portfolio.domain.model.Analysis
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.toBarSeries
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.averages.EMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator

object CalculateMacd {

    fun daily(data: Collection<HistoricalPrice>): Analysis.Macd {
        val bars = data.toBarSeries(null)
        val close = ClosePriceIndicator(bars)
        val macd = MACDIndicator(close, 12, 26)
        val signal = EMAIndicator(macd, 9)
        val idx = bars.endIndex
        val macdVal = macd.getValue(idx).doubleValue()
        val signalVal = signal.getValue(idx).doubleValue()
        return Analysis.Macd(
            macd = macdVal,
            signal = signalVal,
            histogram = macdVal - signalVal
        )
    }
}
