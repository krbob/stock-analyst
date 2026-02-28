package net.bobinski.portfolio.domain.usecase

import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.monthly
import net.bobinski.portfolio.domain.model.toBarSeries
import net.bobinski.portfolio.domain.model.weekly
import org.ta4j.core.BarSeries
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator

object CalculateRsi {

    fun daily(data: Collection<HistoricalPrice>, period: Int = 14): Double {
        return forBars(data.toBarSeries(null), period)
    }

    fun weekly(data: Collection<HistoricalPrice>, period: Int = 14): Double {
        return data.weekly().toBarSeries(null).let { forBars(it, period) }
    }

    fun monthly(data: Collection<HistoricalPrice>, period: Int = 14): Double {
        return data.monthly().toBarSeries(null).let { forBars(it, period) }
    }

    private fun forBars(data: BarSeries, period: Int = 14): Double {
        return RSIIndicator(ClosePriceIndicator(data), period).getValue(data.endIndex)
            .doubleValue()
    }
}
