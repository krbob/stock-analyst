package net.bobinski.portfolio.domain.usecase

import net.bobinski.portfolio.domain.model.Analysis
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.toBarSeries
import org.ta4j.core.indicators.bollinger.BollingerBandFacade

object CalculateBollingerBands {

    fun daily(data: Collection<HistoricalPrice>): Analysis.BollingerBands {
        val bars = data.toBarSeries(null)
        val facade = BollingerBandFacade(bars, 20, 2.0)
        val idx = bars.endIndex
        return Analysis.BollingerBands(
            upper = facade.upper().getValue(idx).doubleValue(),
            middle = facade.middle().getValue(idx).doubleValue(),
            lower = facade.lower().getValue(idx).doubleValue()
        )
    }
}
