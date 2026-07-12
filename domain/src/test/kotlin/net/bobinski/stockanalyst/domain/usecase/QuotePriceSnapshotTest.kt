package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuotePriceSnapshotTest {

    @Test
    fun `appends missing spot and FX points without mutating provider history`() {
        val history = listOf(price(LocalDate(2024, 6, 14), 100.0))
        val conversion = listOf(price(LocalDate(2024, 6, 14), 4.0))

        val snapshot = QuotePriceSnapshot.create(
            history = history,
            conversionHistory = conversion,
            nativeSpotPrice = 120.0,
            spotConversionRate = 4.25,
            marketDate = LocalDate(2024, 6, 15),
            fallbackDate = LocalDate(2024, 6, 15)
        )

        assertEquals(LocalDate(2024, 6, 15), snapshot.terminalDate)
        assertEquals(510.0, snapshot.effectiveSpotPrice)
        assertEquals(listOf(100.0, 120.0), snapshot.history.map { it.close })
        assertEquals(listOf(4.0, 4.25), snapshot.conversionHistory!!.map { it.close })
        assertEquals(listOf(100.0), history.map { it.close })
        assertEquals(listOf(4.0), conversion.map { it.close })
    }

    @Test
    fun `replaces same-day candle once and preserves its corporate actions`() {
        val history = listOf(
            price(LocalDate(2024, 6, 14), 80.0),
            price(LocalDate(2024, 6, 15), 100.0).copy(dividend = 0.5, splitRatio = 10.0)
        )

        val snapshot = QuotePriceSnapshot.create(
            history = history,
            conversionHistory = null,
            nativeSpotPrice = 120.0,
            spotConversionRate = null,
            marketDate = LocalDate(2024, 6, 15),
            fallbackDate = LocalDate(2024, 6, 15)
        )

        val terminal = snapshot.history.single { it.date == LocalDate(2024, 6, 15) }
        assertEquals(2, snapshot.history.size)
        assertEquals(120.0, terminal.close)
        assertEquals(120.0, terminal.high)
        assertEquals(0.5, terminal.dividend)
        assertEquals(10.0, terminal.splitRatio)
        assertEquals(100.0, history.last().close)
    }

    @Test
    fun `uses last market date instead of creating a weekend point`() {
        val history = listOf(
            price(LocalDate(2024, 6, 13), 80.0),
            price(LocalDate(2024, 6, 14), 100.0)
        )

        val snapshot = QuotePriceSnapshot.create(
            history = history,
            conversionHistory = null,
            nativeSpotPrice = 110.0,
            spotConversionRate = null,
            marketDate = LocalDate(2024, 6, 14),
            fallbackDate = LocalDate(2024, 6, 17)
        )

        assertEquals(LocalDate(2024, 6, 14), snapshot.terminalDate)
        assertEquals(listOf(LocalDate(2024, 6, 13), LocalDate(2024, 6, 14)), snapshot.history.map { it.date })
        assertEquals(110.0, snapshot.history.last().close)
    }

    @Test
    fun `carries latest historical FX forward when spot FX is unavailable`() {
        val snapshot = QuotePriceSnapshot.create(
            history = listOf(price(LocalDate(2024, 6, 14), 100.0)),
            conversionHistory = listOf(price(LocalDate(2024, 6, 14), 4.0)),
            nativeSpotPrice = 120.0,
            spotConversionRate = null,
            marketDate = LocalDate(2024, 6, 15),
            fallbackDate = LocalDate(2024, 6, 15)
        )

        assertEquals(4.0, snapshot.effectiveConversionRate)
        assertEquals(480.0, snapshot.effectiveSpotPrice)
        assertEquals(4.0, snapshot.conversionHistory!!.last().close)
    }

    @Test
    fun `uses later FX session as terminal date for a composite currency valuation`() {
        val snapshot = QuotePriceSnapshot.create(
            history = listOf(price(LocalDate(2024, 6, 14), 100.0)),
            conversionHistory = listOf(price(LocalDate(2024, 6, 17), 4.0)),
            nativeSpotPrice = 100.0,
            spotConversionRate = 4.1,
            marketDate = LocalDate(2024, 6, 14),
            fallbackDate = LocalDate(2024, 6, 17),
            conversionMarketDate = LocalDate(2024, 6, 17)
        )

        assertEquals(LocalDate(2024, 6, 17), snapshot.terminalDate)
        assertEquals(410.0, snapshot.effectiveSpotPrice, 0.0001)
        assertEquals(100.0, snapshot.history.last().close)
        assertEquals(4.1, snapshot.conversionHistory!!.last().close)
    }

    @Test
    fun `uses historical terminal date when native spot is unavailable`() {
        val snapshot = QuotePriceSnapshot.create(
            history = listOf(price(LocalDate(2024, 6, 14), 100.0)),
            conversionHistory = null,
            nativeSpotPrice = null,
            spotConversionRate = null,
            marketDate = null,
            fallbackDate = LocalDate(2024, 6, 15)
        )

        assertEquals(LocalDate(2024, 6, 14), snapshot.terminalDate)
        assertEquals(100.0, snapshot.effectiveSpotPrice)
        assertNull(snapshot.effectiveConversionRate)
    }

    private fun price(date: LocalDate, close: Double) = HistoricalPrice(
        date = date,
        open = close,
        close = close,
        low = close,
        high = close,
        volume = 1_000L,
        dividend = 0.0
    )
}
