package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalculateMacdTest {

    @Test
    fun `macd is positive for rising prices`() {
        val data = (0..50).map { i ->
            price(LocalDate(2024, 1, 1).plus(i), close = 100.0 + i * 1.0)
        }

        val result = CalculateMacd.daily(data)

        assertTrue(result.macd > 0, "MACD should be positive for rising prices, was ${result.macd}")
        assertTrue(result.signal > 0, "Signal should be positive for rising prices")
    }

    @Test
    fun `macd is negative for falling prices`() {
        val data = (0..50).map { i ->
            price(LocalDate(2024, 1, 1).plus(i), close = 200.0 - i * 1.0)
        }

        val result = CalculateMacd.daily(data)

        assertTrue(result.macd < 0, "MACD should be negative for falling prices, was ${result.macd}")
    }

    @Test
    fun `histogram is macd minus signal`() {
        val data = (0..50).map { i ->
            price(LocalDate(2024, 1, 1).plus(i), close = 100.0 + i * 0.5)
        }

        val result = CalculateMacd.daily(data)
        val expected = result.macd - result.signal

        assertTrue(
            kotlin.math.abs(result.histogram - expected) < 0.0001,
            "Histogram should equal MACD - Signal"
        )
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
