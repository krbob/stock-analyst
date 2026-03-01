package net.bobinski.stockanalyst.domain.usecase

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import net.bobinski.stockanalyst.domain.error.BackendDataException
import net.bobinski.stockanalyst.domain.model.BasicInfo
import net.bobinski.stockanalyst.domain.model.HistoricalPrice
import net.bobinski.stockanalyst.domain.provider.StockDataProvider
import net.bobinski.stockanalyst.domain.provider.StockDataProvider.Period
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetStockHistoryUseCaseTest {

    private val stockDataProvider = mockk<StockDataProvider>()
    private val useCase = GetStockHistoryUseCase(stockDataProvider = stockDataProvider)

    @Test
    fun `returns stock history for valid symbol and period`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._1y) } returns listOf(
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
        coEvery { stockDataProvider.getHistory("AAPL", Period._1mo) } returns listOf(
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
        coEvery { stockDataProvider.getHistory("INVALID", Period._1y) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("INVALID", Period._1y) }
        assertTrue(exception.message!!.contains("Unknown symbol"))
    }

    @Test
    fun `throws BackendDataException when history is empty`() = runTest {
        coEvery { stockDataProvider.getInfo("NODATA") } returns basicInfo("No Data Corp")
        coEvery { stockDataProvider.getHistory("NODATA", Period._1y) } returns emptyList()

        val exception = assertThrows<BackendDataException> { useCase("NODATA", Period._1y) }
        assertTrue(exception.message!!.contains("Missing history"))
    }

    @Test
    fun `uses correct period value in response`() = runTest {
        coEvery { stockDataProvider.getInfo("AAPL") } returns basicInfo("Apple Inc.")
        coEvery { stockDataProvider.getHistory("AAPL", Period._5d) } returns listOf(
            historicalPrice(LocalDate(2024, 6, 10), 205.0)
        )

        val result = useCase("AAPL", Period._5d)

        assertEquals("5d", result.period)
    }

    @Test
    fun `propagates BackendDataException from provider`() = runTest {
        coEvery { stockDataProvider.getInfo("FAIL") } returns basicInfo("Fail Corp")
        coEvery { stockDataProvider.getHistory("FAIL", Period._1y) } throws
            BackendDataException.backendError("FAIL")

        assertThrows<BackendDataException> { useCase("FAIL", Period._1y) }
    }

    private fun basicInfo(name: String) = BasicInfo(
        name = name, price = 150.0, peRatio = 25.0f, pbRatio = 10.0f, eps = 5.0f, roe = 0.3f,
        marketCap = 1_000_000.0, recommendation = "buy", analystCount = 30,
        fiftyTwoWeekHigh = 200.0f, fiftyTwoWeekLow = 120.0f, beta = 1.2f,
        sector = "Technology", industry = "Consumer Electronics", earningsDate = "2024-07-25",
        dividendRate = 1.0f, trailingAnnualDividendRate = 0.96f
    )

    private fun historicalPrice(date: LocalDate, close: Double) = HistoricalPrice(
        date = date, open = close - 1, close = close,
        low = close - 2, high = close + 1, volume = 1_000_000L,
        dividend = 0.0
    )
}
