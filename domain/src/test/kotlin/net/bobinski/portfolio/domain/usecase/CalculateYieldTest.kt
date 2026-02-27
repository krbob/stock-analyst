package net.bobinski.portfolio.domain.usecase

import kotlinx.datetime.LocalDate
import net.bobinski.portfolio.core.time.MutableCurrentTimeProvider
import net.bobinski.portfolio.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CalculateYieldTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val calculateYield = CalculateYield(timeProvider)

    @Test
    fun `yearly yield sums dividends over past year`() {
        val data = listOf(
            price(LocalDate(2023, 9, 1), close = 100.0, dividend = 0.5),
            price(LocalDate(2024, 3, 1), close = 100.0, dividend = 0.5),
            price(LocalDate(2024, 6, 15), close = 100.0, dividend = 0.0)
        )

        val result = calculateYield.yearly(data, null)

        assertEquals(0.01, result, 0.001)
    }

    @Test
    fun `yearly yield excludes dividends older than one year`() {
        val data = listOf(
            price(LocalDate(2023, 1, 1), close = 100.0, dividend = 10.0),
            price(LocalDate(2024, 6, 15), close = 100.0, dividend = 0.0)
        )

        val result = calculateYield.yearly(data, null)

        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `yearly yield with no dividends returns zero`() {
        val data = listOf(
            price(LocalDate(2024, 1, 1), close = 100.0, dividend = 0.0),
            price(LocalDate(2024, 6, 15), close = 100.0, dividend = 0.0)
        )

        val result = calculateYield.yearly(data, null)

        assertEquals(0.0, result, 0.001)
    }

    private fun price(date: LocalDate, close: Double, dividend: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close, high = close,
        volume = 1000L, dividend = dividend
    )
}
