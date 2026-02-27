package net.bobinski.portfolio.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import net.bobinski.portfolio.core.time.CurrentTimeProvider
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.applyConversion
import net.bobinski.portfolio.domain.model.latestPrice
import net.bobinski.portfolio.domain.model.priceFor

class CalculateYield(private val currentTimeProvider: CurrentTimeProvider) {

    fun yearly(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?
    ): Double {
        val oneYearAgo = currentTimeProvider.localDate().minus(1, DateTimeUnit.YEAR)
        return data.filter { it.date >= oneYearAgo }.sumOf {
            it.dividend.applyConversion(conversion?.priceFor(it.date))
        } / data.latestPrice().applyConversion(conversion?.latestPrice())
    }
}
