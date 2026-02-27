package net.bobinski.portfolio.domain.usecase

import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.applyConversion
import net.bobinski.portfolio.domain.model.latestPrice

object CalculateLastPrice {

    operator fun invoke(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?
    ) = data.latestPrice().applyConversion(conversion?.latestPrice())
}
