package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CalculateIndicatorSeriesTest {

    @Test
    fun `sma50 series has correct length`() {
        val data = risingPrices(100)

        val result = CalculateIndicatorSeries.compute(data, setOf("sma50"))

        val sma50 = checkNotNull(result.sma50)
        assertEquals(51, sma50.size, "SMA50 series should have 100 - 50 + 1 = 51 points")
    }

    @Test
    fun `sma200 series has correct length`() {
        val data = risingPrices(300)

        val result = CalculateIndicatorSeries.compute(data, setOf("sma200"))

        val sma200 = checkNotNull(result.sma200)
        assertEquals(101, sma200.size, "SMA200 series should have 300 - 200 + 1 = 101 points")
    }

    @Test
    fun `sma50 values are above sma200 for rising prices`() {
        val data = risingPrices(300)

        val result = CalculateIndicatorSeries.compute(data, setOf("sma50", "sma200"))

        val lastSma50 = checkNotNull(result.sma50).last().value
        val lastSma200 = checkNotNull(result.sma200).last().value
        assertTrue(lastSma50 > lastSma200, "SMA50 should be above SMA200 for rising prices")
    }

    @Test
    fun `ema50 reacts faster than sma50 to price jump`() {
        val data = (0..250).map { i ->
            val close = if (i < 200) 100.0 else 100.0 + (i - 200) * 3.0
            price(LocalDate(2023, 1, 1).plus(i), close)
        }

        val result = CalculateIndicatorSeries.compute(data, setOf("sma50", "ema50"))

        val lastSma50 = checkNotNull(result.sma50).last().value
        val lastEma50 = checkNotNull(result.ema50).last().value
        assertTrue(lastEma50 > lastSma50, "EMA50 should react faster than SMA50 to price jump")
    }

    @Test
    fun `rsi series values are between 0 and 100`() {
        val data = risingPrices(50)

        val result = CalculateIndicatorSeries.compute(data, setOf("rsi"))

        val rsi = checkNotNull(result.rsi)
        assertTrue(rsi.isNotEmpty())
        rsi.forEach { point ->
            assertTrue(point.value in 0.0..100.0, "RSI should be in [0,100], was ${point.value}")
        }
    }

    @Test
    fun `rsi is high for rising prices`() {
        val data = risingPrices(50)

        val result = CalculateIndicatorSeries.compute(data, setOf("rsi"))

        val lastRsi = checkNotNull(result.rsi).last().value
        assertTrue(lastRsi > 70.0, "RSI for rising prices should be > 70, was $lastRsi")
    }

    @Test
    fun `bollinger bands upper above middle above lower`() {
        val data = (0..50).map { i ->
            val close = 100.0 + (i % 5) * 2.0 - (i % 3) * 1.5
            price(LocalDate(2024, 1, 1).plus(i), close)
        }

        val result = CalculateIndicatorSeries.compute(data, setOf("bb"))

        val bb = checkNotNull(result.bb)
        assertTrue(bb.isNotEmpty())
        bb.forEach { point ->
            assertTrue(point.upper > point.middle, "Upper ${point.upper} should be > middle ${point.middle}")
            assertTrue(point.middle > point.lower, "Middle ${point.middle} should be > lower ${point.lower}")
        }
    }

    @Test
    fun `macd histogram equals macd minus signal`() {
        val data = risingPrices(100)

        val result = CalculateIndicatorSeries.compute(data, setOf("macd"))

        val macd = checkNotNull(result.macd)
        assertTrue(macd.isNotEmpty())
        macd.forEach { point ->
            assertEquals(
                point.macd - point.signal, point.histogram, 1e-10,
                "Histogram should equal MACD - Signal"
            )
        }
    }

    @Test
    fun `only requested indicators are computed`() {
        val data = risingPrices(100)

        val result = CalculateIndicatorSeries.compute(data, setOf("sma50", "rsi"))

        assertNotNull(result.sma50)
        assertNotNull(result.rsi)
        assertNull(result.sma200)
        assertNull(result.ema50)
        assertNull(result.ema200)
        assertNull(result.bb)
        assertNull(result.macd)
    }

    @Test
    fun `empty request returns empty indicators`() {
        val data = risingPrices(100)

        val result = CalculateIndicatorSeries.compute(data, emptySet())

        assertNull(result.sma50)
        assertNull(result.sma200)
        assertNull(result.ema50)
        assertNull(result.ema200)
        assertNull(result.bb)
        assertNull(result.rsi)
        assertNull(result.macd)
    }

    @Test
    fun `invalid indicator keys are ignored`() {
        val data = risingPrices(100)

        val result = CalculateIndicatorSeries.compute(data, setOf("invalid", "nonsense"))

        assertNull(result.sma50)
        assertNull(result.rsi)
        assertNull(result.macd)
    }

    @Test
    fun `series dates are sorted ascending`() {
        val data = risingPrices(100)

        val result = CalculateIndicatorSeries.compute(data, setOf("sma50", "rsi", "macd"))

        checkNotNull(result.sma50).zipWithNext { a, b ->
            assertTrue(a.date < b.date, "SMA50 dates should be ascending")
        }
        checkNotNull(result.rsi).zipWithNext { a, b ->
            assertTrue(a.date < b.date, "RSI dates should be ascending")
        }
        checkNotNull(result.macd).zipWithNext { a, b ->
            assertTrue(a.date < b.date, "MACD dates should be ascending")
        }
    }

    private fun risingPrices(count: Int): List<HistoricalPrice> =
        (0 until count).map { i ->
            price(LocalDate(2023, 1, 1).plus(i), close = 100.0 + i * 0.5)
        }

    private fun LocalDate.plus(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close, close = close, low = close * 0.99, high = close * 1.01,
        volume = 1000L, dividend = 0.0
    )
}
