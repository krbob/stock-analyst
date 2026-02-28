package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.toBarSeries
import org.ta4j.core.indicators.ATRIndicator

object CalculateAtr {

    fun daily(data: Collection<HistoricalPrice>, period: Int = 14): Double {
        val bars = data.toBarSeries(null)
        return ATRIndicator(bars, period).getValue(bars.endIndex).doubleValue()
    }
}
