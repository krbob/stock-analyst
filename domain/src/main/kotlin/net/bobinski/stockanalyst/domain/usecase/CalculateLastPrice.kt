package net.bobinski.stockanalyst.domain.usecase

import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.model.latestPrice

object CalculateLastPrice {

    operator fun invoke(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?
    ) = data.latestPrice().applyConversion(conversion?.latestPrice())
}
