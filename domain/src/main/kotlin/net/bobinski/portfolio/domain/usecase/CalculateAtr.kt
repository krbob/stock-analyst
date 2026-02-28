package net.bobinski.portfolio.domain.usecase

import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.toBarSeries
import org.ta4j.core.indicators.ATRIndicator

object CalculateAtr {

    fun daily(data: Collection<HistoricalPrice>, period: Int = 14): Double {
        val bars = data.toBarSeries(null)
        return ATRIndicator(bars, period).getValue(bars.endIndex).doubleValue()
    }
}
