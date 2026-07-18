package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.AnalyticsStatus
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.DataStatus
import net.bobinski.stockanalyst.domain.model.MarketDataSource
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetQuoteUseCaseTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val stockDataProvider = mockk<StockDataProvider>()
    private val calculateGain = CalculateGain(timeProvider)
    private val calculateYield = CalculateYield(timeProvider)
    private val useCase = GetQuoteUseCase(
        stockDataProvider = stockDataProvider,
        currentTimeProvider = timeProvider,
        calculateGain = calculateGain,
        calculateYield = calculateYield
    )

    @Test
    fun `returns quote for valid symbol`() = runTest {
        val history = priceHistory(2_000)
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns history

        val result = useCase("AAPL")

        assertEquals("AAPL", result.symbol)
        assertEquals("Apple Inc.", result.name)
        assertEquals("USD", result.currency)
        assertEquals(LocalDate(2024, 6, 15), result.date)
        assertTrue(result.lastPrice > 0)
        assertEquals(25.0, result.peRatio)
        assertEquals("buy", result.recommendation)
        assertEquals("Technology", result.sector)
        assertEquals(148.0, result.previousClose)
        assertEquals(MarketDataSource.YAHOO_FINANCE, result.provenance.source)
        assertEquals(DataStatus.FRESH, result.provenance.status)
        assertEquals(DataStatus.FRESH, result.provenance.priceStatus)
        assertEquals(AnalyticsStatus.COMPLETE, result.provenance.analyticsStatus)
        assertEquals(emptyList<String>(), result.provenance.analyticsLimitations)
        assertEquals(LocalDate(2019, 6, 1), result.provenance.coverageFrom)
        assertEquals(LocalDate(2024, 6, 15), result.provenance.coverageTo)
        assertEquals(1.0, result.provenance.unitScale)
    }

    @Test
    fun `throws BackendDataException for unknown symbol`() = runTest {
        coEvery { stockDataProvider.getInfo("INVALID") } returns BasicInfo(
            name = null, price = null, peRatio = null, pbRatio = null, eps = null, roe = null,
            marketCap = null, recommendation = null, analystCount = null,
            fiftyTwoWeekHigh = null, fiftyTwoWeekLow = null, beta = null, sector = null,
            industry = null, earningsDate = null, dividendRate = null,
            trailingAnnualDividendRate = null
        )
        coEvery { stockDataProvider.getHistory("INVALID", any()) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("INVALID") }

        assertTrue(exception.message!!.contains("Unknown symbol"))
        assertEquals(BackendDataException.Reason.NOT_FOUND, exception.reason)
    }

    @Test
    fun `throws BackendDataException when history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("EMPTY") } returns basicInfo("Empty Stock")
        coEvery { stockDataProvider.getHistory("EMPTY", any()) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("EMPTY") }

        assertTrue(exception.message!!.contains("Missing history"))
        assertEquals(BackendDataException.Reason.NOT_FOUND, exception.reason)
    }

    @Test
    fun `falls back to shorter periods when data is insufficient`() = runTest {
        coEvery { stockDataProvider.getInfo("LOW") } returns basicInfo("Low Data Stock")
        coEvery { stockDataProvider.getHistory("LOW", Period._10y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LOW", Period._5y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LOW", Period._2y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LOW", Period._1y) } returns priceHistory(400)

        val result = useCase("LOW")

        assertEquals("LOW", result.symbol)
        assertTrue(result.lastPrice > 0)
    }

    @Test
    fun `sets null gains when data is lacking`() = runTest {
        coEvery { stockDataProvider.getInfo("LACK") } returns basicInfo("Lacking Stock")
        coEvery { stockDataProvider.getHistory("LACK", Period._10y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LACK", Period._5y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LACK", Period._2y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LACK", Period._1y) } returns listOf(singlePrice())
        coEvery { stockDataProvider.getHistory("LACK", Period._1d) } returns listOf(singlePrice())

        val result = useCase("LACK")

        assertNull(result.gain.daily)
        assertNull(result.gain.weekly)
        assertNull(result.dividendYield)
        assertEquals(DataStatus.PARTIAL, result.provenance.status)
        assertEquals(DataStatus.FRESH, result.provenance.priceStatus)
        assertEquals(AnalyticsStatus.UNAVAILABLE, result.provenance.analyticsStatus)
        assertEquals(8, result.provenance.analyticsLimitations?.size)
    }

    @Test
    fun `reports missing five-year analytics separately from fresh price`() = runTest {
        val history = listOf(
            conversionPrice(LocalDate(2020, 1, 2), 80.0),
            conversionPrice(LocalDate(2023, 6, 15), 100.0),
            conversionPrice(LocalDate(2024, 6, 15), 120.0)
        )
        coEvery { stockDataProvider.getInfo("NEW") } returns basicInfo("Newer fund").copy(
            price = 120.0,
            marketDate = LocalDate(2024, 6, 15)
        )
        coEvery { stockDataProvider.getHistory("NEW", Period._10y) } returns history

        val result = useCase("NEW")

        assertNull(result.gain.fiveYear)
        assertEquals(DataStatus.PARTIAL, result.provenance.status)
        assertEquals(DataStatus.FRESH, result.provenance.priceStatus)
        assertEquals(AnalyticsStatus.PARTIAL, result.provenance.analyticsStatus)
        assertEquals(listOf("gain.fiveYear"), result.provenance.analyticsLimitations)
    }

    @Test
    fun `history buffer preserves five-year session across stock and FX boundary`() = runTest {
        timeProvider.setDate(LocalDate(2026, 7, 18))
        coEvery { stockDataProvider.getInfo("ETF") } returns basicInfo("ETF").copy(
            price = 150.0,
            marketDate = LocalDate(2026, 7, 17)
        )
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } returns basicInfo("USD/PLN").copy(
            price = 5.0,
            marketDate = LocalDate(2026, 7, 17)
        )
        coEvery { stockDataProvider.getHistory("ETF", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2020, 7, 17), 80.0),
            conversionPrice(LocalDate(2021, 7, 16), 100.0),
            conversionPrice(LocalDate(2021, 7, 19), 101.0),
            conversionPrice(LocalDate(2026, 7, 17), 150.0)
        )
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2020, 7, 17), 3.8),
            conversionPrice(LocalDate(2021, 7, 15), 4.0),
            conversionPrice(LocalDate(2021, 7, 19), 4.0),
            conversionPrice(LocalDate(2026, 7, 17), 5.0)
        )

        val result = useCase("ETF", "PLN")

        assertEquals(0.875, result.gain.fiveYear)
        assertEquals(DataStatus.FRESH, result.provenance.status)
        assertEquals(DataStatus.FRESH, result.provenance.priceStatus)
        assertEquals(AnalyticsStatus.COMPLETE, result.provenance.analyticsStatus)
        assertEquals(emptyList<String>(), result.provenance.analyticsLimitations)
    }

    @Test
    fun `marks converted price stale when both spot and history FX are old`() = runTest {
        coEvery { stockDataProvider.getInfo("ETF") } returns basicInfo("ETF").copy(
            price = 150.0,
            marketDate = LocalDate(2024, 6, 15)
        )
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } returns basicInfo("USD/PLN").copy(
            price = 4.0,
            marketDate = LocalDate(2024, 6, 7)
        )
        coEvery { stockDataProvider.getHistory("ETF", Period._10y) } returns priceHistory(2_000)
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2019, 6, 14), 3.8),
            conversionPrice(LocalDate(2024, 6, 7), 4.0)
        )

        val result = useCase("ETF", "PLN")

        assertEquals(600.0, result.lastPrice)
        assertEquals(DataStatus.STALE, result.provenance.priceStatus)
    }

    @Test
    fun `does not call a spot price fresh when its market date is unknown`() = runTest {
        coEvery { stockDataProvider.getInfo("UNKNOWN_DATE") } returns basicInfo("Unknown date").copy(
            price = 150.0,
            marketDate = null,
            marketTimestamp = null
        )
        coEvery { stockDataProvider.getHistory("UNKNOWN_DATE", Period._10y) } returns priceHistory(2_000)

        val result = useCase("UNKNOWN_DATE")

        assertEquals(DataStatus.STALE, result.provenance.priceStatus)
    }

    @Test
    fun `does not call converted price fresh when spot FX date is unknown`() = runTest {
        coEvery { stockDataProvider.getInfo("ETF") } returns basicInfo("ETF").copy(
            price = 150.0,
            marketDate = LocalDate(2024, 6, 15)
        )
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } returns basicInfo("USD/PLN").copy(
            price = 4.0,
            marketDate = null,
            marketTimestamp = null
        )
        coEvery { stockDataProvider.getHistory("ETF", Period._10y) } returns priceHistory(2_000)
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns priceHistory(2_000)

        val result = useCase("ETF", "PLN")

        assertEquals(600.0, result.lastPrice)
        assertEquals(DataStatus.STALE, result.provenance.priceStatus)
    }

    @Test
    fun `sets target currency when converting`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        every { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getInfo("EUR=X") } returns basicInfo("EUR/USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("EUR=X", Period._10y) } returns priceHistory(500)

        val result = useCase("AAPL", "EUR")

        assertEquals("Apple Inc.", result.name)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `converts eps and marketCap when currency is provided`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.").copy(
            eps = 6.0
        )
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } returns basicInfo("USD/PLN")
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns priceHistory(500)

        val result = useCase("AAPL", "PLN")

        assertTrue(result.eps!! != 6.0, "EPS should be converted")
        assertTrue(result.marketCap!! != 1_000_000.0, "Market cap should be converted")
        assertEquals(25.0, result.peRatio, "PE ratio should not be converted")
    }

    @Test
    fun `normalizes GBp quoted prices without scaling GBP fundamentals`() = runTest {
        coEvery { stockDataProvider.getInfo("ULVR.L") } returns basicInfo("Unilever").copy(
            currency = "GBp",
            price = 4_599.0,
            previousClose = 4_551.0,
            fiftyTwoWeekHigh = 5_542.11,
            fiftyTwoWeekLow = 3_644.0,
            eps = 2.24,
            marketCap = 99_025_641_472.0
        )
        coEvery { stockDataProvider.getHistory("ULVR.L", Period._10y) } returns priceHistory(500)

        val result = useCase("ULVR.L")

        assertEquals("GBP", result.currency)
        assertEquals(45.99, result.lastPrice)
        assertEquals(45.51, result.previousClose)
        assertEquals(55.42, result.fiftyTwoWeekHigh)
        assertEquals(36.44, result.fiftyTwoWeekLow)
        assertEquals(2.24, result.eps)
        assertEquals(99_025_641_472.0, result.marketCap)
    }

    @Test
    fun `combines GBp price scale with requested currency conversion`() = runTest {
        coEvery { stockDataProvider.getInfo("LSEG.L") } returns basicInfo("London Stock Exchange Group").copy(
            currency = "GBp",
            price = 5_000.0,
            previousClose = 4_900.0,
            fiftyTwoWeekHigh = 6_000.0,
            fiftyTwoWeekLow = 4_000.0,
            eps = 2.0,
            marketCap = 1_000_000_000.0
        )
        every { stockDataProvider.resolveConversionSymbol("GBP", "PLN") } returns "GBPPLN=X"
        coEvery { stockDataProvider.getInfo("GBPPLN=X") } returns basicInfo("GBP/PLN").copy(price = 5.0)
        coEvery { stockDataProvider.getHistory("LSEG.L", Period._10y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("GBPPLN=X", Period._10y) } returns priceHistory(500)

        val result = useCase("LSEG.L", "PLN")

        assertEquals("PLN", result.currency)
        assertEquals(250.0, result.lastPrice)
        assertEquals(245.0, result.previousClose)
        assertEquals(300.0, result.fiftyTwoWeekHigh)
        assertEquals(200.0, result.fiftyTwoWeekLow)
        assertEquals(10.0, result.eps)
        assertEquals(5_000_000_000.0, result.marketCap)
    }

    @Test
    fun `uses major-unit repaired history with GBp spot for quote gains`() = runTest {
        coEvery { stockDataProvider.getInfo("ISF.L") } returns basicInfo("iShares Core FTSE 100").copy(
            currency = "GBp",
            price = 1_023.6,
            marketDate = LocalDate(2024, 6, 15)
        )
        coEvery { stockDataProvider.getHistory("ISF.L", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 13), 10.214),
            conversionPrice(LocalDate(2024, 6, 14), 10.212)
        )

        val result = useCase("ISF.L")

        assertEquals("GBP", result.currency)
        assertEquals(10.24, result.lastPrice)
        assertEquals(0.002, result.gain.daily)
    }

    @Test
    fun `converts repaired GBp history and subunit spot to PLN on one scale`() = runTest {
        coEvery { stockDataProvider.getInfo("ISF.L") } returns basicInfo("iShares Core FTSE 100").copy(
            currency = "GBp",
            price = 1_023.6,
            marketDate = LocalDate(2024, 6, 15)
        )
        every { stockDataProvider.resolveConversionSymbol("GBP", "PLN") } returns "GBPPLN=X"
        coEvery { stockDataProvider.getInfo("GBPPLN=X") } returns basicInfo("GBP/PLN").copy(
            price = 5.0,
            marketDate = LocalDate(2024, 6, 15)
        )
        coEvery { stockDataProvider.getHistory("ISF.L", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 13), 10.214),
            conversionPrice(LocalDate(2024, 6, 14), 10.212)
        )
        coEvery { stockDataProvider.getHistory("GBPPLN=X", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 13), 5.0),
            conversionPrice(LocalDate(2024, 6, 14), 5.0)
        )

        val result = useCase("ISF.L", "PLN")

        assertEquals("PLN", result.currency)
        assertEquals(51.18, result.lastPrice)
        assertEquals(0.002, result.gain.daily)
    }

    @Test
    fun `normalizes South African and Israeli subunit spot currencies`() = runTest {
        val cases = listOf(
            Triple("SOLJ.J", "ZAc", "ZAR"),
            Triple("TEVA.TA", "ILA", "ILS")
        )
        cases.forEach { (symbol, nativeCurrency, expectedCurrency) ->
            coEvery { stockDataProvider.getInfo(symbol) } returns basicInfo(symbol).copy(
                currency = nativeCurrency,
                price = 12_345.0,
                previousClose = 12_300.0,
                marketDate = LocalDate(2024, 6, 15)
            )
            coEvery { stockDataProvider.getHistory(symbol, Period._10y) } returns listOf(
                conversionPrice(LocalDate(2024, 6, 14), 120.0),
                conversionPrice(LocalDate(2024, 6, 15), 123.0)
            )

            val result = useCase(symbol)

            assertEquals(expectedCurrency, result.currency)
            assertEquals(123.45, result.lastPrice)
            assertEquals(123.0, result.previousClose)
        }
    }

    @Test
    fun `falls back to historical fx rate when spot conversion info fails`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.").copy(
            price = 10.0,
            previousClose = 8.0,
            fiftyTwoWeekHigh = 12.0,
            fiftyTwoWeekLow = 7.0,
            eps = 2.0,
            marketCap = 1_000_000.0
        )
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } throws BackendDataException.backendError("PLN=X")
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 14), 4.0),
            conversionPrice(LocalDate(2024, 6, 15), 4.25)
        )

        val result = useCase("AAPL", "PLN")

        assertEquals("PLN", result.currency)
        assertEquals(42.5, result.lastPrice)
        assertEquals(34.0, result.previousClose)
        assertEquals(51.0, result.fiftyTwoWeekHigh)
        assertEquals(29.75, result.fiftyTwoWeekLow)
        assertEquals(8.5, result.eps)
        assertEquals(4_250_000.0, result.marketCap)
        assertEquals(DataStatus.PARTIAL, result.provenance.status)
    }

    @Test
    fun `does not hide spot FX rate limit behind historical fallback`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } throws BackendDataException.rateLimited("PLN=X", "120")
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns priceHistory(500)

        val exception = assertThrows<BackendDataException> { useCase("AAPL", "PLN") }

        assertEquals(BackendDataException.Reason.RATE_LIMITED, exception.reason)
        assertEquals("120", exception.retryAfter)
    }

    @Test
    fun `does not hide spot FX backend saturation behind historical fallback`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } throws
            BackendDataException.serviceUnavailable("PLN=X", "1")
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns priceHistory(500)
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns priceHistory(500)

        val exception = assertThrows<BackendDataException> { useCase("AAPL", "PLN") }

        assertEquals(BackendDataException.Reason.SERVICE_UNAVAILABLE, exception.reason)
        assertEquals("1", exception.retryAfter)
    }

    @Test
    fun `uses effective spot as terminal point for every gain period when history is stale`() = runTest {
        coEvery { stockDataProvider.getInfo("STALE") } returns basicInfo("Stale History Corp").copy(
            price = 120.0,
            marketDate = LocalDate(2024, 6, 15)
        )
        coEvery { stockDataProvider.getHistory("STALE", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2019, 6, 15), 20.0),
            conversionPrice(LocalDate(2023, 6, 15), 40.0),
            conversionPrice(LocalDate(2023, 12, 15), 50.0),
            conversionPrice(LocalDate(2024, 1, 1), 60.0),
            conversionPrice(LocalDate(2024, 3, 15), 70.0),
            conversionPrice(LocalDate(2024, 5, 15), 80.0),
            conversionPrice(LocalDate(2024, 6, 8), 90.0),
            conversionPrice(LocalDate(2024, 6, 14), 100.0)
        )

        val result = useCase("STALE")

        assertEquals(120.0, result.lastPrice)
        assertEquals(0.2, result.gain.daily)
        assertEquals(0.333, result.gain.weekly)
        assertEquals(0.5, result.gain.monthly)
        assertEquals(0.714, result.gain.quarterly)
        assertEquals(1.4, result.gain.halfYearly)
        assertEquals(1.0, result.gain.ytd)
        assertEquals(2.0, result.gain.yearly)
        assertEquals(5.0, result.gain.fiveYear)
    }

    @Test
    fun `replaces stale same-day close with spot before calculating gains`() = runTest {
        coEvery { stockDataProvider.getInfo("SAME") } returns basicInfo("Same Day Corp").copy(
            price = 120.0,
            marketDate = LocalDate(2024, 6, 15)
        )
        coEvery { stockDataProvider.getHistory("SAME", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 14), 80.0),
            conversionPrice(LocalDate(2024, 6, 15), 100.0)
        )

        val result = useCase("SAME")

        assertEquals(120.0, result.lastPrice)
        assertEquals(0.5, result.gain.daily)
    }

    @Test
    fun `anchors closed-market gains to last market session`() = runTest {
        timeProvider.setDate(LocalDate(2024, 6, 17))
        coEvery { stockDataProvider.getInfo("WEEKEND") } returns basicInfo("Weekend Corp").copy(
            price = 110.0,
            marketDate = LocalDate(2024, 6, 14)
        )
        coEvery { stockDataProvider.getHistory("WEEKEND", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 13), 80.0),
            conversionPrice(LocalDate(2024, 6, 14), 100.0)
        )

        val result = useCase("WEEKEND")

        assertEquals(LocalDate(2024, 6, 14), result.date)
        assertEquals(110.0, result.lastPrice)
        assertEquals(0.375, result.gain.daily)
    }

    @Test
    fun `uses fallback history FX for both spot price and terminal gain point`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.").copy(
            price = 120.0,
            marketDate = LocalDate(2024, 6, 15)
        )
        every { stockDataProvider.resolveConversionSymbol("USD", "PLN") } returns "PLN=X"
        coEvery { stockDataProvider.getInfo("PLN=X") } throws BackendDataException.backendError("PLN=X")
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 13), 80.0),
            conversionPrice(LocalDate(2024, 6, 14), 100.0)
        )
        coEvery { stockDataProvider.getHistory("PLN=X", Period._10y) } returns listOf(
            conversionPrice(LocalDate(2024, 6, 13), 3.5),
            conversionPrice(LocalDate(2024, 6, 14), 4.0)
        )

        val result = useCase("AAPL", "PLN")

        assertEquals(480.0, result.lastPrice)
        assertEquals(0.2, result.gain.daily)
    }

    @Test
    fun `throws when requested currency cannot be resolved without native currency`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.").copy(currency = null)
        coEvery { stockDataProvider.getHistory("AAPL", Period._10y) } returns priceHistory(500)

        val exception = assertThrows<BackendDataException> { useCase("AAPL", "EUR") }

        assertEquals(BackendDataException.Reason.INSUFFICIENT_DATA, exception.reason)
        assertTrue(exception.message!!.contains("Currency conversion is unavailable"))
    }

    @Test
    fun `calculates dividend growth from history`() = runTest {
        val today = LocalDate(2024, 6, 15)
        val history = (0 until 730).map { i ->
            val date = today.minus(i, DateTimeUnit.DAY)
            val dividend = when {
                i in 80..82 -> 0.25   // recent year dividend
                i in 170..172 -> 0.25
                i in 260..262 -> 0.25
                i in 350..352 -> 0.25 // total recent = 1.0
                i in 445..447 -> 0.20 // previous year dividend
                i in 535..537 -> 0.20
                i in 625..627 -> 0.20
                i in 715..717 -> 0.20 // total previous = 0.8
                else -> 0.0
            }
            HistoricalPrice(
                date = date, open = 100.0, close = 100.0,
                low = 99.0, high = 101.0, volume = 1000L,
                dividend = dividend
            )
        }
        coEvery { stockDataProvider.getInfo("DIV") } returns basicInfo("Dividend Stock")
        coEvery { stockDataProvider.getHistory("DIV", Period._10y) } returns history

        val result = useCase("DIV")

        assertNotNull(result.dividendGrowth)
        assertTrue(result.dividendGrowth!! > 0.0, "Dividend growth should be positive when recent > previous")
    }

    private fun basicInfo(name: String) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0, pbRatio = 10.0, eps = 5.0, roe = 0.3,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0, fiftyTwoWeekLow = 120.0, beta = 1.2,
        sector = "Technology", industry = "Consumer Electronics",
        earningsDate = LocalDate(2024, 7, 25),
        dividendRate = 1.0, trailingAnnualDividendRate = 0.96, currency = "USD",
        previousClose = 148.0, marketDate = LocalDate(2024, 6, 15)
    )

    private fun priceHistory(days: Int): List<HistoricalPrice> = (0 until days).map { i ->
        val date = LocalDate(2024, 6, 15).minus(i, DateTimeUnit.DAY)
        HistoricalPrice(
            date = date,
            open = 100.0 + i * 0.1,
            close = 100.0 + i * 0.1,
            low = 99.0 + i * 0.1,
            high = 101.0 + i * 0.1,
            volume = 1000L,
            dividend = 0.0
        )
    }

    private fun singlePrice() = HistoricalPrice(
        date = LocalDate(2024, 6, 15),
        open = 100.0, close = 100.0, low = 99.0, high = 101.0,
        volume = 1000L, dividend = 0.0
    )

    private fun conversionPrice(date: LocalDate, rate: Double) = HistoricalPrice(
        date = date,
        open = rate,
        close = rate,
        low = rate,
        high = rate,
        volume = 0L,
        dividend = 0.0
    )
}
