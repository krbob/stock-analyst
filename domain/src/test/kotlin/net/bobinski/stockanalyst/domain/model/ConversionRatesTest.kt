package net.bobinski.stockanalyst.domain.model

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConversionRatesTest {
    private fun price(date: LocalDate, close: Double, timestamp: Long? = null) =
        HistoricalPrice(date, close, close, close, close, 1, 0.5, timestamp)

    @Test
    fun `intraday conversion uses only FX already observed at candle time`() {
        val date = LocalDate(2024, 6, 17)
        val first = java.time.Instant.parse("2024-06-17T13:30:00Z").epochSecond
        val conversion = listOf(price(date, 2.0, first), price(date, 3.0, first + 3600))
        val prices = listOf(price(date, 10.0, first - 900), price(date, 10.0, first), price(date, 10.0, first + 3600))

        val result = prices.convertPrices(conversion)

        assertEquals(listOf(20.0, 30.0), result.map { it.close })
        assertEquals(listOf(1.0, 1.5), result.map { it.dividend })
        assertEquals(listOf(first, first + 3600), result.map { it.timestamp })
    }

    @Test
    fun `daily conversion covers a weekend but rejects old and future rates`() {
        val rates = ConversionRates(listOf(price(LocalDate(2024, 6, 14), 4.0)))
        assertNull(rates.rateFor(LocalDate(2024, 6, 13)))
        assertEquals(4.0, rates.rateFor(LocalDate(2024, 6, 17)))
        assertEquals(4.0, rates.rateFor(LocalDate(2024, 6, 18)))
        assertNull(rates.rateFor(LocalDate(2024, 6, 19)))
        assertNull(rates.rateFor(LocalDate(2024, 6, 30)))
        assertEquals(emptyList<HistoricalPrice>(), listOf(price(LocalDate(2024, 6, 30), 100.0))
            .convertPrices(listOf(price(LocalDate(2024, 6, 1), 4.0))))
    }

    @Test
    fun `invalid rates cannot produce zero negative or non-finite prices`() {
        val date = LocalDate(2024, 6, 17)
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(ConversionRates(listOf(price(date, value))).rateFor(date))
        }
    }
}
