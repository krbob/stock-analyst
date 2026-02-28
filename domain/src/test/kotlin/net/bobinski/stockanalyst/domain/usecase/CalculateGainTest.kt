package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalculateGainTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val calculateGain = CalculateGain(timeProvider)

    @Test
    fun `daily gain calculation`() {
        val data = listOf(
            price(LocalDate(2024, 6, 14), close = 100.0),
            price(LocalDate(2024, 6, 15), close = 110.0)
        )

        val result = calculateGain.daily(data, null)

        assertEquals(0.1, result, 0.001)
    }

    @Test
    fun `weekly gain calculation`() {
        val data = listOf(
            price(LocalDate(2024, 6, 8), close = 100.0),
            price(LocalDate(2024, 6, 15), close = 120.0)
        )

        val result = calculateGain.weekly(data, null)

        assertEquals(0.2, result, 0.001)
    }

    @Test
    fun `monthly gain calculation`() {
        val data = listOf(
            price(LocalDate(2024, 5, 15), close = 100.0),
            price(LocalDate(2024, 6, 15), close = 90.0)
        )

        val result = calculateGain.monthly(data, null)

        assertEquals(-0.1, result, 0.001)
    }

    @Test
    fun `yearly gain with conversion`() {
        val data = listOf(
            price(LocalDate(2023, 6, 15), close = 100.0),
            price(LocalDate(2024, 6, 15), close = 150.0)
        )
        val conversion = listOf(
            price(LocalDate(2023, 6, 15), close = 0.9),
            price(LocalDate(2024, 6, 15), close = 0.9)
        )

        val result = calculateGain.yearly(data, conversion)

        assertEquals(0.5, result, 0.001)
    }

    @Test
    fun `gain returns NaN when old price is zero`() {
        val data = listOf(
            price(LocalDate(2024, 6, 14), close = 0.0),
            price(LocalDate(2024, 6, 15), close = 100.0)
        )

        val result = calculateGain.daily(data, null)

        assertTrue(result.isNaN())
    }

    @Test
    fun `gain returns NaN when data is empty`() {
        val result = calculateGain.daily(emptyList(), null)

        assertTrue(result.isNaN())
    }

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close, high = close,
        volume = 1000L, dividend = 0.0
    )
}
