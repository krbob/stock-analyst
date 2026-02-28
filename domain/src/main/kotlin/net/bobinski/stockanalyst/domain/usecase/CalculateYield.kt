package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.model.latestPrice
import net.bobinski.stockanalyst.domain.model.priceFor

class CalculateYield(private val currentTimeProvider: CurrentTimeProvider) {

    fun yearly(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?
    ): Double {
        val oneYearAgo = currentTimeProvider.localDate().minus(1, DateTimeUnit.YEAR)
        val price = data.latestPrice().applyConversion(conversion?.latestPrice())
        if (price == 0.0 || !price.isFinite()) return Double.NaN
        return data.filter { it.date >= oneYearAgo }.sumOf {
            it.dividend.applyConversion(conversion?.priceFor(it.date))
        } / price
    }
}
