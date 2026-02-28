package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalculateMovingAveragesTest {

    @Test
    fun `sma50 is above sma200 for rising prices`() {
        val data = (0..250).map { i ->
            price(LocalDate(2023, 1, 1).plus(i), close = 100.0 + i * 0.5)
        }

        val result = CalculateMovingAverages.daily(data)

        assertTrue(result.sma50 > result.sma200, "SMA50 should be above SMA200 for rising prices")
        assertTrue(result.ema50 > result.ema200, "EMA50 should be above EMA200 for rising prices")
    }

    @Test
    fun `ema reacts faster than sma to price changes`() {
        val data = (0..250).map { i ->
            val close = if (i < 200) 100.0 else 100.0 + (i - 200) * 3.0
            price(LocalDate(2023, 1, 1).plus(i), close)
        }

        val result = CalculateMovingAverages.daily(data)

        assertTrue(
            result.ema50 > result.sma50,
            "EMA50 should react faster than SMA50 to recent price jump"
        )
    }

    private fun LocalDate.plus(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close * 0.99, high = close * 1.01,
        volume = 1000L, dividend = 0.0
    )
}
