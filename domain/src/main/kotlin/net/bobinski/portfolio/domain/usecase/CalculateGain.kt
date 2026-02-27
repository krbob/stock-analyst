package net.bobinski.portfolio.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import net.bobinski.portfolio.core.time.CurrentTimeProvider
import net.bobinski.portfolio.domain.model.HistoricalPrice
import net.bobinski.portfolio.domain.model.applyConversion
import net.bobinski.portfolio.domain.model.priceFor

class CalculateGain(private val currentTimeProvider: CurrentTimeProvider) {

    fun daily(data: Collection<HistoricalPrice>, conversion: Collection<HistoricalPrice>?) =
        calculateFor(data, conversion, currentTimeProvider.localDate().minus(1, DateTimeUnit.DAY))

    fun weekly(data: Collection<HistoricalPrice>, conversion: Collection<HistoricalPrice>?) =
        calculateFor(data, conversion, currentTimeProvider.localDate().minus(1, DateTimeUnit.WEEK))

    fun monthly(data: Collection<HistoricalPrice>, conversion: Collection<HistoricalPrice>?) =
        calculateFor(data, conversion, currentTimeProvider.localDate().minus(1, DateTimeUnit.MONTH))

    fun quarterly(data: Collection<HistoricalPrice>, conversion: Collection<HistoricalPrice>?) =
        calculateFor(data, conversion, currentTimeProvider.localDate().minus(3, DateTimeUnit.MONTH))

    fun yearly(data: Collection<HistoricalPrice>, conversion: Collection<HistoricalPrice>?) =
        calculateFor(data, conversion, currentTimeProvider.localDate().minus(1, DateTimeUnit.YEAR))

    private fun calculateFor(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        targetDate: kotlinx.datetime.LocalDate
    ): Double {
        val currentPrice = CalculateLastPrice(data, conversion)
        val oldPrice = data.priceFor(targetDate)
            .applyConversion(conversion?.priceFor(targetDate))
        return (currentPrice - oldPrice) / oldPrice
    }
}
