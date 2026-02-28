package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalculateRsiTest {

    @Test
    fun `rsi is between 0 and 100 for normal data`() {
        val data = generatePriceData(30)

        val result = CalculateRsi.daily(data)

        assertTrue(result in 0.0..100.0, "RSI should be between 0 and 100, was $result")
    }

    @Test
    fun `rsi is high for consistently rising prices`() {
        val data = (0..30).map { i ->
            price(LocalDate(2024, 1, 1).plus(i), close = 100.0 + i * 2.0)
        }

        val result = CalculateRsi.daily(data)

        assertTrue(result > 70.0, "RSI for rising prices should be > 70, was $result")
    }

    @Test
    fun `rsi is low for consistently falling prices`() {
        val data = (0..30).map { i ->
            price(LocalDate(2024, 1, 1).plus(i), close = 200.0 - i * 2.0)
        }

        val result = CalculateRsi.daily(data)

        assertTrue(result < 30.0, "RSI for falling prices should be < 30, was $result")
    }

    private fun generatePriceData(days: Int): List<HistoricalPrice> {
        return (0..days).map { i ->
            val closePrice = 100.0 + (i % 5) * 2.0 - (i % 3) * 1.5
            price(LocalDate(2024, 1, 1).plus(i), close = closePrice)
        }
    }

    private fun LocalDate.plus(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close * 0.99, high = close * 1.01,
        volume = 1000L, dividend = 0.0
    )
}
