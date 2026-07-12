package net.bobinski.stockanalyst.domain.usecase

import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.model.DataAdjustment
import net.bobinski.stockanalyst.domain.model.DataStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MarketDataProvenanceTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))

    @Test
    fun `marks recent complete market data fresh and preserves timestamp`() {
        val provenance = marketDataProvenance(
            currentTimeProvider = timeProvider,
            marketDate = LocalDate(2024, 6, 14),
            marketTimestampEpochSeconds = 1_718_406_000,
            currency = "USD",
            adjustment = DataAdjustment.SPLIT_ADJUSTED,
            coverageFrom = LocalDate(2024, 1, 2),
            coverageTo = LocalDate(2024, 6, 14),
            cadence = MarketDataCadence.DAILY
        )

        assertEquals(DataStatus.FRESH, provenance.status)
        assertEquals("2024-06-14T23:00:00Z", provenance.marketTimestamp.toString())
        assertEquals("2024-06-15T00:00:00Z", provenance.retrievedAt.toString())
    }

    @Test
    fun `partial takes precedence and missing or old dates are stale`() {
        val partial = provenance(LocalDate(2020, 1, 1), partial = true)
        val stale = provenance(LocalDate(2024, 6, 7))
        val unknown = provenance(null)

        assertEquals(DataStatus.PARTIAL, partial.status)
        assertEquals(DataStatus.STALE, stale.status)
        assertEquals(DataStatus.STALE, unknown.status)
        assertNull(unknown.marketTimestamp)
    }

    @Test
    fun `uses timestamp before date and applies cadence-specific tolerance`() {
        val staleTimestamp = provenance(
            marketDate = LocalDate(2024, 6, 15),
            marketTimestampEpochSeconds = 1_717_632_000,
            cadence = MarketDataCadence.DAILY
        )
        val validMonthlyObservation = provenance(
            marketDate = LocalDate(2024, 5, 10),
            cadence = MarketDataCadence.MONTHLY
        )

        assertEquals(DataStatus.STALE, staleTimestamp.status)
        assertEquals(DataStatus.FRESH, validMonthlyObservation.status)
    }

    private fun provenance(
        marketDate: LocalDate?,
        marketTimestampEpochSeconds: Long? = null,
        cadence: MarketDataCadence = MarketDataCadence.DAILY,
        partial: Boolean = false
    ) = marketDataProvenance(
        currentTimeProvider = timeProvider,
        marketDate = marketDate,
        marketTimestampEpochSeconds = marketTimestampEpochSeconds,
        currency = "USD",
        adjustment = DataAdjustment.RAW,
        coverageFrom = marketDate,
        coverageTo = marketDate,
        cadence = cadence,
        partial = partial
    )
}
