package net.bobinski.portfolio.domain.usecase

import kotlinx.datetime.LocalDate
import net.bobinski.portfolio.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CalculateLastPriceTest {

    @Test
    fun `returns latest close price without conversion`() {
        val data = listOf(
            price(LocalDate(2024, 1, 1), close = 100.0),
            price(LocalDate(2024, 1, 2), close = 105.0),
            price(LocalDate(2024, 1, 3), close = 110.0)
        )

        val result = CalculateLastPrice(data, null)

        assertEquals(110.0, result)
    }

    @Test
    fun `returns latest close price with conversion`() {
        val data = listOf(
            price(LocalDate(2024, 1, 1), close = 100.0),
            price(LocalDate(2024, 1, 2), close = 110.0)
        )
        val conversion = listOf(
            price(LocalDate(2024, 1, 1), close = 0.9),
            price(LocalDate(2024, 1, 2), close = 0.85)
        )

        val result = CalculateLastPrice(data, conversion)

        assertEquals(110.0 * 0.85, result)
    }

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close, high = close,
        volume = 1000L, dividend = 0.0
    )
}
