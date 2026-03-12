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
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Interval
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetLatestIndicatorsUseCaseTest {

    private val timeProvider = MutableCurrentTimeProvider(LocalDate(2024, 6, 15))
    private val stockDataProvider = mockk<StockDataProvider>()
    private val useCase = GetLatestIndicatorsUseCase(
        stockDataProvider = stockDataProvider,
        currentTimeProvider = timeProvider
    )

    @Test
    fun `returns all indicators when none specified`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(252)

        val result = useCase("AAPL")

        assertEquals("AAPL", result.symbol)
        assertEquals(LocalDate(2024, 6, 15), result.date)
        assertNotNull(result.rsi)
        assertNotNull(result.sma50)
        assertNotNull(result.ema50)
        assertNotNull(result.macd)
        assertNotNull(result.bb)
        assertNotNull(result.sma200)
        assertNotNull(result.ema200)
    }

    @Test
    fun `returns only requested indicators`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(252)

        val result = useCase("AAPL", setOf("rsi", "sma50"))

        assertNotNull(result.rsi)
        assertNotNull(result.sma50)
        assertNull(result.sma200)
        assertNull(result.ema50)
        assertNull(result.ema200)
        assertNull(result.macd)
        assertNull(result.bb)
    }

    @Test
    fun `returns null for indicators with insufficient data`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(10)

        val result = useCase("AAPL", setOf("sma200"))

        assertNull(result.sma200)
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
    fun `applies currency conversion`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        every { stockDataProvider.resolveConversionSymbol("USD", "EUR") } returns "EUR=X"
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(252)
        coEvery { stockDataProvider.getHistory("EUR=X", Period._1y) } returns priceHistory(252)

        val result = useCase("AAPL", setOf("sma50"), "EUR")

        assertEquals("AAPL", result.symbol)
        assertNotNull(result.sma50)
    }

    @Test
    fun `throws when conversion history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        every { stockDataProvider.resolveConversionSymbol("USD", "XYZ") } returns "XYZ=X"
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(252)
        coEvery { stockDataProvider.getHistory("XYZ=X", Period._1y) } returns emptyList()

        assertThrows<BackendDataException> { useCase("AAPL", currency = "XYZ") }
    }

    @Test
    fun `returns macd snapshot with correct fields`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(252)

        val result = useCase("AAPL", setOf("macd"))

        val macd = result.macd
        assertNotNull(macd)
        assertNotNull(macd!!.macd)
        assertNotNull(macd.signal)
        assertNotNull(macd.histogram)
    }

    @Test
    fun `returns bollinger snapshot with correct fields`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns priceHistory(252)

        val result = useCase("AAPL", setOf("bb"))

        val bb = result.bb
        assertNotNull(bb)
        assertNotNull(bb!!.upper)
        assertNotNull(bb.middle)
        assertNotNull(bb.lower)
        assertTrue(bb.upper > bb.middle)
        assertTrue(bb.middle > bb.lower)
    }

    @Test
    fun `returns indicators for weekly interval`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5y, Interval.WEEKLY) } returns priceHistory(260)

        val result = useCase("AAPL", setOf("rsi"), period = Period._5y, interval = Interval.WEEKLY)

        assertEquals("AAPL", result.symbol)
        assertNotNull(result.rsi)
    }

    private fun basicInfo(name: String) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0, pbRatio = 10.0, eps = 5.0, roe = 0.3,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0, fiftyTwoWeekLow = 120.0, beta = 1.2,
        sector = "Technology", industry = "Consumer Electronics",
        earningsDate = LocalDate(2024, 7, 25),
        dividendRate = 1.0, trailingAnnualDividendRate = 0.96, currency = "USD"
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
}
