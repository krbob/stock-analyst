package net.bobinski.portfolio.domain.usecase

import net.bobinski.portfolio.domain.model.Analysis
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.toBarSeries
import org.ta4j.core.indicators.averages.EMAIndicator
import org.ta4j.core.indicators.averages.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator

object CalculateMovingAverages {

    fun daily(data: Collection<HistoricalPrice>): Analysis.MovingAverages {
        val bars = data.toBarSeries(null)
        val close = ClosePriceIndicator(bars)
        val idx = bars.endIndex
        return Analysis.MovingAverages(
            sma50 = SMAIndicator(close, 50).getValue(idx).doubleValue(),
            sma200 = SMAIndicator(close, 200).getValue(idx).doubleValue(),
            ema50 = EMAIndicator(close, 50).getValue(idx).doubleValue(),
            ema200 = EMAIndicator(close, 200).getValue(idx).doubleValue()
        )
    }
}
