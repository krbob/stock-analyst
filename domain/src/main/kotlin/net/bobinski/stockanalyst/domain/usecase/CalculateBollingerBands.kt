package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.model.Analysis
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.toBarSeries
import org.ta4j.core.indicators.bollinger.BollingerBandFacade

object CalculateBollingerBands {

    fun daily(data: Collection<HistoricalPrice>, conversion: Collection<HistoricalPrice>? = null): Analysis.BollingerBands {
        val bars = data.toBarSeries(conversion)
        val facade = BollingerBandFacade(bars, 20, 2.0)
        val idx = bars.endIndex
        return Analysis.BollingerBands(
            upper = facade.upper().getValue(idx).doubleValue(),
            middle = facade.middle().getValue(idx).doubleValue(),
            lower = facade.lower().getValue(idx).doubleValue()
        )
    }
}
