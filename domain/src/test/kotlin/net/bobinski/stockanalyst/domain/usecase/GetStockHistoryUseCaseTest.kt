package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.core.time.MutableCurrentTimeProvider
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetStockHistoryUseCaseTest {

    private val stockDataProvider = mockk<StockDataProvider>()
    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val useCase = GetStockHistoryUseCase(stockDataProvider = stockDataProvider, currentTimeProvider = timeProvider)

    @Test
    fun `returns stock history for valid symbol and period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 2), 185.0),
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period._1y)

        assertEquals("AAPL", result.symbol)
        assertEquals("Apple Inc.", result.name)
        assertEquals("1y", result.period)
        assertEquals(2, result.prices.size)
    }

    @Test
    fun `returns prices sorted by date ascending`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0),
            historicalPrice(LocalDate(2024, 6, 1), 200.0),
            historicalPrice(LocalDate(2024, 6, 10), 205.0)
        )

        val result = useCase("AAPL", Period._1mo)

        assertEquals(LocalDate(2024, 6, 1), result.prices[0].date)
        assertEquals(LocalDate(2024, 6, 10), result.prices[1].date)
        assertEquals(LocalDate(2024, 6, 15), result.prices[2].date)
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
        coEvery { stockDataProvider.getHistory("INVALID", Period._1y, Interval.DAILY) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("INVALID", Period._1y) }
        assertTrue(exception.message!!.contains("Unknown symbol"))
    }

    @Test
    fun `throws BackendDataException when history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("NODATA") } returns basicInfo("No Data Corp")
        coEvery { stockDataProvider.getHistory("NODATA", Period._1y, Interval.DAILY) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("NODATA", Period._1y) }
        assertTrue(exception.message!!.contains("Missing history"))
    }

    @Test
    fun `uses correct period value in response`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5d, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 10), 205.0)
        )

        val result = useCase("AAPL", Period._5d)

        assertEquals("5d", result.period)
    }

    @Test
    fun `propagates BackendDataException from provider`() = runTest {
        coEvery { stockDataProvider.getInfo("FAIL") } returns basicInfo("Fail Corp")
        coEvery { stockDataProvider.getHistory("FAIL", Period._1y, Interval.DAILY) } throws
            BackendDataException.backendError("FAIL")

        assertThrows<BackendDataException> { useCase("FAIL", Period._1y) }
    }

    @Test
    fun `uses daily interval when explicitly overridden for 5y period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period._5y, Interval.DAILY)

        assertEquals("5y", result.period)
        assertEquals(1, result.prices.size)
    }

    @Test
    fun `uses weekly interval for 5y period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.WEEKLY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period._5y)

        assertEquals("5y", result.period)
        assertEquals(1, result.prices.size)
    }

    @Test
    fun `uses monthly interval for max period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period.max, Interval.MONTHLY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 210.0)
        )

        val result = useCase("AAPL", Period.max)

        assertEquals("max", result.period)
        assertEquals(1, result.prices.size)
    }

    @Test
    fun `uses intraday interval when specified`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1d, Interval._5m) } returns listOf(
            intradayPrice(LocalDate(2024, 6, 15), 210.0, 1718451000L),
            intradayPrice(LocalDate(2024, 6, 15), 211.0, 1718451300L)
        )

        val result = useCase("AAPL", Period._1d, Interval._5m)

        assertEquals("1d", result.period)
        assertEquals("5m", result.interval)
        assertEquals(2, result.prices.size)
        assertEquals(1718451000L, result.prices[0].timestamp)
        assertEquals(1718451300L, result.prices[1].timestamp)
    }

    @Test
    fun `sorts intraday prices by timestamp`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1d, Interval._5m) } returns listOf(
            intradayPrice(LocalDate(2024, 6, 15), 212.0, 1718451600L),
            intradayPrice(LocalDate(2024, 6, 15), 210.0, 1718451000L),
            intradayPrice(LocalDate(2024, 6, 15), 211.0, 1718451300L)
        )

        val result = useCase("AAPL", Period._1d, Interval._5m)

        assertEquals(1718451000L, result.prices[0].timestamp)
        assertEquals(1718451300L, result.prices[1].timestamp)
        assertEquals(1718451600L, result.prices[2].timestamp)
    }

    @Test
    fun `converts prices when currency is specified`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 2), 100.0),
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 1), 0.9),
            historicalPrice(LocalDate(2024, 6, 14), 0.95)
        )

        val result = useCase("AAPL", Period._1y, currency = "EUR")

        assertEquals("EUR", result.currency)
        assertEquals(90.0, result.prices[0].close, 0.01) // 100 * 0.9
        assertEquals(190.0, result.prices[1].close, 0.01) // 200 * 0.95
    }

    @Test
    fun `skips conversion when currency matches native`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )

        val result = useCase("AAPL", Period._1y, currency = "USD")

        assertEquals("USD", result.currency)
        assertEquals(200.0, result.prices[0].close)
    }

    @Test
    fun `returns native currency when no currency specified`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )

        val result = useCase("AAPL", Period._1y)

        assertEquals("USD", result.currency)
        assertEquals(200.0, result.prices[0].close)
    }

    @Test
    fun `throws when requested currency cannot be resolved without native currency`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = null)
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )

        val exception = assertThrows<BackendDataException> {
            useCase("AAPL", Period._1y, currency = "EUR")
        }

        assertEquals(BackendDataException.Reason.INSUFFICIENT_DATA, exception.reason)
        assertTrue(exception.message!!.contains("Currency conversion is unavailable"))
    }

    @Test
    fun `trims history to conversion overlap when conversion starts later`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2023, 6, 15), 150.0),
            historicalPrice(LocalDate(2024, 1, 2), 100.0),
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 1), 0.9),
            historicalPrice(LocalDate(2024, 6, 14), 0.95)
        )

        val result = useCase("AAPL", Period._1y, currency = "EUR")

        // Price from 2023-06-15 should be excluded (before conversion start)
        assertEquals(2, result.prices.size)
        assertEquals(LocalDate(2024, 1, 2), result.prices[0].date)
    }

    @Test
    fun `throws when no overlap between stock and conversion history`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2023, 1, 2), 100.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns listOf(
            historicalPrice(LocalDate(2024, 1, 1), 0.9)
        )

        val exception = assertThrows<BackendDataException> {
            useCase("AAPL", Period._1y, currency = "EUR")
        }
        assertEquals(BackendDataException.Reason.INSUFFICIENT_DATA, exception.reason)
    }

    @Test
    fun `throws when conversion history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.", currency = "USD")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 15), 200.0)
        )
        coEvery { stockDataProvider.resolveConversionSymbol("USD", "XYZ") } returns "XYZ=X"
        coEvery { stockDataProvider.getHistory("XYZ=X", Period._1y) } returns emptyList()

        assertThrows<BackendDataException> { useCase("AAPL", Period._1y, currency = "XYZ") }
    }

    @Test
    fun `injects daily dividends into weekly bars`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.WEEKLY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 7), 200.0),
            historicalPrice(LocalDate(2024, 6, 14), 210.0)
        )
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.DAILY) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 3), 198.0),
            historicalPrice(LocalDate(2024, 6, 10), 205.0, dividend = 0.25),
            historicalPrice(LocalDate(2024, 6, 14), 210.0)
        )

        val result = useCase("AAPL", Period._5y, dividends = true)

        assertEquals(0.0, result.prices[0].dividend) // week ending 6/7 — no dividend
        assertEquals(0.25, result.prices[1].dividend, 0.001) // week ending 6/14 — includes 6/10 dividend
    }

    private fun basicInfo(name: String, currency: String? = null) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0, pbRatio = 10.0, eps = 5.0, roe = 0.3,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0, fiftyTwoWeekLow = 120.0, beta = 1.2,
        sector = "Technology", industry = "Consumer Electronics",
        earningsDate = LocalDate(2024, 7, 25),
        dividendRate = 1.0, trailingAnnualDividendRate = 0.96, currency = currency
    )

    private fun historicalPrice(date: LocalDate, close: Double, dividend: Double = 0.0) = HistoricalPrice(
        date = date, open = close - 1, close = close,
        low = close - 2, high = close + 1, volume = 1_000_000L,
        dividend = dividend
    )

    private fun intradayPrice(date: LocalDate, close: Double, timestamp: Long) = HistoricalPrice(
        date = date, open = close - 1, close = close,
        low = close - 2, high = close + 1, volume = 1_000_000L,
        dividend = 0.0, timestamp = timestamp
    )
}
