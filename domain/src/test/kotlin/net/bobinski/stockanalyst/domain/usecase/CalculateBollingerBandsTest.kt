package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalculateBollingerBandsTest {

    @Test
    fun `upper band is above middle and middle is above lower`() {
        val data = (0..30).map { i ->
            val close = 100.0 + (i % 5) * 2.0 - (i % 3) * 1.5
            price(LocalDate(2024, 1, 1).plus(i), close)
        }

        val result = CalculateBollingerBands.daily(data)

        assertTrue(result.upper > result.middle, "Upper should be above middle")
        assertTrue(result.middle > result.lower, "Middle should be above lower")
    }

    @Test
    fun `bands are narrow for stable prices`() {
        val data = (0..30).map { i ->
            price(LocalDate(2024, 1, 1).plus(i), close = 100.0)
        }

        val result = CalculateBollingerBands.daily(data)
        val width = result.upper - result.lower

        assertTrue(width < 1.0, "Bands should be narrow for stable prices, width was $width")
    }

    private fun LocalDate.plus(days: Int): LocalDate {
        val jd = java.time.LocalDate.of(year, month.number, day).plusDays(days.toLong())
        return LocalDate(jd.year, jd.monthValue, jd.dayOfMonth)
    }

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close * 0.99, high = close * 1.01,
        volume = 1000L, dividend = 0.0
    )
}
