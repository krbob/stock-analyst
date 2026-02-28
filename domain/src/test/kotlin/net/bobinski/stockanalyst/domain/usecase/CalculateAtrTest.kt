package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalculateAtrTest {

    @Test
    fun `atr is positive`() {
        val data = (0..30).map { i ->
            val close = 100.0 + (i % 5) * 2.0 - (i % 3) * 1.5
            price(LocalDate(2024, 1, 1).plus(i), close)
        }

        val result = CalculateAtr.daily(data)

        assertTrue(result > 0, "ATR should be positive, was $result")
    }

    @Test
    fun `atr is higher for volatile prices`() {
        val stable = (0..30).map { i ->
            price(LocalDate(2024, 1, 1).plus(i), close = 100.0 + i * 0.1)
        }
        val volatile = (0..30).map { i ->
            val close = 100.0 + if (i % 2 == 0) 10.0 else -10.0
            price(LocalDate(2024, 1, 1).plus(i), close)
        }

        val stableAtr = CalculateAtr.daily(stable)
        val volatileAtr = CalculateAtr.daily(volatile)

        assertTrue(volatileAtr > stableAtr, "Volatile ATR ($volatileAtr) should exceed stable ATR ($stableAtr)")
    }

    private fun LocalDate.plus(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close * 0.97, high = close * 1.03,
        volume = 1000L, dividend = 0.0
    )
}
