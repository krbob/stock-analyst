package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.CurrentTimeProvider
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.model.applyConversion
import net.bobinski.stockanalyst.domain.model.priceFor

class CalculateGain(private val currentTimeProvider: CurrentTimeProvider) {

    fun daily(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, asOfDate.minus(1, DateTimeUnit.DAY))

    fun weekly(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, asOfDate.minus(1, DateTimeUnit.WEEK))

    fun monthly(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, asOfDate.minus(1, DateTimeUnit.MONTH))

    fun quarterly(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, asOfDate.minus(3, DateTimeUnit.MONTH))

    fun halfYearly(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, asOfDate.minus(6, DateTimeUnit.MONTH))

    fun ytd(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, LocalDate(asOfDate.year, 1, 1))

    fun yearly(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, asOfDate.minus(1, DateTimeUnit.YEAR))

    fun fiveYear(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        asOfDate: LocalDate = currentTimeProvider.localDate()
    ) = calculateFor(data, conversion, asOfDate.minus(5, DateTimeUnit.YEAR))

    private fun calculateFor(
        data: Collection<HistoricalPrice>,
        conversion: Collection<HistoricalPrice>?,
        targetDate: LocalDate
    ): Double {
        val currentPrice = CalculateLastPrice(data, conversion)
        val oldPrice = data.priceFor(targetDate)
            .applyConversion(conversion?.priceFor(targetDate))
        if (oldPrice == 0.0 || !oldPrice.isFinite()) return Double.NaN
        return (currentPrice - oldPrice) / oldPrice
    }
}
